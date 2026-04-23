let refrescoTimer = null;
let comparativaRefrescoTimer = null;
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
const chartComparativaPies = new Map();
let departamentosComparativa = [];
let chartComparativaBarras = null;
let chartComparativaGrowth = null;
const comparativaQuartilesData = new Map(); // uuid -> { nombre, q1, total }
const comparativaGrowthData = new Map(); // uuid -> { nombre, growth }

function clearComparativaSelection() {
    departamentosComparativa = [];
    chartComparativaPies.forEach(ch => ch.dispose());
    chartComparativaPies.clear();
    comparativaQuartilesData.clear();
    comparativaGrowthData.clear();
    document.getElementById('comparativaPiesContainer').innerHTML = '';
    renderComparativaBarChart();
    renderComparativaGrowthChart();
    renderComparativaDeptChips();
}

function renderComparativaBarChart() {
    const section = document.getElementById('comparativaBarChartSection');
    const el = document.getElementById('comparativaBarChart');
    if (!section || !el) return;

    const entries = Array.from(comparativaQuartilesData.values())
        .filter(d => d.total > 0)
        .map(d => ({ nombre: d.nombre, pct: Math.round(d.q1 / d.total * 100) }))
        .sort((a, b) => b.pct - a.pct);

    if (entries.length === 0) {
        section.classList.add('hidden');
        if (chartComparativaBarras) { chartComparativaBarras.dispose(); chartComparativaBarras = null; }
        return;
    }

    section.classList.remove('hidden');
    const rowHeight = 32;
    const minHeight = 120;
    el.style.height = Math.max(minHeight, entries.length * rowHeight + 60) + 'px';

    if (!chartComparativaBarras) {
        chartComparativaBarras = echarts.init(el);
    } else {
        chartComparativaBarras.resize();
    }

    const names = entries.map(d => d.nombre);
    const values = entries.map(d => d.pct);

    chartComparativaBarras.setOption({
        tooltip: {
            trigger: 'axis',
            axisPointer: { type: 'shadow' },
            formatter: p => `${p[0].name}: <b>${p[0].value}%</b>`
        },
        grid: { left: 200, right: 48, top: 12, bottom: 24, containLabel: false },
        xAxis: {
            type: 'value',
            max: 100,
            axisLabel: { formatter: '{value}%', color: '#596473', fontSize: 11 },
            splitLine: { lineStyle: { color: '#f1f2f4' } }
        },
        yAxis: {
            type: 'category',
            data: names,
            inverse: true,
            axisLabel: {
                color: '#2a3037',
                fontSize: 11,
                width: 190,
                overflow: 'truncate'
            }
        },
        series: [{
            type: 'bar',
            data: values.map((v, i) => ({
                value: v,
                itemStyle: { color: i === 0 ? '#004d21' : '#008037', borderRadius: [0, 4, 4, 0] }
            })),
            label: { show: true, position: 'right', formatter: '{c}%', fontSize: 11, color: '#596473' },
            barMaxWidth: 24
        }]
    }, true);
}

