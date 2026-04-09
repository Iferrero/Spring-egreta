let refrescoTimer = null;
let chartQuartiles = null;
let chartQuartilesEvolution = null;
let chartOpenAccess = null;
let departamentosCatalogo = [];
let departamentosSeleccionados = [];
let totsElsDepartaments = false;
let personasCatalogo = [];
let personaSeleccionadaUuid = '';
let currentLoadController = null;
let currentLoadSeq = 0;
let allArticles = [];
let openAccessFilter = null; // null | true | false

function mostrarOverlayCargando() {
    const overlay = document.getElementById('loadingOverlay');
    if (!overlay) return;
    overlay.classList.remove('hidden');
}

function ocultarOverlayCargando() {
    const overlay = document.getElementById('loadingOverlay');
    if (!overlay) return;
    overlay.classList.add('hidden');
}

function programarRefresco() {
    clearTimeout(refrescoTimer);
    refrescoTimer = setTimeout(cargarDatos, 250);
}

function hayDepartamentoSeleccionado() {
    return totsElsDepartaments || (Array.isArray(departamentosSeleccionados) && departamentosSeleccionados.length > 0);
}

function obtenerFiltrosActuales() {
    const slider = document.getElementById('sliderAnios');
    const [desdeRaw, hastaRaw] = slider.noUiSlider.get();
    const desde = parseInt(desdeRaw, 10);
    const hasta = parseInt(hastaRaw, 10);
    const filtrePersonalEl = document.querySelector('input[name="filtrePersonal"]:checked');
    const filtrePersonal = filtrePersonalEl ? filtrePersonalEl.value : 'vigent';
    return {
        desde,
        hasta,
        filtrePersonal,
        deptUuid: departamentosSeleccionados.length > 0 ? departamentosSeleccionados : [],
        personUuid: personaSeleccionadaUuid || null
    };
}

function renderizarChipsDepartamentos() {
    const container = document.getElementById('departamentoChipsContainer');
    const select = document.getElementById('departamentoSelect');
    container.innerHTML = '';

    const label = document.getElementById('departamentoDropdownLabel');

    if (totsElsDepartaments) {
        const chip = document.createElement('span');
        chip.className = 'inline-flex items-center gap-2 px-3 py-1 rounded-full bg-slate-100 text-slate-600 text-xs font-semibold';
        chip.textContent = 'Tots els departaments';
        container.appendChild(chip);
        label.textContent = 'Tots els departaments';
        Array.from(select.options).forEach(opt => { opt.selected = false; });
        resetPersonaDropdown();
        programarRefresco();
        return;
    }

    Array.from(select.options).forEach(opt => {
        opt.selected = departamentosSeleccionados.includes(opt.value);
    });

    departamentosSeleccionados.forEach(uuid => {
        const dep = departamentosCatalogo.find(d => d.uuid === uuid);
        if (!dep) return;

        const chip = document.createElement('span');
        chip.className = 'inline-flex items-center gap-2 px-3 py-1 rounded-full bg-indigo-50 text-indigo-700 text-xs font-semibold';
        chip.innerHTML = `${dep.nombre} <button type="button" class="ml-1 text-indigo-500 hover:text-indigo-700" data-remove-dep="${dep.uuid}"><i class="fa-solid fa-xmark"></i></button>`;
        container.appendChild(chip);
    });

    container.querySelectorAll('button[data-remove-dep]').forEach(btn => {
        btn.addEventListener('click', (e) => {
            const uuid = e.currentTarget.getAttribute('data-remove-dep');
            quitarDepartamentoSeleccionado(uuid);
        });
    });

    label.textContent = departamentosSeleccionados.length === 0
        ? 'Afegeix departament...'
        : 'Canvia departament...';

    if (hayDepartamentoSeleccionado()) {
        cargarPersones();
        programarRefresco();
    } else {
        resetPersonaDropdown();
        updateEstado('Selecciona un departament per carregar dades.', false, false);
    }
}

function toggleDepartamentoSeleccionado(uuid) {
    totsElsDepartaments = false;
    if (departamentosSeleccionados.includes(uuid)) {
        quitarDepartamentoSeleccionado(uuid);
    } else {
        departamentosSeleccionados = [uuid];
        renderizarChipsDepartamentos();
    }
}

