let chartPiramide = null;
let chartSexo = null;
let chartContractType = null;
let chartNacionalitat = null;
let chartCatedraticosBreakdown = null;
let _worldGeoJson = null;
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

async function cargarPersonalAcademic() {
    const estado = document.getElementById('estadoPersonalAcademic');
    const valor = document.getElementById('valorPersonalAcademic');

    estado.textContent = 'Carregant dades...';

    try {
        const dept = getDeptParam();
        const url = dept ? `/persons/stats/personal-academic?deptUuid=${encodeURIComponent(dept)}` : '/persons/stats/personal-academic';
        const res = await fetch(apiUrl(url));
        if (!res.ok) throw new Error();
        const data = await res.json();
        valor.textContent = formatearNumero(Number(data?.total ?? 0));
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

        estado.textContent = '';
    } catch (e) {
        estado.textContent = 'No s\'ha pogut carregar';
        contenedor.innerHTML = '<div class="h-full flex items-center justify-center text-sm text-rose-500">Error carregant la piràmide d\'edat</div>';
    }
}

async function cargarSexDistribution() {
    const estado = document.getElementById('estadoSexo');
    const contenedor = document.getElementById('chartSexo');
    estado.textContent = 'Carregant...';
    try {
        const res = await fetch(apiUrl(buildUrl('/persons/stats/sex-distribution', true)));
        if (!res.ok) throw new Error();
        const data = await res.json();
        renderPastelSexo(data.hombres, data.mujeres, data.otros);
    } catch (e) {
        estado.textContent = 'No s\'ha pogut carregar';
        contenedor.innerHTML = '<div class="h-full flex items-center justify-center text-sm text-rose-500">Error carregant el gr\u00e0fic de sexe</div>';
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
    cargarPersonalAcademic();
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
    cargarSexDistribution();
    cargarContractType();
    cargarNacionalitat();
    cargarTooltipSummary();
    cargarCatedraticosBreakdown();
});

selectorDepartamento.addEventListener('change', () => {
    cargarPersonasVigentes();
    cargarPersonalAcademic();
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
    cargarSexDistribution();
    cargarContractType();
    cargarNacionalitat();
    cargarTooltipSummary();
    cargarCatedraticosBreakdown();
});

window.addEventListener('resize', () => {
    if (chartPiramide) chartPiramide.resize();
    if (chartSexo) chartSexo.resize();
    if (chartContractType) chartContractType.resize();
    if (chartNacionalitat) chartNacionalitat.resize();
    if (chartCatedraticosBreakdown) chartCatedraticosBreakdown.resize();
});

async function cargarCatedraticosBreakdown() {
    const estado = document.getElementById('estadoCatedraticosBreakdown');
    const contenedor = document.getElementById('chartCatedraticosBreakdown');
    if (!estado || !contenedor) return;
    estado.textContent = 'Carregant...';
    try {
        const dept = getDeptParam();
        const url = dept
            ? `/persons/stats/catedraticos-breakdown?deptUuid=${encodeURIComponent(dept)}`
            : '/persons/stats/catedraticos-breakdown';
        const res = await fetch(apiUrl(url));
        if (!res.ok) throw new Error();
        const data = await res.json();
        const sorted = [...data].sort((a, b) => a.count - b.count);
        const PALETTE = ['#3b82f6', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#06b6d4', '#ec4899', '#84cc16'];
        if (!chartCatedraticosBreakdown) {
            chartCatedraticosBreakdown = echarts.init(contenedor);
        }
        chartCatedraticosBreakdown.setOption({
            tooltip: {
                trigger: 'axis',
                axisPointer: { type: 'shadow' },
                formatter: p => `<b>${p[0].name}</b><br/>${p[0].value} persones`
            },
            grid: { left: '2%', right: '8%', top: '4%', bottom: '4%', containLabel: true },
            xAxis: {
                type: 'value',
                axisLabel: { color: '#94a3b8' },
                splitLine: { lineStyle: { color: '#f1f5f9' } }
            },
            yAxis: {
                type: 'category',
                data: sorted.map(d => d.tipo),
                axisLabel: { color: '#475569', fontSize: 11, width: 260, overflow: 'truncate' }
            },
            series: [{
                type: 'bar',
                data: sorted.map((d, i) => ({
                    value: d.count,
                    itemStyle: { color: PALETTE[i % PALETTE.length], borderRadius: [0, 4, 4, 0] }
                })),
                label: { show: true, position: 'right', color: '#475569', fontSize: 11,
                         formatter: p => p.value }
            }]
        });
        estado.textContent = '';
    } catch (e) {
        estado.textContent = "No s'ha pogut carregar";
    }
}

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
    cargarPersonalAcademic();
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
    cargarSexDistribution();
    cargarContractType();
    cargarNacionalitat();
    cargarTooltipSummary();
    cargarCatedraticosBreakdown();
});

