// Asegura que el select de modo awards siempre dispara la recarga
document.addEventListener('DOMContentLoaded', function() {
    var select = document.getElementById('selectAwardsDept');
    if (select) {
        select.addEventListener('change', function() {
            programarRefresco();
        });
    }
});
// Instancia global para el gráfico de evolución persona vs depto
let chartEvolucionPersonaDept = null;

/**
 * Renderiza el gráfico de líneas doble: evolución investigador vs media departamento
 * @param {Array<{anio:number, personaImporte:number, deptoMedia:number}>} rows
 */
function renderGraficoEvolucionPersonaDept(rows) {
    const container = document.getElementById('chartEvolucionPersonaDept');
    if (!container) return;
    if (chartEvolucionPersonaDept && typeof chartEvolucionPersonaDept.dispose === 'function') {
        chartEvolucionPersonaDept.dispose();
        chartEvolucionPersonaDept = null;
    }
    chartEvolucionPersonaDept = echarts.init(container);
    const anios = rows.map(r => r.anio);
    const personaData = rows.map(r => r.personaImporte);
    const deptoData = rows.map(r => r.deptoMedia);
    chartEvolucionPersonaDept.setOption({
        tooltip: { trigger: 'axis' },
        legend: { data: ['Investigador', 'Mitja Dept.'], top: 10 },
        grid: { left: 60, right: 60, top: 40, bottom: 40 },
        xAxis: {
            type: 'category',
            data: anios,
            name: 'Any',
            nameLocation: 'middle',
            nameGap: 30
        },
        yAxis: [
            {
                type: 'value',
                name: '€ Investigador',
                position: 'left',
                axisLabel: { formatter: value => formatearNumero(value) }
            },
            {
                type: 'value',
                name: '€ Mitja Dept.',
                position: 'right',
                axisLabel: { formatter: value => formatearNumero(value) }
            }
        ],
        series: [
            {
                name: 'Investigador',
                type: 'line',
                data: personaData,
                yAxisIndex: 0,
                smooth: true,
                lineStyle: { color: UAB_COLORS.campus, width: 3 },
                itemStyle: { color: UAB_COLORS.campus },
                symbol: 'circle',
                symbolSize: 7,
                areaStyle: { color: 'rgba(0,128,55,0.08)' }
            },
            {
                name: 'Mitja Dept.',
                type: 'line',
                data: deptoData,
                yAxisIndex: 1,
                smooth: true,
                lineStyle: { color: UAB_COLORS.cala, width: 3, type: 'dashed' },
                itemStyle: { color: UAB_COLORS.cala },
                symbol: 'rect',
                symbolSize: 7,
                areaStyle: { color: 'rgba(0,77,94,0.08)' }
            }
        ]
    });
}
/**
 * Renderiza el gráfico de Ranking Percentil de investigadores según su importe ponderado.
 * @param {Map} agrupadoPorPersona - Mapa de personas con sus ayudas y totales.
 */
function renderGraficoRankingPercentil(agrupadoPorPersona) {
    const container = document.getElementById('chartRankingPercentil');
    if (!container) return;
    if (
        chartRankingPercentil &&
        typeof chartRankingPercentil.dispose === 'function'
    ) {
        chartRankingPercentil.dispose();
        chartRankingPercentil = null;
    } else {
        chartRankingPercentil = null;
    }

    // 1. Calcular importe ponderado por persona
    const personas = Array.from(agrupadoPorPersona.values()).map(p => ({
        nom: p.nombre,
        uuid: p.uuid,
        importPonderat: p.importePonderado ?? 0
    }));
    // 2. Ordenar descendente por importe ponderado
    const personasSorted = personas.slice().sort((a, b) => b.importPonderat - a.importPonderat);
    // 3. Calcular percentil para cada persona
    const n = personasSorted.length;
    const percentiles = new Map();
    personasSorted.forEach((p, i) => {
        percentiles.set(p.uuid, n > 1 ? (1 - i / (n - 1)) * 100 : 100);
    });
    // 4. Datos para el eje X (nombres) e Y (percentil)
    const nombres = personasSorted.map(p => p.nom);
    const pcts = personasSorted.map(p => percentiles.get(p.uuid));

    // 5. Renderizar gráfico
    chartRankingPercentil = echarts.init(container);
    chartRankingPercentil.setOption({
        grid: { left: 60, right: 30, top: 40, bottom: 80 },
        tooltip: {
            trigger: 'axis',
            formatter: function(params) {
                const i = params[0].dataIndex;
                const p = personasSorted[i];
                return `<b>${p.nom}</b><br>Import ponderat: <b>${formatearNumero(p.importPonderat)} €</b><br>Percentil: <b>${pcts[i].toFixed(1)}</b>`;
            }
        },
        xAxis: {
            type: 'category',
            data: nombres,
            axisLabel: {
                interval: 0,
                rotate: 45,
                fontSize: 10,
                formatter: function(value) {
                    return value.length > 12 ? value.slice(0, 12) + '…' : value;
                }
            },
            name: 'Investigadors (ordenats)',
            nameLocation: 'middle',
            nameGap: 50
        },
        yAxis: {
            type: 'value',
            min: 0,
            max: 100,
            axisLabel: {
                formatter: '{value}'
            },
            name: 'Percentil',
            nameLocation: 'middle',
            nameGap: 40
        },
        series: [
            {
                name: 'Ranking Percentil',
                type: 'line',
                data: pcts,
                smooth: true,
                lineStyle: { color: UAB_COLORS.campus, width: 3 },
                itemStyle: { color: UAB_COLORS.campus },
                symbol: 'circle',
                symbolSize: 7
            }
        ]
    });

    // 6. Renderizar tabla con Tabulator
    const tablaContainer = document.getElementById('tablaRankingPercentilTable');
    if (tablaContainer) {
        // Limpiar el contenedor
        tablaContainer.innerHTML = '';
        // Preparar datos para la tabla
        const tablaData = personasSorted.map((p, i) => ({
            nombre: p.nom,
            percentil: pcts[i].toFixed(1),
            importe: formatearNumero(p.importPonderat)
        }));
        // Crear instancia Tabulator
        new Tabulator(tablaContainer, {
            data: tablaData,
            layout: 'fitColumns',
            columns: [
                { title: 'Nom', field: 'nombre', widthGrow: 2 },
                { title: 'Percentil', field: 'percentil',hozAlign: 'center',  widthGrow: 1,
                    formatter: function(cell) {
                        const pct = parseFloat(cell.getValue());
                        const el = cell.getElement(); 
                        if (pct >= 95) {
                            el.style.backgroundColor = '#f5efcc';
                            el.style.color = '#000';
                            el.style.fontWeight = 'bold';
                        } else if (pct >= 75) {
                            el.style.backgroundColor = '#d1f4e4';
                            el.style.color = '#0f5132';
                        } else if (pct >= 50) {
                            el.style.backgroundColor = '#dfe9f9';
                            el.style.color = '#084298';
                        } else {
                            el.style.backgroundColor = '#f8f9fa';
                            el.style.color = '#6c757d';
                        }

                        return pct; // Retornamos solo el número
                    }
                },                
                { title: 'Import ponderat (€)', field: 'importe', hozAlign: 'right', widthGrow: 1 }
            ],
            height: '350px',
            responsiveLayout: true,
            movableColumns: true
        });
    }
}
// --- Chips y dropdown visual para departamentos ---
function renderizarChipsDepartamentos() {
    const container = document.getElementById('departamentoChipsContainer');
    const select = document.getElementById('departamentoSelect');
    container.innerHTML = '';
    // Sincroniza el select oculto
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
    // Evento para quitar departamento
    container.querySelectorAll('button[data-remove-dep]').forEach(btn => {
        btn.addEventListener('click', (e) => {
            const uuid = e.currentTarget.getAttribute('data-remove-dep');
            quitarDepartamentoSeleccionado(uuid);
        });
    });
    // Actualiza el label del botón
    const label = document.getElementById('departamentoDropdownLabel');
    if (departamentosSeleccionados.length === 0) {
        label.textContent = 'Afegeix departament...';
    } else {
        label.textContent = 'Afegeix més...';
    }
    // Dispara el refresco de filtros
    programarRefresco();
}