function quitarDepartamentoSeleccionado(uuid) {
    departamentosSeleccionados = departamentosSeleccionados.filter(d => d !== uuid);
    renderizarChipsDepartamentos();
}

function abrirDropdownDepartamentos() {
    document.getElementById('departamentoDropdownMenu').classList.remove('hidden');
}

function cerrarDropdownDepartamentos() {
    document.getElementById('departamentoDropdownMenu').classList.add('hidden');
}

function resetPersonaDropdown() {
    personaSeleccionadaUuid = '';
    document.getElementById('personaSeleccionadaUuid').value = '';
    const label = document.getElementById('personaDropdownLabel');
    label.textContent = 'Totes les persones';
    label.classList.add('text-slate-400');
    label.classList.remove('text-slate-800');
    document.getElementById('personaDropdownBtn').disabled = true;
    document.getElementById('personaDropdownList').innerHTML = '';
}

function abrirDropdownPersones() {
    document.getElementById('personaDropdownMenu').classList.remove('hidden');
}

function cerrarDropdownPersones() {
    document.getElementById('personaDropdownMenu').classList.add('hidden');
}

async function cargarPersones() {
    const { desde, hasta, filtrePersonal, deptUuid } = obtenerFiltrosActuales();
    if (!deptUuid || deptUuid.length === 0) {
        resetPersonaDropdown();
        return;
    }
    const uuid = deptUuid[0];
    const params = new URLSearchParams({
        deptUuid: uuid,
        filtrePersonal,
        desde: String(desde),
        hasta: String(hasta)
    });

    const btn = document.getElementById('personaDropdownBtn');
    const list = document.getElementById('personaDropdownList');
    btn.disabled = true;
    list.innerHTML = '<li class="px-3 py-2 text-slate-400 text-sm">Carregant...</li>';

    try {
        const response = await apiFetch(`/persons/by-dept?${params.toString()}`);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const persons = await response.json();
        personasCatalogo = Array.isArray(persons) ? persons : [];

        list.innerHTML = '';

        const allItem = document.createElement('li');
        allItem.className = 'px-3 py-2 hover:bg-indigo-50 cursor-pointer text-sm text-slate-500 italic';
        allItem.textContent = 'Totes les persones';
        allItem.addEventListener('click', () => {
            personaSeleccionadaUuid = '';
            document.getElementById('personaSeleccionadaUuid').value = '';
            const lbl = document.getElementById('personaDropdownLabel');
            lbl.textContent = 'Totes les persones';
            lbl.classList.add('text-slate-400');
            lbl.classList.remove('text-slate-800');
            cerrarDropdownPersones();
            programarRefresco();
        });
        list.appendChild(allItem);

        personasCatalogo.forEach(p => {
            const item = document.createElement('li');
            item.className = 'px-3 py-2 hover:bg-indigo-50 cursor-pointer text-sm';
            const today = new Date().toISOString().slice(0, 10);
            const isGone = p.endDate && p.endDate < today;
            const endDateEs = p.endDate ? p.endDate.slice(8, 10) + '/' + p.endDate.slice(5, 7) + '/' + p.endDate.slice(0, 4) : '';
            if (isGone) {
                item.innerHTML = `${escapeHtml(p.nombre)} <span class="text-slate-400 text-xs">(fins ${escapeHtml(endDateEs)})</span>`;
            } else {
                item.textContent = p.nombre;
            }
            item.addEventListener('click', () => {
                personaSeleccionadaUuid = p.uuid;
                document.getElementById('personaSeleccionadaUuid').value = p.uuid;
                const lbl = document.getElementById('personaDropdownLabel');
                lbl.innerHTML = isGone
                    ? `${escapeHtml(p.nombre)} <span class="text-slate-400 text-xs">(fins ${escapeHtml(endDateEs)})</span>`
                    : escapeHtml(p.nombre);
                lbl.classList.remove('text-slate-400');
                lbl.classList.add('text-slate-800');
                cerrarDropdownPersones();
                programarRefresco();
            });
            list.appendChild(item);
        });

        btn.disabled = false;
    } catch (_error) {
        list.innerHTML = '<li class="px-3 py-2 text-red-500 text-sm">Error carregant persones</li>';
        btn.disabled = false;
    }
}

