// Demo page JS: combina Departaments + Instituts y slider años (2025 - actual)

let demoTable = null;
let debounceTimer = null;
let dedicationChart = null;
let roleChart = null;
let latestChartOptions = { dedication: null, role: null };
let originalChartHeights = { dedication: null, role: null };
let activeTab = 'personal';

// ---- Filtre acadèmic / no acadèmic ----
let personalDataCompleta = [];
let filtreAcademic = 'tots';

const PDI_KEYWORDS = [
    'professor', 'professora', 'lector', 'lectora', 'catedràtic', 'catedrática',
    'catedrático', 'agregat', 'agregada', 'agregado', 'investigador', 'investigadora',
    'doctorand', 'doctoranda', 'becari', 'becaria', 'postdoc', 'post-doc',
    'visitant', 'visitante', 'emèrit', 'emérit', 'col·laborador', 'colaborador',
    'ajudant', 'ayudante', 'contractat', 'contratado', 'recerca', 'research',
    'científic', 'científica'
];

function esAcademic(empleo) {
    if (!empleo || empleo === '-') return false;
    const s = empleo.toLowerCase();
    return PDI_KEYWORDS.some(k => s.includes(k));
}

function filtrarRowsAcademic(rows, filtre) {
    const source = rows || [];
    if (filtre === 'pdi') {
        return source.filter(r => esAcademic(r.empleo_departamento));
    }
    if (filtre === 'pas') {
        return source.filter(r => !esAcademic(r.empleo_departamento));
    }
    return source;
}

function aplicarFiltreAcademic(filtre) {
    filtreAcademic = filtre;

    // Update pill styles
    ['Tots', 'PDI', 'PAS'].forEach(id => {
        const btn = document.getElementById('filtreAcademic' + id);
        if (!btn) return;
        const active = (id.toLowerCase() === filtre) || (id === 'Tots' && filtre === 'tots');
        btn.className = active
            ? 'px-3 py-1.5 bg-indigo-600 text-white text-xs font-semibold'
            : 'px-3 py-1.5 bg-white text-slate-600 hover:bg-slate-50 text-xs font-semibold';
    });

    if (!demoTable) return;

    const filtered = filtrarRowsAcademic(personalDataCompleta, filtre);

    demoTable.setData(filtered);
    updateDedicationChart(filtered);
    updateRoleChart(filtered);
}

function greenColorScaleGenerator(values) {
    const numericValues = values.map(v => Number(v)).filter(v => Number.isFinite(v));
    if (!numericValues.length) return () => 'hsl(142, 45%, 95%)';
    const min = Math.min(...numericValues);
    const max = Math.max(...numericValues);
    return function (x) {
        const value = Number(x);
        if (!Number.isFinite(value) || max === min) return 'hsl(142, 45%, 92%)';
        const ratio = (value - min) / (max - min);
        const lightness = 94 - (ratio * 48);
        return `hsl(142, 55%, ${lightness}%)`;
    };
}

function normalizeDedication(raw) {
    if (raw === null || raw === undefined) return 'No informada';
    const s = String(raw).trim().toLowerCase();
    if (s === '') return 'No informada';
    // If a percentage appears, classify by threshold
    const pctMatch = s.match(/(\d+(?:[\.,]\d+)?)\s*%?/);
    if (pctMatch) {
        const pct = Number(String(pctMatch[1]).replace(',', '.'));
        if (!Number.isNaN(pct)) {
            return pct >= 95 ? 'Completa' : 'Parcial';
        }
    }
    if (s.includes('parci') || s.includes('part') || s.includes('partial')) return 'Parcial';
    if (s.includes('comple') || s.includes('full') || s.includes('complet')) return 'Completa';
    if (s.includes('temps complet') || s.includes('time complete') || s.includes('full-time') || s.includes('full time')) return 'Completa';
    if (s.includes('temps parcial') || s.includes('part-time') || s.includes('part time')) return 'Parcial';
    return 'No informada';
}

function showLoadingDemo() {
    const el = document.getElementById('loadingOverlayDemo');
    if (el) el.classList.remove('hidden');
}

function hideLoadingDemo() {
    const el = document.getElementById('loadingOverlayDemo');
    if (el) el.classList.add('hidden');
}

async function cargarOrganizacionesCombinadas() {
    const optInst = document.getElementById('opt_institutos');

    optInst.innerHTML = '<option value="">Carregant...</option>';

    try {
        showLoadingDemo();
        const institutosUrl = (typeof apiUrl === 'function')
            ? apiUrl('/persons/institutos')
            : '/api/persons/institutos';
        const resInst = await fetch(institutosUrl);
        const insts = resInst.ok ? await resInst.json() : [];

        optInst.innerHTML = '';

        insts.forEach(i => {
            const opt = document.createElement('option');
            opt.value = `inst:${i.uuid}`;
            opt.textContent = i.nombre;
            optInst.appendChild(opt);
        });

    } catch (e) {
        optInst.innerHTML = '<option value="">Error</option>';
        console.error('Error carregant instituts', e);
    }
    finally {
        hideLoadingDemo();
    }
}

function inicializarSliderDemo() {
    const slider = document.getElementById('sliderAnios');
    const currentYear = new Date().getFullYear();
    const minYear = 2015;
    const maxYear = currentYear;
    // Valores iniciales del slider: por defecto 2021..2025 (ajustamos si excede maxYear)
    const defaultFrom = 2021;
    const defaultTo = 2025;
    const startFrom = Math.max(minYear, Math.min(defaultFrom, maxYear));
    const startTo = Math.max(minYear, Math.min(defaultTo, maxYear));

    noUiSlider.create(slider, {
        start: [startFrom, startTo],
        connect: true,
        step: 1,
        range: { 'min': minYear, 'max': maxYear },
        format: {
            to: v => Math.round(v),
            from: v => Number(v)
        }
    });

    const valorRango = document.getElementById('valorRango');
    slider.noUiSlider.on('update', (vals) => {
        valorRango.textContent = `${vals[0]} — ${vals[1]}`;
    });

    // Debounce load on change
    slider.noUiSlider.on('change', () => {
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(() => {
            if (activeTab === 'ajuts') cargarAjutsData();
            else if (activeTab === 'tesis') cargarTesisData();
            else cargarDemoData();
        }, 300);
    });
}