function toggleDepartamentoSeleccionado(uuid) {
    if (departamentosSeleccionados.includes(uuid)) {
        quitarDepartamentoSeleccionado(uuid);
    } else {
        // Solo permitir uno: sustituir el anterior
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
// Cerrar el menú si se hace click fuera
document.addEventListener('click', (e) => {
    const btn = document.getElementById('departamentoDropdownBtn');
    const menu = document.getElementById('departamentoDropdownMenu');
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
/** Estado de categorías cargadas */
let categoriasCatalogo = [];
/** Estado de categorías seleccionadas (para chips) */
let categoriasSeleccionadas = [];
/** Estado de tipos de award cargados */
let tipusCatalogo = [];
/** Relación categoria -> tipus disponible */
let tipusPerCategoriaCatalogo = [];
/** Estado de tipos seleccionados (para chips) */
let tipusSeleccionados = [];

/** Carga las categorías y prepara el selector tipo chips. */
async function cargarCategorias() {
    const select = document.getElementById('categoriaSelect');
    const dropdownMenu = document.getElementById('categoriaDropdownMenu');
    select.innerHTML = '';
    dropdownMenu.innerHTML = '';
    try {
        const res = await fetch(apiUrl('/awards/stats/categories'));
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const categorias = await res.json();
        categoriasCatalogo = categorias;
        categorias.forEach(cat => {
            // Opciones ocultas para el select (por compatibilidad)
            const option = document.createElement('option');
            option.value = cat;
            option.textContent = cat;
            select.appendChild(option);
            // Opciones para el menú personalizado
            const item = document.createElement('div');
            item.className = 'px-3 py-2 hover:bg-indigo-50 cursor-pointer text-sm';
            item.textContent = cat;
            item.dataset.value = cat;
            item.addEventListener('click', () => {
                toggleCategoriaSeleccionada(cat);
                cerrarDropdownCategorias();
            });
            dropdownMenu.appendChild(item);
        });
    } catch (error) {
        const option = document.createElement('option');
        option.value = '';
        option.textContent = 'No s\'han pogut carregar les categories';
        select.appendChild(option);
        dropdownMenu.innerHTML = '<div class="px-3 py-2 text-red-600 text-sm">No s\'han pogut carregar les categories</div>';
    }
    renderizarChipsCategorias();
}

function renderizarChipsCategorias() {
    const container = document.getElementById('categoriaChipsContainer');
    const select = document.getElementById('categoriaSelect');
    container.innerHTML = '';
    // Sincroniza el select oculto
    Array.from(select.options).forEach(opt => {
        opt.selected = categoriasSeleccionadas.includes(opt.value);
    });
    categoriasSeleccionadas.forEach(cat => {
        const chip = document.createElement('span');
        chip.className = 'inline-flex items-center gap-2 px-3 py-1 rounded-full bg-indigo-50 text-indigo-700 text-xs font-semibold';
        chip.innerHTML = `${cat} <button type="button" class="ml-1 text-indigo-500 hover:text-indigo-700" data-remove-cat="${cat}"><i class="fa-solid fa-xmark"></i></button>`;
        container.appendChild(chip);
    });
    // Evento para quitar categoría
    container.querySelectorAll('button[data-remove-cat]').forEach(btn => {
        btn.addEventListener('click', (e) => {
            const cat = e.currentTarget.getAttribute('data-remove-cat');
            quitarCategoriaSeleccionada(cat);
        });
    });
    // Actualiza el label del botón
    const label = document.getElementById('categoriaDropdownLabel');
    if (categoriasSeleccionadas.length === 0) {
        label.textContent = 'Afegeix categoria...';
    } else {
        label.textContent = 'Afegeix més...';
    }

    actualitzarTipusSegonsCategories();

    // Dispara el refresco de filtros
    programarRefresco();
}

function toggleCategoriaSeleccionada(cat) {
    if (categoriasSeleccionadas.includes(cat)) {
        quitarCategoriaSeleccionada(cat);
    } else {
        categoriasSeleccionadas.push(cat);
        renderizarChipsCategorias();
    }
}

function quitarCategoriaSeleccionada(cat) {
    categoriasSeleccionadas = categoriasSeleccionadas.filter(c => c !== cat);
    renderizarChipsCategorias();
}

function abrirDropdownCategorias() {
    document.getElementById('categoriaDropdownMenu').classList.remove('hidden');
}
function cerrarDropdownCategorias() {
    document.getElementById('categoriaDropdownMenu').classList.add('hidden');
}
// Cerrar el menú si se hace click fuera
document.addEventListener('click', (e) => {
    const btn = document.getElementById('categoriaDropdownBtn');
    const menu = document.getElementById('categoriaDropdownMenu');
    if (!btn.contains(e.target) && !menu.contains(e.target)) {
        cerrarDropdownCategorias();
    }
});
document.getElementById('categoriaDropdownBtn').addEventListener('click', (e) => {
    e.stopPropagation();
    const menu = document.getElementById('categoriaDropdownMenu');
    if (menu.classList.contains('hidden')) {
        abrirDropdownCategorias();
    } else {
        cerrarDropdownCategorias();
    }
});

/** Carga los tipos de award (en catalán) y prepara el selector tipo chips. */
async function cargarTipus() {
    try {
        const relRes = await fetch(apiUrl('/awards/stats/tipus-per-categoria'));
        if (relRes.ok) {
            const relacions = await relRes.json();
            tipusPerCategoriaCatalogo = Array.isArray(relacions) ? relacions : [];
            const ordenats = [];
            const vistos = new Set();
            tipusPerCategoriaCatalogo.forEach(rel => {
                const tipus = String(rel.tipus || '').trim();
                if (!tipus || vistos.has(tipus)) return;
                vistos.add(tipus);
                ordenats.push(tipus);
            });
            tipusCatalogo = ordenats.sort((a, b) => a.localeCompare(b, 'ca', { sensitivity: 'base' }));
        }

        if (!tipusCatalogo.length) {
            const res = await fetch(apiUrl('/awards/stats/tipus'));
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            const tipus = await res.json();
            tipusCatalogo = Array.isArray(tipus)
                ? [...tipus].sort((a, b) => String(a).localeCompare(String(b), 'ca', { sensitivity: 'base' }))
                : [];
            tipusPerCategoriaCatalogo = [];
        }
    } catch (error) {
        const select = document.getElementById('tipusSelect');
        const dropdownMenu = document.getElementById('tipusDropdownMenu');
        select.innerHTML = '';
        dropdownMenu.innerHTML = '';
        const option = document.createElement('option');
        option.value = '';
        option.textContent = 'No s\'han pogut carregar els tipus';
        select.appendChild(option);
        dropdownMenu.innerHTML = '<div class="px-3 py-2 text-red-600 text-sm">No s\'han pogut carregar els tipus</div>';
    }

    renderOpcionsTipus();
    renderitzarChipsTipus();
}

function obtenirTipusDisponibles() {
    if (!tipusPerCategoriaCatalogo.length || categoriasSeleccionadas.length === 0) {
        return [...tipusCatalogo]
            .sort((a, b) => String(a).localeCompare(String(b), 'ca', { sensitivity: 'base' }));
    }

    const categoriesSet = new Set(categoriasSeleccionadas);
    const disponibles = new Set(
        tipusPerCategoriaCatalogo
            .filter(rel => categoriesSet.has(rel.categoria))
            .map(rel => rel.tipus)
            .filter(Boolean)
    );

    return tipusCatalogo
        .filter(t => disponibles.has(t))
        .sort((a, b) => String(a).localeCompare(String(b), 'ca', { sensitivity: 'base' }));
}

function renderOpcionsTipus() {
    const select = document.getElementById('tipusSelect');
    const dropdownMenu = document.getElementById('tipusDropdownMenu');
    select.innerHTML = '';
    dropdownMenu.innerHTML = '';

    const tipusDisponibles = obtenirTipusDisponibles();
    if (!tipusDisponibles.length) {
        dropdownMenu.innerHTML = '<div class="px-3 py-2 text-slate-500 text-sm">No hi ha tipus per la categoria seleccionada</div>';
        return;
    }

    tipusDisponibles.forEach(tipusNom => {
        const option = document.createElement('option');
        option.value = tipusNom;
        option.textContent = tipusNom;
        select.appendChild(option);

        const item = document.createElement('div');
        item.className = 'px-3 py-2 hover:bg-indigo-50 cursor-pointer text-sm';
        item.textContent = tipusNom;
        item.dataset.value = tipusNom;
        item.addEventListener('click', () => {
            toggleTipusSeleccionat(tipusNom);
            tancarDropdownTipus();
        });
        dropdownMenu.appendChild(item);
    });
}

function actualitzarTipusSegonsCategories() {
    const disponibles = new Set(obtenirTipusDisponibles());
    const tipusValids = tipusSeleccionados.filter(t => disponibles.has(t));
    const hiHaCanvis = tipusValids.length !== tipusSeleccionados.length;
    tipusSeleccionados = tipusValids;

    renderOpcionsTipus();
    renderitzarChipsTipus();

    if (!hiHaCanvis) {
        // Ja hem refrescat les opcions de tipus per coherència visual.
        window.tipusSeleccionados = [...tipusSeleccionados];
    }
}

function renderitzarChipsTipus() {
    const container = document.getElementById('tipusChipsContainer');
    const select = document.getElementById('tipusSelect');
    container.innerHTML = '';

    Array.from(select.options).forEach(opt => {
        opt.selected = tipusSeleccionados.includes(opt.value);
    });

    tipusSeleccionados.forEach(tipusNom => {
        const chip = document.createElement('span');
        chip.className = 'inline-flex items-center gap-2 px-3 py-1 rounded-full bg-indigo-50 text-indigo-700 text-xs font-semibold';
        chip.innerHTML = `${tipusNom} <button type="button" class="ml-1 text-indigo-500 hover:text-indigo-700" data-remove-tipus="${tipusNom}"><i class="fa-solid fa-xmark"></i></button>`;
        container.appendChild(chip);
    });

    container.querySelectorAll('button[data-remove-tipus]').forEach(btn => {
        btn.addEventListener('click', (e) => {
            const tipusNom = e.currentTarget.getAttribute('data-remove-tipus');
            quitarTipusSeleccionat(tipusNom);
        });
    });

    const label = document.getElementById('tipusDropdownLabel');
    if (tipusSeleccionados.length === 0) {
        label.textContent = 'Afegeix tipus...';
    } else {
        label.textContent = 'Afegeix més...';
    }

    // Compatibilidad con código legado que lee desde window.
    window.tipusSeleccionados = [...tipusSeleccionados];
    programarRefresco();
}

function toggleTipusSeleccionat(tipusNom) {
    if (tipusSeleccionados.includes(tipusNom)) {
        quitarTipusSeleccionat(tipusNom);
    } else {
        tipusSeleccionados.push(tipusNom);
        renderitzarChipsTipus();
    }
}

function quitarTipusSeleccionat(tipusNom) {
    tipusSeleccionados = tipusSeleccionados.filter(t => t !== tipusNom);
    renderitzarChipsTipus();
}

function obrirDropdownTipus() {
    document.getElementById('tipusDropdownMenu').classList.remove('hidden');
}

function tancarDropdownTipus() {
    document.getElementById('tipusDropdownMenu').classList.add('hidden');
}

document.addEventListener('click', (e) => {
    const btn = document.getElementById('tipusDropdownBtn');
    const menu = document.getElementById('tipusDropdownMenu');
    if (!btn || !menu) return;
    if (!btn.contains(e.target) && !menu.contains(e.target)) {
        tancarDropdownTipus();
    }
});

document.getElementById('tipusDropdownBtn').addEventListener('click', (e) => {
    e.stopPropagation();
    const menu = document.getElementById('tipusDropdownMenu');
    if (menu.classList.contains('hidden')) {
        obrirDropdownTipus();
    } else {
        tancarDropdownTipus();
    }
});
/**
 * Dashboard "Resum Persona/Any".
 *
 * Responsabilidades:
 * - Cargar datos agregados desde la API de awards.
 * - Pintar gráficos con ECharts.
 * - Mostrar tablas ordenables con Tabulator.
 * - Sincronizar filtros (rango años, departamento, texto persona).
 */

/** Estado de refresco/debounce. */
let refrescoTimer = null;

/** Instancias de gráficos ECharts. */
let chartImporteAnio = null;
let chartProyectosAnio = null;
let chartTopPersonas = null;
let chartComparativaDept = null;
let chartLiderazgo = null;
let chartQuadrantsPersona = null;
let chartPersonasConSinProyectos = null;
let chartEmploymentTypeConSin = null;

let chartPareto = null;

/** Estado de selección y datos en memoria del dashboard. */

let topPersonasSeleccionables = [];
let filasResumenActual = [];
let filasResumenTablaActual = [];
let personaTopSeleccionada = null;
let employmentTypePorPersonaUuid = new Map();
let employmentTypeSeleccionado = null;
let employmentTypeSegmentoSeleccionado = null;
let departamentosCatalogo = [];
let departamentosComparativa = [];
let departamentosSeleccionados = [];
let personalDeptCache = new Map();
let modoTablaResumenActual = 'awardDate';
// Variable global para la tabla de evolución persona vs departamento
let tablaEvolucionPersonaDept = null;


const UAB_COLORS = {
    campus: '#008037',
    collserola: '#004d21',
    cala: '#004D5E',
    ocas: '#F88C12',
    pissarra: '#2a3037',
    tauro: '#596473',
    columna: '#d4d8de',
    boira: '#f1f2f4',
    areaCampus: 'rgba(0,128,55,0.15)'
};

/** Instancias Tabulator para tablas principal y detalle. */
let tablaResumen = null;
let tablaAwards = null;
let tablaCrecimiento = null;

/**
 * Formatea importes con locale catalán y 2 decimales.
 * @param {number|string|null|undefined} valor
 * @returns {string}
 */
function formatearNumero(valor) {
    const num = Number(valor || 0);
    return num.toLocaleString('ca-ES', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/**
 * Formatea fechas en locale catalán (dd/mm/aaaa).
 * @param {string|null|undefined} valor
 * @returns {string}
 */
function formatearFechaCatalan(valor) {
    if (!valor) return '-';
    const fecha = new Date(valor);
    if (Number.isNaN(fecha.getTime())) {
        return valor;
    }
    return fecha.toLocaleDateString('ca-ES');
}

/**
 * Formatea valores del eje Y en notación compacta (K/M).
 * @param {number|string} valor
 * @returns {string}
 */
function formatearCompactoEje(valor) {
    const num = Number(valor || 0);
    if (Math.abs(num) >= 1000000) {
        return `${(num / 1000000).toLocaleString('ca-ES', { maximumFractionDigits: 1 })}M`;
    }
    if (Math.abs(num) >= 1000) {
        return `${(num / 1000).toLocaleString('ca-ES', { maximumFractionDigits: 0 })}K`;
    }
    return num.toLocaleString('ca-ES');
}

/**
 * Suma una lista de valores numéricos.
 * @param {Array<number|string>} values
 * @returns {number}
 */
function sumarValores(values) {
    return (values || []).reduce((acc, value) => acc + Number(value || 0), 0);
}

function calcularPorcentajeCrecimiento(valorInicial, valorFinal) {
    const inicial = Number(valorInicial || 0);
    const final = Number(valorFinal || 0);

    if (inicial === 0 && final === 0) {
        return 0;
    }

    if (inicial === 0) {
        return null;
    }

    return ((final - inicial) / inicial) * 100;
}

function calcularCrecimientoPersonas(filas, desde, hasta) {
    const porPersona = new Map();

    (filas || []).forEach(fila => {
        const anio = Number(fila['Año']);
        if (!Number.isFinite(anio)) return;

        const persona = fila['Persona'] || 'N/D';
        const personaUuid = fila['PersonaUuid'] || '';
        const key = `${personaUuid || 'sin-uuid'}|${persona}`;

        if (!porPersona.has(key)) {
            porPersona.set(key, {
                persona,
                personaUuid,
                porAnio: new Map()
            });
        }

        const registroPersona = porPersona.get(key);
        if (!registroPersona.porAnio.has(anio)) {
            registroPersona.porAnio.set(anio, { proyectos: 0, importe: 0 });
        }

        const baseAnio = registroPersona.porAnio.get(anio);
        baseAnio.proyectos += Number(fila['Total_Proyectos'] || 0);
        baseAnio.importe += Number(fila['Importe_Total (€)'] || 0);
    });

    return Array.from(porPersona.values())
        .map(item => {
            const inicial = item.porAnio.get(desde) || { proyectos: 0, importe: 0 };
            const final = item.porAnio.get(hasta) || { proyectos: 0, importe: 0 };

            const crecimientoProyectosPct = calcularPorcentajeCrecimiento(inicial.proyectos, final.proyectos);
            const crecimientoImportePct = calcularPorcentajeCrecimiento(inicial.importe, final.importe);

            return {
                persona: item.persona,
                personaUuid: item.personaUuid,
                proyectosInicio: inicial.proyectos,
                proyectosFin: final.proyectos,
                crecimientoProyectosPct,
                importeInicio: inicial.importe,
                importeFin: final.importe,
                crecimientoImportePct
            };
        })
        .sort((a, b) => {
            const aVal = a.crecimientoImportePct ?? Number.NEGATIVE_INFINITY;
            const bVal = b.crecimientoImportePct ?? Number.NEGATIVE_INFINITY;
            return bVal - aVal;
        });
}

function formatearCrecimiento(cell) {
    const valor = cell.getValue();
    if (valor == null || !Number.isFinite(Number(valor))) {
        return `<span style="color:${UAB_COLORS.tauro};font-weight:600;">-</span>`;
    }

    const numero = Number(valor);
    const texto = `${numero > 0 ? '+' : ''}${numero.toLocaleString('ca-ES', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}%`;

    if (numero > 0) {
        return `<span style="color:${UAB_COLORS.campus};font-weight:700;">${texto}</span>`;
    }
    if (numero < 0) {
        return `<span style="color:${UAB_COLORS.ocas};font-weight:700;">${texto}</span>`;
    }

    return `<span style="color:${UAB_COLORS.pissarra};font-weight:700;">${texto}</span>`;
}

function renderTablaCrecimiento(filas, desde, hasta) {
    if (!tablaCrecimiento) return;
    const rows = calcularCrecimientoPersonas(filas, desde, hasta);
    tablaCrecimiento.setData(rows);
}

/**
 * Renderiza la tabla de evolución persona vs departamento
 * @param {Array<object>} filas
 * @param {{persona:string, personaUuid:string}} personaSel
 */
function renderTablaEvolucionPersonaDept(filas, personaSel) {
    if (!Tabulator) return;
    const tablaDiv = document.getElementById('tablaEvolucionPersonaDeptTable');
    if (!tablaDiv) return;
    // Para calcular la media del departamento, necesitamos todas las filas originales (sin filtrar por persona)
    const filasOriginales = filasResumenActual ? filasResumenActual : filas;
    // Agrupar por año y calcular importe ponderado para la persona seleccionada
    const aniosSet = new Set();
    const personaPorAnio = {};
    // Agrupar por año y por persona para calcular la media del departamento
    const deptoPorAnioPersona = {};
    for (const f of filasOriginales) {
        const anio = Number(f['Año']);
        if (!Number.isFinite(anio)) continue;
        aniosSet.add(anio);
        const personaUuid = String(f['PersonaUuid']||'').trim();
        // Importe ponderado
        const impPond = Number(f['Importe_Ponderado (€)'] ?? 0);
        // Persona seleccionada (solo para la línea del investigador)
        if (personaUuid === String(personaSel.personaUuid||'').trim()) {
            if (!personaPorAnio[anio]) personaPorAnio[anio] = { importe: 0 };
            personaPorAnio[anio].importe += impPond;
        }
        // Departamento: agrupar por persona
        if (!deptoPorAnioPersona[anio]) deptoPorAnioPersona[anio] = {};
        if (!deptoPorAnioPersona[anio][personaUuid]) deptoPorAnioPersona[anio][personaUuid] = 0;
        deptoPorAnioPersona[anio][personaUuid] += impPond;
    }
    // Calcular media del departamento por año (media del importe ponderado de todas las personas del departamento)
    const deptoPorAnio = {};
    for (const anio of aniosSet) {
        const personaImportes = Object.values(deptoPorAnioPersona[anio] || {});
        // Solo contar personas con importe > 0 ese año
        const importesValidos = personaImportes.filter(v => v > 0);
        const suma = importesValidos.reduce((acc, v) => acc + v, 0);
        const count = importesValidos.length;
        deptoPorAnio[anio] = { importe: suma, count };
    }
    const anios = Array.from(aniosSet).sort((a, b) => a - b);
    // Calcular % crecimiento investigador año a año
    let prevImporte = null;
    const rows = anios.map(anio => {
        const personaImporte = personaPorAnio[anio]?.importe || 0;
        const deptoImporte = deptoPorAnio[anio]?.importe || 0;
        const deptoCount = deptoPorAnio[anio]?.count || 1;
        // Corregido: calcular la media real del departamento
        const deptoMedia = deptoCount > 0 ? deptoImporte / deptoCount : 0;
        let crecimiento = null;
        if (prevImporte !== null && prevImporte !== 0) {
            crecimiento = ((personaImporte - prevImporte) / prevImporte) * 100;
        }
        const row = {
            anio,
            personaImporte,
            crecimiento,
            deptoMedia
        };
        prevImporte = personaImporte;
        return row;
    });
    // Renderizar gráfico de líneas doble
    console.log(rows);
    renderGraficoEvolucionPersonaDept(rows);
    // Crear o actualizar tabla
    /*if (!tablaEvolucionPersonaDept) {
        tablaEvolucionPersonaDept = new Tabulator(tablaDiv, {
            layout: 'fitDataStretch',
            maxHeight: '40vh',
            reactiveData: false,
            placeholder: 'No hi ha dades per a la comparativa.',
            columns: [
                { title: 'Any', field: 'anio', sorter: 'number', hozAlign: 'left' },
                { title: '€ Investigador (Ponderat)', field: 'personaImporte', sorter: 'number', hozAlign: 'right', formatter: (cell) => formatearNumero(cell.getValue()) },
                { title: '% Creixement Inv.', field: 'crecimiento', sorter: 'number', hozAlign: 'right', formatter: (cell) => {
                    const v = cell.getValue();
                    if (v == null || isNaN(v)) return '-';
                    const num = Number(v);
                    const texto = `${num > 0 ? '+' : ''}${num.toLocaleString('ca-ES', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}%`;
                    if (num > 0) return `<span style=\"color:${UAB_COLORS.campus};font-weight:700;\">${texto}</span>`;
                    if (num < 0) return `<span style=\"color:${UAB_COLORS.ocas};font-weight:700;\">${texto}</span>`;
                    return `<span style=\"color:${UAB_COLORS.pissarra};font-weight:700;\">${texto}</span>`;
                } },
                { title: '€ Mitjana Dept.', field: 'deptoMedia', sorter: 'number', hozAlign: 'right', formatter: (cell) => formatearNumero(cell.getValue()) }
            ]
        });
    }
    tablaEvolucionPersonaDept.setData(rows);*/
}

/**
 * Alterna pantalla completa de una tarjeta de gráfico.
 * @param {string} elementId - ID del contenedor de la tarjeta.
 */
function abrirPantallaCompleta(elementId) {
    const card = document.getElementById(elementId);
    if (!card) return;

    if (document.fullscreenElement) {
        document.exitFullscreen();
        return;
    }

    if (card.requestFullscreen) {
        card.requestFullscreen();
    }
}

/** Programa una recarga con debounce para evitar exceso de peticiones. */
function programarRefresco() {
    clearTimeout(refrescoTimer);
    refrescoTimer = setTimeout(cargarDatos, 350);
}

/**
 * Muestra un estado visual de carga con icono de reloj.
 */
function mostrarEstadoCargando() {
    const estado = document.getElementById('estado');
    if (!estado) return;
    estado.innerHTML = '<span class="inline-flex items-center gap-2"><i class="fa-regular fa-clock fa-spin"></i> Recarregant dades...</span>';
    estado.className = 'p-4 text-sm text-indigo-600 font-semibold';
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

/**
 * Lee los filtros actuales desde el DOM.
 * @returns {{desde:number,hasta:number,deptUuid:string,persona:string,modoAnio:string}}
 */
function obtenerFiltrosActuales() {
    const [desdeRaw, hastaRaw] = document.getElementById('sliderAnios').noUiSlider.get();
    const desde = parseInt(desdeRaw, 10);
    const hasta = parseInt(hastaRaw, 10);
    // Ahora soporta múltiples departamentos seleccionados
    const deptUuid = departamentosSeleccionados.length > 0 ? departamentosSeleccionados : '';
    const persona = document.getElementById('personaInput').value.trim();
    const modoAnio = document.getElementById('modoAnioSelect').value || 'awardDate';
    // Devuelve todas las categorías seleccionadas como array (o string vacío si ninguna)
    const categoria = categoriasSeleccionadas.length > 0 ? categoriasSeleccionadas : '';
    const tipus = tipusSeleccionados.length > 0 ? tipusSeleccionados : '';
    return { desde, hasta, deptUuid, persona, modoAnio, categoria, tipus };
}

/**
 * Agrupa filas del resumen en una sola fila por investigador.
 * @param {Array<object>} filas
 * @param {number} desde
 * @param {number} hasta
 * @returns {Array<object>}
 */
function resumirFilasPorInvestigador(filas, desde, hasta) {
    const acumulado = new Map();

    const normalizarNombre = (valor) => String(valor || '')
        .trim()
        .toLowerCase()
        .normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '');

    const construirClavePersona = (uuid, nombre) => {
        const uuidLimpio = String(uuid || '').trim();
        if (uuidLimpio) return `uuid:${uuidLimpio}`;
        return `nom:${normalizarNombre(nombre)}`;
    };

    (filas || []).forEach(fila => {
        const personaUuid = fila['PersonaUuid'] || '';
        const persona = fila['Persona'] || '-';
        const key = construirClavePersona(personaUuid, persona);

        if (!acumulado.has(key)) {
            acumulado.set(key, {
                'Año': `${desde}-${hasta}`,
                'PersonaUuid': personaUuid,
                'Persona': persona,
                'Proyectos_IP': 0,
                'Proyectos_CoIP': 0,
                'Proyectos_Miembro': 0,
                'Total_Proyectos': 0,
                'Importe_IP (€)': 0,
                'Importe_CoIP (€)': 0,
                'Importe_Miembro (€)': 0,
                'Importe_Ponderado (€)': 0
            });
        }

        const item = acumulado.get(key);

            item['Proyectos_IP'] += Number(fila['Proyectos_IP'] || 0);
            item['Proyectos_CoIP'] += Number(fila['Proyectos_CoIP'] || 0);
            item['Proyectos_Miembro'] += Number(fila['Proyectos_Miembro'] || 0);
            item['Total_Proyectos'] += Number(fila['Total_Proyectos'] || 0);
            item['Importe_IP (€)'] += Number(fila['Importe_IP (€)'] || 0);
            item['Importe_CoIP (€)'] += Number(fila['Importe_CoIP (€)'] || 0);
            item['Importe_Miembro (€)'] += Number(fila['Importe_Miembro (€)'] || 0);
            item['Importe_Ponderado (€)'] += Number(fila['Importe_Ponderado (€)'] || 0);
        
    });



    return Array.from(acumulado.values())
        .map(item => {
            delete item['__ayudas'];
            return item;
        })
        .sort((a, b) => Number(b['Importe_Ponderado (€)'] || 0) - Number(a['Importe_Ponderado (€)'] || 0));
}

function obtenerNombrePersonaDesdeCatalogo(persona) {
    if (!persona || typeof persona !== 'object') return '';
    if (persona.fullName) return String(persona.fullName).trim();

    const nombre = String(persona.firstName || persona?.name?.firstName || '').trim();
    const apellido = String(persona.lastName || persona?.name?.lastName || '').trim();
    if (apellido && nombre) return `${apellido}, ${nombre}`;
    return apellido || nombre;
}

function asociacionVigente(assoc) {
    if (!assoc || typeof assoc !== 'object') return false;
    const startDateRaw = assoc?.period?.startDate;
    const endDateRaw = assoc?.period?.endDate;

    const hoy = new Date();
    hoy.setHours(0, 0, 0, 0);

    if (startDateRaw) {
        const startDate = new Date(startDateRaw);
        if (Number.isNaN(startDate.getTime())) return false;
        if (startDate > hoy) return false;
    }

    if (!endDateRaw) return true;
    const endDate = new Date(endDateRaw);
    if (Number.isNaN(endDate.getTime())) return false;
    return endDate >= hoy;
}

function timestampFecha(valor) {
    if (!valor) return Number.NEGATIVE_INFINITY;
    const fecha = new Date(valor);
    if (Number.isNaN(fecha.getTime())) return Number.NEGATIVE_INFINITY;
    return fecha.getTime();
}

function seleccionarAsociacionPreferida(asociaciones) {
    if (!Array.isArray(asociaciones) || asociaciones.length === 0) return null;

    const ordenadas = [...asociaciones].sort((a, b) => {
        const primA = a?.primaryAssociation ? 1 : 0;
        const primB = b?.primaryAssociation ? 1 : 0;
        if (primA !== primB) return primB - primA;

        const startA = timestampFecha(a?.period?.startDate);
        const startB = timestampFecha(b?.period?.startDate);
        if (startA !== startB) return startB - startA;

        const pureA = Number(a?.pureId || 0);
        const pureB = Number(b?.pureId || 0);
        return pureB - pureA;
    });

    return ordenadas[0] || null;
}

function esAsociacionAcademica(assoc) {
    if (!assoc || typeof assoc !== 'object') return false;
    const staffType = assoc?.staffType || {};
    const candidatos = [
        staffType?.nombreEs,
        staffType?.term?.ca_ES,
        staffType?.term?.es_ES,
        staffType?.term?.en_GB,
        staffType?.uri
    ].filter(Boolean).map(v => String(v).toLowerCase());

    return candidatos.some(v => v.includes('acad'));
}

function obtenerDatosLaboralesPersona(persona, deptId) {
    const asociaciones = Array.isArray(persona?.staffOrganizationAssociations)
        ? persona.staffOrganizationAssociations
        : [];

    const asociacionesDepto = asociaciones.filter(assoc => {
        const uuidOrg = String(assoc?.organization?.uuid || '').trim();
        return !deptId || uuidOrg === String(deptId).trim();
    });

    const vigentes = asociacionesDepto.filter(asociacionVigente);
    const base = seleccionarAsociacionPreferida(vigentes)
        || seleccionarAsociacionPreferida(asociacionesDepto)
        || seleccionarAsociacionPreferida(asociaciones);

    const term = base?.employmentType?.term || {};
    const value =
        term.ca_ES ||
        term.es_ES ||
        term.en_GB ||
        base?.employmentType?.term?.text?.value ||
        base?.employmentType?.uri ||
        'Sense employmentType';

    return {
        employmentType: String(value || 'Sense employmentType').trim() || 'Sense employmentType',
        esAcademico: esAsociacionAcademica(base)
    };
}

async function cargarPersonalDepartamento(deptUuid) {
    const deptId = Array.isArray(deptUuid) ? (deptUuid[0] || '') : (deptUuid || '');
    if (!deptId) return [];

    if (personalDeptCache.has(deptId)) {
        return personalDeptCache.get(deptId);
    }

    const personas = [];
    let pagina = 0;
    let totalPaginas = 1;

    while (pagina < totalPaginas) {
        const params = new URLSearchParams({
            deptUuid: String(deptId),
            page: String(pagina)
        });

        const res = await fetch(apiUrl(`/persons/vigentes?${params.toString()}`));
        if (!res.ok) {
            throw new Error(`HTTP ${res.status}`);
        }

        const data = await res.json();
        const content = Array.isArray(data?.content) ? data.content : [];
        content.forEach(p => {
            const personaUuid = String(p?.uuid || '').trim();
            const personaNombre = obtenerNombrePersonaDesdeCatalogo(p);
            const datosLaborales = obtenerDatosLaboralesPersona(p, deptId);
            if (!personaUuid && !personaNombre) return;
            personas.push({
                personaUuid,
                persona: personaNombre || '-',
                employmentType: datosLaborales.employmentType,
                esAcademico: Boolean(datosLaborales.esAcademico)
            });
        });

        const total = Number(data?.page?.totalPages);
        if (Number.isFinite(total) && total > 0) {
            totalPaginas = total;
        } else {
            totalPaginas = pagina + 1;
        }
        pagina += 1;
    }

    const personasUnicas = [];
    const seen = new Set();
    personas.forEach(p => {
        const key = String(p.personaUuid || '').trim() || `nom:${String(p.persona || '').trim()}`;
        if (seen.has(key)) return;
        seen.add(key);
        personasUnicas.push(p);
    });

    personalDeptCache.set(deptId, personasUnicas);
    return personasUnicas;
}

function completarResumenConPersonal(resumenRows, personalDept, desde, hasta) {
    if (!Array.isArray(personalDept) || personalDept.length === 0) {
        return resumenRows;
    }

    const base = Array.isArray(resumenRows) ? [...resumenRows] : [];
    const existentes = new Set(
        base.map(item => `${String(item['PersonaUuid'] || '').trim()}||${String(item['Persona'] || '').trim()}`)
    );

    personalDept.forEach(p => {
        const personaUuid = String(p?.personaUuid || '').trim();
        const persona = String(p?.persona || '-').trim() || '-';
        const key = `${personaUuid}||${persona}`;
        if (existentes.has(key)) return;

        base.push({
            'Año': `${desde}-${hasta}`,
            'PersonaUuid': personaUuid,
            'Persona': persona,
            'Proyectos_IP': 0,
            'Proyectos_CoIP': 0,
            'Proyectos_Miembro': 0,
            'Total_Proyectos': 0,
            'Importe_IP (€)': 0,
            'Importe_CoIP (€)': 0,
            'Importe_Miembro (€)': 0,
            'Importe_Ponderado (€)': 0
        });
    });

    return base.sort((a, b) => {
        const diffImporte = Number(b['Importe_Ponderado (€)'] || 0) - Number(a['Importe_Ponderado (€)'] || 0);
        if (diffImporte !== 0) return diffImporte;
        return String(a['Persona'] || '').localeCompare(String(b['Persona'] || ''), 'ca', { sensitivity: 'base' });
    });
}

/** Inicializa tablas Tabulator (resumen y detalle awards). */
function inicializarTablas() {
    const formatoImporte = (cell) => formatearNumero(cell.getValue());

    tablaResumen = new Tabulator('#tablaResumenTable', {
        layout: 'fitDataStretch',
        maxHeight: '50vh',
        reactiveData: false,
        placeholder: 'No hi ha resultats per als filtres seleccionats.',
        columns: [
            {
                title: 'Any',
                field: 'anio',
                sorter: 'string',
                hozAlign: 'left',
                bottomCalc: () => 'TOTAL'
            },
            { title: 'Persona', field: 'persona', sorter: 'string', headerFilter: 'input', headerFilterPlaceholder: 'Buscar persona...' },
            {
                title: 'Ajuts IP',
                field: 'proyectosIp',
                sorter: 'number',
                hozAlign: 'right',
                bottomCalc: (values) => sumarValores(values)
            },
            {
                title: 'Ajuts CoIP',
                field: 'proyectosCoip',
                sorter: 'number',
                hozAlign: 'right',
                bottomCalc: (values) => sumarValores(values)
            },
            {

                title: 'Ajuts Membre',
                field: 'proyectosMiembro',
                sorter: 'number',
                hozAlign: 'right',
                bottomCalc: (values) => sumarValores(values)
            },
            {
                title: 'Total Ajuts',
                field: 'totalProyectos',
                sorter: 'number',
                hozAlign: 'right',
                bottomCalc: (values) => sumarValores(values)
            },
            {
                title: 'Import IP (€)',
                field: 'importeIp',
                sorter: 'number',
                hozAlign: 'right',
                formatter: formatoImporte,
                bottomCalc: (values) => sumarValores(values),
                bottomCalcFormatter: (cell) => {
                    const value = cell.getValue();
                    return (value === '' || value == null) ? '' : formatearNumero(value);
                }
            },
            {
                title: 'Import CoIP (€)',
                field: 'importeCoip',
                sorter: 'number',
                hozAlign: 'right',
                formatter: formatoImporte,
                bottomCalc: (values) => sumarValores(values),
                bottomCalcFormatter: (cell) => {
                    const value = cell.getValue();
                    return (value === '' || value == null) ? '' : formatearNumero(value);
                }
            },

            {
                title: 'Import Membre (€)',
                field: 'importeMiembro',
                sorter: 'number',
                hozAlign: 'right',
                formatter: formatoImporte,
                bottomCalc: (values) => sumarValores(values),
                bottomCalcFormatter: (cell) => {
                    const value = cell.getValue();
                    return (value === '' || value == null) ? '' : formatearNumero(value);
                }
            },
            {
                title: 'Import Ponderat (€)',
                field: 'importePonderado',
                sorter: 'number',
                hozAlign: 'right',
                formatter: formatoImporte,
                bottomCalc: (values) => sumarValores(values),
                bottomCalcFormatter: (cell) => {
                    const value = cell.getValue();
                    return (value === '' || value == null) ? '' : formatearNumero(value);
                }
            }
        ]
    });

    tablaResumen.on('rowClick', (_event, row) => {
        const data = row.getData();
        void aplicarSeleccionPersona({
            persona: data.persona,
            personaUuid: data.personaUuid || undefined
        });
    });

    tablaAwards = new Tabulator('#tablaAwardsTable', {
        layout: 'fitColumns',
        reactiveData: false,
        placeholder: 'No hi ha awards per a aquesta persona amb els filtres actuals.',
        columns: [
            { title: 'Any', field: 'anyo', sorter: 'string', hozAlign: 'left', width: 70 },
            { title: "Tipus d'award", field: 'tipoAward', sorter: 'string', width: 110 },
            { title: 'Títol', field: 'titulo', sorter: 'string', widthGrow: 2 },
            { title: 'Rol', field: 'rol', sorter: 'string', width: 80 },
            { title: 'Institutional Part (€)', field: 'institutionalPart', sorter: 'number', hozAlign: 'right', formatter: formatoImporte, width: 120 },
            { title: 'Vigència inici', field: 'vigenciaInicio', sorter: 'string', formatter: (cell) => formatearFechaCatalan(cell.getValue()), width: 110 },
            { title: 'Vigència fi', field: 'vigenciaFin', sorter: 'string', formatter: (cell) => formatearFechaCatalan(cell.getValue()), width: 110 },
        ]
    });

    tablaCrecimiento = new Tabulator('#tablaCrecimientoPersonasTable', {
        layout: 'fitDataStretch',
        maxHeight: '45vh',
        reactiveData: false,
        placeholder: 'No hi ha dades suficients per calcular creixement.',
        columns: [
            { title: 'Persona', field: 'persona', sorter: 'string' },
            { title: 'Ajuts inici', field: 'proyectosInicio', sorter: 'number', hozAlign: 'right' },
            { title: 'Ajuts fi', field: 'proyectosFin', sorter: 'number', hozAlign: 'right' },
            { title: '% creixement ajuts', field: 'crecimientoProyectosPct', sorter: 'number', hozAlign: 'right', formatter: formatearCrecimiento },
            { title: 'Import inici (€)', field: 'importeInicio', sorter: 'number', hozAlign: 'right', formatter: (cell) => formatearNumero(cell.getValue()) },
            { title: 'Import fi (€)', field: 'importeFin', sorter: 'number', hozAlign: 'right', formatter: (cell) => formatearNumero(cell.getValue()) },
            { title: '% creixement import', field: 'crecimientoImportePct', sorter: 'number', hozAlign: 'right', formatter: formatearCrecimiento }
        ]
    });

    tablaCrecimiento.on('rowClick', (_event, row) => {
        const data = row.getData();
        void aplicarSeleccionPersona({
            persona: data.persona,
            personaUuid: data.personaUuid || undefined
        });
    });
}

/**
 * Carga datos en la tabla principal de resumen.
 * @param {Array<object>} filas
 * @param {string} modoAnio
 * @param {number} desde
 * @param {number} hasta
 */
function renderTabla(filas, modoAnio = 'awardDate', desde = 0, hasta = 0, personalDept = []) {
    if (!tablaResumen) return;
    modoTablaResumenActual = modoAnio;
    const resumenRows = resumirFilasPorInvestigador(filas, desde, hasta);
    const sourceRows = completarResumenConPersonal(resumenRows, personalDept, desde, hasta);
    filasResumenTablaActual = sourceRows;
    console.log('Filas para tabla resumen:', sourceRows);
    const rows = sourceRows.map(f => {
        if (modoAnio === 'vigencia') {
            // En modo vigencia, los datos vienen de resumirFilasPorInvestigador
            // Mapear correctamente los importes y el ponderado
            const proyectosIp = Number(f['Proyectos_IP'] ?? 0);
            const proyectosCoip = Number(f['Proyectos_CoIP'] ?? 0);
            const proyectosMiembro = Number(f['Proyectos_Miembro'] ?? 0);
            return {
                anio: f['Año'] ?? '-',
                personaUuid: f['PersonaUuid'] ?? '',
                persona: f['Persona'] ?? '-',
                proyectosIp,
                proyectosCoip,
                proyectosMiembro,
                totalProyectos: proyectosIp + proyectosCoip + proyectosMiembro,
                importeIp: Number(f['Importe_IP (€)'] ?? 0),
                importeCoip: Number(f['Importe_CoIP (€)'] ?? 0),
                importeMiembro: Number(f['Importe_Miembro (€)'] ?? 0),
                importePonderado: Number(f['Importe_Ponderado (€)'] ?? 0)
            };
        } else {
            const proyectosIp = Number(f['Proyectos_IP'] ?? 0);
            const proyectosCoip = Number(f['Proyectos_CoIP'] ?? 0);
            const proyectosMiembro = Number(f['Proyectos_Miembro'] ?? 0);
            return {
                anio: f['Año'] ?? '-',
                personaUuid: f['PersonaUuid'] ?? '',
                persona: f['Persona'] ?? '-',
                proyectosIp,
                proyectosCoip,
                proyectosMiembro,
                totalProyectos: proyectosIp + proyectosCoip + proyectosMiembro,
                importeIp: Number(f['Importe_IP (€)'] ?? 0),
                importeCoip: Number(f['Importe_CoIP (€)'] ?? 0),
                importeMiembro: Number(f['Importe_Miembro (€)'] ?? 0),
                importePonderado: Number(f['Importe_Ponderado (€)'] ?? 0)
            };
        }
    });

    const personalDeptArray = Array.isArray(personalDept) ? personalDept : [];
    const personalDeptAcademico = personalDeptArray.filter(p => Boolean(p?.esAcademico));
    const baseCensoTabla = personalDeptAcademico.length > 0 ? personalDeptAcademico : personalDeptArray;
    const uuidsPersonalVigente = new Set(
        baseCensoTabla
            .map(p => String(p?.personaUuid || '').trim())
            .filter(Boolean)
    );
    const nombresPersonalVigente = new Set(
        baseCensoTabla
            .map(p => String(p?.persona || '').trim().toLowerCase())
            .filter(Boolean)
    );

    const rowsTablaRaw = uuidsPersonalVigente.size > 0
        ? rows.filter(r => {
            const uuid = String(r?.personaUuid || '').trim();
            if (uuid && uuidsPersonalVigente.has(uuid)) return true;
            const nombre = String(r?.persona || '').trim().toLowerCase();
            return !uuid && nombresPersonalVigente.has(nombre);
        })
        : rows;

    const rowsTabla = [];
    const vistosTabla = new Set();
    rowsTablaRaw.forEach(r => {
        const uuid = String(r?.personaUuid || '').trim();
        const nombre = String(r?.persona || '')
            .trim()
            .toLowerCase()
            .normalize('NFD')
            .replace(/[\u0300-\u036f]/g, '');
        const key = uuid ? `uuid:${uuid}` : `nom:${nombre}`;
        if (vistosTabla.has(key)) return;
        vistosTabla.add(key);
        rowsTabla.push(r);
    });

    employmentTypePorPersonaUuid = new Map();
    (Array.isArray(personalDept) ? personalDept : []).forEach(p => {
        const uuid = String(p?.personaUuid || '').trim();
        if (!uuid) return;
        employmentTypePorPersonaUuid.set(uuid, String(p?.employmentType || 'Sense employmentType').trim() || 'Sense employmentType');
    });

    tablaResumen.setData(rowsTabla);
    aplicarFiltroTablaPorEmploymentType();
    tablaResumen.recalc();
    renderGraficoDistribucionProyectos(rowsTabla, personalDept);
    estado.textContent = `Resultats: ${rowsTabla.length} files`;
}

function aplicarFiltroTablaPorEmploymentType() {
    if (!tablaResumen) return;

    tablaResumen.clearFilter();

    if (!employmentTypeSeleccionado) {
        return;
    }

    tablaResumen.setFilter((data) => {
        const uuid = String(data?.personaUuid || '').trim();
        if (!uuid) return false;
        const tipo = employmentTypePorPersonaUuid.get(uuid);
        if (tipo !== employmentTypeSeleccionado) return false;

        if (employmentTypeSegmentoSeleccionado === 'con') {
            return Number(data?.totalProyectos || 0) > 0;
        }

        if (employmentTypeSegmentoSeleccionado === 'sin') {
            return Number(data?.totalProyectos || 0) === 0;
        }

        return true;
    });
}

function renderGraficoDistribucionProyectos(rows, personalDept = []) {
    const container = document.getElementById('chartPersonasConSinProyectos');
    if (!container || typeof echarts === 'undefined') return;

    if (!chartPersonasConSinProyectos) {
        chartPersonasConSinProyectos = echarts.init(container);
    }

    const proyectosPorPersona = new Map();
    (rows || []).forEach(r => {
        const key = String(r.personaUuid || '').trim();
        const total = Number(r.totalProyectos || 0);
        if (!key) return;
        proyectosPorPersona.set(key, (proyectosPorPersona.get(key) || 0) + total);
    });

    const personal = Array.isArray(personalDept) ? personalDept : [];
    const personalAcademico = personal.filter(p => Boolean(p?.esAcademico));

    const usarPersonalAcademico = personalAcademico.length > 0;
    const total = usarPersonalAcademico ? personalAcademico.length : (Array.isArray(rows) ? rows.length : 0);
    const conProyectos = usarPersonalAcademico
        ? personalAcademico.filter(p => Number(proyectosPorPersona.get(String(p?.personaUuid || '').trim()) || 0) > 0).length
        : (rows || []).filter(r => Number(r.totalProyectos || 0) > 0).length;
    const sinProyectos = Math.max(0, total - conProyectos);

    if (total === 0) {
        chartPersonasConSinProyectos.setOption({
            title: {
                text: 'Sense dades per als filtres actuals',
                left: 'center',
                top: '45%',
                textStyle: { color: UAB_COLORS.tauro, fontSize: 13, fontWeight: 600 }
            },
            series: []
        }, true);
        return;
    }

    chartPersonasConSinProyectos.setOption({
        tooltip: {
            trigger: 'item',
            formatter: ({ name, value, percent }) => `${name}: <b>${value}</b> (${percent}%)`
        },
        legend: {
            orient: 'horizontal',
            bottom: 8,
            data: ['Amb projectes', 'Sense projectes']
        },
        graphic: [
            {
                type: 'text',
                left: 'center',
                top: '44%',
                style: {
                    text: `${total}`,
                    textAlign: 'center',
                    fill: UAB_COLORS.pissarra,
                    fontSize: 28,
                    fontWeight: 700
                }
            },
            {
                type: 'text',
                left: 'center',
                top: '55%',
                style: {
                    text: 'Persones',
                    textAlign: 'center',
                    fill: UAB_COLORS.tauro,
                    fontSize: 12,
                    fontWeight: 600
                }
            }
        ],
        series: [
            {
                name: 'Distribució personal',
                type: 'pie',
                radius: ['48%', '72%'],
                center: ['50%', '45%'],
                itemStyle: {
                    borderRadius: 8,
                    borderColor: '#ffffff',
                    borderWidth: 2
                },
                label: {
                    formatter: ({ name, percent }) => `${name}\n${percent}%`,
                    fontSize: 12,
                    color: UAB_COLORS.pissarra
                },
                data: [
                    {
                        value: conProyectos,
                        name: 'Amb projectes',
                        itemStyle: { color: UAB_COLORS.campus }
                    },
                    {
                        value: sinProyectos,
                        name: 'Sense projectes',
                        itemStyle: { color: UAB_COLORS.columna }
                    }
                ]
            }
        ]
    }, true);

    renderGraficoEmploymentTypeConSin(rows, personalDept);
}

function renderGraficoEmploymentTypeConSin(rows, personalDept = []) {
    const container = document.getElementById('chartEmploymentTypeConSin');
    if (!container || typeof echarts === 'undefined') return;

    if (!chartEmploymentTypeConSin) {
        chartEmploymentTypeConSin = echarts.init(container);
    }

    const personal = Array.isArray(personalDept) ? personalDept : [];
    if (!personal.length) {
        employmentTypeSeleccionado = null;
        employmentTypeSegmentoSeleccionado = null;
        aplicarFiltroTablaPorEmploymentType();
        chartEmploymentTypeConSin.setOption({
            title: {
                text: 'Selecciona un departament per veure employmentType',
                left: 'center',
                top: '45%',
                textStyle: { color: UAB_COLORS.tauro, fontSize: 12, fontWeight: 600 }
            },
            series: []
        }, true);
        return;
    }

    const personalAcademico = personal.filter(p => Boolean(p?.esAcademico));
    if (!personalAcademico.length) {
        employmentTypeSeleccionado = null;
        employmentTypeSegmentoSeleccionado = null;
        aplicarFiltroTablaPorEmploymentType();
        chartEmploymentTypeConSin.setOption({
            title: {
                text: 'No hi ha personal acadèmic amb els filtres actuals',
                left: 'center',
                top: '45%',
                textStyle: { color: UAB_COLORS.tauro, fontSize: 12, fontWeight: 600 }
            },
            series: []
        }, true);
        return;
    }

    const proyectosPorPersona = new Map();
    (rows || []).forEach(r => {
        const key = String(r.personaUuid || '').trim();
        const total = Number(r.totalProyectos || 0);
        if (!key) return;
        proyectosPorPersona.set(key, (proyectosPorPersona.get(key) || 0) + total);
    });

    const porTipo = new Map();
    personalAcademico.forEach(p => {
        const tipo = String(p?.employmentType || 'Sense employmentType').trim() || 'Sense employmentType';
        const uuid = String(p?.personaUuid || '').trim();
        const totalProyectos = uuid ? Number(proyectosPorPersona.get(uuid) || 0) : 0;
        const tiene = totalProyectos > 0;

        if (!porTipo.has(tipo)) {
            porTipo.set(tipo, { tipo, total: 0, con: 0, sin: 0 });
        }

        const item = porTipo.get(tipo);
        item.total += 1;
        if (tiene) item.con += 1;
        else item.sin += 1;
    });

    const filasTipo = Array.from(porTipo.values())
        .sort((a, b) => String(b.tipo).localeCompare(String(a.tipo), 'ca', { sensitivity: 'base' }));

    if (!filasTipo.length) {
        chartEmploymentTypeConSin.setOption({
            title: {
                text: 'Sense dades d\'employmentType',
                left: 'center',
                top: '45%',
                textStyle: { color: UAB_COLORS.tauro, fontSize: 12, fontWeight: 600 }
            },
            series: []
        }, true);
        return;
    }

    const categorias = filasTipo.map(f => f.tipo);
    const serieCon = filasTipo.map(f => (f.con / f.total) * 100);
    const serieSin = filasTipo.map(f => (f.sin / f.total) * 100);

    const dataCon = filasTipo.map((f, idx) => {
        const selectedTipo = employmentTypeSeleccionado === f.tipo;
        const selected = selectedTipo && (!employmentTypeSegmentoSeleccionado || employmentTypeSegmentoSeleccionado === 'con');
        const hasSelection = Boolean(employmentTypeSeleccionado);
        return {
            value: serieCon[idx],
            itemStyle: {
                color: UAB_COLORS.campus,
                opacity: !hasSelection ? 1 : (selected ? 1 : 0.25),
                borderColor: selected ? '#1f2937' : undefined,
                borderWidth: selected ? 1.5 : 0
            }
        };
    });

    const dataSin = filasTipo.map((f, idx) => {
        const selectedTipo = employmentTypeSeleccionado === f.tipo;
        const selected = selectedTipo && (!employmentTypeSegmentoSeleccionado || employmentTypeSegmentoSeleccionado === 'sin');
        const hasSelection = Boolean(employmentTypeSeleccionado);
        return {
            value: serieSin[idx],
            itemStyle: {
                color: UAB_COLORS.columna,
                opacity: !hasSelection ? 1 : (selected ? 1 : 0.25),
                borderColor: selected ? '#1f2937' : undefined,
                borderWidth: selected ? 1.5 : 0
            }
        };
    });

    chartEmploymentTypeConSin.setOption({
        color: [UAB_COLORS.campus, UAB_COLORS.columna],
        tooltip: {
            trigger: 'axis',
            axisPointer: { type: 'shadow' },
            formatter: params => {
                const idx = params?.[0]?.dataIndex ?? 0;
                const row = filasTipo[idx];
                if (!row) return '';
                return [
                    `<b>${row.tipo}</b>`,
                    `Total: <b>${row.total}</b>`,
                    `Amb projectes: <b>${row.con}</b> (${serieCon[idx].toFixed(1)}%)`,
                    `Sense projectes: <b>${row.sin}</b> (${serieSin[idx].toFixed(1)}%)`
                ].join('<br>');
            }
        },
        legend: {
            bottom: 0,
            data: ['Amb projectes', 'Sense projectes']
        },
        grid: { left: 20, right: 20, top: 20, bottom: 45, containLabel: true },
        xAxis: {
            type: 'value',
            min: 0,
            max: 100,
            axisLabel: { formatter: '{value}%' },
            splitLine: { lineStyle: { color: UAB_COLORS.columna } }
        },
        yAxis: {
            type: 'category',
            data: categorias,
            axisLabel: {
                color: UAB_COLORS.pissarra,
                fontSize: 11,
                interval: 0,
                formatter: value => String(value || '')
            }
        },
        series: [
            {
                name: 'Amb projectes',
                type: 'bar',
                stack: 'total',
                data: dataCon,
                label: {
                    show: true,
                    position: 'inside',
                    formatter: ({ value }) => (value >= 8 ? `${Number(value).toFixed(0)}%` : ''),
                    color: '#fff',
                    fontWeight: 700
                }
            },
            {
                name: 'Sense projectes',
                type: 'bar',
                stack: 'total',
                data: dataSin,
                label: {
                    show: true,
                    position: 'inside',
                    formatter: ({ value }) => (value >= 8 ? `${Number(value).toFixed(0)}%` : ''),
                    color: UAB_COLORS.pissarra,
                    fontWeight: 700
                }
            }
        ]
    }, true);

    chartEmploymentTypeConSin.off('click');
    chartEmploymentTypeConSin.on('click', (params) => {
        const idx = params?.dataIndex;
        if (idx == null || idx < 0 || idx >= filasTipo.length) return;

        const tipo = filasTipo[idx].tipo;
        const segmento = params?.seriesName === 'Amb projectes' ? 'con' : 'sin';
        const mismoTipo = employmentTypeSeleccionado === tipo;
        const mismoSegmento = employmentTypeSegmentoSeleccionado === segmento;

        if (mismoTipo && mismoSegmento) {
            employmentTypeSeleccionado = null;
            employmentTypeSegmentoSeleccionado = null;
        } else {
            employmentTypeSeleccionado = tipo;
            employmentTypeSegmentoSeleccionado = segmento;
        }

        aplicarFiltroTablaPorEmploymentType();

        // Repinta para reflejar visualmente la barra seleccionada.
        renderGraficoEmploymentTypeConSin(rows, personalDept);
    });
}

/** Libera instancias de ECharts actuales antes de repintar. */
function destruirGraficos() {
    if (chartImporteAnio) { chartImporteAnio.dispose(); chartImporteAnio = null; }
    if (chartProyectosAnio) { chartProyectosAnio.dispose(); chartProyectosAnio = null; }
    if (chartTopPersonas) { chartTopPersonas.dispose(); chartTopPersonas = null; }
    if (chartLiderazgo) { chartLiderazgo.dispose(); chartLiderazgo = null; }
    if (chartQuadrantsPersona) { chartQuadrantsPersona.dispose(); chartQuadrantsPersona = null; }
    if (chartPareto) { chartPareto.dispose(); chartPareto = null; }
}

/**
 * Agrega filas por año para construir series de gráficos.
 * @param {Array<object>} filas
 * @returns {Record<number,{importeTotal:number,proyectos:number,ipcoip:number,miembro:number}>}
 */
function agruparPorAnio(filas) {
    const porAnio = {};
    filas.forEach(f => {
        const anio = Number(f['Año']);
        if (!Number.isFinite(anio)) return;
        if (!porAnio[anio]) {
            porAnio[anio] = {
                importeTotal: 0,
                proyectos: 0,
                ipcoip: 0,
                coip: 0,
                miembro: 0
            };
        }
        porAnio[anio].importeTotal += Number(f['Importe_Total (€)'] || 0);
        porAnio[anio].ipcoip += Number(f['Proyectos_IP'] || 0);
        porAnio[anio].coip += Number(f['Proyectos_CoIP'] || 0);
        porAnio[anio].miembro += Number(f['Proyectos_Miembro'] || 0);
        porAnio[anio].proyectos += Number(f['Proyectos_IP'] || 0) + Number(f['Proyectos_CoIP'] || 0) + Number(f['Proyectos_Miembro'] || 0);
    });
    return porAnio;
}

/**
 * Calcula top de personas por importe total.
 * @param {Array<object>} filas
 * @param {number} [limite=8]
 * @returns {Array<{persona:string,personaUuid?:string,importe:number}>}
 */
function agruparTopPersonas(filas, limite = 8) {
    const porPersona = {};
    filas.forEach(f => {
        const persona = f['Persona'] ?? f.persona ?? 'N/D';
        const personaUuid = f['PersonaUuid'] ?? f.personaUuid;
        const importe = Number(f['Importe_Total (€)'] ?? f.importeTotal ?? 0);
        const key = `${personaUuid || 'sin-uuid'}|${persona}`;
        if (!porPersona[key]) {
            porPersona[key] = {
                persona,
                personaUuid,
                importe: 0
            };
        }
        porPersona[key].importe += importe;
    });

    return Object.values(porPersona)
        .sort((a, b) => b.importe - a.importe)
        .slice(0, limite);
}

function obtenerIdentidadPersona(fila) {
    const persona = String(fila?.Persona ?? fila?.persona ?? '').trim();
    const personaUuid = String(fila?.PersonaUuid ?? fila?.personaUuid ?? '').trim();
    return { persona, personaUuid };
}

function normalizarTexto(valor) {
    return String(valor || '').trim().toLowerCase();
}

/**
 * Si hay persona top seleccionada, filtra filas para series vinculadas.
 * @param {Array<object>} filas
 * @returns {Array<object>}
 */
function obtenerFilasFiltradasPorTop(filas) {
    if (!personaTopSeleccionada) {
        return filas;
    }

    const seleccionPersona = normalizarTexto(personaTopSeleccionada.persona);
    const seleccionUuid = String(personaTopSeleccionada.personaUuid || '').trim();

    return filas.filter(f => {
        const identidad = obtenerIdentidadPersona(f);
        if (seleccionUuid) {
            if (identidad.personaUuid === seleccionUuid) {
                return true;
            }
            return normalizarTexto(identidad.persona) === seleccionPersona;
        }

        return normalizarTexto(identidad.persona) === seleccionPersona;
    });
}

/** Actualiza títulos de gráficos según selección de persona top. */
function actualizarTitulosGraficos() {
    const sufijo = personaTopSeleccionada ? ` · ${personaTopSeleccionada.persona}` : '';
    document.getElementById('tituloChartImporteAnio').textContent = `Import total per projectes (sense duplicar)${sufijo}`;
    document.getElementById('tituloChartProyectosAnio').textContent = `Projectes per any${sufijo}`;
    document.getElementById('tituloChartTopPersonas').textContent = personaTopSeleccionada
        ? `Top persones per import ponderat (seleccionada: ${personaTopSeleccionada.persona})`
        : 'Top persones per import ponderat (període seleccionat)';
}

function esMismaPersonaSeleccionada(persona) {
    if (!personaTopSeleccionada || !persona) {
        return false;
    }

    const actual = obtenerIdentidadPersona(personaTopSeleccionada);
    const nueva = obtenerIdentidadPersona(persona);

    if (actual.personaUuid && nueva.personaUuid) {
        return actual.personaUuid === nueva.personaUuid;
    }

    return normalizarTexto(actual.persona) === normalizarTexto(nueva.persona);
}

async function aplicarSeleccionPersona(persona) {
    if (!persona) return;

    const identidad = obtenerIdentidadPersona(persona);
    if (!identidad.persona && !identidad.personaUuid) {
        return;
    }

    const yaSeleccionada = esMismaPersonaSeleccionada(identidad);
    personaTopSeleccionada = yaSeleccionada ? null : identidad;
    // Filtrar filas por persona seleccionada si hay selección
    let filasFiltradas = filasResumenActual;
    if (!yaSeleccionada && identidad.personaUuid) {
        filasFiltradas = filasResumenActual.filter(f => {
            return (f['PersonaUuid'] ?? f.personaUuid ?? '') === identidad.personaUuid;
        });
    }
    renderGraficos(filasFiltradas);
    
    // Mostrar/ocultar tabla de evolución persona vs departamento
    const seccionEvol = document.getElementById('seccionEvolucionPersonaDept');
    if (personaTopSeleccionada && seccionEvol) {
        seccionEvol.classList.remove('hidden');
        renderTablaEvolucionPersonaDept(filasFiltradas, personaTopSeleccionada);
    } else if (seccionEvol) {
        seccionEvol.classList.add('hidden');
        if (window.tablaEvolucionPersonaDept) window.tablaEvolucionPersonaDept.clearData();
    }

    if (!personaTopSeleccionada) {
        document.getElementById('seccionAwardsPersona').classList.add('hidden');
        if (tablaAwards) tablaAwards.clearData();
        return;
    }

    try {
        await cargarAwardsPersona(personaTopSeleccionada);
    } catch (error) {
        const seccion = document.getElementById('seccionAwardsPersona');
        const titulo = document.getElementById('tituloAwardsPersona');
        titulo.textContent = 'Ajuts de la persona seleccionada';
        seccion.classList.remove('hidden');
        if (tablaAwards) tablaAwards.clearData();
    }
}

/**
 * Carga detalle de awards en tabla inferior.
 * @param {Array<object>} filas
 * @param {string} personaNombre
 */
function renderAwardsPersona(filas, personaNombre) {
    const seccion = document.getElementById('seccionAwardsPersona');
    const titulo = document.getElementById('tituloAwardsPersona');

    titulo.textContent = `Awards de ${personaNombre}`;
    seccion.classList.remove('hidden');

    if (!tablaAwards) return;
    const uniqueByAward = new Map();

    (filas || []).forEach(f => {
        const awardUuid = f.awardUuid ?? '-';
        const current = uniqueByAward.get(awardUuid);
        const candidate = {
            anyo: f.anyo ?? '-',
            vigenciaInicio: f.vigenciaInicio ?? '-',
            vigenciaFin: f.vigenciaFin ?? '-',
            tipoAward: f.tipoAward ?? '-',
            titulo: f.titulo ?? '-',
            rol: f.rol ?? '-',
            institutionalPart: Number(f.institutionalPart ?? 0),
            awardUuid,
            managingOrganization: f.managingOrganization ?? '-',
            comanagingOrganization: f.comanagingOrganization ?? f.coManagingOrganization ?? f.coManagingOrganizations ?? '-'
        };
        if (!current) {
            uniqueByAward.set(awardUuid, candidate);
            return;
        }
        current.institutionalPart = Math.max(current.institutionalPart, candidate.institutionalPart);
        if (current.anyo === '-' && candidate.anyo !== '-') {
            current.anyo = candidate.anyo;
        }
        if (current.vigenciaInicio === '-' && candidate.vigenciaInicio !== '-') {
            current.vigenciaInicio = candidate.vigenciaInicio;
        }
        if (current.vigenciaFin === '-' && candidate.vigenciaFin !== '-') {
            current.vigenciaFin = candidate.vigenciaFin;
        }
        if (current.managingOrganization === '-' && candidate.managingOrganization !== '-') {
            current.managingOrganization = candidate.managingOrganization;
        }
        if (current.comanagingOrganization === '-' && candidate.comanagingOrganization !== '-') {
            current.comanagingOrganization = candidate.comanagingOrganization;
        }
    });

    const rows = Array.from(uniqueByAward.values());

    const fullCellTooltip = (e, cell, onRender) => {
        const value = cell.getValue();
        return value == null ? '' : String(value);
    };

    // Add managingOrganization and comanagingOrganization columns if not present
    tablaAwards.setColumns([
        { title: 'Any', field: 'anyo', width: 70 },
        { title: 'Títol', field: 'titulo', widthGrow: 2 },
        { title: 'Tipus', field: 'tipoAward', widthGrow: 1 },
        { title: 'Rol', field: 'rol', widthGrow: 1 },
        { title: 'Import (€)', field: 'institutionalPart', hozAlign: 'right', widthGrow: 1 },
        { title: 'Unitat organitzativa de gestió', field: 'managingOrganization', widthGrow: 1 },
        { title: 'Unitat organitzativa de cogestió', field: 'comanagingOrganization', widthGrow: 1 }
    ]);
    tablaAwards.setData(rows);
}

/**
 * Pide a API el detalle de awards de una persona.
 * @param {{persona:string,personaUuid?:string}} persona
 * @returns {Promise<void>}
 */
async function cargarAwardsPersona(persona) {
    if (!persona || !persona.personaUuid) {
        return;
    }

    const { desde, hasta, deptUuid, modoAnio, categoria, tipus } = obtenerFiltrosActuales();
    const modoAwardsDept = document.getElementById('selectAwardsDept')?.value || 'miembros';
    const params = new URLSearchParams({
        personUuid: persona.personaUuid,
        desde: String(desde),
        hasta: String(hasta),
        modoAnio
    });
    // Añadir tipos seleccionados
    if (tipus && tipus.length > 0) {
        tipus.forEach(t => params.append('tipus', t));
    }
    if (deptUuid) {
        params.set('deptUuid', deptUuid);
    }
    if (categoria && Array.isArray(categoria) && categoria.length > 0) {
        categoria.forEach(cat => params.append('categoria', cat));
    } else if (categoria) {
        params.set('categoria', categoria);
    }
    // Añadir el parámetro gestionadosPorDept si el modo es gestionados
    if (modoAwardsDept === 'gestionados') {
        params.set('gestionadosPorDept', 'managed');
    }
    const res = await fetch(apiUrl(`/awards/stats/persona-awards?${params.toString()}`));
    if (!res.ok) {
        throw new Error(`HTTP ${res.status}`);
    }
    const awards = await res.json();
    renderAwardsPersona(awards, persona.persona);
}

/**
 * Renderiza los tres gráficos principales del dashboard.
 * @param {Array<object>} filas
 */
function renderGraficos(filas) {
    destruirGraficos();
    const seccionMatriz = document.getElementById('seccionMatrizLiderazgo');
    const seccionQuadrants = document.getElementById('seccionQuadrantsPersona');
    const seccionPareto = document.getElementById('seccionPareto');
    const seccionRanking = document.getElementById('seccionRankingPercentil');
    if (departamentosSeleccionados.length > 0) {
        seccionMatriz.classList.remove('hidden');
        seccionQuadrants.classList.remove('hidden');
        seccionPareto.classList.remove('hidden');
        seccionRanking.classList.remove('hidden');
    } else {
        seccionMatriz.classList.add('hidden');
        seccionQuadrants.classList.add('hidden');
        seccionPareto.classList.add('hidden');
        seccionRanking.classList.add('hidden');
    }


    // Usar siempre los datos filtrados por el backend (ya incluyen categoría)
    // Usar siempre los datos recibidos (filtrados) para todos los gráficos
    const filasParaSeries = filas;
    const filasParaTop = filas;

    const porAnio = agruparPorAnio(filasParaSeries);
    const anios = Object.keys(porAnio).map(Number).sort((a, b) => a - b);

    const importes = anios.map(a => porAnio[a].importeTotal);
    const proyectos = anios.map(a => porAnio[a].proyectos);
    const proyectosIp = anios.map(a => porAnio[a].ipcoip);
    const proyectosMiembro = anios.map(a => porAnio[a].miembro);

    chartImporteAnio = echarts.init(document.getElementById('chartImporteAnio'));
    chartImporteAnio.setOption({
        tooltip: { trigger: 'axis' },
        grid: { left: 50, right: 20, top: 20, bottom: 30 },
        xAxis: { type: 'category', data: anios },
        yAxis: {
            type: 'value',
            axisLabel: {
                formatter: (value) => formatearCompactoEje(value)
            },
            splitLine: { lineStyle: { color: UAB_COLORS.columna } }
        },
        series: [{
            name: 'Import total (€)',
            type: 'line',
            data: importes,
            smooth: 0.25,
            areaStyle: { color: UAB_COLORS.areaCampus },
            lineStyle: { color: UAB_COLORS.campus },
            itemStyle: { color: UAB_COLORS.campus }
        }]
    });

    chartProyectosAnio = echarts.init(document.getElementById('chartProyectosAnio'));
    chartProyectosAnio.setOption({
        tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
        legend: { bottom: 0 },
        grid: { left: 50, right: 20, top: 20, bottom: 50 },
        xAxis: { type: 'category', data: anios },
        yAxis: {
            type: 'value',
            minInterval: 1,
            splitLine: { lineStyle: { color: UAB_COLORS.columna } }
        },
        series: [
            {
                name: 'IP/CoIP',
                type: 'bar',
                data: proyectosIp,
                itemStyle: { color: UAB_COLORS.cala }
            },
            {
                name: 'Membre',
                type: 'bar',
                data: proyectosMiembro,
                itemStyle: { color: UAB_COLORS.campus }
            },
            {
                name: 'Total projectes',
                type: 'line',
                data: proyectos,
                smooth: 0.2,
                lineStyle: { color: UAB_COLORS.ocas },
                itemStyle: { color: UAB_COLORS.ocas },
                symbolSize: 6
            }
        ]
    });



    // Calcular importe ponderado por persona para el top usando sólo filasParaTop
    const agrupadoPorPersonaTop = new Map();
    for (const f of filasParaTop) {
        const nombre = f['Persona'] ?? f.persona ?? 'N/D';
        const uuid = f['PersonaUuid'] ?? f.personaUuid ?? '';
        const key = `${uuid}||${nombre}`;
        if (!agrupadoPorPersonaTop.has(key)) {
            agrupadoPorPersonaTop.set(key, {
                persona: nombre,
                personaUuid: uuid,
                importePonderado: 0
            });
        }
        const entry = agrupadoPorPersonaTop.get(key);
        // Usar directamente el campo del JSON
        entry.importePonderado += Number(f['Importe_Ponderado (€)'] ?? 0);
    }
    // Toggle para awards gestionados vs miembros
    let modoAwardsDept = 'miembros'; // 'miembros' o 'gestionados'

    function crearToggleAwardsDept() {
        const container = document.getElementById('departamentoChipsContainer');
        if (!container) return;
        let toggle = document.getElementById('toggleAwardsDept');
        if (!toggle) {
            toggle = document.createElement('div');
            toggle.id = 'toggleAwardsDept';
            toggle.className = 'mt-2 mb-2 flex items-center gap-2';
            toggle.innerHTML = `
                <label class="text-xs font-semibold text-slate-600">Mostrar:</label>
                <select id="selectAwardsDept" class="border border-slate-300 rounded px-2 py-1 text-xs">
                    <option value="miembros">Ajuts dels membres del departament</option>
                    <option value="gestionados">Ajuts gestionats pel departament</option>
                </select>
            `;
            container.parentNode.insertBefore(toggle, container.nextSibling);
            document.getElementById('selectAwardsDept').addEventListener('change', (e) => {
                modoAwardsDept = e.target.value;
                programarRefresco();
            });
        }
    }

    // Modifica renderizarChipsDepartamentos para llamar crearToggleAwardsDept
    const origRenderizarChipsDepartamentos = window.renderizarChipsDepartamentos;
    window.renderizarChipsDepartamentos = function() {
        if (origRenderizarChipsDepartamentos) origRenderizarChipsDepartamentos.apply(this, arguments);
        crearToggleAwardsDept();
    };

    // Modifica cargarDatos para alternar endpoint
    async function cargarDatos() {
        const estado = document.getElementById('estado');
        const { desde, hasta, deptUuid, persona, modoAnio, categoria, tipus } = obtenerFiltrosActuales();
        document.getElementById('seccionAwardsPersona').classList.add('hidden');
        if (tablaAwards) tablaAwards.clearData();
        mostrarOverlayCargando();

        try {
            mostrarEstadoCargando();
            let data = [];
            let serieProyectos = [];
            // Leer el valor actual del select
            const modoAwardsDept = document.getElementById('selectAwardsDept')?.value || 'miembros';
            if (modoAwardsDept === 'gestionados' && deptUuid) {
                
                // Awards gestionados por el departamento
                const params = new URLSearchParams({ deptUuid, gestionadosPorDept: 'gestionados' });
                const res = await fetch(apiUrl(`/awards/stats/persona-resumen?${params.toString()}`));
                if (!res.ok) throw new Error(`HTTP ${res.status}`);
                data = await res.json();
                // Adaptar data si es necesario para los gráficos/tablas
                // (aquí puedes mapear los campos si el formato es diferente)
            } else {
                // Awards de miembros (lógica original)
                const params = new URLSearchParams({
                    desde: String(desde),
                    hasta: String(hasta),
                    modoAnio
                });
                if (deptUuid) params.set('deptUuid', deptUuid);
                if (persona) params.set('persona', persona);
                if (Array.isArray(categoria) && categoria.length > 0) {
                    categoria.forEach(cat => params.append('categoria', cat));
                } else if (typeof categoria === 'string' && categoria) {
                    params.set('categoria', categoria);
                }
                if (Array.isArray(tipus) && tipus.length > 0) {
                    tipus.forEach(t => params.append('tipus', t));
                } else if (typeof tipus === 'string' && tipus) {
                    params.set('tipus', tipus);
                }
                if (modoAwardsDept === 'gestionados') {
                    params.set('gestionadosPorDept', 'managed');
                }
                const [resumenRes, serieProyectosRes] = await Promise.all([
                    fetch(apiUrl(`/awards/stats/persona-resumen?${params.toString()}`)),
                    cargarSerieProyectosAnio()
                ]);
                if (!resumenRes.ok) throw new Error(`HTTP ${resumenRes.status}`);
                data = await resumenRes.json();
                serieProyectos = await serieProyectosRes;
            }
            filasResumenActual = data;
            personaTopSeleccionada = null;
            const seccionEvol = document.getElementById('seccionEvolucionPersonaDept');
            if (seccionEvol) {
                seccionEvol.classList.add('hidden');
                if (window.tablaEvolucionPersonaDept) window.tablaEvolucionPersonaDept.clearData();
            }
            renderTabla(data, modoAnio, desde, hasta);
            renderTablaCrecimiento(data, desde, hasta);
            renderGraficos(data);
            if (chartImporteAnio) chartImporteAnio.dispose();
            renderGraficoImportePorProyecto(serieProyectos);
            
            estado.className = 'p-4 text-sm text-emerald-600 font-semibold';
        } catch (error) {
            estado.textContent = `Error en carregar dades: ${error.message}`;
            estado.className = 'p-4 text-sm text-red-600';
            renderTabla([], modoAnio, desde, hasta);
            renderTablaCrecimiento([], desde, hasta);
            renderTablaEvolucionPersonaDept([], desde, hasta);
            renderGraficos([]);
            renderGraficoImportePorProyecto([]);
        } finally {
            ocultarOverlayCargando();
        }
    }
    // ...existing code...
    // Ordenar descendente y tomar top 8
    const top = Array.from(agrupadoPorPersonaTop.values()).sort((a, b) => b.importePonderado - a.importePonderado).slice(0, 8);
    topPersonasSeleccionables = top;
    chartTopPersonas = echarts.init(document.getElementById('chartTopPersonas'));
    chartTopPersonas.setOption({
        tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
        grid: { left: 90, right: 10, top: 10, bottom: 10, containLabel: true },
        xAxis: { type: 'value' },
        yAxis: {
            type: 'category',
            data: top.map(t => t.persona),
            inverse: true,
            axisLine: { show: false },
            axisTick: { show: false },
            axisLabel: {
                width: 140,
                overflow: 'truncate'
            }
        },
        series: [{
            name: 'Import ponderat (€)',
            type: 'bar',
            data: top.map(t => t.importePonderado),
            barWidth: '55%',
            itemStyle: {
                color: (params) => {
                    const t = top[params.dataIndex];
                    const seleccionada = personaTopSeleccionada && (
                        (personaTopSeleccionada.personaUuid && personaTopSeleccionada.personaUuid === t.personaUuid) ||
                        (!personaTopSeleccionada.personaUuid && personaTopSeleccionada.persona === t.persona)
                    );
                    if (!personaTopSeleccionada) return UAB_COLORS.cala;
                    return seleccionada ? UAB_COLORS.campus : UAB_COLORS.columna;
                }
            }
        }]
    });

    chartTopPersonas.off('click');
    chartTopPersonas.on('click', async (params) => {
        const index = params.dataIndex;
        if (index == null || index < 0) return;
        const persona = topPersonasSeleccionables[index];
        await aplicarSeleccionPersona(persona);
    });


        // --- Matriz de Liderazgo (Scatter Plot, eje X = dinero ponderado, eje Y = nº ayudas) ---
        // Agrupar por persona y sumar importe ponderado igual que el top
        const agrupadoLiderazgo = new Map();
        for (const f of filas) {
            const nombre = f['Persona'] ?? f.persona ?? 'N/D';
            const uuid = f['PersonaUuid'] ?? f.personaUuid ?? '';
            const key = `${uuid}||${nombre}`;
            if (!agrupadoLiderazgo.has(key)) {
                agrupadoLiderazgo.set(key, {
                    nombre,
                    uuid,
                    ponderado: 0,
                    ayudas: 0,
                    desglose: {
                        'IP': { n: 0, suma: 0, peso: 1.0 },
                        'CoIP': { n: 0, suma: 0, peso: 0.5 },
                        'Membre': { n: 0, suma: 0, peso: 0.2 }
                    }
                });
            }
            const entry = agrupadoLiderazgo.get(key);
            entry.ponderado += Number(f['Importe_Ponderado (€)'] ?? 0);
            const ip = Number(f['Proyectos_IP'] ?? 0);
            const coip = Number(f['Proyectos_CoIP'] ?? 0);
            const miembro = Number(f['Proyectos_Miembro'] ?? 0);
            entry.ayudas += ip + coip + miembro;
            entry.desglose['IP'].n += ip;
            entry.desglose['IP'].suma += Number(f['Importe_IP (€)'] ?? 0);
            entry.desglose['CoIP'].n += coip;
            entry.desglose['CoIP'].suma += Number(f['Importe_CoIP (€)'] ?? 0);
            entry.desglose['Membre'].n += miembro;
            entry.desglose['Membre'].suma += Number(f['Importe_Miembro (€)'] ?? 0);
        }
        const datosLiderazgo = Array.from(agrupadoLiderazgo.values()).filter(d => d.ayudas > 0 || d.ponderado > 0);

        // --- Ranking Percentil ---
        // Generar agrupadoPorPersona para ranking
        const agrupadoPorPersonaRanking = new Map();
        for (const f of filas) {
            const nombre = f['Persona'] ?? f.persona ?? 'N/D';
            const uuid = f['PersonaUuid'] ?? f.personaUuid ?? '';
            const key = `${uuid}||${nombre}`;
            if (!agrupadoPorPersonaRanking.has(key)) {
                agrupadoPorPersonaRanking.set(key, {
                    nombre,
                    uuid,
                    ayudas: new Map(),
                    totalImporte: 0,
                    importePonderado: 0
                });
            }
            const entry = agrupadoPorPersonaRanking.get(key);
            // Usar directamente el campo del JSON si existe
            entry.importePonderado += Number(f['Importe_Ponderado (€)'] ?? 0);
        }
        renderGraficoRankingPercentil(agrupadoPorPersonaRanking);

        // Calcular medianas para los cuadrantes
        function mediana(arr) {
            if (!arr.length) return 0;
            const s = [...arr].sort((a, b) => a - b);
            const m = Math.floor(s.length / 2);
            return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
        }
        const ponderadoArr = datosLiderazgo.map(d => d.ponderado);
        const ayudasArr = datosLiderazgo.map(d => d.ayudas);
        const medianaPonderado = mediana(ponderadoArr);
        const medianaAyudas = mediana(ayudasArr);


        chartLiderazgo = echarts.init(document.getElementById('chartLiderazgo'));
        // Colores más contrastados para los cuadrantes
        const COLOR_LIDERS = '#1a7cff';         // Azul fuerte
        const COLOR_ESPECIALISTES = '#ff3b30';  // Rojo fuerte
        const COLOR_FORMIGUES = '#ffb800';      // Amarillo fuerte
        const COLOR_ALTRES = '#7c3aed';         // Morado fuerte

        chartLiderazgo.setOption({
            grid: { left: 60, right: 30, top: 40, bottom: 50 },
            tooltip: {
                trigger: 'item',
                formatter: function(params) {
                    const d = params.data;
                    let html = `<b>${d.nombre}</b><br>Ingresos ponderats: <b>${formatearNumero(d.ponderado)} €</b><br>Ajuts: <b>${d.ayudas}</b>`;
                    if (d.desglose) {
                        html += '<br><u>Quantitats per rol:</u>';
                        Object.entries(d.desglose).forEach(([rol, info]) => {
                            html += `<br>${rol}: <b>${formatearNumero(info.suma)} €</b> (${info.n} ajut${info.n > 1 ? 's' : ''}, pes ${info.peso})`;
                        });
                    }
                    return html;
                }
            },
            dataZoom: [
                {
                    type: 'inside',
                    xAxisIndex: 0,
                    filterMode: 'none'
                },
                {
                    type: 'inside',
                    yAxisIndex: 0,
                    filterMode: 'none'
                },
                {
                    type: 'slider',
                    xAxisIndex: 0,
                    filterMode: 'none'
                },
                {
                    type: 'slider',
                    yAxisIndex: 0,
                    filterMode: 'none'
                }
            ],
            xAxis: {
                name: "Dinero ponderat (€)",
                nameLocation: 'middle',
                nameGap: 30,
                type: 'value',
                min: 0,
                axisLabel: {
                    formatter: (value) => formatearCompactoEje(value),
                    fontWeight: 600
                },
                splitLine: { lineStyle: { color: UAB_COLORS.columna } }
            },
            yAxis: {
                name: "Ajuts",
                nameLocation: 'middle',
                nameGap: 40,
                type: 'value',
                min: 0,
                axisLabel: { fontWeight: 600 },
                splitLine: { lineStyle: { color: UAB_COLORS.columna } }
            },
            series: [{
                symbolSize: 18,
                type: 'scatter',
                data: datosLiderazgo.map(d => ({
                    value: [d.ponderado, d.ayudas],
                    nombre: d.nombre,
                    uuid: d.uuid,
                    ponderado: d.ponderado,
                    ayudas: d.ayudas,
                    desglose: d.desglose
                })),
                itemStyle: {
                    color: function(params) {
                        // Colorear por cuadrante con colores muy contrastados
                        const x = params.data.ponderado;
                        const y = params.data.ayudas;
                        if (x >= medianaPonderado && y >= medianaAyudas) return COLOR_LIDERS; // Líderes
                        if (x < medianaPonderado && y >= medianaAyudas) return COLOR_ESPECIALISTES; // Especialistas
                        if (x >= medianaPonderado && y < medianaAyudas) return COLOR_FORMIGUES; // Formigues
                        return COLOR_ALTRES; // Altres
                    }
                },
                emphasis: {
                    focus: 'series',
                    itemStyle: { borderColor: '#222', borderWidth: 2 }
                },
                label: {
                    show: true,
                    position: 'top',
                    formatter: function(params) {
                        return params.data.nombre;
                    },
                    fontSize: 11,
                    color: '#444',
                    fontWeight: 600
                }
            }],
            // Líneas de referencia para cuadrantes
            graphic: [
                // Etiquetas de cuadrantes (aproximadas, con colores nuevos)
                {
                    type: 'text',
                    left: '70%', top: '10%',
                    style: { text: 'Líders', fill: COLOR_LIDERS, font: 'bold 14px sans-serif' }
                },
                {
                    type: 'text',
                    left: '10%', top: '10%',
                    style: { text: 'Especialistes', fill: COLOR_ESPECIALISTES, font: 'bold 14px sans-serif' }
                },
                {
                    type: 'text',
                    left: '70%', top: '80%',
                    style: { text: 'Formigues', fill: COLOR_FORMIGUES, font: 'bold 14px sans-serif' }
                },
                {
                    type: 'text',
                    left: '10%', top: '80%',
                    style: { text: 'Altres', fill: COLOR_ALTRES, font: 'bold 14px sans-serif' }
                }
            ]
        });

        // Evento: mostrar awards de la persona al hacer clic en un punto de la matriz
        chartLiderazgo.off('click');
        chartLiderazgo.on('click', async (params) => {
            if (!params.data || !params.data.nombre || !params.data.uuid) return;
            await aplicarSeleccionPersona({
                persona: params.data.nombre,
                personaUuid: params.data.uuid
            });
        });

        actualizarTitulosGraficos();

        // --- Segunda matriz: solo cuadrantes con nombres, sin ejes ---
        const cuadrantes = {
            LIDERS: [],
            ESPECIALISTES: [],
            FORMIGUES: [],
            ALTRES: []
        };
        for (const d of datosLiderazgo) {
            const x = d.ponderado;
            const y = d.ayudas;
            if (x >= medianaPonderado && y >= medianaAyudas) {
                cuadrantes.LIDERS.push(d);
            } else if (x < medianaPonderado && y >= medianaAyudas) {
                cuadrantes.ESPECIALISTES.push(d);
            } else if (x >= medianaPonderado && y < medianaAyudas) {
                cuadrantes.FORMIGUES.push(d);
            } else {
                cuadrantes.ALTRES.push(d);
            }
        }
        // Render HTML grid de cuadrantes
        const container = document.getElementById('chartQuadrantsPersona');
        if (container) {
            container.innerHTML = `
                <div style="display: grid; grid-template-columns: 1fr 1fr; grid-template-rows: 1fr 1fr; gap: 12px; height: 100%; width: 100%;">
                    <div style="background:${COLOR_ESPECIALISTES}22; border:2px solid ${COLOR_ESPECIALISTES}; border-radius:16px; padding:12px; overflow:auto;">
                        <div style="font-weight:bold; color:${COLOR_ESPECIALISTES}; font-size:15px; margin-bottom:6px;">Especialistes</div>
                        <ul style="margin:0; padding:0; list-style:none;">
                            ${cuadrantes.ESPECIALISTES.map(p => `<li style='margin-bottom:2px;cursor:pointer;font-weight:600;color:${COLOR_ESPECIALISTES}' onclick='window.aplicarSeleccionPersona && aplicarSeleccionPersona({persona: ${JSON.stringify(p.nombre)}, personaUuid: ${JSON.stringify(p.uuid)}})'>${p.nombre}</li>`).join('')}
                        </ul>
                    </div>
                    <div style="background:${COLOR_LIDERS}22; border:2px solid ${COLOR_LIDERS}; border-radius:16px; padding:12px; overflow:auto;">
                        <div style="font-weight:bold; color:${COLOR_LIDERS}; font-size:15px; margin-bottom:6px;">Líders</div>
                        <ul style="margin:0; padding:0; list-style:none;">
                            ${cuadrantes.LIDERS.map(p => `<li style='margin-bottom:2px;cursor:pointer;font-weight:600;color:${COLOR_LIDERS}' onclick='window.aplicarSeleccionPersona && aplicarSeleccionPersona({persona: ${JSON.stringify(p.nombre)}, personaUuid: ${JSON.stringify(p.uuid)}})'>${p.nombre}</li>`).join('')}
                        </ul>
                    </div>
                    <div style="background:${COLOR_ALTRES}22; border:2px solid ${COLOR_ALTRES}; border-radius:16px; padding:12px; overflow:auto;">
                        <div style="font-weight:bold; color:${COLOR_ALTRES}; font-size:15px; margin-bottom:6px;">Altres</div>
                        <ul style="margin:0; padding:0; list-style:none;">
                            ${cuadrantes.ALTRES.map(p => `<li style='margin-bottom:2px;cursor:pointer;font-weight:600;color:${COLOR_ALTRES}' onclick='window.aplicarSeleccionPersona && aplicarSeleccionPersona({persona: ${JSON.stringify(p.nombre)}, personaUuid: ${JSON.stringify(p.uuid)}})'>${p.nombre}</li>`).join('')}
                        </ul>
                    </div>
                    <div style="background:${COLOR_FORMIGUES}22; border:2px solid ${COLOR_FORMIGUES}; border-radius:16px; padding:12px; overflow:auto;">
                        <div style="font-weight:bold; color:${COLOR_FORMIGUES}; font-size:15px; margin-bottom:6px;">Formigues</div>
                        <ul style="margin:0; padding:0; list-style:none;">
                            ${cuadrantes.FORMIGUES.map(p => `<li style='margin-bottom:2px;cursor:pointer;font-weight:600;color:${COLOR_FORMIGUES}' onclick='window.aplicarSeleccionPersona && aplicarSeleccionPersona({persona: ${JSON.stringify(p.nombre)}, personaUuid: ${JSON.stringify(p.uuid)}})'>${p.nombre}</li>`).join('')}
                        </ul>
                    </div>
                </div>
            `;
        }

            // --- Gràfic d'Anàlisi de Pareto (80/20) ---
            const paretoContainer = document.getElementById('chartPareto');
            if (paretoContainer) {
                // Usar datos de liderazgo directamente
                const personesSorted = datosLiderazgo.slice().sort((a, b) => b.ponderado - a.ponderado);
                const sumaTotal = personesSorted.reduce((acc, p) => acc + p.ponderado, 0);
                let acumulat = 0;
                let idx80 = -1;
                const data = personesSorted.map((p, i) => {
                    acumulat += p.ponderado;
                    const pct = sumaTotal > 0 ? (acumulat / sumaTotal) * 100 : 0;
                    if (idx80 === -1 && pct >= 80) idx80 = i;
                    return {
                        ...p,
                        acumulat,
                        pct
                    };
                });
                const noms = data.map(p => p.nombre);
                const pcts = data.map(p => p.pct);
                chartPareto = echarts.init(paretoContainer);
                chartPareto.setOption({
                    grid: { left: 60, right: 30, top: 40, bottom: 80 },
                    tooltip: {
                        trigger: 'axis',
                        formatter: function(params) {
                            const i = params[0].dataIndex;
                            const p = data[i];
                            // Usar p.nombre para mostrar el nombre
                            return `<b>${p.nombre}</b><br>Import acumulat: <b>${formatearNumero(p.acumulat)} €</b><br>Percentatge acumulat: <b>${p.pct.toFixed(1)}%</b>`;
                        }
                    },
                    xAxis: {
                        type: 'category',
                        data: noms,
                        axisLabel: {
                            interval: 0,
                            rotate: 45,
                            fontSize: 10,
                            formatter: function(value) {
                                return value.length > 12 ? value.slice(0, 12) + '…' : value;
                            }
                        },
                        name: 'Investigadors (ordenats)',
                        nameLocation: 'middle',
                        nameGap: 50
                    },
                    yAxis: {
                        type: 'value',
                        min: 0,
                        max: 100,
                        axisLabel: {
                            formatter: '{value}%'
                        },
                        name: 'Percentatge acumulat',
                        nameLocation: 'middle',
                        nameGap: 40
                    },
                    series: [
                        {
                            name: 'Pareto',
                            type: 'line',
                            data: pcts,
                            smooth: true,
                            lineStyle: { color: UAB_COLORS.campus, width: 3 },
                            itemStyle: { color: UAB_COLORS.campus },
                            symbol: 'circle',
                            symbolSize: 7,
                            markLine: {
                                silent: true,
                                data: [
                                    {
                                        xAxis: idx80 >= 0 ? noms[idx80] : null,
                                        lineStyle: { color: UAB_COLORS.ocas, type: 'dashed', width: 2 },
                                        label: {
                                            formatter: '20% persones',
                                            position: 'insideEndTop',
                                            color: UAB_COLORS.ocas,
                                            fontWeight: 'bold'
                                        }
                                    },
                                    {
                                        yAxis: 80,
                                        lineStyle: { color: UAB_COLORS.ocas, type: 'dashed', width: 2 },
                                        label: {
                                            formatter: '80% fons',
                                            position: 'end',
                                            color: UAB_COLORS.ocas,
                                            fontWeight: 'bold'
                                        }
                                    }
                                ]
                            }
                        }
                    ]
                });
            }

            
            
}

/**
 * Recupera serie anual de proyectos/importes para gráfico superior.
 * @returns {Promise<Array<{anio:number,importeTotal:number}>>}
 */
async function cargarSerieProyectosAnio() {
    const { desde, hasta, deptUuid, persona, modoAnio, categoria, tipus } = obtenerFiltrosActuales();
    const params = new URLSearchParams({
        desde: String(desde),
        hasta: String(hasta),
        modoAnio
    });
    if (Array.isArray(deptUuid) && deptUuid.length > 0) {
        deptUuid.forEach(dep => params.append('deptUuid', dep));
    } else if (typeof deptUuid === 'string' && deptUuid) {
        params.set('deptUuid', deptUuid);
    }
    if (persona) {
        params.set('persona', persona);
    }
    if (Array.isArray(categoria) && categoria.length > 0) {
        categoria.forEach(cat => params.append('categoria', cat));
    } else if (typeof categoria === 'string' && categoria) {
        params.set('categoria', categoria);
    }
    if (Array.isArray(tipus) && tipus.length > 0) {
        tipus.forEach(t => params.append('tipus', t));
    } else if (typeof tipus === 'string' && tipus) {
        params.set('tipus', tipus);
    }

    const res = await fetch(apiUrl(`/awards/stats/proyectos-anio?${params.toString()}`));
    if (!res.ok) {
        throw new Error(`HTTP ${res.status}`);
    }
    return res.json();
}

/**
 * Pinta el gráfico de importe anual a partir de una serie preagregada.
 * @param {Array<{anio:number,importeTotal:number}>} serie
 */
function renderGraficoImportePorProyecto(serie) {
    // Obtener el rango de años seleccionado
    const { desde, hasta } = obtenerFiltrosActuales();
    const anios = [];
    for (let year = desde; year <= hasta; year++) {
        anios.push(year);
    }
    // Mapear los importes por año
    const byYear = new Map((serie || []).map(s => [Number(s.anio), Number(s.importeTotal || 0)]));
    const importes = anios.map(y => byYear.get(y) ?? 0);
    if (chartImporteAnio) {
        chartImporteAnio.dispose();
    }
    chartImporteAnio = echarts.init(document.getElementById('chartImporteAnio'));
    chartImporteAnio.setOption({
        tooltip: { trigger: 'axis' },
        grid: { left: 50, right: 20, top: 20, bottom: 30 },
        xAxis: { type: 'category', data: anios },
        yAxis: {
            type: 'value',
            axisLabel: {
                formatter: (value) => formatearCompactoEje(value)
            },
            splitLine: { lineStyle: { color: UAB_COLORS.columna } }
        },
        series: [{
            name: 'Import total (€)',
            type: 'line',
            data: importes,
            smooth: 0.25,
            areaStyle: { color: UAB_COLORS.areaCampus },
            lineStyle: { color: UAB_COLORS.campus },
            itemStyle: { color: UAB_COLORS.campus }
        }]
    });
}

async function actualizarGraficoComparativaDepartamentos() {
    if (!chartComparativaDept) {
        chartComparativaDept = echarts.init(document.getElementById('chartComparativaDept'));
    }

    const { desde, hasta, persona, modoAnio, categoria, tipus } = obtenerFiltrosActuales();
    const anios = [];
    for (let year = desde; year <= hasta; year++) {
        anios.push(year);
    }

    if (!departamentosComparativa.length) {
        chartComparativaDept.setOption({
            title: {
                text: 'Afegeix departaments per comparar',
                left: 'center',
                top: 'middle',
                textStyle: { color: UAB_COLORS.tauro, fontSize: 14, fontWeight: 600 }
            },
            xAxis: { type: 'category', data: anios },
            yAxis: { type: 'value' },
            series: []
        }, true);
        return;
    }

    const colors = [
        UAB_COLORS.campus,
        UAB_COLORS.cala,
        UAB_COLORS.ocas,
        UAB_COLORS.collserola,
        UAB_COLORS.pissarra,
        UAB_COLORS.tauro,
        '#73a437',
        '#faae57'
    ];
    const seriesData = await Promise.all(
        departamentosComparativa.map(async (dep, index) => {
            const params = new URLSearchParams({ desde: String(desde), hasta: String(hasta), deptUuid: dep.uuid, modoAnio });
            if (persona) {
                params.set('persona', persona);
            }
            // Añadir filtro de categoría si aplica
            if (Array.isArray(categoria) && categoria.length > 0) {
                categoria.forEach(cat => params.append('categoria', cat));
            } else if (typeof categoria === 'string' && categoria) {
                params.set('categoria', categoria);
            }
            if (Array.isArray(tipus) && tipus.length > 0) {
                tipus.forEach(t => params.append('tipus', t));
            } else if (typeof tipus === 'string' && tipus) {
                params.set('tipus', tipus);
            }
            const res = await fetch(apiUrl(`/awards/stats/proyectos-anio?${params.toString()}`));
            if (!res.ok) {
                return {
                    name: dep.nombre,
                    type: 'line',
                    data: anios.map(() => 0),
                    smooth: 0.2,
                    lineStyle: { width: 2, color: colors[index % colors.length] },
                    itemStyle: { color: colors[index % colors.length] }
                };
            }

            const rows = await res.json();
            const byYear = new Map(rows.map(r => [Number(r.anio), Number(r.importeTotal || 0)]));

            return {
                name: dep.nombre,
                type: 'line',
                data: anios.map(y => byYear.get(y) ?? 0),
                smooth: 0.2,
                lineStyle: { width: 2, color: colors[index % colors.length] },
                itemStyle: { color: colors[index % colors.length] }
            };
        })
    );

    chartComparativaDept.setOption({
        title: { text: '' },
        tooltip: { trigger: 'axis' },
        legend: { type: 'scroll', bottom: 0 },
        grid: { left: 55, right: 20, top: 20, bottom: 60 },
        xAxis: { type: 'category', data: anios },
        yAxis: {
            type: 'value',
            axisLabel: {
                formatter: (value) => formatearCompactoEje(value)
            },
            splitLine: { lineStyle: { color: UAB_COLORS.columna } }
        },
        series: seriesData
    }, true);
}

function renderDepartamentosComparativaSeleccionados() {
    const container = document.getElementById('compareDepartamentosSeleccionados');
    container.innerHTML = '';

    departamentosComparativa.forEach(dep => {
        const chip = document.createElement('span');
        chip.className = 'inline-flex items-center gap-2 px-3 py-1 rounded-full bg-indigo-50 text-indigo-700 text-xs font-semibold';
        chip.innerHTML = `${dep.nombre} <button type="button" class="text-indigo-500 hover:text-indigo-700" data-remove-uuid="${dep.uuid}"><i class="fa-solid fa-xmark"></i></button>`;
        container.appendChild(chip);
    });

    container.querySelectorAll('button[data-remove-uuid]').forEach(btn => {
        btn.addEventListener('click', async (event) => {
            const uuid = event.currentTarget.getAttribute('data-remove-uuid');
            departamentosComparativa = departamentosComparativa.filter(d => d.uuid !== uuid);
            renderDepartamentosComparativaSeleccionados();
            await actualizarGraficoComparativaDepartamentos();
        });
    });
}

async function agregarDepartamentoComparativa() {
    const select = document.getElementById('compareDepartamentoSelect');
    const uuid = select.value;
    if (!uuid) return;

    const existente = departamentosComparativa.some(d => d.uuid === uuid);
    if (existente) return;

    const depto = departamentosCatalogo.find(d => d.uuid === uuid);
    if (!depto) return;

    departamentosComparativa.push({ uuid: depto.uuid, nombre: depto.nombre });
    renderDepartamentosComparativaSeleccionados();
    await actualizarGraficoComparativaDepartamentos();
}

/**
 * Flujo principal de carga: consulta APIs, actualiza tablas y gráficos.
 * @returns {Promise<void>}
 */
async function cargarDatos() {
    const estado = document.getElementById('estado');
    const { desde, hasta, deptUuid, persona, modoAnio, categoria, tipus } = obtenerFiltrosActuales();
    document.getElementById('seccionAwardsPersona').classList.add('hidden');
    if (tablaAwards) tablaAwards.clearData();
    mostrarOverlayCargando();

    try {
        mostrarEstadoCargando();
        let data = [];
        let serieProyectos = [];
        // Leer el valor actual del select
        const modoAwardsDept = document.getElementById('selectAwardsDept')?.value || 'miembros';
            if (modoAwardsDept === 'gestionados' && deptUuid) {
                // Awards gestionados por el departamento
                const params = new URLSearchParams({
                    desde: String(desde),
                    hasta: String(hasta),
                    modoAnio
                });
                params.set('deptUuid', deptUuid);
                if (persona) params.set('persona', persona);
                if (Array.isArray(categoria) && categoria.length > 0) {
                    categoria.forEach(cat => params.append('categoria', cat));
                } else if (typeof categoria === 'string' && categoria) {
                    params.set('categoria', categoria);
                }
                if (Array.isArray(tipus) && tipus.length > 0) {
                    tipus.forEach(t => params.append('tipus', t));
                } else if (typeof tipus === 'string' && tipus) {
                    params.set('tipus', tipus);
                }
                if (modoAwardsDept === 'gestionados') {
                    params.set('gestionadosPorDept', 'managed');
                }
                const res = await fetch(apiUrl(`/awards/stats/persona-resumen?${params.toString()}`));
                if (!res.ok) throw new Error(`HTTP ${res.status}`);
                data = await res.json();
                // Adaptar data si es necesario para los gráficos/tablas
                // (aquí puedes mapear los campos si el formato es diferente)
            } else {
            // Awards de miembros (lógica original)
            const params = new URLSearchParams({
                desde: String(desde),
                hasta: String(hasta),
                modoAnio
            });
            if (deptUuid) params.set('deptUuid', deptUuid);
            if (persona) params.set('persona', persona);
            if (Array.isArray(categoria) && categoria.length > 0) {
                categoria.forEach(cat => params.append('categoria', cat));
            } else if (typeof categoria === 'string' && categoria) {
                params.set('categoria', categoria);
            }
            if (Array.isArray(tipus) && tipus.length > 0) {
                tipus.forEach(t => params.append('tipus', t));
            } else if (typeof tipus === 'string' && tipus) {
                params.set('tipus', tipus);
            }
            if (modoAwardsDept === 'gestionados') {
                params.set('gestionadosPorDept', 'managed');
            }
            const [resumenRes, serieProyectosRes] = await Promise.all([
                fetch(apiUrl(`/awards/stats/persona-resumen?${params.toString()}`)),
                cargarSerieProyectosAnio()
            ]);
            if (!resumenRes.ok) throw new Error(`HTTP ${resumenRes.status}`);
            data = await resumenRes.json();
            serieProyectos = await serieProyectosRes;
        }
        let personalDept = [];
        if (Array.isArray(deptUuid) ? deptUuid.length > 0 : Boolean(deptUuid)) {
            try {
                personalDept = await cargarPersonalDepartamento(deptUuid);
            } catch (e) {
                console.warn('No s\'ha pogut carregar el personal del departament per completar la taula resum.', e);
            }
        }

        filasResumenActual = data;
        personaTopSeleccionada = null;
        const seccionEvol = document.getElementById('seccionEvolucionPersonaDept');
        if (seccionEvol) {
            seccionEvol.classList.add('hidden');
            if (window.tablaEvolucionPersonaDept) window.tablaEvolucionPersonaDept.clearData();
        }
        renderTabla(data, modoAnio, desde, hasta, personalDept);
        renderTablaCrecimiento(data, desde, hasta);
        renderGraficos(data);
        if (chartImporteAnio) chartImporteAnio.dispose();
        renderGraficoImportePorProyecto(serieProyectos);
        //estado.textContent = `Resultats: ${data.length} files`;
        estado.className = 'p-4 text-sm text-emerald-600 font-semibold';
    } catch (error) {
        estado.textContent = `Error en carregar dades: ${error.message}`;
        estado.className = 'p-4 text-sm text-red-600';
        renderTabla([], modoAnio, desde, hasta);
        renderTablaCrecimiento([], desde, hasta);
        renderTablaEvolucionPersonaDept([], desde, hasta);
        renderGraficos([]);
        renderGraficoImportePorProyecto([]);
    } finally {
        ocultarOverlayCargando();
    }
}

/**
 * Carga departamentos en el selector de filtro.
 * @returns {Promise<void>}
 */
async function cargarDepartamentos() {
    const select = document.getElementById('departamentoSelect');
    const dropdownMenu = document.getElementById('departamentoDropdownMenu');
    select.innerHTML = '';
    dropdownMenu.innerHTML = '';
    // --- AUTOCOMPLETER: input de búsqueda ---
    const searchInput = document.createElement('input');
    searchInput.type = 'text';
    searchInput.placeholder = 'Buscar departamento...';
    searchInput.className = 'w-full px-3 py-2 mb-2 border border-gray-200 rounded text-sm';
    dropdownMenu.appendChild(searchInput);
    // Contenedor para las opciones filtradas
    const optionsContainer = document.createElement('div');
    dropdownMenu.appendChild(optionsContainer);
    let departamentos = [];
    try {
        const res = await fetch(apiUrl('/persons/departamentos'));
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        departamentos = await res.json();
        departamentosCatalogo = departamentos;
        // Para comparativa
        const compareSelect = document.getElementById('compareDepartamentoSelect');
        departamentos.forEach(dep => {
            const option = document.createElement('option');
            option.value = dep.uuid;
            option.textContent = dep.nombre;
            compareSelect.appendChild(option);
        });
    } catch (error) {
        const option = document.createElement('option');
        option.value = '';
        option.textContent = 'No s\'han pogut carregar els departaments';
        select.appendChild(option);
        optionsContainer.innerHTML = '<div class="px-3 py-2 text-red-600 text-sm">No s\'han pogut carregar els departaments</div>';
        renderizarChipsDepartamentos();
        return;
    }
    // Opciones ocultas para el select (por compatibilidad)
    departamentos.forEach(dep => {
        const option = document.createElement('option');
        option.value = dep.uuid;
        option.textContent = dep.nombre;
        select.appendChild(option);
    });
    // --- Renderizado dinámico de opciones filtradas ---
    function renderOpcionesDepartamento(filtro) {
        optionsContainer.innerHTML = '';
        const texto = (filtro || '').toLowerCase();
        const filtrados = departamentos.filter(dep => dep.nombre.toLowerCase().includes(texto));
        if (filtrados.length === 0) {
            optionsContainer.innerHTML = '<div class="px-3 py-2 text-gray-400 text-sm">No hi ha resultats</div>';
            return;
        }
        filtrados.forEach(dep => {
            const item = document.createElement('div');
            item.className = 'px-3 py-2 hover:bg-indigo-50 cursor-pointer text-sm';
            item.textContent = dep.nombre;
            item.dataset.value = dep.uuid;
            item.addEventListener('click', () => {
                toggleDepartamentoSeleccionado(dep.uuid);
                cerrarDropdownDepartamentos();
            });
            optionsContainer.appendChild(item);
        });
    }
    // Inicial y eventos
    renderOpcionesDepartamento('');
    searchInput.addEventListener('input', (e) => {
        renderOpcionesDepartamento(e.target.value);
    });
    renderizarChipsDepartamentos();
}

/** Inicializa noUiSlider del rango temporal. */
function configurarSlider() {
    const ahora = new Date().getFullYear();
    const min = 2010;
    const max = ahora + 1;
    const inicioDesde = 2021;
    const inicioHasta = 2025;
    const slider = document.getElementById('sliderAnios');
    const valorRango = document.getElementById('valorRango');

    noUiSlider.create(slider, {
        start: [inicioDesde, inicioHasta],
        connect: true,
        step: 1,
        range: { min, max },
        format: {
            to: value => Math.round(value),
            from: value => Number(value)
        }
    });

    slider.noUiSlider.on('update', (values) => {
        valorRango.textContent = `${values[0]} - ${values[1]}`;
        programarRefresco();
    });
}


// El select oculto ya no dispara el refresco, lo hace el sistema de chips
//document.getElementById('departamentoSelect').addEventListener('change', programarRefresco);
document.getElementById('personaInput').addEventListener('input', programarRefresco);
document.getElementById('modoAnioSelect').addEventListener('change', programarRefresco);
// El select oculto ya no dispara el refresco, lo hace el sistema de chips
document.getElementById('btnAddDepartamentoCompare').addEventListener('click', agregarDepartamentoComparativa);

document.addEventListener('fullscreenchange', () => {
    if (chartImporteAnio) chartImporteAnio.resize();
    if (chartProyectosAnio) chartProyectosAnio.resize();
    if (chartTopPersonas) chartTopPersonas.resize();
    if (chartComparativaDept) chartComparativaDept.resize();
    if (chartPersonasConSinProyectos) chartPersonasConSinProyectos.resize();
    if (chartEmploymentTypeConSin) chartEmploymentTypeConSin.resize();
});

window.addEventListener('resize', () => {
    if (chartImporteAnio) chartImporteAnio.resize();
    if (chartProyectosAnio) chartProyectosAnio.resize();
    if (chartTopPersonas) chartTopPersonas.resize();
    if (chartComparativaDept) chartComparativaDept.resize();
    if (chartPersonasConSinProyectos) chartPersonasConSinProyectos.resize();
    if (chartEmploymentTypeConSin) chartEmploymentTypeConSin.resize();
});

async function init() {
    inicializarTablas();
    chartComparativaDept = echarts.init(document.getElementById('chartComparativaDept'));
    configurarSlider();
    await cargarDepartamentos();
    await cargarCategorias();
    await cargarTipus();
    await actualizarGraficoComparativaDepartamentos();
    
    cargarDatos();
}

// Tabs logic: keep filters always visible, switch content, and show dept tab only if a department is selected
document.addEventListener('DOMContentLoaded', function() {
    const tabResumenBtn = document.getElementById('tabResumenBtn');
    const tabDeptBtn = document.getElementById('tabDeptBtn');
    const tabResumen = document.getElementById('tabResumen');
    const tabDept = document.getElementById('tabDept');
    const tabNav = tabDeptBtn.parentElement;

    function resizeDeptCharts() {
        // Redimensiona todos los gráficos ECharts de la pestaña depto
        if (chartImporteAnio && typeof chartImporteAnio.resize === 'function') chartImporteAnio.resize();
        if (chartProyectosAnio && typeof chartProyectosAnio.resize === 'function') chartProyectosAnio.resize();
        if (chartTopPersonas && typeof chartTopPersonas.resize === 'function') chartTopPersonas.resize();
        if (chartLiderazgo && typeof chartLiderazgo.resize === 'function') chartLiderazgo.resize();
        if (chartQuadrantsPersona && typeof chartQuadrantsPersona.resize === 'function') chartQuadrantsPersona.resize();
        if (chartPareto && typeof chartPareto.resize === 'function') chartPareto.resize();
       
    }

    function updateDeptTabVisibility() {
        // departamentosSeleccionados es global en persona-resumen.js
        const hasDept = departamentosSeleccionados && departamentosSeleccionados.length > 0;
        if (hasDept) {
            tabDeptBtn.classList.remove('hidden');
            // Si la pestaña estaba activa y se quitó el último departamento, volver a resumen
        } else {
            tabDeptBtn.classList.add('hidden');
            tabDept.classList.add('hidden');
            tabResumen.classList.remove('hidden');
            tabResumenBtn.classList.add('text-indigo-700', 'border-indigo-600');
            tabResumenBtn.classList.remove('text-slate-500', 'border-transparent');
            tabDeptBtn.classList.remove('text-indigo-700', 'border-indigo-600');
            tabDeptBtn.classList.add('text-slate-500', 'border-transparent');
        }
    }

    tabResumenBtn.addEventListener('click', function() {
        tabResumen.classList.remove('hidden');
        tabDept.classList.add('hidden');
        tabResumenBtn.classList.add('text-indigo-700', 'border-indigo-600');
        tabResumenBtn.classList.remove('text-slate-500', 'border-transparent');
        tabDeptBtn.classList.remove('text-indigo-700', 'border-indigo-600');
        tabDeptBtn.classList.add('text-slate-500', 'border-transparent');
    });
    tabDeptBtn.addEventListener('click', function() {
        tabResumen.classList.add('hidden');
        tabDept.classList.remove('hidden');
        tabDeptBtn.classList.add('text-indigo-700', 'border-indigo-600');
        tabDeptBtn.classList.remove('text-slate-500', 'border-transparent');
        tabResumenBtn.classList.remove('text-indigo-700', 'border-indigo-600');
        tabResumenBtn.classList.add('text-slate-500', 'border-transparent');
        setTimeout(resizeDeptCharts, 100);
    });

    // Hook into chips rendering to update tab visibility
    const origRenderizarChipsDepartamentos = window.renderizarChipsDepartamentos;
    window.renderizarChipsDepartamentos = function() {
        if (origRenderizarChipsDepartamentos) origRenderizarChipsDepartamentos.apply(this, arguments);
        updateDeptTabVisibility();
    };
    // Llamar al cargar
    updateDeptTabVisibility();
});

init();