async function cargarContractType() {
    const estado = document.getElementById('estadoContractType');
    const contenedor = document.getElementById('chartContractType');
    if (!estado || !contenedor) return;
    estado.textContent = 'Carregant...';
    try {
        const res = await fetch(apiUrl(buildUrl('/persons/stats/contract-type', true)));
        if (!res.ok) throw new Error();
        const data = await res.json();
        const permanent = Number(data?.permanent ?? 0);
        const noPermament = Number(data?.noPermament ?? 0);

        if (!chartContractType) {
            chartContractType = echarts.init(contenedor);
        }
        chartContractType.setOption({
            tooltip: {
                trigger: 'item',
                formatter: params => `${params.name}: ${params.value} (${params.percent}%)`
            },
            legend: { bottom: 0 },
            series: [{
                name: 'Tipus de contracte',
                type: 'pie',
                radius: ['45%', '70%'],
                avoidLabelOverlap: true,
                label: { formatter: '{b}: {d}%' },
                data: [
                    { value: permanent,   name: 'Permanent',     itemStyle: { color: '#004D5E' } },
                    { value: noPermament, name: 'No permanent',  itemStyle: { color: '#F88C12' } }
                ]
            }]
        });
        estado.textContent = '';
    } catch (e) {
        estado.textContent = 'No s\'han pogut carregar les dades.';
    }
}

// ── Origen per nacionalitat ───────────────────────────────────────────────────

async function getWorldGeoJson() {
    if (_worldGeoJson) return _worldGeoJson;
    const res = await fetch('https://cdn.jsdelivr.net/npm/echarts@4.9.0/map/json/world.json');
    if (!res.ok) throw new Error('GeoJSON no disponible');
    _worldGeoJson = await res.json();
    echarts.registerMap('world', _worldGeoJson);
    return _worldGeoJson;
}

