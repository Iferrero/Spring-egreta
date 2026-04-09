let chartPiramide = null;
let chartSexo = null;
const selectorPersonalType = document.getElementById('selectorPersonalType');
const selectorDepartamento = document.getElementById('selectorDepartamento');

function getPersonalTypeParam() {
    return encodeURIComponent(selectorPersonalType?.value || 'all');
}

function getDeptParam() {
    return selectorDepartamento?.value || '';
}

function buildUrl(path, includePersonalType = true) {
    const params = new URLSearchParams();
    if (includePersonalType) {
        params.set('personalType', selectorPersonalType?.value || 'all');
    }
    const dept = getDeptParam();
    if (dept) {
        params.set('deptUuid', dept);
    }
    const query = params.toString();
    return query ? `${path}?${query}` : path;
}

async function cargarDepartamentos() {
    if (!selectorDepartamento) return;
    try {
        const res = await fetch(apiUrl('/persons/departamentos'));
        if (!res.ok) throw new Error('No s\'han pogut carregar departaments');
        const departamentos = await res.json();
        departamentos.forEach(dep => {
            const option = document.createElement('option');
            option.value = dep.uuid;
            option.textContent = dep.nombre;
            selectorDepartamento.appendChild(option);
        });
    } catch (_) {
        const option = document.createElement('option');
        option.value = '';
        option.textContent = 'No s\'han pogut carregar els departaments';
        selectorDepartamento.appendChild(option);
    }
}

function formatearNumero(valor) {
    return Number(valor || 0).toLocaleString('ca-ES');
}

async function cargarPersonasVigentes() {
    const estado = document.getElementById('estadoVigentes');
    const valor = document.getElementById('valorVigentes');

    estado.textContent = 'Carregant dades...';

    try {
        const url = buildUrl('/persons/vigentes', true);
        const separator = url.includes('?') ? '&' : '?';
        const res = await fetch(apiUrl(`${url}${separator}page=0`));
        const data = await res.json();

        const total = data?.page?.totalElements
            ?? data?.totalElements
            ?? (Array.isArray(data?.content) ? data.content.length : 0);

        valor.textContent = formatearNumero(total);
        estado.textContent = '';
    } catch (e) {
        valor.textContent = '--';
        estado.textContent = 'No s\'han pogut carregar les dades.';
    }
}

async function cargarCatedraticos() {
    const estado = document.getElementById('estadoCatedraticos');
    const valor = document.getElementById('valorCatedraticos');

    estado.textContent = 'Carregant dades...';

    try {
        const res = await fetch(apiUrl(buildUrl('/persons/stats/catedraticos', true)));
        if (!res.ok) throw new Error('No s\'han pogut carregar els catedràtics.');

        const data = await res.json();
        const total = Number(data?.total ?? data?.value?.total ?? 0);

        valor.textContent = formatearNumero(total);
        estado.textContent = '';
    } catch (e) {
        valor.textContent = '--';
        estado.textContent = 'No s\'han pogut carregar les dades.';
    }
}

async function cargarTitulares() {
    const estado = document.getElementById('estadoTitulares');
    const valor = document.getElementById('valorTitulares');

    estado.textContent = 'Carregant dades...';

    try {
        const res = await fetch(apiUrl(buildUrl('/persons/stats/titulares', true)));
        if (!res.ok) throw new Error('No s\'han pogut carregar els titulars.');

        const data = await res.json();
        const total = Number(data?.total ?? data?.value?.total ?? 0);

        valor.textContent = formatearNumero(total);
        estado.textContent = '';
    } catch (e) {
        valor.textContent = '--';
        estado.textContent = 'No s\'han pogut carregar les dades.';
    }
}

async function cargarAgregados() {
    const estado = document.getElementById('estadoAgregados');
    const valor = document.getElementById('valorAgregados');

    estado.textContent = 'Carregant dades...';

    try {
        const res = await fetch(apiUrl(buildUrl('/persons/stats/agregados', true)));
        if (!res.ok) throw new Error('No s\'han pogut carregar els agregats.');

        const data = await res.json();
        const total = Number(data?.total ?? data?.value?.total ?? 0);

        valor.textContent = formatearNumero(total);
        estado.textContent = '';
    } catch (e) {
        valor.textContent = '--';
        estado.textContent = 'No s\'han pogut carregar les dades.';
    }
}

async function cargarLectores() {
    const estado = document.getElementById('estadoLectores');
    const valor = document.getElementById('valorLectores');

    estado.textContent = 'Carregant dades...';

    try {
        const res = await fetch(apiUrl(buildUrl('/persons/stats/lectores', true)));
        if (!res.ok) throw new Error('No s\'han pogut carregar els lectors.');

        const data = await res.json();
        const total = Number(data?.total ?? data?.value?.total ?? 0);

        valor.textContent = formatearNumero(total);
        estado.textContent = '';
    } catch (e) {
        valor.textContent = '--';
        estado.textContent = 'No s\'han pogut carregar les dades.';
    }
}

