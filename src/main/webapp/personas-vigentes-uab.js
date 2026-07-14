let chartPiramide = null;
let chartSexo = null;
let chartContractType = null;
let chartNacionalitat = null;
let chartCatedraticosBreakdown = null;
let chartEvolucion = null;
let chartModalBreakdown = null;
let _worldGeoJson = null;
const selectorPersonalType = document.getElementById('selectorPersonalType');
const selectorDepartamento = document.getElementById('selectorDepartamento');
const selectorAny = document.getElementById('selectorAny');
const selectorTypology = document.getElementById('selectorTypology');

const TYPOLOGY_PATTERNS = {
    catedraticos: 'catedr|chair|full professor',
    titulares: 'titular|tenured|tenure',
    agregados: 'agregat|agregado|associate professor',
    lectores: 'lector|lectura|reader',
    icrea: 'icrea',
    predoctorals: 'predoctoral|en formaci|FPI|FI-JOAN|FI-SDUR|novell|La Caixa|pre-doctoral|research training|novel research',
    postdoctorals: 'ordinari|postdoctoral|Cajal|Beatriu|Cierva|doctor distingit|director investigaci|regular researcher|post-doctoral|distinguished research|research director',
    total_vigentes: 'catedr|chair|full professor|titular|tenured|tenure|agregat|agregado|associate professor|lector|lectura|reader|predoctoral|en formaci|FPI|FI-JOAN|FI-SDUR|novell|La Caixa|pre-doctoral|research training|novel research|ordinari|postdoctoral|Cajal|Beatriu|Cierva|doctor distingit|director investigaci|regular researcher|post-doctoral|distinguished research|research director'
};

function getPersonalTypeParam() {
    return encodeURIComponent(selectorPersonalType?.value || 'all');
}

function getDeptParam() {
    return selectorDepartamento?.value || '';
}

function getCategoryPatternParam() {
    const val = selectorTypology?.value;
    return val ? (TYPOLOGY_PATTERNS[val] || '') : '';
}