const ISO2_TO_MAP_NAME = {
    ES: 'Spain', FR: 'France', DE: 'Germany', IT: 'Italy', PT: 'Portugal',
    GB: 'United Kingdom', NL: 'Netherlands', BE: 'Belgium', CH: 'Switzerland',
    AT: 'Austria', PL: 'Poland', SE: 'Sweden', NO: 'Norway', DK: 'Denmark',
    FI: 'Finland', CZ: 'Czech Rep.', HU: 'Hungary', RO: 'Romania', GR: 'Greece',
    RU: 'Russia', UA: 'Ukraine', TR: 'Turkey', IL: 'Israel',
    US: 'United States', CA: 'Canada', MX: 'Mexico', BR: 'Brazil',
    AR: 'Argentina', CL: 'Chile', CO: 'Colombia', VE: 'Venezuela', PE: 'Peru',
    UY: 'Uruguay', BO: 'Bolivia', EC: 'Ecuador', PY: 'Paraguay', CU: 'Cuba',
    CN: 'China', JP: 'Japan', KR: 'S. Korea', IN: 'India', AU: 'Australia',
    ZA: 'South Africa', EG: 'Egypt', MA: 'Morocco', TN: 'Tunisia', NG: 'Nigeria',
    IR: 'Iran', IQ: 'Iraq', PK: 'Pakistan', BD: 'Bangladesh', TH: 'Thailand',
    VN: 'Vietnam', PH: 'Philippines', ID: 'Indonesia', MY: 'Malaysia',
    GT: 'Guatemala', HN: 'Honduras', SV: 'El Salvador', NI: 'Nicaragua',
    CR: 'Costa Rica', PA: 'Panama', DO: 'Dominican Rep.',
    CY: 'Cyprus', MT: 'Malta', LU: 'Luxembourg', IE: 'Ireland', SK: 'Slovakia',
    SI: 'Slovenia', HR: 'Croatia', BA: 'Bosnia and Herz.', RS: 'Serbia',
    MK: 'Macedonia', AL: 'Albania', BG: 'Bulgaria', LT: 'Lithuania',
    LV: 'Latvia', EE: 'Estonia', BY: 'Belarus', MD: 'Moldova',
    GE: 'Georgia', AM: 'Armenia', AZ: 'Azerbaijan', KZ: 'Kazakhstan',
    UZ: 'Uzbekistan', AF: 'Afghanistan', SY: 'Syria', LB: 'Lebanon',
    JO: 'Jordan', SA: 'Saudi Arabia', AE: 'United Arab Emirates',
    QA: 'Qatar', KW: 'Kuwait', BH: 'Bahrain', OM: 'Oman', YE: 'Yemen',
    LY: 'Libya', DZ: 'Algeria', SD: 'Sudan', ET: 'Ethiopia', KE: 'Kenya',
    UG: 'Uganda', TZ: 'Tanzania', MZ: 'Mozambique', ZW: 'Zimbabwe',
    CM: 'Cameroon', CI: "Côte d'Ivoire", SN: 'Senegal', GH: 'Ghana',
    NZ: 'New Zealand'
};

const COUNTRY_NAMES_CA = {
    ES: 'Espanya', FR: 'França', DE: 'Alemanya', IT: 'Itàlia', PT: 'Portugal',
    GB: 'Regne Unit', NL: 'Països Baixos', BE: 'Bèlgica', CH: 'Suïssa',
    AT: 'Àustria', PL: 'Polònia', SE: 'Suècia', NO: 'Noruega', DK: 'Dinamarca',
    FI: 'Finlàndia', CZ: 'Txèquia', HU: 'Hongria', RO: 'Romania', GR: 'Grècia',
    RU: 'Rússia', UA: 'Ucraïna', TR: 'Turquia', IL: 'Israel',
    US: 'Estats Units', CA: 'Canadà', MX: 'Mèxic', BR: 'Brasil',
    AR: 'Argentina', CL: 'Xile', CO: 'Colòmbia', VE: 'Veneçuela', PE: 'Perú',
    UY: 'Uruguai', BO: 'Bolívia', EC: 'Equador', PY: 'Paraguai', CU: 'Cuba',
    CN: 'Xina', JP: 'Japó', KR: 'Corea del Sud', IN: 'Índia', AU: 'Austràlia',
    ZA: 'Sud-àfrica', EG: 'Egipte', MA: 'Marroc', TN: 'Tunísia', NG: 'Nigèria',
    IR: 'Iran', IQ: 'Iraq', PK: 'Pakistan', BD: 'Bangladesh', TH: 'Tailàndia',
    VN: 'Vietnam', PH: 'Filipines', ID: 'Indonèsia', MY: 'Malàisia',
    GT: 'Guatemala', HN: 'Hondures', SV: 'El Salvador', NI: 'Nicaragua',
    CR: 'Costa Rica', PA: 'Panamà', DO: 'Rep. Dominicana', PR: 'Puerto Rico',
    CY: 'Xipre', MT: 'Malta', LU: 'Luxemburg', IE: 'Irlanda', SK: 'Eslovàquia',
    SI: 'Eslovènia', HR: 'Croàcia', BA: 'Bòsnia i Hercegovina', RS: 'Sèrbia',
    MK: 'Macedònia del Nord', AL: 'Albània', BG: 'Bulgària', LT: 'Lituània',
    LV: 'Letònia', EE: 'Estònia', BY: 'Bielorússia', MD: 'Moldàvia',
    GE: 'Geòrgia', AM: 'Armènia', AZ: 'Azerbaidjan', KZ: 'Kazakhstan',
    UZ: 'Uzbekistan', AF: 'Afganistan', SY: 'Síria', LB: 'Líban',
    JO: 'Jordània', SA: 'Aràbia Saudita', AE: 'Emirats Àrabs Units',
    QA: 'Qatar', KW: 'Kuwait', BH: 'Bahrain', OM: 'Oman', YE: 'Iemen',
    LY: 'Líbia', DZ: 'Algèria', SD: 'Sudan', ET: 'Etiòpia', KE: 'Kenya',
    UG: 'Uganda', TZ: 'Tanzània', MZ: 'Moçambic', ZW: 'Zimbabwe',
    CM: 'Camerun', CI: "Costa d'Ivori", SN: 'Senegal', GH: 'Ghana',
    NZ: 'Nova Zelanda'
};