async function cargarAsociados() {
    const estado = document.getElementById('estadoAsociados');
    const valor = document.getElementById('valorAsociados');

    estado.textContent = 'Carregant dades...';

    try {
        const res = await fetch(apiUrl(buildUrl('/persons/stats/asociados', true)));
        if (!res.ok) throw new Error('No s\'han pogut carregar els associats.');

        const data = await res.json();
        const total = Number(data?.total ?? data?.value?.total ?? 0);

        valor.textContent = formatearNumero(total);
        estado.textContent = '';
    } catch (e) {
        valor.textContent = '--';
        estado.textContent = 'No s\'han pogut carregar les dades.';
    }
}

async function cargarSubstituts() {
    const estado = document.getElementById('estadoSubstituts');
    const valor = document.getElementById('valorSubstituts');

    estado.textContent = 'Carregant dades...';

    try {
        const res = await fetch(apiUrl(buildUrl('/persons/stats/substituts', true)));
        if (!res.ok) throw new Error('No s\'han pogut carregar els substituts.');

        const data = await res.json();
        const total = Number(data?.total ?? data?.value?.total ?? 0);

        valor.textContent = formatearNumero(total);
        estado.textContent = '';
    } catch (e) {
        valor.textContent = '--';
        estado.textContent = 'No s\'han pogut carregar les dades.';
    }
}

async function cargarIcrea() {
    const estado = document.getElementById('estadoIcrea');
    const valor = document.getElementById('valorIcrea');

    estado.textContent = 'Carregant dades...';

    try {
        const res = await fetch(apiUrl(buildUrl('/persons/stats/icrea', true)));
        if (!res.ok) throw new Error('No s\'han pogut carregar les dades ICREA.');

        const data = await res.json();
        const total = Number(data?.total ?? data?.value?.total ?? 0);

        valor.textContent = formatearNumero(total);
        estado.textContent = '';
    } catch (e) {
        valor.textContent = '--';
        estado.textContent = 'No s\'han pogut carregar les dades.';
    }
}

async function cargarPiramideEdad() {
    const estado = document.getElementById('estadoPiramide');
    const contenedor = document.getElementById('chartPiramideEdad');

    try {
        const res = await fetch(apiUrl(buildUrl('/persons/stats/age-pyramid', true)));
        const raw = await res.json();
        const data = Array.isArray(raw) ? raw : (Array.isArray(raw?.value) ? raw.value : []);

        const rangos = (data || []).map(d => d.rango || '-');
        const hombres = (data || []).map(d => -Number(d.hombres || 0));
        const mujeres = (data || []).map(d => Number(d.mujeres || 0));
        const otros = (data || []).map(d => Number(d.otros || 0));
        const totalHombres = (data || []).reduce((acc, d) => acc + Number(d.hombres || 0), 0);
        const totalMujeres = (data || []).reduce((acc, d) => acc + Number(d.mujeres || 0), 0);
        const totalOtros = (data || []).reduce((acc, d) => acc + Number(d.otros || 0), 0);

        if (!chartPiramide) {
            chartPiramide = echarts.init(contenedor);
        }

        chartPiramide.setOption({
            tooltip: {
                trigger: 'axis',
                axisPointer: { type: 'shadow' },
                formatter: params => {
                    const h = Math.abs(Number(params.find(p => p.seriesName === 'Homes')?.value || 0));
                    const m = Math.abs(Number(params.find(p => p.seriesName === 'Dones')?.value || 0));
                    const o = Math.abs(Number(params.find(p => p.seriesName === 'Altres')?.value || 0));
                    return `${params[0].axisValue}<br/>Homes: ${h}<br/>Dones: ${m}<br/>Altres: ${o}`;
                }
            },
            legend: { data: ['Homes', 'Dones', 'Altres'] },
            grid: { left: 70, right: 70, top: 30, bottom: 20 },
            xAxis: {
                type: 'value',
                axisLabel: { formatter: value => Math.abs(value) },
                splitLine: { lineStyle: { color: '#d4d8de' } }
            },
            yAxis: {
                type: 'category',
                data: rangos,
                axisTick: { show: false }
            },
            series: [
                {
                    name: 'Homes',
                    type: 'bar',
                    stack: 'total',
                    itemStyle: { color: '#004D5E' },
                    data: hombres
                },
                {
                    name: 'Dones',
                    type: 'bar',
                    stack: 'total',
                    itemStyle: { color: '#008037' },
                    data: mujeres
                },
                {
                    name: 'Altres',
                    type: 'bar',
                    stack: 'total',
                    itemStyle: { color: '#F88C12' },
                    data: otros
                }
            ]
        });

        renderPastelSexo(totalHombres, totalMujeres, totalOtros);

        estado.textContent = '';
    } catch (e) {
        estado.textContent = 'No s\'ha pogut carregar';
        contenedor.innerHTML = '<div class="h-full flex items-center justify-center text-sm text-rose-500">Error carregant la piràmide d\'edat</div>';
    }
}