function generarDatosDummy(desde, hasta) {
    const rows = [];
    for (let y = desde; y <= hasta; y++) {
        rows.push({ anio: y, cantidad: Math.floor(Math.random() * 50) + 1 });
    }
    return rows;
}

// Charts removed for demo: only table and KPI remain

function renderDemoTable(rows) {
    const el = document.getElementById('demoTable');
    // Función auxiliar: formatea fecha ISO a formato catalán (p. ej. "9 de març de 2026")
    function formatDateCatalan(iso) {
        if (!iso) return '-';
        try {
            const d = new Date(iso);
            if (isNaN(d)) return iso;
            const dd = String(d.getDate()).padStart(2, '0');
            const mm = String(d.getMonth() + 1).padStart(2, '0');
            const yyyy = d.getFullYear();
            return `${dd}/${mm}/${yyyy}`;
        } catch (e) {
            return iso;
        }
    }

    // Columnas fijas para la vista de asociaciones por instituto/departamento
    const cols = [
        { title: 'Nom', field: 'nombre', sorter: 'string' },
        { title: 'Càrrec', field: 'empleo_departamento', sorter: 'string' },
        { title: 'Dedicació', field: 'dedicacion', sorter: 'string' },
        {
            title: 'Inici', field: 'inicio_instituto', sorter: 'date', sorterParams: { format: 'yyyy-MM-dd' },
            formatter: (cell) => formatDateCatalan(cell.getValue())
        },
        {
            title: 'Fi', field: 'fin_instituto', sorter: 'date', sorterParams: { format: 'yyyy-MM-dd' },
            formatter: (cell) => formatDateCatalan(cell.getValue())
        }
    ];

    if (!demoTable) {
        demoTable = new Tabulator(el, {
            layout: 'fitColumns',
            placeholder: 'No hi ha dades.',
            columns: cols,
            initialSort: [ { column: 'nombre', dir: 'asc' } ],
            height: '500px',
            renderVertical: 'virtual',
        });
    } else {
        demoTable.setColumns(cols);
    }

    personalDataCompleta = rows || [];
    // Apply current academic filter
    const toShow = filtrarRowsAcademic(personalDataCompleta, filtreAcademic);
    demoTable.setData(toShow);
}

function initDedicationChart() {
    const el = document.getElementById('dedicationChart');
    if (!el) return;
    // dispose previous instance if exists
    if (dedicationChart && dedicationChart.dispose) {
        try { dedicationChart.dispose(); } catch (e) { /* ignore */ }
    }
    dedicationChart = echarts.init(el);
    const option = {
        tooltip: { trigger: 'item' },
        legend: { orient: 'horizontal', bottom: 0 },
        series: [{
            name: 'Dedicació',
            type: 'pie',
            radius: '60%',
            data: [],
            label: {
                formatter: '{b}: {d}%',
                position: 'outside'
            },
            emphasis: { itemStyle: { shadowBlur: 10, shadowOffsetX: 0, shadowColor: 'rgba(0,0,0,0.2)' } }
        }]
    };
    dedicationChart.setOption(option);
    latestChartOptions.dedication = option;
    // store original height to restore after fullscreen
    try { originalChartHeights.dedication = el.style.height || `${el.clientHeight}px`; } catch (e) { originalChartHeights.dedication = '360px'; }
}

function updateDedicationChart(rows) {
    const container = document.getElementById('chartContainer');
    const placeholder = document.getElementById('chartPlaceholder');
    if (!rows || rows.length === 0) {
        if (container) container.classList.add('hidden');
        if (placeholder) placeholder.classList.remove('hidden');
        return;
    }
    if (container) container.classList.remove('hidden');
    if (placeholder) placeholder.classList.add('hidden');

    const counts = {};
    rows.forEach(r => {
        const k = (r.dedicacion || r.dedicacion === 0) ? String(r.dedicacion) : '-';
        counts[k] = (counts[k] || 0) + 1;
    });

    const data = Object.keys(counts).map(k => ({ name: k, value: counts[k] }));

    if (!dedicationChart) initDedicationChart();
    if (!dedicationChart) return;

    const opt = {
        series: [{ data }],
        legend: { data: Object.keys(counts) }
    };
    latestChartOptions.dedication = opt;
    dedicationChart.setOption(opt);
}

function initRoleChart() {
    const el = document.getElementById('roleChart');
    if (!el) return;
    if (roleChart && roleChart.dispose) {
        try { roleChart.dispose(); } catch (e) { /* ignore */ }
    }
    roleChart = echarts.init(el);
    const option = {
        tooltip: { trigger: 'item' },
        series: [{
            name: 'Càrrec',
            type: 'pie',
            radius: '60%',
            data: [],
            label: { formatter: '{b}: {d}%', position: 'outside' },
            emphasis: { itemStyle: { shadowBlur: 10, shadowOffsetX: 0, shadowColor: 'rgba(0,0,0,0.2)' } }
        }]
    };
    roleChart.setOption(option);
    latestChartOptions.role = option;
    try { originalChartHeights.role = el.style.height || `${el.clientHeight}px`; } catch (e) { originalChartHeights.role = '360px'; }
}