function countryName(code) {
    return COUNTRY_NAMES_CA[code] || code;
}

async function cargarNacionalitat() {
    const estado = document.getElementById('estadoNacionalitat');
    const contenedor = document.getElementById('chartNacionalitat');
    if (!estado || !contenedor) return;
    estado.textContent = 'Carregant...';

    try {
        const [, res] = await Promise.all([
            getWorldGeoJson(),
            fetch(apiUrl(buildUrl('/persons/stats/nationality', true)))
        ]);

        if (!res.ok) throw new Error();
        const data = await res.json();

        if (!data || data.length === 0) {
            estado.textContent = 'Sense dades de nacionalitat.';
            return;
        }

        const mapData = data
            .filter(d => ISO2_TO_MAP_NAME[d.country])
            .map(d => ({
                name: ISO2_TO_MAP_NAME[d.country],
                value: Number(d.count),
                iso2: d.country
            }));

        if (!chartNacionalitat) {
            chartNacionalitat = echarts.init(contenedor);
        }

        chartNacionalitat.setOption({
            backgroundColor: '#f8f9fb',
            tooltip: {
                trigger: 'item',
                formatter: params => {
                    if (!params.data) return `<b>${params.name}</b>`;
                    const label = COUNTRY_NAMES_CA[params.data.iso2] || params.name;
                    return `<b>${label}</b> (${params.data.iso2}): ${params.value}`;
                }
            },
            visualMap: {
                type: 'piecewise',
                pieces: [
                    { min: 1001,          label: '> 1.000',    color: '#003d1f' },
                    { min: 201, max: 1000, label: '201 – 1.000', color: '#005c2e' },
                    { min: 51,  max: 200,  label: '51 – 200',   color: '#008037' },
                    { min: 11,  max: 50,   label: '11 – 50',    color: '#4aaa6e' },
                    { min: 1,   max: 10,   label: '1 – 10',     color: '#a8d8b8' }
                ],
                outOfRange: { color: '#e4e8ec' },
                orient: 'vertical',
                left: 'left',
                bottom: 20,
                textStyle: { color: '#333', fontSize: 11 }
            },
            series: [{
                type: 'map',
                map: 'world',
                roam: true,
                zoom: 1.15,
                data: mapData,
                itemStyle: {
                    areaColor: '#e4e8ec',
                    borderColor: '#ffffff',
                    borderWidth: 0.5
                },
                emphasis: {
                    label: { show: false },
                    itemStyle: { areaColor: '#004D5E' }
                }
            }]
        }, true);

        estado.textContent = '';
    } catch (e) {
        estado.textContent = 'No s\'han pogut carregar les dades.';
        if (contenedor) contenedor.innerHTML = '<div class="h-full flex items-center justify-center text-sm text-rose-500">Error carregant les dades de nacionalitat</div>';
    }
}
