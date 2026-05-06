// Demo page JS: combina Departaments + Instituts y slider años (2025 - actual)

let demoTable = null;
let debounceTimer = null;
let dedicationChart = null;
let roleChart = null;
let latestChartOptions = { dedication: null, role: null };
let originalChartHeights = { dedication: null, role: null };

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
        debounceTimer = setTimeout(cargarDemoData, 300);
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

    demoTable.setData(rows || []);
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
            updateDedicationChart(rows);
            updateRoleChart(rows);
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
        debounceTimer = setTimeout(cargarDemoData, 300);
    });

    if (filtrePersonalSelect) {
        filtrePersonalSelect.addEventListener('change', () => {
            clearTimeout(debounceTimer);
            debounceTimer = setTimeout(cargarDemoData, 300);
        });
    }

    // Carga inicial
    cargarDemoData();
});