function updateRoleChart(rows) {
    const container = document.getElementById('chartContainer');
    const placeholder = document.getElementById('chartPlaceholder');
    if (!rows || rows.length === 0) {
        if (container) container.classList.add('hidden');
        if (placeholder) placeholder.classList.remove('hidden');
        return;
    }
    if (container) container.classList.remove('hidden');
    if (placeholder) placeholder.classList.add('hidden');

    const counts = {};
    rows.forEach(r => {
        let k = (r.empleo_departamento || '-');
        try { k = String(k).trim(); } catch (e) { k = '-'; }
        if (k === '') k = '-';
        counts[k] = (counts[k] || 0) + 1;
    });

    const data = Object.keys(counts).map(k => ({ name: k, value: counts[k] }));

    if (!roleChart) initRoleChart();
    if (!roleChart) return;

    const opt = { series: [{ data }] };
    latestChartOptions.role = opt;
    roleChart.setOption(opt);
}

/**
 * Abrir en pantalla completa el contenedor de un gráfico (igual que persona-resumen).
 * @param {string} kind - 'dedication' | 'role'
 */
/**
 * Abrir en pantalla completa el contenedor de un gráfico (igual que persona-resumen).
 * Acepta tanto el id del elemento (`dedicationCard`/`roleCard`) como la clave corta ('dedication'|'role').
 * @param {string} elementIdOrKind
 */
function abrirPantallaCompleta(elementIdOrKind) {
    const idMap = { dedication: 'dedicationCard', role: 'roleCard' };
    const cardId = document.getElementById(elementIdOrKind) ? elementIdOrKind : (idMap[elementIdOrKind] || null);
    const card = cardId ? document.getElementById(cardId) : null;
    if (!card) return;

    if (document.fullscreenElement) {
        document.exitFullscreen();
        return;
    }

    if (card.requestFullscreen) {
        try {
            card.requestFullscreen();
        } catch (err) {
            console.error('requestFullscreen failed', err);
        }
    }
}

// Resize charts when entering/exiting fullscreen so ECharts redraws to new size
document.addEventListener('fullscreenchange', () => {
    try {
        const el = document.fullscreenElement;
        if (!el) {
            // exited fullscreen
            try {
                const dEl = document.getElementById('dedicationChart');
                if (dEl && originalChartHeights.dedication) dEl.style.height = originalChartHeights.dedication;
                if (dedicationChart && dedicationChart.resize) dedicationChart.resize();
            } catch (e) {}
            try {
                const rEl = document.getElementById('roleChart');
                if (rEl && originalChartHeights.role) rEl.style.height = originalChartHeights.role;
                if (roleChart && roleChart.resize) roleChart.resize();
            } catch (e) {}
            return;
        }

        // entering fullscreen: detect which card is fullscreen and resize its chart
        const id = el.id || '';
        setTimeout(() => {
            try {
                if (id === 'dedicationCard') {
                    const dEl = document.getElementById('dedicationChart');
                    if (dEl) dEl.style.height = 'calc(100vh - 120px)';
                    if (dedicationChart && dedicationChart.resize) dedicationChart.resize();
                }
                else if (id === 'roleCard') {
                    const rEl = document.getElementById('roleChart');
                    if (rEl) rEl.style.height = 'calc(100vh - 120px)';
                    if (roleChart && roleChart.resize) roleChart.resize();
                }
                else {
                    try { const dEl = document.getElementById('dedicationChart'); if (dEl) dEl.style.height = 'calc(50vh)'; } catch(e){}
                    try { const rEl = document.getElementById('roleChart'); if (rEl) rEl.style.height = 'calc(50vh)'; } catch(e){}
                    try { if (dedicationChart && dedicationChart.resize) dedicationChart.resize(); } catch (e) {}
                    try { if (roleChart && roleChart.resize) roleChart.resize(); } catch (e) {}
                }
            } catch (e) { console.error('fullscreen resize error', e); }
        }, 120);
    } catch (e) { console.error('fullscreenchange handler error', e); }
});

// Delegate expand button clicks -> abrirPantallaCompleta
document.addEventListener('click', (ev) => {
    const t = ev.target;
    if (!t) return;
    if (t.matches && t.matches('button[data-chart]')) {
        const kind = t.getAttribute('data-chart');
        if (kind === 'dedication' || kind === 'role') abrirPantallaCompleta(kind);
    }
});

function actualizarKPI(rows) {
    const total = (rows && rows.length) ? rows.length : 0;
    document.getElementById('kpiTotalValue').textContent = String(total);
}