function renderComparativaGrowthChart() {
    const section = document.getElementById('comparativaGrowthChartSection');
    const el = document.getElementById('comparativaGrowthChart');
    if (!section || !el) return;

    const entries = Array.from(comparativaGrowthData.values())
        .filter(d => d.growth !== null && d.growth !== undefined)
        .sort((a, b) => b.growth - a.growth);

    if (entries.length === 0) {
        section.classList.add('hidden');
        if (chartComparativaGrowth) { chartComparativaGrowth.dispose(); chartComparativaGrowth = null; }
        return;
    }

    section.classList.remove('hidden');
    const rowHeight = 32;
    const minHeight = 120;
    el.style.height = Math.max(minHeight, entries.length * rowHeight + 60) + 'px';

    if (!chartComparativaGrowth) {
        chartComparativaGrowth = echarts.init(el);
    } else {
        chartComparativaGrowth.resize();
    }

    const names = entries.map(d => d.nombre);
    const values = entries.map(d => d.growth);

    chartComparativaGrowth.setOption({
        tooltip: {
            trigger: 'axis',
            axisPointer: { type: 'shadow' },
            formatter: p => `${p[0].name}: <b>${p[0].value > 0 ? '+' : ''}${p[0].value.toFixed(1)}%</b>`
        },
        grid: { left: 200, right: 48, top: 12, bottom: 24, containLabel: false },
        xAxis: {
            type: 'value',
            axisLabel: { formatter: '{value}%', color: '#596473', fontSize: 11 },
            splitLine: { lineStyle: { color: '#f1f2f4' } }
        },
        yAxis: {
            type: 'category',
            data: names,
            inverse: true,
            axisLabel: {
                color: '#2a3037',
                fontSize: 11,
                width: 190,
                overflow: 'truncate'
            }
        },
        series: [{
            type: 'bar',
            data: values.map((v) => ({
                value: v,
                itemStyle: { color: v >= 0 ? '#004d21' : '#dc2626', borderRadius: v >= 0 ? [0, 4, 4, 0] : [4, 0, 0, 4] }
            })),
            label: { show: true, position: 'right', formatter: p => `${p.value > 0 ? '+' : ''}${p.value.toFixed(1)}%`, fontSize: 11, color: '#596473' },
            barMaxWidth: 24
        }]
    }, true);
}

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

function hayComparativaSeleccionada() {
    return Array.isArray(departamentosComparativa) && departamentosComparativa.length > 0;
}

function programarRefrescoComparativa() {
    clearTimeout(comparativaRefrescoTimer);
    comparativaRefrescoTimer = setTimeout(() => {
        refrescarComparativa();
    }, 250);
}