async function cargarDepartamentos() {
    const select = document.getElementById('departamentoSelect');
    const menu = document.getElementById('departamentoDropdownMenu');
    select.innerHTML = '';
    menu.innerHTML = '';

    try {
        const response = await apiFetch('/persons/departamentos');
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }

        const departamentos = await response.json();
        departamentosCatalogo = Array.isArray(departamentos) ? departamentos : [];

        const totsItem = document.createElement('div');
        totsItem.className = 'px-3 py-2 hover:bg-indigo-50 cursor-pointer text-sm font-semibold text-slate-700 border-b border-slate-100';
        totsItem.textContent = 'Tots els departaments';
        totsItem.addEventListener('click', () => {
            totsElsDepartaments = true;
            departamentosSeleccionados = [];
            renderizarChipsDepartamentos();
            cerrarDropdownDepartamentos();
        });
        menu.appendChild(totsItem);

        departamentosCatalogo.forEach(dep => {
            const option = document.createElement('option');
            option.value = dep.uuid;
            option.textContent = dep.nombre;
            select.appendChild(option);

            const item = document.createElement('div');
            item.className = 'px-3 py-2 hover:bg-indigo-50 cursor-pointer text-sm';
            item.textContent = dep.nombre;
            item.dataset.value = dep.uuid;
            item.addEventListener('click', () => {
                toggleDepartamentoSeleccionado(dep.uuid);
                cerrarDropdownDepartamentos();
            });
            menu.appendChild(item);
        });
    } catch (_error) {
        const option = document.createElement('option');
        option.value = '';
        option.textContent = 'No s\'han pogut carregar els departaments';
        select.appendChild(option);
        menu.innerHTML = '<div class="px-3 py-2 text-red-600 text-sm">No s\'han pogut carregar els departaments</div>';
    }

    totsElsDepartaments = false;
    renderizarChipsDepartamentos();
}

function configurarSlider(min, max, defaultDesde = 2021, defaultHasta = 2025) {
    const slider = document.getElementById('sliderAnios');
    const startDesde = Math.max(min, Math.min(max, defaultDesde));
    const startHasta = Math.max(startDesde, Math.min(max, defaultHasta));
    noUiSlider.create(slider, {
        start: [startDesde, startHasta],
        connect: true,
        step: 1,
        tooltips: true,
        range: { min, max },
        format: {
            to: value => Math.round(value),
            from: value => Number(value)
        }
    });

    const valorRango = document.getElementById('valorRango');
    slider.noUiSlider.on('update', (values) => {
        valorRango.textContent = `${values[0]} - ${values[1]}`;
    });

    slider.noUiSlider.on('set', () => {
        if (hayDepartamentoSeleccionado()) {
            resetPersonaDropdown();
            cargarPersones();
            programarRefresco();
        }
    });
}

function getUabColor(cssVarName, fallback) {
    const value = getComputedStyle(document.documentElement)
        .getPropertyValue(cssVarName)
        .trim();
    return value || fallback;
}

function getColorByQuartile(q) {
    const palette = {
        Q1: getUabColor('--uab-collserola', '#004d21'),
        Q2: getUabColor('--uab-campus', '#008037'),
        Q3: getUabColor('--uab-pesol', '#00cc58'),
        Q4: getUabColor('--uab-cala', '#004d5e'),
        'Sense Quartil': getUabColor('--uab-cendra', '#a9b1bc')
    };
    return palette[q] || getUabColor('--uab-tauro', '#596473');
}

function updateEstado(message, isError = false, isSuccess = false) {
    const estado = document.getElementById('estado');
    estado.textContent = message;
    estado.className = isError
        ? 'text-sm text-red-600 mb-3'
        : isSuccess
            ? 'text-sm text-emerald-600 font-semibold mb-3'
            : 'text-sm text-indigo-600 font-semibold mb-3';
}