async function cargarDemoData() {
    const select = document.getElementById('orgSelect');
    const filtrePersonalSelect = document.getElementById('filtrePersonalSelect');
    const orgVal = select.value; // formato tipo:uuid o empty
    const filtrePersonal = filtrePersonalSelect ? filtrePersonalSelect.value : 'periode';
    const [desdeRaw, hastaRaw] = document.getElementById('sliderAnios').noUiSlider.get();
    const desde = parseInt(desdeRaw, 10);
    const hasta = parseInt(hastaRaw, 10);

    // Si no se selecciona org, limpiamos la tabla y gráficos
    if (!orgVal) {
        const rows = [];
        renderDemoTable(rows);
        actualizarKPI(rows);
        updateDedicationChart([]);
        updateRoleChart([]);
        return;
    }

        // Consultar endpoint que implementa la pipeline específica de instituto
    try {
        showLoadingDemo();
        const [tipo, uuid] = orgVal.split(':');
        const startDate = `${desde}-01-01`;
        const endDate = `${hasta}-12-31`;
        const associationsUrl = (typeof apiUrl === 'function')
            ? apiUrl('/persons/associations/latest')
            : '/persons/associations/latest';
        const res = await fetch(`${associationsUrl}?orgUuid=${encodeURIComponent(uuid)}&startDate=${encodeURIComponent(startDate)}&endDate=${encodeURIComponent(endDate)}&filtrePersonal=${encodeURIComponent(filtrePersonal)}`);
        if (!res.ok) throw new Error('endpoint no disponible');
        const data = await res.json();

        // El endpoint devuelve: nombre, empleo_departamento, dedicacion, inicio_instituto, fin_instituto
        function formatNombre(fullName) {
            if (!fullName) return '-';
            // If backend already sends a display name, keep it as-is.
            if (fullName.includes(',')) return fullName.trim();
            return fullName.trim().replace(/\s{2,}/g, ' ');
        }

        const rows = (data || []).map(d => {
            const dedRaw = d.dedicacion ?? d.dedicacio ?? d.dedication ?? d.empleo_dedicacion ?? d.empleo;
            const ded = normalizeDedication(dedRaw);
            return {
                nombre: (d.lastName && d.firstName) ? `${d.lastName}, ${d.firstName}` : formatNombre(d.nombre || '-'),
                empleo_departamento: d.empleo || d.empleo_departamento || '-',
                dedicacion: ded,
                inicio_instituto: d.inicio_asociacion_IBB || d.inicio_instituto || '-',
                fin_instituto: d.fin_asociacion_IBB || d.fin_instituto || '-'
            };
        });

        renderDemoTable(rows);
        actualizarKPI(rows);
        const rowsFiltrades = filtrarRowsAcademic(rows, filtreAcademic);
        updateDedicationChart(rowsFiltrades);
        updateRoleChart(rowsFiltrades);
    } catch (e) {
        // Fallback: tabla vacía para evitar filas sin correspondencia de columnas
        const rows = [];
        renderDemoTable(rows);
        actualizarKPI(rows);
        updateDedicationChart([]);
        updateRoleChart([]);
    }
    finally {
        hideLoadingDemo();
    }
}

function switchTab(tab) {
    activeTab = tab;
    const panels = document.querySelectorAll('.tab-panel');
    panels.forEach(p => p.classList.add('hidden'));
    const tabIdMap = { personal: 'tabPersonal', ajuts: 'tabAjuts', tesis: 'tabTesis' };
    document.getElementById(tabIdMap[tab] || 'tabPersonal').classList.remove('hidden');

    const buttons = document.querySelectorAll('.tab-btn');
    buttons.forEach(b => {
        b.classList.remove('bg-white', 'text-indigo-700');
        b.classList.add('bg-slate-50', 'text-slate-500');
    });
    const btnIdMap = { personal: 'tabBtnPersonal', ajuts: 'tabBtnAjuts', tesis: 'tabBtnTesis' };
    const activeBtn = document.getElementById(btnIdMap[tab] || 'tabBtnPersonal');
    activeBtn.classList.remove('bg-slate-50', 'text-slate-500');
    activeBtn.classList.add('bg-white', 'text-indigo-700');

    if (tab === 'ajuts') {
        cargarAjutsData();
    } else if (tab === 'tesis') {
        cargarTesisData();
    }
}

function renderAjutsTable(data) {
    const renderers = $.pivotUtilities.renderers;
    const sum = $.pivotUtilities.aggregatorTemplates.sum;
    const numberFormat = $.pivotUtilities.numberFormat;
    const monedaEs = numberFormat({
        digitsAfterDecimal: 2,
        thousandsSep: '.',
        decimalSep: ',',
        scaler: 1,
        suffix: ' €'
    });

    // Reset pivot filter state when pivot is rebuilt
    pivotFiltro = null;
    actualizarIndicadorFiltrePivot();

    const normalized = data.map(d => ({
        categoria: d.categoria || 'Sense categoria',
        tipo: d.tipo || '(Sense tipus)',
        anio: String(Number(d.anio || 0)),
        ajuts: Number(d.ajuts || 0),
        import: Number(d.import || 0)
    }));

    $('#ajuts-pivot-container').pivot(normalized, {
        rows: ['tipo'],
        cols: ['anio'],
        vals: ['import'],
        renderer: renderers['Heatmap'],
        aggregator: sum(monedaEs)(['import']),
        rendererOptions: {
            heatmap: {
                colorScaleGenerator: greenColorScaleGenerator
            }
        }
    });

    // Attach cell click handlers after pivot renders
    adjuntarClicksPivot();
}

function construirPivotDataDesdeLlista(rows) {
    const byKey = new Map();
    (rows || []).forEach(d => {
        const tipo = d.tipoAward || '(Sense tipus)';
        const anio = String(Number(d.anyo || 0));
        const key = `${tipo}||${anio}`;
        if (!byKey.has(key)) {
            byKey.set(key, {
                categoria: d.categoria || tipo,
                tipo,
                anio,
                ajuts: 0,
                import: 0
            });
        }
        const acc = byKey.get(key);
        acc.ajuts += 1;
        acc.import += Number(d.institutionalPart || 0);
    });
    return Array.from(byKey.values());
}

function renderAjutsTablePreservantVista(data) {
    const scrollX = window.scrollX;
    const scrollY = window.scrollY;
    const activeEl = document.activeElement;
    renderAjutsTable(data);
    window.scrollTo(scrollX, scrollY);
    if (activeEl && typeof activeEl.focus === 'function') {
        try {
            activeEl.focus({ preventScroll: true });
        } catch (e) {
            activeEl.focus();
        }
    }
}

function renderAjutsPivotFiltratPerPersona() {
    if (!ajutsIpsFiltrePersonaUuid) {
        renderAjutsTablePreservantVista(ajutsPivotDataCompleta || []);
        return;
    }
    const rows = (ajutsLlistaDataCompleta || []).filter(d => {
        const holders = d.awardHoldersUuids || [];
        return holders.includes(ajutsIpsFiltrePersonaUuid);
    });
    const pivotData = construirPivotDataDesdeLlista(rows);
    renderAjutsTablePreservantVista(pivotData);
}

let tesisTable = null;
let directorsTable = null;
let llistaData = [];
let selectedDirectorUuid = null;

