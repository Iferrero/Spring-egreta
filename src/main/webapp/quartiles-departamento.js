let refrescoTimer = null;
let chartQuartiles = null;
let chartQuartilesEvolution = null;
let departamentosCatalogo = [];
let departamentosSeleccionados = [];

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
    return Array.isArray(departamentosSeleccionados) && departamentosSeleccionados.length > 0;
}

function obtenerFiltrosActuales() {
    const slider = document.getElementById('sliderAnios');
    const [desdeRaw, hastaRaw] = slider.noUiSlider.get();
    const desde = parseInt(desdeRaw, 10);
    const hasta = parseInt(hastaRaw, 10);
    return {
        desde,
        hasta,
        deptUuid: departamentosSeleccionados.length > 0 ? departamentosSeleccionados : []
    };
}

function renderizarChipsDepartamentos() {
    const container = document.getElementById('departamentoChipsContainer');
    const select = document.getElementById('departamentoSelect');
    container.innerHTML = '';

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

    const label = document.getElementById('departamentoDropdownLabel');
    label.textContent = departamentosSeleccionados.length === 0
        ? 'Afegeix departament...'
        : 'Canvia departament...';

    if (hayDepartamentoSeleccionado()) {
        programarRefresco();
    } else {
        updateEstado('Selecciona un departament per carregar dades.', false, false);
    }
}

function toggleDepartamentoSeleccionado(uuid) {
    if (departamentosSeleccionados.includes(uuid)) {
        quitarDepartamentoSeleccionado(uuid);
    } else {
        // Mantener un solo departamento para este informe.
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
    const quartiles = ['Q1', 'Q2', 'Q3', 'Q4', 'Sense Quartil'];

    const series = quartiles.map(q => ({
        name: q,
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        itemStyle: { color: getColorByQuartile(q) },
        lineStyle: { color: getColorByQuartile(q), width: 2 },
        data: rows.map(item => Number(item[q] || 0))
    }));

    chartQuartilesEvolution.setOption({
        tooltip: {
            trigger: 'axis'
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
        return `
            <tr class="odd:bg-white even:bg-slate-50 align-top">
                <td class="px-3 py-2 border-b border-slate-100">${escapeHtml(year)}</td>
                <td class="px-3 py-2 border-b border-slate-100 font-semibold">${escapeHtml(quartile)}</td>
                <td class="px-3 py-2 border-b border-slate-100 text-slate-700">${escapeHtml(cita)}</td>
            </tr>`;
    }).join('');
}

async function cargarDatos() {
    const { desde, hasta, deptUuid } = obtenerFiltrosActuales();

    if (!hayDepartamentoSeleccionado() || !Array.isArray(deptUuid) || deptUuid.length === 0) {
        updateEstado('Selecciona un departament per carregar dades.', false, false);
        return;
    }

    const params = new URLSearchParams({
        desde: String(desde),
        hasta: String(hasta)
    });

    deptUuid.forEach(dep => params.append('deptUuid', dep));

    const deptName = deptUuid.length === 1
        ? (departamentosCatalogo.find(d => d.uuid === deptUuid[0])?.nombre || '')
        : '';

    mostrarOverlayCargando();
    updateEstado('Recarregant dades...');

    try {
        const [responsePie, responseTable, responseEvolution] = await Promise.all([
            apiFetch(`/pure/stats/quartiles?${params.toString()}`),
            apiFetch(`/pure/stats/quartiles/articles?${params.toString()}`),
            apiFetch(`/pure/stats/quartiles/evolution?${params.toString()}`)
        ]);

        if (!responsePie.ok) {
            throw new Error(`Pie HTTP ${responsePie.status}`);
        }
        if (!responseTable.ok) {
            throw new Error(`Table HTTP ${responseTable.status}`);
        }
        if (!responseEvolution.ok) {
            throw new Error(`Evolution HTTP ${responseEvolution.status}`);
        }

        const data = await responsePie.json();
        const articles = await responseTable.json();
        const evolution = await responseEvolution.json();

        renderPie(Array.isArray(data) ? data : [], deptName);
        renderArticlesTable(articles);
        renderEvolution(evolution, deptName);

        const total = (Array.isArray(data) ? data : [])
            .reduce((acc, item) => acc + Number(item.total || 0), 0);
        updateEstado(`Total d'articles analitzats: ${total}`, false, true);
    } catch (error) {
        renderPie([], deptName);
        renderArticlesTable([]);
        renderEvolution([], deptName);
        updateEstado(`Error carregant el grafic: ${error.message}`, true);
    } finally {
        ocultarOverlayCargando();
    }
}

document.addEventListener('click', (e) => {
    const btn = document.getElementById('departamentoDropdownBtn');
    const menu = document.getElementById('departamentoDropdownMenu');
    if (!btn || !menu) return;
    if (!btn.contains(e.target) && !menu.contains(e.target)) {
        cerrarDropdownDepartamentos();
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
}

init();