async function refrescarComparativa() {
    if (!hayComparativaSeleccionada()) return;

    const deps = [...departamentosComparativa];
    await Promise.allSettled(deps.map(dep => carregarComparativaPiePerDept(dep)));

    requestAnimationFrame(() => {
        chartComparativaPies.forEach(ch => ch.resize());
        if (chartComparativaBarras) chartComparativaBarras.resize();
        if (chartComparativaGrowth) chartComparativaGrowth.resize();
    });
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
        if (hayComparativaSeleccionada()) {
            programarRefrescoComparativa();
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
    const quartiles = ['Q1', 'Q2', 'Q3', 'Q4', 'Sense Quartil'];

    const yearTotals = rows.map(item =>
        quartiles.reduce((sum, q) => sum + Number(item[q] || 0), 0)
    );

    const series = quartiles.map(q => ({
        name: q,
        type: 'bar',
        stack: 'total',
        barMaxWidth: 48,
        itemStyle: { color: getColorByQuartile(q), borderRadius: q === 'Sense Quartil' ? [4, 4, 0, 0] : 0 },
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
    chartComparativaPies.forEach(ch => ch.resize());
    if (chartComparativaBarras) chartComparativaBarras.resize();
    if (chartComparativaGrowth) chartComparativaGrowth.resize();
});

// ─── Comparativa ─────────────────────────────────────────────────────────────
function activateTab(tab) {
    const tabDept = document.getElementById('tabDept');
    const tabComp = document.getElementById('tabComparativa');
    const btnDept = document.getElementById('tabDeptBtn');
    const btnComp = document.getElementById('tabComparativaBtn');
    const ACTIVE = 'px-4 py-2 text-sm font-semibold text-indigo-700 border-b-2 border-indigo-600 focus:outline-none';
    const INACTIVE = 'px-4 py-2 text-sm font-semibold text-slate-500 hover:text-indigo-700 border-b-2 border-transparent focus:outline-none';
    if (tab === 'comparativa') {
        tabDept.classList.add('hidden');
        tabComp.classList.remove('hidden');
        btnDept.className = INACTIVE;
        btnComp.className = ACTIVE;

        // Re-render after the tab becomes visible to avoid hidden-container render glitches.
        requestAnimationFrame(() => {
            departamentosComparativa.forEach(dep => {
                carregarComparativaPiePerDept(dep);
            });

            requestAnimationFrame(() => {
                chartComparativaPies.forEach(ch => ch.resize());
                if (chartComparativaBarras) chartComparativaBarras.resize();
                if (chartComparativaGrowth) chartComparativaGrowth.resize();
            });
        });
    } else {
        tabComp.classList.add('hidden');
        tabDept.classList.remove('hidden');
        btnComp.className = INACTIVE;
        btnDept.className = ACTIVE;
    }
}

function renderComparativaDeptChips() {
    const container = document.getElementById('compareDepartamentosSeleccionados');
    container.innerHTML = '';
    departamentosComparativa.forEach(dep => {
        const chip = document.createElement('span');
        chip.className = 'inline-flex items-center gap-2 px-3 py-1 rounded-full bg-indigo-50 text-indigo-700 text-xs font-semibold';
        chip.innerHTML = `${escapeHtml(dep.nombre)} <button type="button" class="ml-1 text-indigo-500 hover:text-indigo-700" data-comp-remove="${escapeHtml(dep.uuid)}"><i class="fa-solid fa-xmark"></i></button>`;
        container.appendChild(chip);
    });
    container.querySelectorAll('button[data-comp-remove]').forEach(btn => {
        btn.addEventListener('click', e => {
            const uuid = e.currentTarget.getAttribute('data-comp-remove');
            departamentosComparativa = departamentosComparativa.filter(d => d.uuid !== uuid);
            const ch = chartComparativaPies.get(uuid);
            if (ch) { ch.dispose(); chartComparativaPies.delete(uuid); }
            const card = document.getElementById(`comp-pie-${uuid}`);
            if (card) card.remove();
            comparativaQuartilesData.delete(uuid);
            renderComparativaBarChart();
            renderComparativaDeptChips();
        });
    });
}

async function carregarComparativaPiePerDept(dep) {
    const slider = document.getElementById('sliderAnios');
    const [desdeRaw, hastaRaw] = slider.noUiSlider.get();
    const desde = parseInt(desdeRaw, 10);
    const hasta = parseInt(hastaRaw, 10);
    const filtrePersonalEl = document.querySelector('input[name="filtrePersonal"]:checked');
    const filtrePersonal = filtrePersonalEl ? filtrePersonalEl.value : 'vigent';
    const params = new URLSearchParams({ desde: String(desde), hasta: String(hasta), filtrePersonal });
    params.append('deptUuid', dep.uuid);

    const container = document.getElementById('comparativaPiesContainer');
    let card = document.getElementById(`comp-pie-${dep.uuid}`);
    if (!card) {
        card = document.createElement('section');
        card.id = `comp-pie-${dep.uuid}`;
        card.className = 'bg-white border border-slate-100 rounded-2xl shadow-sm p-4';
        card.innerHTML = `<h3 class="text-xs font-bold text-slate-700 mb-2 truncate" title="${escapeHtml(dep.nombre)}">${escapeHtml(dep.nombre)}</h3><div id="comp-pie-chart-${escapeHtml(dep.uuid)}" class="w-full h-[280px]"></div>`;
        container.appendChild(card);
    }

    const chartEl = document.getElementById(`comp-pie-chart-${dep.uuid}`);
    let ch = chartComparativaPies.get(dep.uuid);
    if (ch) { ch.dispose(); }
    ch = echarts.init(chartEl);
    chartComparativaPies.set(dep.uuid, ch);

    try {
        const response = await apiFetch(`/pure/stats/quartiles/dashboard?${params.toString()}`);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const dashboard = await response.json();
        const data = Array.isArray(dashboard?.quartiles) ? dashboard.quartiles : [];
        const chartData = data.map(item => ({
            name: item.quartile,
            value: item.total,
            itemStyle: { color: getColorByQuartile(item.quartile) }
        }));

        // Store Q1 data for bar chart
        const total = data.reduce((acc, d) => acc + Number(d.total || 0), 0);
        const q1 = Number(data.find(d => d.quartile === 'Q1')?.total || 0);
        comparativaQuartilesData.set(dep.uuid, { nombre: dep.nombre, q1, total });
        renderComparativaBarChart();

        // Compute growth from evolution data (structure: [{year, Q1, Q2, Q3, Q4, ...}])
        const evolution = Array.isArray(dashboard?.evolution) ? dashboard.evolution : [];
        const sortedEvo = evolution.slice().sort((a, b) => Number(a.year) - Number(b.year));
        let growth = null;
        if (sortedEvo.length >= 2) {
            const firstQ1 = Number(sortedEvo[0]['Q1'] || 0);
            const lastQ1 = Number(sortedEvo[sortedEvo.length - 1]['Q1'] || 0);
            if (firstQ1 > 0) {
                growth = (lastQ1 - firstQ1) / firstQ1 * 100;
            } else if (lastQ1 > 0) {
                growth = 100;
            } else {
                growth = 0;
            }
        }
        comparativaGrowthData.set(dep.uuid, { nombre: dep.nombre, growth });
        renderComparativaGrowthChart();

        ch.setOption({
            tooltip: { trigger: 'item', formatter: p => `${p.name}: <b>${p.value}</b> (${p.percent}%)` },
            legend: { orient: 'horizontal', bottom: 0, textStyle: { fontSize: 10, color: '#596473' } },
            series: [{
                name: 'Quartils', type: 'pie', radius: ['30%', '65%'], center: ['50%', '44%'],
                itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
                label: { show: false },
                emphasis: { label: { show: true, formatter: '{b}: {c}', fontSize: 12 } },
                data: chartData
            }]
        });

        requestAnimationFrame(() => ch.resize());
    } catch (_err) {
        ch.setOption({ graphic: [{ type: 'text', left: 'center', top: 'middle',
            style: { text: 'Error carregant dades', fill: '#ef4444', fontSize: 13 } }] });
    }
}

function afegirDepartamentComparativa(dep) {
    if (departamentosComparativa.some(d => d.uuid === dep.uuid)) return;
    departamentosComparativa.push(dep);
    renderComparativaDeptChips();
    carregarComparativaPiePerDept(dep);
}

function poblarCompareDeptoDropdown() {
    const menu = document.getElementById('compareDeptoDropdownMenu');
    menu.innerHTML = '';

    const totsItem = document.createElement('div');
    totsItem.className = 'px-3 py-2 hover:bg-indigo-50 cursor-pointer text-sm font-semibold text-slate-700 border-b border-slate-100';
    totsItem.textContent = 'Tots els departaments';
    totsItem.addEventListener('click', () => {
        clearComparativaSelection();

        departamentosCatalogo.forEach(dep => {
            afegirDepartamentComparativa(dep);
        });

        document.getElementById('compareDeptoDropdownMenu').classList.add('hidden');
    });
    menu.appendChild(totsItem);

    departamentosCatalogo.forEach(dep => {
        const item = document.createElement('div');
        item.className = 'px-3 py-2 hover:bg-indigo-50 cursor-pointer text-sm';
        item.textContent = dep.nombre;
        item.addEventListener('click', () => {
            afegirDepartamentComparativa(dep);
            document.getElementById('compareDeptoDropdownMenu').classList.add('hidden');
        });
        menu.appendChild(item);
    });
}

async function carregarAmbits() {
    const select = document.getElementById('compareAmbitSelect');
    try {
        const response = await apiFetch('/persons/ambits');
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const ambits = await response.json();
        select.innerHTML = '<option value="">Selecciona un àmbit...</option>';
        (Array.isArray(ambits) ? ambits : []).forEach(a => {
            const opt = document.createElement('option');
            opt.value = a;
            opt.textContent = a;
            select.appendChild(opt);
        });
    } catch (_err) {
        select.innerHTML = '<option value="">Error carregant àmbits</option>';
    }
}

async function carregarAmbit() {
    const ambit = document.getElementById('compareAmbitSelect').value;
    if (!ambit) return;
    try {
        const response = await apiFetch(`/persons/departamentos-by-ambit?ambit=${encodeURIComponent(ambit)}`);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const depts = await response.json();
        clearComparativaSelection();
        (Array.isArray(depts) ? depts : []).forEach(dep => {
            afegirDepartamentComparativa({ uuid: dep.uuid, nombre: dep.nombre });
        });
        renderComparativaDeptChips();
    } catch (_err) { /* silent */ }
}

function configurarModeComparativa() {
    const btnDepto = document.getElementById('btnModeDepto');
    const btnAmbit = document.getElementById('btnModeAmbit');
    const ambitSelect = document.getElementById('compareAmbitSelect');
    const btnClear = document.getElementById('btnClearComparativaSelection');
    const btnClearAmbit = document.getElementById('btnClearAmbitSelection');
    const panelDepto = document.getElementById('compareModeDepartament');
    const panelAmbit = document.getElementById('compareModeAmbit');
    const ACTIVE_BTN = 'px-3 py-1.5 text-xs font-semibold rounded-lg bg-indigo-600 text-white';
    const INACTIVE_BTN = 'px-3 py-1.5 text-xs font-semibold rounded-lg bg-white border border-slate-300 text-slate-600 hover:border-indigo-400';

    btnDepto.addEventListener('click', () => {
        btnDepto.className = ACTIVE_BTN;
        btnAmbit.className = INACTIVE_BTN;
        panelDepto.style.display = 'flex';
        panelAmbit.style.display = 'none';
    });
    btnAmbit.addEventListener('click', () => {
        btnAmbit.className = ACTIVE_BTN;
        btnDepto.className = INACTIVE_BTN;
        panelAmbit.style.display = 'flex';
        panelDepto.style.display = 'none';
    });

    if (ambitSelect) {
        ambitSelect.addEventListener('change', carregarAmbit);
    }

    if (btnClear) {
        btnClear.addEventListener('click', () => clearComparativaSelection());
    }

    if (btnClearAmbit) {
        btnClearAmbit.addEventListener('click', () => {
            const ambitSelect = document.getElementById('compareAmbitSelect');
            if (ambitSelect) ambitSelect.value = '';
            clearComparativaSelection();
        });
    }

    const compMenu = document.getElementById('compareDeptoDropdownMenu');
    const compBtn = document.getElementById('compareDeptoDropdownBtn');
    compBtn.addEventListener('click', e => {
        e.stopPropagation();
        if (compMenu.classList.contains('hidden')) {
            poblarCompareDeptoDropdown();
            compMenu.classList.remove('hidden');
        } else {
            compMenu.classList.add('hidden');
        }
    });
    document.addEventListener('click', e => {
        if (!compBtn.contains(e.target) && !compMenu.contains(e.target)) {
            compMenu.classList.add('hidden');
        }
    });
}
// ─────────────────────────────────────────────────────────────────────────────

async function init() {
    const min = 2015;
    const max = 2024;
    configurarSlider(min, max, 2021, 2024);
    await cargarDepartamentos();

    await carregarAmbits();
    document.getElementById('tabDeptBtn').addEventListener('click', () => activateTab('dept'));
    document.getElementById('tabComparativaBtn').addEventListener('click', () => activateTab('comparativa'));
    configurarModeComparativa();

    document.querySelectorAll('input[name="filtrePersonal"]').forEach(radio => {
        radio.addEventListener('change', () => {
            if (hayDepartamentoSeleccionado()) {
                resetPersonaDropdown();
                cargarPersones();
                programarRefresco();
            }
            if (hayComparativaSeleccionada()) {
                programarRefrescoComparativa();
            }
        });
    });
}

init();