// ---- Llista d'ajuts de l'institut ----
let ajutsLlistaTable = null;
let ajutsLlistaDataCompleta = [];
let ajutsPivotDataCompleta = [];
let pivotFiltro = null; // { anio, tipo } or null

// ---- Taula IPs / Co-IPs de l'institut ----
let ajutsIpsTable = null;
let ajutsIpsFiltrePersonaUuid = null;
let ajutsIpsFiltrePersonaNom = null;

function actualizarIndicadorFiltrePivot() {
    const indicador = document.getElementById('ajutsFiltreIndicador');
    const text = document.getElementById('ajutsFiltreText');
    if (!indicador || !text) return;
    if (!pivotFiltro) {
        indicador.classList.add('hidden');
        indicador.classList.remove('flex');
        return;
    }
    const parts = [];
    if (pivotFiltro.anio) parts.push(pivotFiltro.anio);
    if (pivotFiltro.tipo) parts.push(pivotFiltro.tipo);
    text.textContent = parts.join(' · ');
    indicador.classList.remove('hidden');
    indicador.classList.add('flex');
}

function aplicarFiltrePivotALlista() {
    if (!ajutsLlistaTable) return;
    let base = ajutsLlistaDataCompleta;
    // Filtre per persona IP/Co-IP + injectar camp _rol
    if (ajutsIpsFiltrePersonaUuid) {
        base = base
            .filter(d => {
                const holders = d.awardHoldersUuids || [];
                return holders.includes(ajutsIpsFiltrePersonaUuid);
            })
            .map(d => ({ ...d, _rol: rolPersonaEnAjut(d) }));
    } else {
        base = base.map(d => ({ ...d, _rol: null }));
    }
    if (!pivotFiltro) {
        ajutsLlistaTable.setData(base);
        return;
    }
    const { anio, tipo } = pivotFiltro;
    const filtered = base.filter(d => {
        const anyoMatch = !anio || Number(d.anyo) === Number(anio);
        const tipusActual = d.tipoAward || '(Sense tipus)';
        const tipusMatch = !tipo || tipusActual === tipo;
        return anyoMatch && tipusMatch;
    });
    ajutsLlistaTable.setData(filtered);
}

function netejarFiltreIps() {
    ajutsIpsFiltrePersonaUuid = null;
    ajutsIpsFiltrePersonaNom = null;
    if (ajutsIpsTable) {
        ajutsIpsTable.getRows().forEach(r => r.getElement().classList.remove('bg-indigo-50', 'font-bold'));
    }
    // Actualitzar indicador
    const ind = document.getElementById('ajutsIpsFiltreIndicador');
    if (ind) ind.classList.add('hidden');
    renderAjutsPivotFiltratPerPersona();
    aplicarFiltrePivotALlista();
}

function netejarFiltrePivot() {
    pivotFiltro = null;
    $('#ajuts-pivot-container td.pvtVal').removeClass('pvt-selected');
    aplicarFiltrePivotALlista();
    actualizarIndicadorFiltrePivot();
}

function onAjutsIpsRowClick(e, row) {
    const data = row.getData();
    const uuid = data.personUuid;
    const nom = data.nombre;
    if (!uuid) return;

    // Toggle
    if (ajutsIpsFiltrePersonaUuid === uuid) {
        netejarFiltreIps();
        return;
    }

    ajutsIpsFiltrePersonaUuid = uuid;
    ajutsIpsFiltrePersonaNom = nom;

    // Marcar la fila seleccionada
    if (ajutsIpsTable) {
        ajutsIpsTable.getRows().forEach(r => {
            r.getElement().classList.toggle('bg-indigo-50', r.getData().personUuid === uuid);
        });
    }

    // Mostrar indicador
    const ind = document.getElementById('ajutsIpsFiltreIndicador');
    const txt = document.getElementById('ajutsIpsFiltreText');
    if (ind && txt) {
        txt.textContent = nom;
        ind.classList.remove('hidden');
        ind.classList.add('flex');
    }

    renderAjutsPivotFiltratPerPersona();
    aplicarFiltrePivotALlista();
}

function adjuntarClicksPivot() {
    const $container = $('#ajuts-pivot-container');
    // Collect column headers (years) in order
    const colHeaders = [];
    $container.find('thead tr th.pvtColLabel').each(function () {
        colHeaders.push($(this).text().trim());
    });

    // Attach click to each data cell
    $container.find('tbody tr').each(function () {
        const $row = $(this);
        const tipo = $row.find('th.pvtRowLabel').text().trim() || '(Sense tipus)';
        $row.find('td.pvtVal').each(function (colIdx) {
            const anio = colHeaders[colIdx] || '';
            const $cell = $(this);
            $cell.on('click', function () {
                const isSame = pivotFiltro &&
                    pivotFiltro.anio === anio &&
                    pivotFiltro.tipo === tipo;
                if (isSame) {
                    netejarFiltrePivot();
                } else {
                    pivotFiltro = { anio, tipo };
                    $container.find('td.pvtVal').removeClass('pvt-selected');
                    $cell.addClass('pvt-selected');
                    aplicarFiltrePivotALlista();
                    actualizarIndicadorFiltrePivot();
                    // Scroll to the list
                    const wrapper = document.getElementById('ajutsLlistaWrapper');
                    if (wrapper) wrapper.scrollIntoView({ behavior: 'smooth', block: 'start' });
                }
            });
        });
    });

    // Wire up the clear button (pivot)
    const clearBtn = document.getElementById('ajutsFiltreClear');
    if (clearBtn) {
        clearBtn.onclick = netejarFiltrePivot;
    }

    // Wire up the clear button (persona IPs)
    const clearIpsBtn = document.getElementById('ajutsIpsFiltreClear');
    if (clearIpsBtn) {
        clearIpsBtn.onclick = netejarFiltreIps;
    }
}