function renderPie(data, deptName) {
    const container = document.getElementById('quartilesPie');
    if (!chartQuartiles) {
        chartQuartiles = echarts.init(container);
    }

    const chartData = (Array.isArray(data) ? data : []).map(item => ({
        name: item.quartile,
        value: item.total,
        itemStyle: { color: getColorByQuartile(item.quartile) }
    }));

    chartQuartiles.setOption({
        tooltip: {
            trigger: 'item',
            formatter: params => `${params.name}: <b>${params.value}</b> (${params.percent}%)`
        },
        legend: {
            orient: 'vertical',
            right: 10,
            top: 'center',
            textStyle: {
                color: getUabColor('--uab-tauro', '#596473')
            }
        },
        series: [
            {
                name: 'Quartils',
                type: 'pie',
                radius: ['35%', '70%'],
                center: ['40%', '50%'],
                itemStyle: {
                    borderRadius: 8,
                    borderColor: getUabColor('--uab-coco', '#ffffff'),
                    borderWidth: 2
                },
                label: {
                    formatter: '{b}: {c}'
                },
                data: chartData
            }
        ],
        title: {
            text: deptName ? `Distribucio de quartils - ${deptName}` : 'Distribucio de quartils',
            left: 'center',
            top: 6,
            textStyle: {
                fontSize: 14,
                fontWeight: 700,
                color: getUabColor('--uab-pissarra', '#2a3037')
            }
        }
    });
}

function renderEvolution(data, deptName) {
    const container = document.getElementById('quartilesEvolution');
    const sectionTitle = document.getElementById('quartilesEvolutionTitle');
    if (!chartQuartilesEvolution) {
        chartQuartilesEvolution = echarts.init(container);
    }

    if (sectionTitle) {
        sectionTitle.textContent = deptName
            ? `Evolucio anual per quartils - ${deptName}`
            : 'Evolucio anual per quartils';
    }

    const rows = Array.isArray(data) ? data : [];
    const years = rows.map(item => String(item.year ?? ''));
    const quartiles = ['Q1', 'Q2', 'Q3', 'Q4'];

    const yearTotals = rows.map(item =>
        quartiles.reduce((sum, q) => sum + Number(item[q] || 0), 0)
    );

    const series = quartiles.map(q => ({
        name: q,
        type: 'bar',
        stack: 'total',
        barMaxWidth: 48,
        itemStyle: { color: getColorByQuartile(q), borderRadius: q === 'Q4' ? [4, 4, 0, 0] : 0 },
        emphasis: { focus: 'series' },
        label: {
            show: true,
            position: 'inside',
            formatter: p => {
                if (p.value <= 0) return '';
                const total = yearTotals[p.dataIndex] || 0;
                const pct = total > 0 ? Math.round(p.value / total * 100) : 0;
                return `${pct}%`;
            },
            fontSize: 11,
            color: '#fff',
            lineHeight: 16
        },
        data: rows.map(item => Number(item[q] || 0))
    }));

    chartQuartilesEvolution.setOption({
        tooltip: {
            trigger: 'axis',
            axisPointer: { type: 'shadow' }
        },
        legend: {
            top: 0
        },
        grid: {
            left: 42,
            right: 20,
            top: 42,
            bottom: 28
        },
        xAxis: {
            type: 'category',
            data: years,
            axisLabel: { color: getUabColor('--uab-tauro', '#596473') }
        },
        yAxis: {
            type: 'value',
            minInterval: 1,
            axisLabel: { color: getUabColor('--uab-tauro', '#596473') }
        },
        series
    });
}