function buildUrl(path, includePersonalType = true, includeCategoryPattern = true) {
    const params = new URLSearchParams();
    if (includePersonalType) {
        params.set('personalType', selectorPersonalType?.value || 'all');
    }
    const dept = getDeptParam();
    if (dept) {
        params.set('deptUuid', dept);
    }
    const yearVal = selectorAny?.value;
    if (yearVal) {
        params.set('year', yearVal);
    }
    if (includeCategoryPattern) {
        const pattern = getCategoryPatternParam();
        if (pattern) {
            params.set('categoryPattern', pattern);
        }
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

// Cache compartit per /stats/vigentes-por-categoria — s'invalida si canvien els filtres
let _catsCache = { key: null, promise: null };

function _fetchVigentesCats() {
    const url = buildUrl('/persons/stats/vigentes-por-categoria', true);
    if (_catsCache.key !== url) {
        _catsCache.key = url;
        _catsCache.promise = fetch(apiUrl(url))
            .then(r => { if (!r.ok) throw new Error(); return r.json(); });
    }
    return _catsCache.promise;
}

function _sumCatsByRegex(cats, pattern) {
    const re = new RegExp(pattern, 'i');
    return (Array.isArray(cats) ? cats : [])
        .filter(d => d.categoria_laboral && re.test(d.categoria_laboral))
        .reduce((sum, d) => sum + (d.total_personas ?? 0), 0);
}

async function cargarPersonasVigentes() {
    const estado = document.getElementById('estadoVigentes');
    const valor = document.getElementById('valorVigentes');
    estado.textContent = 'Carregant dades...';
    try {
        const results = await Promise.all([
            cargarCatedraticos(),
            cargarTitulares(),
            cargarAgregados(),
            cargarLectores(),
            cargarIcrea(),
            cargarPredoctorals(),
            cargarPostdoctorals()
        ]);
        const sum = results.reduce((a, b) => a + b, 0);
        valor.textContent = formatearNumero(sum);
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
        const cats = await _fetchVigentesCats();
        const total = _sumCatsByRegex(cats, 'catedr|chair|full professor');
        valor.textContent = formatearNumero(total);
        estado.textContent = '';
        return total;
    } catch (e) {
        valor.textContent = '--';
        estado.textContent = 'No s\'han pogut carregar les dades.';
        return 0;
    }
}

async function cargarTitulares() {
    const estado = document.getElementById('estadoTitulares');
    const valor = document.getElementById('valorTitulares');
    estado.textContent = 'Carregant dades...';
    try {
        const cats = await _fetchVigentesCats();
        const total = _sumCatsByRegex(cats, 'titular|tenured|tenure');
        valor.textContent = formatearNumero(total);
        estado.textContent = '';
        return total;
    } catch (e) {
        valor.textContent = '--';
        estado.textContent = 'No s\'han pogut carregar les dades.';
        return 0;
    }
}

async function cargarAgregados() {
    const estado = document.getElementById('estadoAgregados');
    const valor = document.getElementById('valorAgregados');
    estado.textContent = 'Carregant dades...';
    try {
        const cats = await _fetchVigentesCats();
        const total = _sumCatsByRegex(cats, 'agregat|agregado|associate professor');
        valor.textContent = formatearNumero(total);
        estado.textContent = '';
        return total;
    } catch (e) {
        valor.textContent = '--';
        estado.textContent = 'No s\'han pogut carregar les dades.';
        return 0;
    }
}

async function cargarLectores() {
    const estado = document.getElementById('estadoLectores');
    const valor = document.getElementById('valorLectores');
    estado.textContent = 'Carregant dades...';
    try {
        const cats = await _fetchVigentesCats();
        const total = _sumCatsByRegex(cats, 'lector|lectura|reader');
        valor.textContent = formatearNumero(total);
        estado.textContent = '';
        return total;
    } catch (e) {
        valor.textContent = '--';
        estado.textContent = 'No s\'han pogut carregar les dades.';
        return 0;
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

        const el = document.getElementById('tooltipIcrea');
        if (el) {
            el.innerHTML = `Personal investigador ICREA <span class="opacity-60">(${total})</span>`;
        }

        return total;
    } catch (e) {
        valor.textContent = '--';
        estado.textContent = 'No s\'han pogut carregar les dades.';
        return 0;
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

function recargarTodo() {
    cargarPersonasVigentes();
    cargarPersonalAcademic();
    cargarPiramideEdad();
    cargarSexDistribution();
    cargarContractType();
    cargarNacionalitat();
    cargarTooltipSummary();
    cargarCatedraticosBreakdown();
    cargarEvolucion();
}

selectorPersonalType?.addEventListener('change', recargarTodo);
selectorDepartamento?.addEventListener('change', recargarTodo);
selectorAny?.addEventListener('change', recargarTodo);
selectorTypology?.addEventListener('change', recargarTodo);

window.addEventListener('resize', () => {
    if (chartPiramide) chartPiramide.resize();
    if (chartSexo) chartSexo.resize();
    if (chartContractType) chartContractType.resize();
    if (chartNacionalitat) chartNacionalitat.resize();
    if (chartCatedraticosBreakdown) chartCatedraticosBreakdown.resize();
    if (chartEvolucion) chartEvolucion.resize();
    if (chartModalBreakdown) chartModalBreakdown.resize();
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
        const cats = await _fetchVigentesCats();

        const tooltipGroups = [
            { id: 'tooltipCatedraticos',  pattern: 'catedr|chair|full professor' },
            { id: 'tooltipTitulares',     pattern: 'titular|tenured|tenure' },
            { id: 'tooltipAgregados',     pattern: 'agregat|agregado|associate professor' },
            { id: 'tooltipLectores',      pattern: 'lector|lectura|reader' },
            { id: 'tooltipAsociados',     pattern: 'associat|asociad|adjunct' },
            { id: 'tooltipSubstituts',    pattern: 'substitu|sustitu' },
            { id: 'tooltipPredoctorals',  pattern: 'predoctoral|en formaci|FPI|FI-JOAN|FI-SDUR|novell|La Caixa|pre-doctoral|research training|novel research' },
            { id: 'tooltipPostdoctorals', pattern: 'ordinari|postdoctoral|Cajal|Beatriu|Cierva|doctor distingit|director investigaci|regular researcher|post-doctoral|distinguished research|research director' },
        ];

        for (const { id, pattern } of tooltipGroups) {
            const el = document.getElementById(id);
            if (!el) continue;
            const re = new RegExp(pattern, 'i');
            const matches = (Array.isArray(cats) ? cats : [])
                .filter(d => d.categoria_laboral && re.test(d.categoria_laboral))
                .sort((a, b) => b.total_personas - a.total_personas);
            if (matches.length > 0) {
                el.innerHTML = matches
                    .map(d => `${d.categoria_laboral} <span class="opacity-60">(${d.total_personas})</span>`)
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
        const cats = await _fetchVigentesCats();
        const total = _sumCatsByRegex(cats, 'predoctoral|en formaci|FPI|FI-JOAN|FI-SDUR|novell|La Caixa|pre-doctoral|research training|novel research');
        valor.textContent = formatearNumero(total);
        estado.textContent = '';
        return total;
    } catch (e) {
        valor.textContent = '--';
        estado.textContent = 'No s\'han pogut carregar les dades.';
        return 0;
    }
}

async function cargarPostdoctorals() {
    const estado = document.getElementById('estadoPostdoctorals');
    const valor = document.getElementById('valorPostdoctorals');
    estado.textContent = 'Carregant dades...';
    try {
        const cats = await _fetchVigentesCats();
        const total = _sumCatsByRegex(cats, 'ordinari|postdoctoral|Cajal|Beatriu|Cierva|doctor distingit|director investigaci|regular researcher|post-doctoral|distinguished research|research director');
        valor.textContent = formatearNumero(total);
        estado.textContent = '';
        return total;
    } catch (e) {
        valor.textContent = '--';
        estado.textContent = 'No s\'han pogut carregar les dades.';
        return 0;
    }
}

function inicializarSelectorAny() {
    if (!selectorAny) return;
    const startYear = 2018;
    const currentYear = new Date().getFullYear();
    selectorAny.innerHTML = '';
    
    const optionActual = document.createElement('option');
    optionActual.value = '';
    optionActual.textContent = 'Actual';
    selectorAny.appendChild(optionActual);
    
    for (let y = currentYear; y >= startYear; y--) {
        const option = document.createElement('option');
        option.value = String(y);
        option.textContent = String(y);
        selectorAny.appendChild(option);
    }
}

inicializarSelectorAny();
cargarDepartamentos().finally(() => {
    recargarTodo();
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

function buildUrlForYear(path, year, includePersonalType = true, includeCategoryPattern = true) {
    const params = new URLSearchParams();
    if (includePersonalType) {
        params.set('personalType', selectorPersonalType?.value || 'all');
    }
    const dept = getDeptParam();
    if (dept) {
        params.set('deptUuid', dept);
    }
    if (year) {
        params.set('year', year);
    }
    if (includeCategoryPattern) {
        const pattern = getCategoryPatternParam();
        if (pattern) {
            params.set('categoryPattern', pattern);
        }
    }
    const query = params.toString();
    return query ? `${path}?${query}` : path;
}

async function cargarEvolucion() {
    const estado = document.getElementById('estadoEvolucion');
    const contenedor = document.getElementById('chartEvolucion');
    if (!estado || !contenedor) return;
    estado.textContent = 'Carregant...';

    try {
        const startYear = 2018;
        const currentYear = new Date().getFullYear();
        const years = [];
        for (let y = startYear; y <= currentYear; y++) {
            years.push(y);
        }

        const promises = years.map(async (year) => {
            const isCurrentYear = (year === currentYear);
            const urlCats = isCurrentYear
                ? buildUrl('/persons/stats/vigentes-por-categoria', true, true)
                : buildUrlForYear('/persons/stats/vigentes-por-categoria', year, true, true);
            const urlIcrea = isCurrentYear
                ? buildUrl('/persons/stats/icrea', true, true)
                : buildUrlForYear('/persons/stats/icrea', year, true, true);

            const [resCats, resIcrea] = await Promise.all([
                fetch(apiUrl(urlCats)).then(r => r.ok ? r.json() : []),
                fetch(apiUrl(urlIcrea)).then(r => r.ok ? r.json() : { total: 0 })
            ]);

            const cats = Array.isArray(resCats) ? resCats : [];
            const icreaTotal = Number(resIcrea?.total ?? resIcrea?.value?.total ?? 0);

            return {
                year,
                catedraticos: _sumCatsByRegex(cats, 'catedr|chair|full professor'),
                titulares: _sumCatsByRegex(cats, 'titular|tenured|tenure'),
                agregados: _sumCatsByRegex(cats, 'agregat|agregado|associate professor'),
                lectores: _sumCatsByRegex(cats, 'lector|lectura|reader'),
                predoctorals: _sumCatsByRegex(cats, 'predoctoral|en formaci|FPI|FI-JOAN|FI-SDUR|novell|La Caixa|pre-doctoral|research training|novel research'),
                postdoctorals: _sumCatsByRegex(cats, 'ordinari|postdoctoral|Cajal|Beatriu|Cierva|doctor distingit|director investigaci|regular researcher|post-doctoral|distinguished research|research director'),
                icrea: icreaTotal
            };
        });

        const dataPoints = await Promise.all(promises);

        renderLineaEvolucion(dataPoints, years);
        estado.textContent = '';
    } catch (e) {
        estado.textContent = 'Error';
        contenedor.innerHTML = '<div class="h-full flex items-center justify-center text-sm text-rose-500">Error carregant el gràfic d\'evolució</div>';
    }
}

function renderLineaEvolucion(dataPoints, years) {
    const contenedor = document.getElementById('chartEvolucion');
    if (!chartEvolucion) {
        chartEvolucion = echarts.init(contenedor);
    }

    const allSeries = [
        {
            name: 'Total',
            type: 'line',
            data: dataPoints.map(d => d.catedraticos + d.titulares + d.agregados + d.lectores + d.predoctorals + d.postdoctorals + d.icrea),
            itemStyle: { color: '#008037' },
            lineStyle: { width: 3, type: 'dashed' },
            smooth: true
        },
        {
            name: 'Catedràtics',
            type: 'line',
            data: dataPoints.map(d => d.catedraticos),
            itemStyle: { color: '#004D5E' },
            smooth: true
        },
        {
            name: 'Titulars',
            type: 'line',
            data: dataPoints.map(d => d.titulares),
            itemStyle: { color: '#73a437' },
            smooth: true
        },
        {
            name: 'Agregats',
            type: 'line',
            data: dataPoints.map(d => d.agregados),
            itemStyle: { color: '#F88C12' },
            smooth: true
        },
        {
            name: 'Lectors',
            type: 'line',
            data: dataPoints.map(d => d.lectores),
            itemStyle: { color: '#004D21' },
            smooth: true
        },
        {
            name: 'Predoctorals',
            type: 'line',
            data: dataPoints.map(d => d.predoctorals),
            itemStyle: { color: '#1a6b1a' },
            smooth: true
        },
        {
            name: 'Postdoctorals',
            type: 'line',
            data: dataPoints.map(d => d.postdoctorals),
            itemStyle: { color: '#5a1a8b' },
            smooth: true
        },
        {
            name: 'ICREA',
            type: 'line',
            data: dataPoints.map(d => d.icrea),
            itemStyle: { color: '#003a47' },
            smooth: true
        }
    ];

    const typ = selectorTypology?.value || 'all';
    let filteredSeries = allSeries;
    let legendData = ['Total', 'Catedràtics', 'Titulars', 'Agregats', 'Lectors', 'Predoctorals', 'Postdoctorals', 'ICREA'];

    if (typ !== 'all') {
        const typMap = {
            catedraticos: 'Catedràtics',
            titulares: 'Titulars',
            agregados: 'Agregats',
            lectores: 'Lectors',
            predoctorals: 'Predoctorals',
            postdoctorals: 'Postdoctorals',
            icrea: 'ICREA',
            total_vigentes: 'Total'
        };
        const targetName = typMap[typ];
        if (targetName) {
            filteredSeries = allSeries.filter(s => s.name === targetName);
            legendData = [targetName];
        }
    }

    chartEvolucion.setOption({
        tooltip: {
            trigger: 'axis',
            axisPointer: { type: 'line' }
        },
        legend: {
            data: legendData,
            bottom: 0
        },
        grid: {
            left: '3%',
            right: '4%',
            top: '8%',
            bottom: '12%',
            containLabel: true
        },
        xAxis: {
            type: 'category',
            boundaryGap: false,
            data: years.map(y => y === new Date().getFullYear() ? `${y} (actual)` : String(y)),
            axisLine: { lineStyle: { color: '#d4d8de' } },
            axisLabel: { color: '#434b56' }
        },
        yAxis: {
            type: 'value',
            axisLine: { show: false },
            splitLine: { lineStyle: { color: '#edf0f4' } },
            axisLabel: { color: '#434b56' }
        },
        series: filteredSeries
    }, true);
}

function abrirModalBreakdown(tipo) {
    const namesMap = {
        catedraticos: { title: 'Catedràtics', regex: 'catedr|chair|full professor', color: '#004D5E' },
        titulares: { title: 'Professors titulars', regex: 'titular|tenured|tenure', color: '#73a437' },
        agregados: { title: 'Agregats', regex: 'agregat|agregado|associate professor', color: '#F88C12' },
        lectores: { title: 'Lectors', regex: 'lector|lectura|reader', color: '#004D21' },
        icrea: { title: 'Personal investigador ICREA', regex: 'icrea', color: '#003a47' },
        predoctorals: { title: 'Inv. Predoctorals', regex: 'predoctoral|en formaci|FPI|FI-JOAN|FI-SDUR|novell|La Caixa|pre-doctoral|research training|novel research', color: '#1a6b1a' },
        postdoctorals: { title: 'Inv. Postdoctorals', regex: 'ordinari|postdoctoral|Cajal|Beatriu|Cierva|doctor distingit|director investigaci|regular researcher|post-doctoral|distinguished research|research director', color: '#5a1a8b' }
    };

    const info = namesMap[tipo];
    if (!info) return;

    document.getElementById('modalTitle').textContent = `Detall de la Categoria: ${info.title}`;
    
    const selectedYear = selectorAny?.value || 'Actual';
    document.getElementById('modalSelectedYearBadge').textContent = `Any: ${selectedYear}`;
    
    document.getElementById('modalBreakdown').classList.remove('hidden');
    
    // Set loading state in table
    const tableBody = document.getElementById('modalTableBody');
    tableBody.innerHTML = '<tr><td colspan="2" class="px-4 py-4 text-center text-xs text-[#596473]">Carregant dades...</td></tr>';
    
    // Clear/show loading state in chart
    const chartCont = document.getElementById('modalChart');
    if (chartModalBreakdown) {
        chartModalBreakdown.clear();
    } else {
        chartModalBreakdown = echarts.init(chartCont);
    }
    chartModalBreakdown.showLoading({ text: 'Carregant evolució...' });

    // Load table data and evolution data
    Promise.all([
        _fetchVigentesCats(),
        _cargarEvolucionModal(info, tipo)
    ]).then(([activeCats, evolutionData]) => {
        // 1. Populate Table
        const re = new RegExp(info.regex, 'i');
        let filtered = activeCats.filter(d => d.categoria_laboral && re.test(d.categoria_laboral));
        
        if (tipo === 'icrea') {
            const sum = filtered.reduce((acc, d) => acc + (d.total_personas ?? 0), 0);
            filtered = sum > 0 ? [{ categoria_laboral: 'Personal investigador ICREA', total_personas: sum }] : [];
        } else {
            filtered.sort((a, b) => (b.total_personas ?? 0) - (a.total_personas ?? 0));
        }
        
        if (filtered.length === 0) {
            tableBody.innerHTML = '<tr><td colspan="2" class="px-4 py-4 text-center text-xs text-[#596473]">No hi ha dades disponibles</td></tr>';
        } else {
            tableBody.innerHTML = filtered.map(d => `
                <tr class="hover:bg-slate-50/80 transition-colors">
                    <td class="px-4 py-3 text-[#2a3037] font-medium">${d.categoria_laboral}</td>
                    <td class="px-4 py-3 text-right font-semibold text-[#111111]">${formatearNumero(d.total_personas)}</td>
                </tr>
            `).join('');
        }

        // 2. Render Modal Chart
        chartModalBreakdown.hideLoading();
        chartModalBreakdown.setOption({
            tooltip: {
                trigger: 'axis',
                axisPointer: { type: 'line' }
            },
            legend: {
                type: 'scroll',
                bottom: 0
            },
            grid: {
                left: '3%',
                right: '4%',
                top: '10%',
                bottom: '15%',
                containLabel: true
            },
            xAxis: {
                type: 'category',
                boundaryGap: false,
                data: evolutionData.years.map(y => y === new Date().getFullYear() ? `${y} (actual)` : String(y)),
                axisLine: { lineStyle: { color: '#d4d8de' } },
                axisLabel: { color: '#434b56' }
            },
            yAxis: {
                type: 'value',
                axisLine: { show: false },
                splitLine: { lineStyle: { color: '#edf0f4' } },
                axisLabel: { color: '#434b56' }
            },
            series: evolutionData.series
        }, true);
    }).catch(err => {
        tableBody.innerHTML = '<tr><td colspan="2" class="px-4 py-4 text-center text-xs text-rose-500">Error carregant les dades</td></tr>';
        chartModalBreakdown.hideLoading();
    });
}

async function _cargarEvolucionModal(info, tipo) {
    const startYear = 2018;
    const currentYear = new Date().getFullYear();
    const years = [];
    for (let y = startYear; y <= currentYear; y++) {
        years.push(y);
    }
    
    const promises = years.map(async (year) => {
        const isCurrentYear = (year === currentYear);
        const urlCats = isCurrentYear
            ? buildUrl('/persons/stats/vigentes-por-categoria', true)
            : buildUrlForYear('/persons/stats/vigentes-por-categoria', year, true);
        
        const resCats = await fetch(apiUrl(urlCats)).then(r => r.ok ? r.json() : []);
        const cats = Array.isArray(resCats) ? resCats : [];
        
        const re = new RegExp(info.regex, 'i');
        const matched = cats.filter(d => d.categoria_laboral && re.test(d.categoria_laboral));
        
        if (tipo === 'icrea') {
            const sum = matched.reduce((acc, d) => acc + (d.total_personas ?? 0), 0);
            return {
                year,
                cats: sum > 0 ? [{ categoria_laboral: 'Personal investigador ICREA', total_personas: sum }] : []
            };
        } else {
            return {
                year,
                cats: matched
            };
        }
    });
    
    const historicalData = await Promise.all(promises);
    
    // Extract unique subcategory names
    const subCatNamesSet = new Set();
    historicalData.forEach(d => {
        d.cats.forEach(c => {
            subCatNamesSet.add(c.categoria_laboral);
        });
    });
    const subCatNames = Array.from(subCatNamesSet);
    
    const series = subCatNames.map(name => {
        const data = historicalData.map(d => {
            const match = d.cats.find(c => c.categoria_laboral === name);
            return match ? (match.total_personas ?? 0) : 0;
        });
        return {
            name: name,
            type: 'line',
            data: data,
            smooth: true
        };
    });
    
    return { years, series };
}

function cerrarModalBreakdown() {
    document.getElementById('modalBreakdown').classList.add('hidden');
}

window.abrirModalBreakdown = abrirModalBreakdown;
window.cerrarModalBreakdown = cerrarModalBreakdown;