function escaparHtmlDemo(valor) {
    return String(valor ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function formatearTituloAjutConEnlace(cell) {
    const row = cell.getRow()?.getData?.() || {};
    const titulo = cell.getValue() ?? '-';
    const pureId = row.pureId;
    if (pureId == null || String(pureId).trim() === '') {
        return escaparHtmlDemo(titulo);
    }
    const tituloEsc = escaparHtmlDemo(titulo);
    const idEsc = escaparHtmlDemo(String(pureId));
    return `<button type="button" data-egreta-id="${idEsc}" data-egreta-title="${tituloEsc}" class="text-left w-full text-indigo-700 hover:text-indigo-900 hover:underline cursor-pointer">${tituloEsc}</button>`;
}

function formatearNumeroDemo(valor) {
    const num = Number(valor || 0);
    return num.toLocaleString('ca-ES', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatearFechaDemo(cell) {
    const valor = cell.getValue();
    if (!valor) return '-';
    const fecha = new Date(valor);
    if (Number.isNaN(fecha.getTime())) return valor;
    return fecha.toLocaleDateString('ca-ES');
}

function rolPersonaEnAjut(d) {
    if (!ajutsIpsFiltrePersonaUuid) return null;
    const holders = d.awardHolders || [];
    const holder = holders.find(h => h && h.person && h.person.uuid === ajutsIpsFiltrePersonaUuid);
    if (!holder) return null;
    const term = holder.role && holder.role.term;
    if (!term) return null;
    const t = term.ca_ES || term.es_ES || term.en_GB || '';
    if (/Co-Investigador/i.test(t)) return 'Co-IP';
    if (/Investigador.*principal/i.test(t) || /Principal Investigator/i.test(t)) return 'IP';
    // shorten any other role
    return t.length > 15 ? t.substring(0, 14) + '…' : t;
}

function renderAjutsLlistaTable(data) {
    ajutsLlistaDataCompleta = data || [];
    pivotFiltro = null;
    actualizarIndicadorFiltrePivot();

    const wrapperEl = document.getElementById('ajutsLlistaWrapper');
    if (wrapperEl) wrapperEl.classList.toggle('hidden', ajutsLlistaDataCompleta.length === 0);

    const cols = [
        { title: 'Any', field: 'anyo', sorter: 'number', hozAlign: 'center', width: 70 },
        { title: "Tipus", field: 'tipoAward', sorter: 'string', width: 130, tooltip: true },
        {
            title: 'Rol', field: '_rol', sorter: 'string', width: 75, hozAlign: 'center',
            formatter: cell => {
                const v = cell.getValue();
                if (!v) return '';
                const cls = v === 'IP'
                    ? 'bg-indigo-100 text-indigo-700'
                    : v === 'Co-IP'
                        ? 'bg-violet-100 text-violet-700'
                        : 'bg-slate-100 text-slate-600';
                return `<span class="inline-flex items-center justify-center px-2 py-0.5 rounded-full text-xs font-semibold ${cls}">${v}</span>`;
            }
        },
        { title: 'Títol', field: 'titulo', sorter: 'string', widthGrow: 2, formatter: formatearTituloAjutConEnlace },
        { title: 'Import (€)', field: 'institutionalPart', sorter: 'number', hozAlign: 'right', formatter: (cell) => formatearNumeroDemo(cell.getValue()), width: 120 },
        { title: 'Inici', field: 'vigenciaInicio', sorter: 'string', formatter: formatearFechaDemo, width: 100 },
        { title: 'Fi', field: 'vigenciaFin', sorter: 'string', formatter: formatearFechaDemo, width: 100 },
    ];

    if (!ajutsLlistaTable) {
        ajutsLlistaTable = new Tabulator('#ajuts-llista-table', {
            data: ajutsLlistaDataCompleta,
            layout: 'fitColumns',
            maxHeight: '520px',
            reactiveData: false,
            placeholder: 'No hi ha ajuts per a l\'institut en el període seleccionat.',
            initialSort: [{ column: 'anyo', dir: 'desc' }],
            columns: cols
        });
    } else {
        ajutsLlistaTable.setColumns(cols);
        ajutsLlistaTable.setData(ajutsLlistaDataCompleta);
    }
}

function renderTesisTable(data) {
    const el = document.getElementById('tesis-table-container');
    if (!tesisTable) {
        tesisTable = new Tabulator(el, {
            data: data || [],
            layout: 'fitColumns',
            placeholder: 'No hi ha tesis en el període seleccionat.',
            columns: [
                { title: 'Any', field: 'any', sorter: 'number', hozAlign: 'center', headerHozAlign: 'center', width: 120 },
                { title: 'Tesis dirigides', field: 'tesis', sorter: 'number', hozAlign: 'center', headerHozAlign: 'center' }
            ],
            initialSort: [{ column: 'any', dir: 'asc' }],
        });
    } else {
        tesisTable.setData(data || []);
    }
}

function onDirectorRowClick(e, row) {
    const { uuid, nom } = row.getData();
    if (selectedDirectorUuid === uuid) {
        selectedDirectorUuid = null;
        directorsTable.getRows().forEach(r => r.getElement().classList.remove('tabulator-selected-row'));
    } else {
        selectedDirectorUuid = uuid;
        directorsTable.getRows().forEach(r => {
            r.getElement().classList.toggle('tabulator-selected-row', r.getData().uuid === uuid);
        });
    }
    applyLlistaFilter(selectedDirectorUuid, nom);
}

function renderDirectorsTable(data) {
    const el = document.getElementById('directors-table-container');
    selectedDirectorUuid = null;
    if (!directorsTable) {
        directorsTable = new Tabulator(el, {
            data: data || [],
            layout: 'fitColumns',
            height: '215px',
            placeholder: 'No hi ha directors en el període seleccionat.',
            columns: [
                { title: 'Director/a', field: 'nom', sorter: 'string' },
                { title: 'Tesis dirigides', field: 'tesis', sorter: 'number', hozAlign: 'center', headerHozAlign: 'center', width: 150 }
            ],
            initialSort: [{ column: 'nom', dir: 'asc' }],
        });
        directorsTable.on('rowClick', onDirectorRowClick);
    } else {
        directorsTable.setData(data || []);
    }
}

const MESOS_CA = ['gen.','feb.','març','abr.','maig','juny','jul.','ag.','set.','oct.','nov.','des.'];

function buildLlistaHtml(data) {
    if (!data || data.length === 0) {
        return '<p class="text-sm text-slate-400 py-4">No hi ha tesis en el període seleccionat.</p>';
    }
    let html = '<div class="divide-y divide-slate-100">';
    data.forEach(t => {
        const titol = t.titol || '(Sense títol)';
        const autors = (t.autors || []).map(a => `${a} (Autor)`).join(', ');
        const directors = (t.directors || []).map(d => `${d} (Director/a)`).join(', ');
        let data_str = '';
        if (t.any) {
            const mes = t.mes && t.mes >= 1 && t.mes <= 12 ? MESOS_CA[t.mes - 1] : null;
            const dia = t.dia ? `${t.dia} de ` : '';
            data_str = mes ? `${dia}${mes} ${t.any}` : String(t.any);
        }
        const meta = [autors, directors, data_str].filter(Boolean).join(', ');
        html += `<div class="py-3">
            <p class="text-sm font-medium text-slate-800">${titol}</p>
            <p class="text-xs text-slate-500 mt-0.5">${meta}</p>
        </div>`;
    });
    html += '</div>';
    return html;
}

function applyLlistaFilter(directorUuid, directorNom) {
    const container = document.getElementById('tesis-llista-container');
    const filtered = directorUuid
        ? llistaData.filter(t => (t.directorUuids || []).includes(directorUuid))
        : llistaData;
    container.innerHTML = buildLlistaHtml(filtered);

    const wrapper = document.getElementById('tesisLlistaWrapper');
    const title = wrapper ? wrapper.querySelector('h3') : null;
    if (title) {
        title.textContent = directorUuid
            ? `Tesis de ${directorNom} (clic per desseleccionar)`
            : 'Llista de tesis';
    }
}

function renderLlistaTesis(data) {
    llistaData = data || [];
    selectedDirectorUuid = null;
    applyLlistaFilter(null, null);
}

async function cargarTesisData() {
    const select = document.getElementById('orgSelect');
    const orgVal = select.value;
    const filtrePersonalSelect = document.getElementById('filtrePersonalSelect');
    const filtrePersonal = filtrePersonalSelect ? filtrePersonalSelect.value : 'periode';

    const loadingEl = document.getElementById('tesisLoading');
    const wrapperEl = document.getElementById('tesisWrapper');
    const llistaWrapper = document.getElementById('tesisLlistaWrapper');

    if (!orgVal) {
        if (loadingEl) loadingEl.classList.add('hidden');
        if (tesisTable) tesisTable.setData([]);
        if (directorsTable) directorsTable.setData([]);
        llistaData = [];
        selectedDirectorUuid = null;
        document.getElementById('tesis-llista-container').innerHTML = '';
        return;
    }

    const [, uuid] = orgVal.split(':');
    const [desdeRaw, hastaRaw] = document.getElementById('sliderAnios').noUiSlider.get();
    const desde = parseInt(desdeRaw, 10);
    const hasta = parseInt(hastaRaw, 10);

    try {
        if (loadingEl) loadingEl.classList.remove('hidden');
        if (wrapperEl) wrapperEl.classList.add('hidden');
        if (llistaWrapper) llistaWrapper.classList.add('hidden');

        const params = `orgUuid=${encodeURIComponent(uuid)}&desde=${desde}&hasta=${hasta}&filtrePersonal=${encodeURIComponent(filtrePersonal)}`;
        const basePerAny = (typeof apiUrl === 'function') ? apiUrl('/student-theses/stats/per-year-institute') : '/student-theses/stats/per-year-institute';
        const baseDirectors = (typeof apiUrl === 'function') ? apiUrl('/student-theses/stats/directors-institut') : '/student-theses/stats/directors-institut';
        const baseLlista = (typeof apiUrl === 'function') ? apiUrl('/student-theses/stats/list-institute') : '/student-theses/stats/list-institute';

        const [resPerAny, resDirectors, resLlista] = await Promise.all([
            fetch(`${basePerAny}?${params}`),
            fetch(`${baseDirectors}?${params}`),
            fetch(`${baseLlista}?${params}`)
        ]);

        if (!resPerAny.ok || !resDirectors.ok || !resLlista.ok) throw new Error('Error carregant tesis');

        const [dataPerAny, dataDirectors, dataLlista] = await Promise.all([
            resPerAny.json(), resDirectors.json(), resLlista.json()
        ]);

        if (loadingEl) loadingEl.classList.add('hidden');
        if (wrapperEl) wrapperEl.classList.remove('hidden');
        if (llistaWrapper) llistaWrapper.classList.remove('hidden');

        renderTesisTable(dataPerAny || []);
        renderDirectorsTable(dataDirectors || []);
        renderLlistaTesis(dataLlista || []);
    } catch (e) {
        if (loadingEl) loadingEl.classList.add('hidden');
        if (wrapperEl) wrapperEl.classList.remove('hidden');
        if (llistaWrapper) llistaWrapper.classList.remove('hidden');
        if (tesisTable) tesisTable.setData([]);
        if (directorsTable) directorsTable.setData([]);
        document.getElementById('tesis-llista-container').innerHTML = '<p class="text-sm text-slate-400 py-4">Error carregant dades.</p>';
        console.error('Error carregant tesis', e);
    }
}

function renderAjutsIPsTable(data) {
    ajutsIpsFiltrePersonaUuid = null;
    ajutsIpsFiltrePersonaNom = null;
    const ind = document.getElementById('ajutsIpsFiltreIndicador');
    if (ind) ind.classList.add('hidden');

    const el = document.getElementById('ajuts-ips-table');
    if (!el) return;

    const cols = [
        {
            title: 'Nom', field: 'nombre', sorter: 'string', widthGrow: 2,
            formatter: cell => {
                const v = cell.getValue() || '-';
                const uuid = cell.getRow().getData().personUuid;
                if (uuid) {
                    return `<span class="font-medium">${v}</span>`;
                }
                return v;
            }
        },
        {
            title: 'IP', field: 'nAwardsIP', sorter: 'number', width: 55, hozAlign: 'center',
            formatter: cell => {
                const v = cell.getValue();
                return v > 0 ? `<span class="inline-flex items-center justify-center w-6 h-6 rounded-full bg-indigo-100 text-indigo-700 text-xs font-bold">${v}</span>` : '';
            }
        },
        {
            title: 'Co-IP', field: 'nAwardsCoIP', sorter: 'number', width: 65, hozAlign: 'center',
            formatter: cell => {
                const v = cell.getValue();
                return v > 0 ? `<span class="inline-flex items-center justify-center w-6 h-6 rounded-full bg-violet-100 text-violet-700 text-xs font-bold">${v}</span>` : '';
            }
        }
    ];

    if (!ajutsIpsTable) {
        ajutsIpsTable = new Tabulator(el, {
            layout: 'fitColumns',
            placeholder: 'Sense dades.',
            columns: cols,
            data: data || [],
            initialSort: [{ column: 'nAwardsIP', dir: 'desc' }],
            height: '420px',
            renderVertical: 'virtual',
        });
        ajutsIpsTable.on('rowClick', onAjutsIpsRowClick);
    } else {
        ajutsIpsTable.setData(data || []);
    }
}

async function cargarAjutsData() {
    const select = document.getElementById('orgSelect');
    const orgVal = select.value;

    const loadingEl = document.getElementById('ajutsLoading');
    const wrapperEl = document.getElementById('ajutsWrapper');

    if (!orgVal) {
        if (loadingEl) loadingEl.classList.add('hidden');
        $('#ajuts-pivot-container').empty();
        return;
    }

    const [, uuid] = orgVal.split(':');
    const [desdeRaw, hastaRaw] = document.getElementById('sliderAnios').noUiSlider.get();
    const desde = parseInt(desdeRaw, 10);
    const hasta = parseInt(hastaRaw, 10);

    try {
        if (loadingEl) loadingEl.classList.remove('hidden');
        if (wrapperEl) wrapperEl.classList.add('hidden');

        const pivotUrl = (typeof apiUrl === 'function')
            ? apiUrl(`/awards/stats/powertable?collaboratorUuid=${encodeURIComponent(uuid)}&desde=${desde}&hasta=${hasta}`)
            : `/awards/stats/powertable?collaboratorUuid=${encodeURIComponent(uuid)}&desde=${desde}&hasta=${hasta}`;
        const llistaUrl = (typeof apiUrl === 'function')
            ? apiUrl(`/awards/stats/llista-ajuts-institut?collaboratorUuid=${encodeURIComponent(uuid)}&desde=${desde}&hasta=${hasta}`)
            : `/awards/stats/llista-ajuts-institut?collaboratorUuid=${encodeURIComponent(uuid)}&desde=${desde}&hasta=${hasta}`;
        const ipsUrl = (typeof apiUrl === 'function')
            ? apiUrl(`/awards/stats/ips-institut?collaboratorUuid=${encodeURIComponent(uuid)}&desde=${desde}&hasta=${hasta}`)
            : `/awards/stats/ips-institut?collaboratorUuid=${encodeURIComponent(uuid)}&desde=${desde}&hasta=${hasta}`;

        const [resPivot, resLlista, resIps] = await Promise.all([fetch(pivotUrl), fetch(llistaUrl), fetch(ipsUrl)]);
        if (!resPivot.ok) throw new Error('Error carregant ajuts');
        const data = await resPivot.json();
        const dataLlista = resLlista.ok ? await resLlista.json() : [];
        const dataIps = resIps.ok ? await resIps.json() : [];

        ajutsPivotDataCompleta = data || [];

        if (loadingEl) loadingEl.classList.add('hidden');
        if (wrapperEl) wrapperEl.classList.remove('hidden');

        renderAjutsTable(data || []);
        renderAjutsLlistaTable(dataLlista || []);
        renderAjutsIPsTable(dataIps || []);
    } catch (e) {
        if (loadingEl) loadingEl.classList.add('hidden');
        if (wrapperEl) wrapperEl.classList.remove('hidden');
        $('#ajuts-pivot-container').html('<p class="p-8 text-center text-slate-400">Error carregant dades.</p>');
        console.error('Error carregant ajuts', e);
    }
}

window.addEventListener('DOMContentLoaded', async () => {
    await cargarOrganizacionesCombinadas();
    inicializarSliderDemo();
    initDedicationChart();
    initRoleChart();

    // Actualizar automáticamente cuando cambie la organización
    const orgSelect = document.getElementById('orgSelect');
    const filtrePersonalSelect = document.getElementById('filtrePersonalSelect');
    orgSelect.addEventListener('change', () => {
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(() => {
            if (activeTab === 'ajuts') cargarAjutsData();
            else if (activeTab === 'tesis') cargarTesisData();
            else cargarDemoData();
        }, 300);
    });

    if (filtrePersonalSelect) {
        filtrePersonalSelect.addEventListener('change', () => {
            clearTimeout(debounceTimer);
            debounceTimer = setTimeout(() => {
                if (activeTab === 'tesis') cargarTesisData();
                else cargarDemoData();
            }, 300);
        });
    }

    // Carga inicial
    cargarDemoData();
});