function renderOpenAccessPie(data) {
    const container = document.getElementById('openAccessPie');
    if (!chartOpenAccess) {
        chartOpenAccess = echarts.init(container);
    }

    const rows = Array.isArray(data) ? data : [];
    const total = rows.reduce((acc, r) => acc + Number(r.value || 0), 0);

    if (rows.length === 0 || total === 0) {
        chartOpenAccess.setOption({
            title: { text: 'Accés obert', left: 'center', top: 6, textStyle: { fontSize: 14, fontWeight: 700, color: getUabColor('--uab-pissarra', '#2a3037') } },
            series: [{ type: 'pie', radius: ['35%', '70%'], data: [], label: { show: true, formatter: 'Sense dades' } }]
        });
        return;
    }

    const colorMap = {
        'Accés obert': getUabColor('--uab-campus', '#008037'),
        'Accés tancat': getUabColor('--uab-cendra', '#a9b1bc')
    };

    const chartData = rows.map(r => ({
        name: r.label,
        value: r.value,
        itemStyle: { color: colorMap[r.label] || getUabColor('--uab-tauro', '#596473') }
    }));

    chartOpenAccess.setOption({
        tooltip: {
            trigger: 'item',
            formatter: params => `${params.name}: <b>${params.value}</b> (${params.percent}%)`
        },
        series: [{
            name: 'Accés obert',
            type: 'pie',
            radius: ['35%', '70%'],
            center: ['50%', '55%'],
            cursor: 'pointer',
            itemStyle: {
                borderRadius: 8,
                borderColor: getUabColor('--uab-coco', '#ffffff'),
                borderWidth: 2
            },
            label: { formatter: '{b}: {c}' },
            data: chartData
        }],
        title: {
            text: '',
            left: 'center',
            top: 6,
            textStyle: { fontSize: 14, fontWeight: 700, color: getUabColor('--uab-pissarra', '#2a3037') }
        }
    });

    chartOpenAccess.off('click');
    chartOpenAccess.on('click', params => {
        applyOpenAccessFilter(params.name);
    });
}