function renderPastelSexo(hombres, mujeres, otros) {
    const estado = document.getElementById('estadoSexo');
    const contenedor = document.getElementById('chartSexo');

    if (!chartSexo) {
        chartSexo = echarts.init(contenedor);
    }

    chartSexo.setOption({
        tooltip: {
            trigger: 'item',
            formatter: params => `${params.name}: ${params.value} (${params.percent}%)`
        },
        legend: {
            bottom: 0
        },
        series: [
            {
                name: 'Sexe',
                type: 'pie',
                radius: ['45%', '70%'],
                avoidLabelOverlap: true,
                label: {
                    formatter: '{b}: {d}%'
                },
                data: [
                    { value: Number(hombres || 0), name: 'Homes', itemStyle: { color: '#004D5E' } },
                    { value: Number(mujeres || 0), name: 'Dones', itemStyle: { color: '#008037' } },
                    { value: Number(otros || 0), name: 'Altres', itemStyle: { color: '#F88C12' } }
                ]
            }
        ]
    });

    estado.textContent = '';
}

selectorPersonalType.addEventListener('change', () => {
    cargarPersonasVigentes();
    cargarCatedraticos();
    cargarTitulares();
    cargarAgregados();
    cargarLectores();
    cargarAsociados();
    cargarSubstituts();
    cargarIcrea();
    cargarPredoctorals();
    cargarPostdoctorals();
    cargarPiramideEdad();
    cargarTooltipSummary();
});

selectorDepartamento.addEventListener('change', () => {
    cargarPersonasVigentes();
    cargarCatedraticos();
    cargarTitulares();
    cargarAgregados();
    cargarLectores();
    cargarAsociados();
    cargarSubstituts();
    cargarIcrea();
    cargarPredoctorals();
    cargarPostdoctorals();
    cargarPiramideEdad();
    cargarTooltipSummary();
});

window.addEventListener('resize', () => {
    if (chartPiramide) chartPiramide.resize();
    if (chartSexo) chartSexo.resize();
});

async function cargarTooltipSummary() {
    try {
        const res = await fetch(apiUrl(buildUrl('/persons/stats/employment-types-summary', true)));
        if (!res.ok) return;
        const data = await res.json();

        const tooltipIds = {
            catedraticos: 'tooltipCatedraticos',
            titulares:    'tooltipTitulares',
            agregados:    'tooltipAgregados',
            lectores:     'tooltipLectores',
            asociados:    'tooltipAsociados',
            substituts:   'tooltipSubstituts',
            icrea:        'tooltipIcrea',
            predoctorals: 'tooltipPredoctorals',
            postdoctorals:'tooltipPostdoctorals'
        };

        for (const [key, id] of Object.entries(tooltipIds)) {
            const el = document.getElementById(id);
            if (el && Array.isArray(data[key]) && data[key].length > 0) {
                el.innerHTML = data[key]
                    .map(t => `${t.term} <span class="opacity-60">(${t.count})</span>`)
                    .join('<br>');
            }
        }
    } catch (_) { /* tooltip fallback remains static */ }
}

async function cargarPredoctorals() {
    const estado = document.getElementById('estadoPredoctorals');
    const valor = document.getElementById('valorPredoctorals');
    estado.textContent = 'Carregant dades...';
    try {
        const res = await fetch(apiUrl(buildUrl('/persons/stats/predoctorals', true)));
        if (!res.ok) throw new Error();
        const data = await res.json();
        valor.textContent = formatearNumero(Number(data?.total ?? 0));
        estado.textContent = '';
    } catch (e) {
        valor.textContent = '--';
        estado.textContent = 'No s\'han pogut carregar les dades.';
    }
}

async function cargarPostdoctorals() {
    const estado = document.getElementById('estadoPostdoctorals');
    const valor = document.getElementById('valorPostdoctorals');
    estado.textContent = 'Carregant dades...';
    try {
        const res = await fetch(apiUrl(buildUrl('/persons/stats/postdoctorals', true)));
        if (!res.ok) throw new Error();
        const data = await res.json();
        valor.textContent = formatearNumero(Number(data?.total ?? 0));
        estado.textContent = '';
    } catch (e) {
        valor.textContent = '--';
        estado.textContent = 'No s\'han pogut carregar les dades.';
    }
}

cargarDepartamentos().finally(() => {
    cargarPersonasVigentes();
    cargarCatedraticos();
    cargarTitulares();
    cargarAgregados();
    cargarLectores();
    cargarAsociados();
    cargarSubstituts();
    cargarIcrea();
    cargarPredoctorals();
    cargarPostdoctorals();
    cargarPiramideEdad();
    cargarTooltipSummary();
});