function escapeHtml(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function renderArticlesTable(rows) {
    const tbody = document.getElementById('tablaArticulosBody');
    const data = Array.isArray(rows) ? rows : [];

    if (data.length === 0) {
        tbody.innerHTML = '<tr><td colspan="3" class="px-3 py-3 text-slate-500">No hi ha articles per als filtres seleccionats.</td></tr>';
        return;
    }

    tbody.innerHTML = data.map(item => {
        const year = item.year ?? '-';
        const quartile = item.quartile ?? 'Sense Quartil';
        const cita = item.cita ?? '-';
        const oa = item.openAccess === true;
        const oaBadge = oa
            ? '<span class="inline-block ml-1 px-1.5 py-0.5 rounded text-[10px] font-bold bg-emerald-100 text-emerald-700">OA</span>'
            : '';
        return `
            <tr class="odd:bg-white even:bg-slate-50 align-top">
                <td class="px-3 py-2 border-b border-slate-100">${escapeHtml(year)}</td>
                <td class="px-3 py-2 border-b border-slate-100 font-semibold">${escapeHtml(quartile)}${oaBadge}</td>
                <td class="px-3 py-2 border-b border-slate-100 text-slate-700">${escapeHtml(cita)}</td>
            </tr>`;
    }).join('');
}

function applyOpenAccessFilter(label) {
    // Toggle: clicking the same segment again clears the filter
    if (label === 'Accés obert') {
        openAccessFilter = openAccessFilter === true ? null : true;
    } else if (label === 'Accés tancat') {
        openAccessFilter = openAccessFilter === false ? null : false;
    } else {
        openAccessFilter = null;
    }
    updateOpenAccessFilterBadge();
    const filtered = openAccessFilter === null
        ? allArticles
        : allArticles.filter(a => a.openAccess === openAccessFilter);
    renderArticlesTable(filtered);
}

function updateOpenAccessFilterBadge() {
    const badge = document.getElementById('openAccessFilterBadge');
    if (!badge) return;
    if (openAccessFilter === null) {
        badge.classList.add('hidden');
        badge.textContent = '';
    } else {
        const label = openAccessFilter ? 'Accés obert' : 'Accés tancat';
        badge.textContent = `Filtre: ${label} ✕`;
        badge.classList.remove('hidden');
    }
}

async function cargarDatos() {
    const { desde, hasta, deptUuid, filtrePersonal, personUuid } = obtenerFiltrosActuales();

    if (!hayDepartamentoSeleccionado()) {
        if (currentLoadController) {
            currentLoadController.abort();
            currentLoadController = null;
        }
        ocultarOverlayCargando();
        updateEstado('Selecciona un departament per carregar dades.', false, false);
        return;
    }

    const params = new URLSearchParams({
        desde: String(desde),
        hasta: String(hasta),
        filtrePersonal
    });

    if (personUuid) params.append('personUuid', personUuid);
    if (!totsElsDepartaments) {
        deptUuid.forEach(dep => params.append('deptUuid', dep));
    }

    const deptName = totsElsDepartaments
        ? 'Tots els departaments'
        : (deptUuid.length === 1 ? (departamentosCatalogo.find(d => d.uuid === deptUuid[0])?.nombre || '') : '');

    if (currentLoadController) {
        currentLoadController.abort();
    }
    const controller = new AbortController();
    currentLoadController = controller;
    const requestSeq = ++currentLoadSeq;

    mostrarOverlayCargando();
    updateEstado('Recarregant dades...');

    try {
        const response = await apiFetch(`/pure/stats/quartiles/dashboard?${params.toString()}`, {
            signal: controller.signal
        });
        if (!response.ok) {
            throw new Error(`Dashboard HTTP ${response.status}`);
        }

        const dashboard = await response.json();
        if (requestSeq !== currentLoadSeq) {
            return;
        }

        const data = Array.isArray(dashboard?.quartiles) ? dashboard.quartiles : [];
        const articles = Array.isArray(dashboard?.articles) ? dashboard.articles : [];
        const evolution = Array.isArray(dashboard?.evolution) ? dashboard.evolution : [];
        const openAccess = Array.isArray(dashboard?.openAccess) ? dashboard.openAccess : [];

        allArticles = articles;
        openAccessFilter = null;
        updateOpenAccessFilterBadge();

        renderPie(Array.isArray(data) ? data : [], deptName);
        renderArticlesTable(articles);
        renderEvolution(evolution, deptName);
        renderOpenAccessPie(openAccess);

        const total = (Array.isArray(data) ? data : [])
            .reduce((acc, item) => acc + Number(item.total || 0), 0);
        updateEstado(`Total d'articles analitzats: ${total}`, false, true);
    } catch (error) {
        if (error && error.name === 'AbortError') {
            return;
        }

        if (requestSeq !== currentLoadSeq) {
            return;
        }

        allArticles = [];
        openAccessFilter = null;
        updateOpenAccessFilterBadge();
        renderPie([], deptName);
        renderArticlesTable([]);
        renderEvolution([], deptName);
        renderOpenAccessPie([]);
        updateEstado(`Error carregant el grafic: ${error.message}`, true);
    } finally {
        if (requestSeq === currentLoadSeq) {
            ocultarOverlayCargando();
        }
    }
}

document.addEventListener('click', (e) => {
    const btn = document.getElementById('departamentoDropdownBtn');
    const menu = document.getElementById('departamentoDropdownMenu');
    if (!btn || !menu) return;
    if (!btn.contains(e.target) && !menu.contains(e.target)) {
        cerrarDropdownDepartamentos();
    }

    const pBtn = document.getElementById('personaDropdownBtn');
    const pMenu = document.getElementById('personaDropdownMenu');
    if (!pBtn || !pMenu) return;
    if (!pBtn.contains(e.target) && !pMenu.contains(e.target)) {
        cerrarDropdownPersones();
    }
});

document.getElementById('departamentoDropdownBtn').addEventListener('click', (e) => {
    e.stopPropagation();
    const menu = document.getElementById('departamentoDropdownMenu');
    if (menu.classList.contains('hidden')) {
        abrirDropdownDepartamentos();
    } else {
        cerrarDropdownDepartamentos();
    }
});

document.getElementById('personaDropdownBtn').addEventListener('click', (e) => {
    e.stopPropagation();
    const menu = document.getElementById('personaDropdownMenu');
    if (menu.classList.contains('hidden')) {
        abrirDropdownPersones();
    } else {
        cerrarDropdownPersones();
    }
});

window.addEventListener('resize', () => {
    if (chartQuartiles) {
        chartQuartiles.resize();
    }
    if (chartQuartilesEvolution) {
        chartQuartilesEvolution.resize();
    }
});

async function init() {
    const min = 2015;
    const max = 2024;
    configurarSlider(min, max, 2021, 2024);
    await cargarDepartamentos();

    document.querySelectorAll('input[name="filtrePersonal"]').forEach(radio => {
        radio.addEventListener('change', () => {
            if (hayDepartamentoSeleccionado()) {
                resetPersonaDropdown();
                cargarPersones();
                programarRefresco();
            }
        });
    });
}

init();
