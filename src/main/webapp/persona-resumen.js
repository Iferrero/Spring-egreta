/**
 * Renderiza un gráfico de pastel con la proporción de PDI que ha generado ingresos y la que no.
 * @param {Array<object>} filas - Filas de datos de personas (resumen).
 */
function renderGraficoPdiIngresos(filas) {
    // Contar PDI con ingresos (>0) y sin ingresos (==0)
    let conIngresos = 0;
    let sinIngresos = 0;
    (filas || []).forEach(f => {
        const importe = Number(f['Importe_Ponderado (€)'] ?? f.importePonderado ?? 0);
        if (importe > 0) conIngresos++;
        else sinIngresos++;
    });

    // Crear o reutilizar el contenedor
    let container = document.getElementById('chartPdiIngresos');
    if (!container) {
        container = document.createElement('div');
        container.id = 'chartPdiIngresos';
        container.style = 'width: 100%; max-width: 340px; height: 260px; margin: 0 auto 18px auto;';
        // Insertar antes del grid de cuartiles si existe
        const grid = document.getElementById('cuartilesPersonasGrid');
        if (grid && grid.parentNode) {
            grid.parentNode.insertBefore(container, grid);
        } else {
            document.body.appendChild(container);
        }
    }

    // Inicializar ECharts
    const chart = echarts.init(container);
    chart.setOption({
        title: {
            text: 'PDI segons generació d\'ingressos',
            left: 'center',
            top: 10,
            textStyle: { fontSize: 15, fontWeight: 700 }
        },
        tooltip: {
            trigger: 'item',
            formatter: p => `<b>${p.name}</b>: <b>${p.value}</b> (${p.percent.toFixed(1)}%)`
        },
        legend: {
            orient: 'horizontal',
            bottom: 0,
            left: 'center',
            textStyle: { fontSize: 11 },
            itemWidth: 12, itemHeight: 12
        },
        series: [{
            name: 'PDI',
            type: 'pie',
            radius: ['40%', '70%'],
            center: ['50%', '50%'],
            avoidLabelOverlap: true,
            label: {
                show: true,
                formatter: p => `${p.name}: ${p.value}`,
                fontSize: 13,
                fontWeight: 600
            },
            labelLine: { show: true },
            data: [
                { value: conIngresos, name: 'Amb ingressos', itemStyle: { color: UAB_COLORS.campus } },
                { value: sinIngresos, name: 'Sense ingressos', itemStyle: { color: UAB_COLORS.ocas } }
            ]
        }]
    });
}
/**
 * Calcula la edad a partir de una fecha de nacimiento en formato ISO (YYYY-MM-DD) o Date.
 * @param {string|Date} fechaNacimiento
 * @returns {number|null} Edad en años o null si no se puede calcular
 */
function calcularEdad(fechaNacimiento) {
    if (!fechaNacimiento) return null;
    let fecha;
    if (typeof fechaNacimiento === 'string') {
        // Acepta formatos YYYY-MM-DD, YYYY/MM/DD, o ISO
        const clean = fechaNacimiento.replace(/\//g, '-');
        fecha = new Date(clean);
        if (isNaN(fecha)) return null;
    } else if (fechaNacimiento instanceof Date) {
        fecha = fechaNacimiento;
    } else {
        return null;
    }
    const hoy = new Date();
    let edad = hoy.getFullYear() - fecha.getFullYear();
    const m = hoy.getMonth() - fecha.getMonth();
    if (m < 0 || (m === 0 && hoy.getDate() < fecha.getDate())) {
        edad--;
    }
    return edad;
}

// Instancia global para el gráfico de evolución persona vs depto
let chartEvolucionPersonaDept = null;
// Instancia global para el gráfico de cuartiles de ingresos
let chartCuartilesIngresos = null;
let chartCuartilesPie = null;


/**
 * Renderiza el gráfico de líneas doble: evolución investigador vs media departamento
 * @param {Array<{anio:number, personaImporte:number, deptoMedia:number}>} rows
 */
function renderGraficoEvolucionPersonaDept(rows) {
    const container = document.getElementById('chartEvolucionPersonaDept');
    if (!container) return;
    chartEvolucionPersonaDept?.dispose();
    chartEvolucionPersonaDept = echarts.init(container);
    const anios = rows.map(r => r.anio);
    const personaData = rows.map(r => r.personaImporte);
    const deptoData = rows.map(r => r.deptoMedia);
    chartEvolucionPersonaDept.setOption({
        tooltip: {
            trigger: 'axis',
            formatter: params => params.map(p =>
                `${p.marker} ${p.seriesName}: <b>${formatearNumero(p.value)} €</b>`
            ).join('<br>')
        },
        legend: { data: ['Investigador', 'Mitja Dept.'], top: 10 },
        grid: { left: '5%', right: 40, top: 40, bottom: 40, containLabel: true },
        xAxis: {
            type: 'category',
            data: anios,
            name: 'Any',
            nameLocation: 'middle',
            nameGap: 30
        },
        yAxis: {
            type: 'value',
            name: '€ Mitja Dept.',
            position: 'left',
            axisLabel: {
                formatter: value => formatearNumero(value) + ' €',
                overflow: 'truncate',
                width: 120
            }
        },
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
                yAxisIndex: 0,
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
// --- Chips y dropdown visual para departamentos ---
/**
 * Calcula els grups de quartils a partir d'un mapa de persones amb importePonderado.
 * @param {Map} agrupadoPorPersona
 * @returns {Array<{label:string,color:string,bg:string,border:string,textColor:string,items:Array}>}
 */
function calcularQuartilesGrups(agrupadoPorPersona) {
    const personas = Array.from(agrupadoPorPersona.values()).map(p => ({
        nom: p.nombre ?? p.nom ?? p.persona ?? '',
        uuid: p.uuid ?? p.personaUuid ?? '',
        importPonderat: p.importePonderado ?? 0,
        edad: p.edad ?? null
    }));
    const sorted = personas.slice().sort((a, b) => b.importPonderat - a.importPonderat);
    const n = sorted.length;
    const grups = [
        { label: '≥ 95 · Top 5%',        color: '#c9a227', bg: '#f5efcc', border: '#e0d9b6', textColor: '#7a6200', items: [] },
        { label: '≥ 75 · Alt rendiment',  color: '#1a9e6b', bg: '#d1f4e4', border: '#a3e4c7', textColor: '#0f5132', items: [] },
        { label: '≥ 50 · Mitjana',        color: '#1a7cff', bg: '#dfe9f9', border: '#b5c9e6', textColor: '#084298', items: [] },
        { label: '< 50 · Per sota',       color: '#888',    bg: '#f8f9fa', border: '#e2e3e5', textColor: '#6c757d', items: [] }
    ];
    if (n === 0) return grups;
    if (n <= 4) {
        const sizes = [Math.ceil(n / 4), 0, 0, 0];
        sizes[1] = Math.ceil((n - sizes[0]) / 3);
        sizes[2] = Math.ceil((n - sizes[0] - sizes[1]) / 2);
        sizes[3] = n - sizes[0] - sizes[1] - sizes[2];
        let idx = 0;
        for (let g = 0; g < 4; g++) {
            for (let k = 0; k < sizes[g] && idx < n; k++, idx++) grups[g].items.push(sorted[idx]);
        }
    } else {
        sorted.forEach((p, i) => { p.percentil = (1 - i / (n - 1)) * 100; });
        for (const p of sorted) {
            if (p.percentil >= 95)      grups[0].items.push(p);
            else if (p.percentil >= 75) grups[1].items.push(p);
            else if (p.percentil >= 50) grups[2].items.push(p);
            else                        grups[3].items.push(p);
        }
    }
    return grups;
}

/**
 * Renderiza el gráfico de distribución por cuartiles de ingresos.
 * @param {Map} agrupadoPorPersona - Mapa de personas con importePonderado.
 */
function renderGraficoCuartilesIngresos(agrupadoPorPersona) {
    const container = document.getElementById('chartCuartilesIngresos');
    if (!container) return;
    chartCuartilesIngresos?.dispose();
    chartCuartilesIngresos = null;

    const grups = calcularQuartilesGrups(agrupadoPorPersona);

    // Grupos con datos (para gráficos)
    const grupsPoblats = grups.filter(g => g.items.length > 0);

    // 4a. Gráfico de pastel: nº de personas por grupo
    const pieContainer = document.getElementById('chartCuartilesPie');
    if (pieContainer) {
        chartCuartilesPie?.dispose();
        chartCuartilesPie = echarts.init(pieContainer);
        if (grupsPoblats.length === 0) {
            chartCuartilesPie.setOption({ title: { text: 'Sense dades', left: 'center', top: 'center', textStyle: { color: '#aaa', fontSize: 13 } } });
        } else {
            chartCuartilesPie.setOption({
                tooltip: {
                    trigger: 'item',
                    formatter: p => `<b>${p.name}</b><br>Persones: <b>${p.value}</b><br>${p.percent.toFixed(1)}%`
                },
                legend: {
                    orient: 'horizontal',
                    bottom: 4,
                    left: 'center',
                    textStyle: { fontSize: 10 },
                    itemWidth: 10, itemHeight: 10,
                    formatter: name => name.split('·')[0].trim()
                },
                series: [{
                    name: 'Quartil',
                    type: 'pie',
                    radius: ['35%', '62%'],
                    center: ['50%', '44%'],
                    avoidLabelOverlap: true,
                    label: {
                        show: true,
                        formatter: p => p.value > 0 ? `${p.value}` : '',
                        fontSize: 13,
                        fontWeight: 700
                    },
                    labelLine: { show: true },
                    data: grupsPoblats.map(g => ({
                        name: g.label,
                        value: g.items.length,
                        itemStyle: { color: g.color }
                    }))
                }]
            });
        }
    }

    // 4b. Gráfico de barras horizontales: número de personas por grupo (altura) y dinero ponderado como etiqueta interna
    // Invertimos para que ECharts (que renderiza de abajo a arriba) muestre Top 5% arriba
    const grupsPoblatsInv = [...grupsPoblats].reverse();
    chartCuartilesIngresos = echarts.init(container);
    if (grupsPoblatsInv.length === 0) {
        chartCuartilesIngresos.setOption({ title: { text: 'Sense dades', left: 'center', top: 'center', textStyle: { color: '#aaa', fontSize: 13 } } });
    } else {
        chartCuartilesIngresos.setOption({
            grid: { containLabel: true, left: 8, right: 16, top: 10, bottom: 10 },
            tooltip: {
                trigger: 'axis',
                formatter: params => {
                    const g = grupsPoblatsInv[params[0].dataIndex];
                    const total = g.items.reduce((s, p) => s + p.importPonderat, 0);
                    return `<b>${g.label}</b><br>Persones: <b>${g.items.length}</b><br>Total ingressos: <b>${formatearNumero(total)} €</b>`;
                }
            },
            xAxis: {
                type: 'value',
                axisLabel: { formatter: v => v + 'p', fontSize: 10 },
                splitLine: { lineStyle: { type: 'dashed', color: '#eee' } }
            },
            yAxis: {
                type: 'category',
                data: grupsPoblatsInv.map(g => g.label),
                axisLabel: { fontSize: 10, fontWeight: 600, overflow: 'none' }
            },
            series: [{
                type: 'bar',
                barMaxWidth: 44,
                data: grupsPoblatsInv.map(g => ({
                    value: g.items.length,
                    totalImporte: g.items.reduce((s, p) => s + p.importPonderat, 0),
                    itemStyle: { color: g.color, borderRadius: [0, 6, 6, 0] }
                })),
                label: {
                    show: true,
                    position: 'insideRight',
                    formatter: params => {
                        const g = grupsPoblatsInv[params.dataIndex];
                        const total = g.items.reduce((s, p) => s + p.importPonderat, 0);
                        return `${g.items.length}p\n${formatearNumero(total)} €`;
                    },
                    fontSize: 11,
                    fontWeight: 700,
                    color: '#fff',
                    textBorderColor: 'transparent',
                    lineHeight: 15
                }
            }]
        });
    }

    // 5. Cards con nombres abreviados
    const grid = document.getElementById('cuartilesPersonasGrid');
    if (!grid) return;
    grid.innerHTML = '';
    grups.forEach(g => {
        const card = document.createElement('div');
        card.style.cssText = `background:${g.bg};border:1px solid ${g.border};border-radius:12px;padding:12px;`;
        
                        
        const chips = g.items.map(p => {
            const nombreEscapado = p.nom.replace(/'/g, "\\'");
            const uuidEscapado = p.uuid.replace(/'/g, "\\'");
            return `<span title="${p.nom}" 
            onclick="aplicarSeleccionPersona ({persona:'${nombreEscapado}',personaUuid:'${uuidEscapado}'})"                             
            style="color: ${g.textColor};"
            class="chip-persona-clicable">
            ${p.nom}</span>`;
        }).join('');
        card.innerHTML = `
            <div style="font-size:12px;font-weight:700;color:${g.textColor};margin-bottom:6px">
                ${g.label} <span style="font-weight:400;opacity:0.6">(${g.items.length})</span>
            </div>
            <div>${chips || '<span style="font-size:11px;opacity:0.45">Cap investigador</span>'}</div>`;
        grid.appendChild(card);
    });
}

/**
 * Renderitza un panell de chips genèric.
 * @param {string} containerId  ID del contenidor de chips
 * @param {string} selectId     ID del select ocult associat
 * @param {string[]} items      Valors seleccionats
 * @param {function(string):string} getLabel  Retorna el text de la chip per a cada valor
 * @param {string} removeAttr   Nom de l'atribut data- del botó d'eliminar
 * @param {function(string):void} onRemove   Cridat quan l'usuari elimina un element
 * @param {string} labelId      ID de l'element de text del botó del desplegable
 * @param {string} emptyText    Text quan no hi ha selecció
 * @param {function():void} [onAfter]  Tasca addicional que s'executa al final
 */
function renderChipsPanel(containerId, selectId, items, getLabel, removeAttr, onRemove, labelId, emptyText, onAfter) {
    const container = document.getElementById(containerId);
    const select = document.getElementById(selectId);
    container.innerHTML = '';
    Array.from(select.options).forEach(opt => {
        opt.selected = items.includes(opt.value);
    });
    items.forEach(value => {
        const label = getLabel(value);
        if (label == null) return;
        const chip = document.createElement('span');
        chip.className = 'inline-flex items-center gap-2 px-3 py-1 rounded-full bg-indigo-50 text-indigo-700 text-xs font-semibold';
        chip.innerHTML = `${label} <button type="button" class="ml-1 text-indigo-500 hover:text-indigo-700" ${removeAttr}="${value}"><i class="fa-solid fa-xmark"></i></button>`;
        container.appendChild(chip);
    });
    container.querySelectorAll(`button[${removeAttr}]`).forEach(btn => {
        btn.addEventListener('click', e => onRemove(e.currentTarget.getAttribute(removeAttr)));
    });
    const btnLabel = document.getElementById(labelId);
    btnLabel.textContent = items.length === 0 ? emptyText : 'Afegeix més...';
    if (onAfter) onAfter();
    if (!_inicializando) programarRefresco();
}

function renderizarChipsDepartamentos() {
    renderChipsPanel(
        'departamentoChipsContainer', 'departamentoSelect',
        departamentosSeleccionados,
        uuid => { const d = departamentosCatalogo.find(x => x.uuid === uuid); return d ? d.nombre : null; },
        'data-remove-dep',
        quitarDepartamentoSeleccionado,
        'departamentoDropdownLabel', 'Afegeix departament...'
    );
}

function toggleDepartamentoSeleccionado(uuid) {
    if (departamentosSeleccionados.includes(uuid)) {
        quitarDepartamentoSeleccionado(uuid);
    } else {
        // Solo permitir uno: sustituir el anterior
        departamentosSeleccionados = [uuid];
        renderizarChipsDepartamentos();
        // Limpiar el análisis IA si existe el div
        const aiDeptOutput = document.getElementById('ai-dept-output');
        if (aiDeptOutput) {
            aiDeptOutput.textContent = "Prem el botó per obtenir l'anàlisi automàtica del departament.";
        }
    }
}

function quitarDepartamentoSeleccionado(uuid) {
    departamentosSeleccionados = departamentosSeleccionados.filter(d => d !== uuid);
    renderizarChipsDepartamentos();
    // Limpiar el análisis IA si existe el div
    const aiDeptOutput = document.getElementById('ai-dept-output');
    if (aiDeptOutput) {
        aiDeptOutput.textContent = "Prem el botó per obtenir l'anàlisi automàtica del departament.";
    }
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
    renderChipsPanel(
        'categoriaChipsContainer', 'categoriaSelect',
        categoriasSeleccionadas,
        cat => cat,
        'data-remove-cat',
        quitarCategoriaSeleccionada,
        'categoriaDropdownLabel', 'Afegeix categoria...',
        actualitzarTipusSegonsCategories
    );
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
    renderChipsPanel(
        'tipusChipsContainer', 'tipusSelect',
        tipusSeleccionados,
        tipusNom => tipusNom,
        'data-remove-tipus',
        quitarTipusSeleccionat,
        'tipusDropdownLabel', 'Afegeix tipus...',
        () => { window.tipusSeleccionados = [...tipusSeleccionados]; }
    );
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
let currentLoadController = null;
let currentLoadSeq = 0;
/** Evita llamadas a cargarDatos durante la inicialización (carga de catálogos). */
let _inicializando = true;

const _requestCache = new Map();

function _cacheKey(params, modo) {
    const sorted = new URLSearchParams([...params.entries()].sort());
    return `${modo}|${sorted.toString()}`;
}

/**
 * Afegeix els filtres de categoria, tipus i deptUuid a un URLSearchParams.
 * @param {URLSearchParams} params
 * @param {{categoria?:string|string[], tipus?:string|string[], deptUuid?:string|string[]}} filtres
 */
function appendFilterParams(params, { categoria, tipus, deptUuid } = {}) {
    if (Array.isArray(deptUuid) && deptUuid.length > 0) {
        deptUuid.forEach(dep => params.append('deptUuid', dep));
    } else if (typeof deptUuid === 'string' && deptUuid) {
        params.set('deptUuid', deptUuid);
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
}

/** Instancias de gráficos ECharts. */
let chartImporteAnio = null;
let chartProyectosAnio = null;
const chartComparativaPies = new Map();
let chartLiderazgo = null;
let chartQuadrantsPersona = null;

let chartPareto = null;

/** Estado de selección y datos en memoria del dashboard. */

let filasResumenActual = [];
let filasResumenTablaActual = [];
let personaTopSeleccionada = null;
let departamentosCatalogo = [];
let departamentosComparativa = [];
let departamentosSeleccionados = [];
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
let informeWordToastTimer = null;

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

function escaparHtml(valor) {
    return String(valor ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function formatearTituloAwardConEnlace(cell) {
    const row = cell.getRow()?.getData?.() || {};
    const titulo = cell.getValue() ?? '-';
    const pureId = row.pureId;
    if (pureId == null || String(pureId).trim() === '') {
        return escaparHtml(titulo);
    }
    const tituloEsc = escaparHtml(titulo);
    const idEsc = escaparHtml(String(pureId));
    return `<button type="button" data-egreta-id="${idEsc}" data-egreta-title="${tituloEsc}" class="text-left w-full text-indigo-700 hover:text-indigo-900 hover:underline cursor-pointer">${tituloEsc}</button>`;
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
    renderGraficoEvolucionPersonaDept(rows);
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

function mostrarAvisoGenerandoInformeWord() {
    let toast = document.getElementById('wordReportToast');
    if (!toast) {
        toast = document.createElement('div');
        toast.id = 'wordReportToast';
        toast.className = 'fixed bottom-5 right-5 z-50 bg-white border border-slate-200 shadow-lg rounded-xl px-4 py-3 text-sm text-slate-700 flex items-center gap-2';
        toast.innerHTML = '<i class="fa-solid fa-spinner fa-spin text-[#4F81BD]"></i><span>Generant informe Word...</span>';
        document.body.appendChild(toast);
    }
    toast.classList.remove('hidden');
}

function ocultarAvisoGenerandoInformeWord() {
    const toast = document.getElementById('wordReportToast');
    if (!toast) return;
    toast.classList.add('hidden');
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

    (filas || []).forEach(fila => {
        const personaUuid = fila['PersonaUuid'] || '';
        const persona = fila['Persona'] || '-';
        const key = `${personaUuid}||${persona}`;

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
            { title: 'Persona', field: 'persona', sorter: 'string', headerFilter: 'input', headerFilterPlaceholder: 'Cercar persona...' },
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
            { title: 'Títol', field: 'titulo', sorter: 'string', widthGrow: 2, formatter: formatearTituloAwardConEnlace },
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

    document.getElementById('btnDescargarExcelResumen')?.addEventListener('click', () => {
        if (tablaResumen) tablaResumen.download('xlsx', 'resum-investigadors.xlsx');
    });
    document.getElementById('btnDescargarExcelAwards')?.addEventListener('click', () => {
        if (tablaAwards) tablaAwards.download('xlsx', 'awards-persona.xlsx');
    });
}

/**
 * Carga datos en la tabla principal de resumen.
 * @param {Array<object>} filas
 * @param {string} modoAnio
 * @param {number} desde
 * @param {number} hasta
 */
function renderTabla(filas, modoAnio = 'awardDate', desde = 0, hasta = 0) {
    if (!tablaResumen) return;
    modoTablaResumenActual = modoAnio;
    const sourceRows = resumirFilasPorInvestigador(filas, desde, hasta);
    filasResumenTablaActual = sourceRows;
    const rows = sourceRows.map(f => {
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
    });
    tablaResumen.setData(rows);
    tablaResumen.recalc();
    estado.textContent = `Resultats: ${rows.length} files`;
}

/** Libera instancias de ECharts actuales antes de repintar. */
function destruirGraficos() {
    chartImporteAnio?.dispose();    chartImporteAnio = null;
    chartProyectosAnio?.dispose();  chartProyectosAnio = null;
    chartLiderazgo?.dispose();      chartLiderazgo = null;
    chartQuadrantsPersona?.dispose(); chartQuadrantsPersona = null;
    chartPareto?.dispose();         chartPareto = null;
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
        const cat = f['FunderType'] || 'Desconegut';
        if (!porAnio[anio]) {
            porAnio[anio] = {
                importeTotal: 0,
                importePonderado: 0,
                proyectos: 0,
                ipcoip: 0,
                coip: 0,
                miembro: 0,
                categorias: {}
            };
        }
        const ip = Number(f['Proyectos_IP'] || 0);
        const coip = Number(f['Proyectos_CoIP'] || 0);
        const miem = Number(f['Proyectos_Miembro'] || 0);
        const total = ip + coip + miem;
        porAnio[anio].importeTotal += Number(f['Importe_IP (€)'] ?? 0) + Number(f['Importe_CoIP (€)'] ?? 0) + Number(f['Importe_Miembro (€)'] ?? 0);
        porAnio[anio].importePonderado += Number(f['Importe_Ponderado (€)'] ?? 0);
        porAnio[anio].ipcoip += ip + coip;
        porAnio[anio].coip += coip;
        porAnio[anio].miembro += miem;
        porAnio[anio].proyectos += total;
        if (!porAnio[anio].categorias[cat]) porAnio[anio].categorias[cat] = 0;
        porAnio[anio].categorias[cat] += total;
    });
    return porAnio;
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

function renderizarChipPersona() {
    const container = document.getElementById('personaChipContainer');
    if (!container) return;
    container.innerHTML = '';
    if (!personaTopSeleccionada || !personaTopSeleccionada.persona) return;
    const chip = document.createElement('span');
    chip.className = 'inline-flex items-center gap-2 px-3 py-1 rounded-full bg-indigo-50 text-indigo-700 text-xs font-semibold';
    chip.innerHTML = `${personaTopSeleccionada.persona} <button type="button" class="ml-1 text-indigo-500 hover:text-indigo-700"><i class="fa-solid fa-xmark"></i></button>`;
    chip.querySelector('button').addEventListener('click', () => {
        aplicarSeleccionPersona(personaTopSeleccionada);
    });
    container.appendChild(chip);
}

async function aplicarSeleccionPersona(persona) {
    if (!persona) return;

    const identidad = obtenerIdentidadPersona(persona);
    if (!identidad.persona && !identidad.personaUuid) {
        return;
    }

    const yaSeleccionada = esMismaPersonaSeleccionada(identidad);
    personaTopSeleccionada = yaSeleccionada ? null : identidad;
    renderizarChipPersona();
    // Filtrar filas per persona seleccionada (només per al gràfic de projectes per any)
    let filasFiltrades = filasResumenActual;
    if (!yaSeleccionada && identidad.personaUuid) {
        filasFiltrades = filasResumenActual.filter(f => {
            return (f['PersonaUuid'] ?? f.personaUuid ?? '') === identidad.personaUuid;
        });
    }
    // Renderitzem tots els gràfics amb les dades globals (sense filtrar per persona)
    renderGraficos(filasResumenActual);
    // Sobreescrivim només el gràfic "Projectes per any" amb les dades de la persona
    renderGraficoProjectesPerAny(filasFiltrades);
    
    // Mostrar/ocultar tabla de evolución persona vs departamento
    const seccionEvol = document.getElementById('seccionEvolucionPersonaDept');
    if (personaTopSeleccionada && seccionEvol) {
        seccionEvol.classList.remove('hidden');
        // Cambiar el título del gráfico de evolución con el nombre de la persona
        const tituloEvol = seccionEvol.querySelector('h2');
        if (tituloEvol && personaTopSeleccionada.persona) {
            tituloEvol.textContent = `Evolució de ${personaTopSeleccionada.persona} vs departament`;
        } else if (tituloEvol) {
            tituloEvol.textContent = 'Evolució persona vs departament';
        }
        renderTablaEvolucionPersonaDept(filasFiltrades, personaTopSeleccionada);
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
            pureId: f.pureId ?? '',
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

    // Add managingOrganization and comanagingOrganization columns if not present
    tablaAwards.setColumns([
        { title: 'Any', field: 'anyo', width: 70 },
        { title: 'Títol', field: 'titulo', widthGrow: 2, formatter: formatearTituloAwardConEnlace },
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
    appendFilterParams(params, { deptUuid, categoria, tipus });
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
 * Genera l'informe Word de la persona seleccionada amb el mateix format que el certificat.
 * Utilitza el rang d'anys actiu i crida l'endpoint /persons/reports/word/person.
 */
function generarInformePersona() {
    if (!personaTopSeleccionada || !personaTopSeleccionada.personaUuid) {
        return;
    }
    const botonInforme = document.getElementById('btnGenerarInformeAwards');
    if (botonInforme) {
        botonInforme.disabled = true;
        botonInforme.classList.add('opacity-60', 'cursor-not-allowed');
    }

    mostrarAvisoGenerandoInformeWord();

    const finalizarAviso = () => {
        ocultarAvisoGenerandoInformeWord();
        if (botonInforme) {
            botonInforme.disabled = false;
            botonInforme.classList.remove('opacity-60', 'cursor-not-allowed');
        }
    };

    if (informeWordToastTimer) {
        clearTimeout(informeWordToastTimer);
    }
    informeWordToastTimer = setTimeout(finalizarAviso, 12000);
    window.addEventListener('focus', finalizarAviso, { once: true });
    window.addEventListener('pageshow', finalizarAviso, { once: true });

    const { desde, hasta, modoAnio, categoria, tipus } = obtenerFiltrosActuales();
    const startDate = `${desde}-01-01`;
    const endDate   = `${hasta}-12-31`;
    const params = new URLSearchParams({
        personUuid: personaTopSeleccionada.personaUuid,
        startDate,
        endDate,
        projectFilter: 'all',
        lang: 'ca',
        onlyAwards: 'true',
        modoAnio: modoAnio || 'awardDate'
    });
    appendFilterParams(params, { categoria, tipus });
    const url = apiUrl('/persons/reports/word/person') + '?' + params.toString();
    const a = document.createElement('a');
    a.href = url;
    a.download = '';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
}

/**
 * Renderiza los tres gráficos principales del dashboard.
 * @param {Array<object>} filas
 */
/**
 * Renderitza (o actualitza) únicament el gràfic "Projectes per any".
 * Accepta les files ja filtrades i, opcionalment, porAnio/anios/seriesCategorias
 * precalculats (quan es crida des de renderGraficos). Si no es passen, els calcula.
 * @param {Array<object>} filas
 * @param {object} [porAnioExt]
 * @param {number[]} [aniosExt]
 * @param {Array} [seriesCatsExt]
 */
function renderGraficoProjectesPerAny(filas, porAnioExt, aniosExt, seriesCatsExt) {
    const porAnio = porAnioExt ?? agruparPorAnio(filas);
    const anios   = aniosExt  ?? Object.keys(porAnio).map(Number).sort((a, b) => a - b);

    const importesPonderados = anios.map(a => porAnio[a].importePonderado);
    const proyectos          = anios.map(a => porAnio[a].proyectos);

    // Sèries per categoria (si no vénen precalculades, les recalculem)
    let seriesCategorias = seriesCatsExt;
    if (!seriesCategorias) {
        const todasCategorias = [...new Set(
            anios.flatMap(a => Object.keys(porAnio[a].categorias))
        )].sort();
        const PALETTE_CATS = [
            UAB_COLORS.campus,      // #008037 verd campus
            UAB_COLORS.ocas,        // #F88C12 ocas taronja
            UAB_COLORS.cala,        // #004D5E cala blau-verd
            UAB_COLORS.tauro,       // #596473 taure gris
            UAB_COLORS.collserola,  // #004d21 verd collserola
            '#00a34f',              // verd campus clar
            '#fab84c',              // ocas clar
            '#006b7a',              // cala clar
            '#8a99a8',              // taure clar
            UAB_COLORS.pissarra     // #2a3037 pissarra fosc
        ];
        // Asignar color por nombre de categoría (hash estable) para que cada clase
        // mantenga sempre el mateix color independentment del filtrat activo.
        // Excepcions fijes: pública → campus (verd), privada → ocas (taronja).
        const CAT_COLOR_FIXED = {
            'pública': UAB_COLORS.campus,
            'publica': UAB_COLORS.campus,
            'privada': UAB_COLORS.ocas,
        };
        function catColor(name) {
            const key = name.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '');
            const fixedKey = Object.keys(CAT_COLOR_FIXED).find(k =>
                name.toLowerCase() === k || key === k.normalize('NFD').replace(/[\u0300-\u036f]/g, '')
            );
            if (fixedKey) return CAT_COLOR_FIXED[fixedKey];
            let h = 0;
            for (let i = 0; i < name.length; i++) h = (Math.imul(31, h) + name.charCodeAt(i)) | 0;
            return PALETTE_CATS[Math.abs(h) % PALETTE_CATS.length];
        }
        seriesCategorias = todasCategorias.map(cat => ({
            name: cat, type: 'bar', stack: 'cats', yAxisIndex: 0,
            data: anios.map(a => porAnio[a].categorias[cat] ?? 0),
            itemStyle: { color: catColor(cat) }
        }));
    }

    if (chartProyectosAnio) {
        chartProyectosAnio.dispose();
        chartProyectosAnio = null;
    }
    chartProyectosAnio = echarts.init(document.getElementById('chartProyectosAnio'));
    chartProyectosAnio.setOption({
        tooltip: {
            trigger: 'axis',
            axisPointer: { type: 'shadow' },
            formatter: function(params) {
                let html = `<b>${params[0].axisValue}</b><br>`;
                params.forEach(p => {
                    if (p.value === 0 || p.value === null) return;
                    const val = p.seriesName.includes('€')
                        ? formatearNumero(p.value) + ' €'
                        : p.value;
                    html += `${p.marker}${p.seriesName}: <b>${val}</b><br>`;
                });
                return html;
            }
        },
        legend: { bottom: 0, type: 'scroll', textStyle: { fontSize: 11 } },
        grid: { left: 50, right: 60, top: 20, bottom: 70 },
        xAxis: { type: 'category', data: anios },
        yAxis: [
            {
                type: 'value', minInterval: 1,
                splitLine: { lineStyle: { color: UAB_COLORS.columna } },
                name: 'Projectes', nameTextStyle: { fontSize: 10 }
            },
            {
                type: 'value', name: 'Import (€)',
                nameTextStyle: { fontSize: 10 },
                axisLabel: { formatter: v => formatearCompactoEje(v), fontSize: 10 },
                splitLine: { show: false }
            }
        ],
        series: [
            ...seriesCategorias,
            /*{
                name: 'Total projectes', type: 'line', yAxisIndex: 0,
                data: proyectos, smooth: 0.2,
                lineStyle: { color: UAB_COLORS.ocas },
                itemStyle: { color: UAB_COLORS.ocas }, symbolSize: 6
            },*/
            {
                name: 'Import ponderat (€)', type: 'line', yAxisIndex: 1,
                data: importesPonderados, smooth: 0.25,
                lineStyle: { color: '#e05252', width: 2, type: 'dashed' },
                itemStyle: { color: '#e05252' }, symbolSize: 6
            }
        ]
    });
}

function renderGraficos(filas) {
    destruirGraficos();
    const seccionMatriz = document.getElementById('seccionMatrizLiderazgo');
    const seccionQuadrants = document.getElementById('seccionQuadrantsPersona');
    const seccionPareto = document.getElementById('seccionPareto');
    if (departamentosSeleccionados.length > 0) {
        seccionMatriz.classList.remove('hidden');
        seccionQuadrants.classList.remove('hidden');
        seccionPareto.classList.remove('hidden');
    } else {
        seccionMatriz.classList.add('hidden');
        seccionQuadrants.classList.add('hidden');
        seccionPareto.classList.add('hidden');
    }


    // Usar siempre los datos filtrados por el backend (ya incluyen categoría)
    // Usar siempre los datos recibidos (filtrados) para todos los gráficos
    const filasParaSeries = filas;
    const filasParaTop = filas;

    const porAnio = agruparPorAnio(filasParaSeries);
    const anios = Object.keys(porAnio).map(Number).sort((a, b) => a - b);

    const importes = anios.map(a => porAnio[a].importeTotal);
    const importesPonderados = anios.map(a => porAnio[a].importePonderado);
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

    // Collect all unique categories across all years, sorted
    const todasCategorias = [...new Set(
        anios.flatMap(a => Object.keys(porAnio[a].categorias))
    )].sort();
    const PALETTE_CATS = [
        UAB_COLORS.campus,      // #008037 verd campus
        UAB_COLORS.ocas,        // #F88C12 ocas taronja
        UAB_COLORS.cala,        // #004D5E cala blau-verd
        UAB_COLORS.tauro,       // #596473 taure gris
        UAB_COLORS.collserola,  // #004d21 verd collserola
        '#00a34f',              // verd campus clar
        '#fab84c',              // ocas clar
        '#006b7a',              // cala clar
        '#8a99a8',              // taure clar
        UAB_COLORS.pissarra     // #2a3037 pissarra fosc
    ];
    // Asignar color por nombre de categoría (hash estable) para que cada clase
    // mantenga sempre el mateix color independentment del filtrat activo.
    // Excepcions fijes: pública → campus (verd), privada → ocas (taronja).
    const CAT_COLOR_FIXED = {
        'pública': UAB_COLORS.campus,
        'publica': UAB_COLORS.campus,
        'privada': UAB_COLORS.ocas,
    };
    function catColor(name) {
        const key = name.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '');
        const fixedKey = Object.keys(CAT_COLOR_FIXED).find(k =>
            name.toLowerCase() === k || key === k.normalize('NFD').replace(/[\u0300-\u036f]/g, '')
        );
        if (fixedKey) return CAT_COLOR_FIXED[fixedKey];
        let h = 0;
        for (let i = 0; i < name.length; i++) h = (Math.imul(31, h) + name.charCodeAt(i)) | 0;
        return PALETTE_CATS[Math.abs(h) % PALETTE_CATS.length];
    }
    const seriesCategorias = todasCategorias.map((cat) => ({
        name: cat,
        type: 'bar',
        stack: 'cats',
        yAxisIndex: 0,
        data: anios.map(a => porAnio[a].categorias[cat] ?? 0),
        itemStyle: { color: catColor(cat) }
    }));

    renderGraficoProjectesPerAny(filas, porAnio, anios, seriesCategorias);



    // Calcular importe ponderado por persona para el top usando sólo filasParaTop
    const agrupadoPorPersonaTop = new Map();
    for (const f of filasParaTop) {
        const nombre = f['Persona'] ?? f.persona ?? 'N/D';
        const uuid = f['PersonaUuid'] ?? f.personaUuid ?? '';
        const fechaNac = f['Fecha_Nacimiento'] ?? f['fecha_nacimiento'] ?? f['nacimiento'] ?? f['birthdate'] ?? null;
        const edad = calcularEdad(fechaNac);
        const key = `${uuid}||${nombre}`;
        if (!agrupadoPorPersonaTop.has(key)) {
            agrupadoPorPersonaTop.set(key, {
                persona: nombre,
                personaUuid: uuid,
                importePonderado: 0,
                edad: edad
            });
        }
        const entry = agrupadoPorPersonaTop.get(key);
        // Usar directamente el campo del JSON
        entry.importePonderado += Number(f['Importe_Ponderado (€)'] ?? 0);
        // Si no tenía edad, la añade si la fila la tiene
        if (entry.edad == null && edad != null) entry.edad = edad;
    }
    // Guardar el agrupado de personas del departamento seleccionado en window para análisis IA
    window.agrupadoPorPersona = agrupadoPorPersonaTop;
    // Toggle para awards gestionados vs miembros
    let modoAwardsDept = 'miembros'; // 'miembros' o 'gestionados'

    function crearToggleAwardsDept() {
        const container = document.getElementById('toggleAwardsDeptContainer');
        if (!container) return;
        if (document.getElementById('toggleAwardsDept')) return;
        const toggle = document.createElement('div');
        toggle.id = 'toggleAwardsDept';
        toggle.className = 'flex items-center gap-2 mb-1';
        toggle.innerHTML = `
            <label class="text-xs font-semibold text-slate-600">Mostrar:</label>
            <select id="selectAwardsDept" class="border border-slate-300 rounded px-2 py-1 text-xs">
                <option value="miembros">Ajuts dels membres del departament</option>
                <option value="gestionados">Ajuts gestionats pel departament</option>
            </select>
        `;
        container.appendChild(toggle);
        document.getElementById('selectAwardsDept').addEventListener('change', (e) => {
            modoAwardsDept = e.target.value;
            programarRefresco();
        });
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
        if (currentLoadController) currentLoadController.abort();
        const controller = new AbortController();
        currentLoadController = controller;
        const requestSeq = ++currentLoadSeq;

        try {
            mostrarEstadoCargando();
            let data = [];
            let serieProyectos = [];
            // Leer el valor actual del select
            const modoAwardsDept = document.getElementById('selectAwardsDept')?.value || 'miembros';
            if (modoAwardsDept === 'gestionados' && deptUuid) {
                
                // Awards gestionados por el departamento
                const params = new URLSearchParams({ gestionadosPorDept: 'managed' });
                appendFilterParams(params, { deptUuid });
                if (persona) params.set('persona', persona);
                appendFilterParams(params, { categoria, tipus });
                const ckey = _cacheKey(params, modoAwardsDept);
                if (_requestCache.has(ckey)) {
                    data = _requestCache.get(ckey).data;
                } else {
                    const res = await fetch(apiUrl(`/awards/stats/persona-resumen?${params.toString()}`), { signal: controller.signal });
                    if (!res.ok) throw new Error(`HTTP ${res.status}`);
                    data = await res.json();
                    _requestCache.set(ckey, { data });
                }
            } else {
                // Awards de miembros (lógica original)
                const params = new URLSearchParams({
                    desde: String(desde),
                    hasta: String(hasta),
                    modoAnio
                });
                if (persona) params.set('persona', persona);
                appendFilterParams(params, { deptUuid, categoria, tipus });
                if (modoAwardsDept === 'gestionados') params.set('gestionadosPorDept', 'managed');
                const ckey = _cacheKey(params, modoAwardsDept);
                if (_requestCache.has(ckey)) {
                    const cached = _requestCache.get(ckey);
                    data = cached.data;
                    serieProyectos = cached.serieProyectos;
                } else {
                    const [resumenRes, serieProyectos_] = await Promise.all([
                        fetch(apiUrl(`/awards/stats/persona-resumen?${params.toString()}`), { signal: controller.signal }),
                        cargarSerieProyectosAnio(params, controller.signal)
                    ]);
                    if (!resumenRes.ok) throw new Error(`HTTP ${resumenRes.status}`);
                    data = await resumenRes.json();
                    serieProyectos = serieProyectos_;
                    _requestCache.set(ckey, { data, serieProyectos });
                }
            }
            if (requestSeq !== currentLoadSeq) return;
            filasResumenActual = data;
            personaTopSeleccionada = null;
            renderizarChipPersona();
            const seccionEvol = document.getElementById('seccionEvolucionPersonaDept');
            if (seccionEvol) {
                seccionEvol.classList.add('hidden');
                if (window.tablaEvolucionPersonaDept) window.tablaEvolucionPersonaDept.clearData();
            }
            renderTabla(data, modoAnio, desde, hasta);
            renderTablaCrecimiento(data, desde, hasta);
            renderGraficos(data);
            renderGraficoImportePorProyecto(serieProyectos);
            estado.className = 'p-4 text-sm text-emerald-600 font-semibold';
        } catch (error) {
            if (error && error.name === 'AbortError') return;
            if (requestSeq !== currentLoadSeq) return;
            estado.textContent = `Error en carregar dades: ${error.message}`;
            estado.className = 'p-4 text-sm text-red-600';
            renderTabla([], modoAnio, desde, hasta);
            renderTablaCrecimiento([], desde, hasta);
            renderTablaEvolucionPersonaDept([], desde, hasta);
            renderGraficos([]);
            renderGraficoImportePorProyecto([]);
        } finally {
            if (requestSeq === currentLoadSeq) ocultarOverlayCargando();
        }
    }
    // ...existing code...

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
        const datosLiderazgo = Array.from(agrupadoLiderazgo.values());

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
        renderGraficoCuartilesIngresos(agrupadoPorPersonaRanking);

        // Calcular medianas solo sobre personas con awards (excluir los 0/null)
        function mediana(arr) {
            if (!arr.length) return 0;
            const s = [...arr].sort((a, b) => a - b);
            const m = Math.floor(s.length / 2);
            return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
        }

        const datosConAwards = datosLiderazgo.filter(d =>
            (d.ponderado != null && d.ponderado > 0) || (d.ayudas != null && d.ayudas > 0)
        );
        const ponderadoArr = datosConAwards.map(d => d.ponderado);
        const ayudasArr = datosConAwards.map(d => d.ayudas);
        const medianaPonderado = mediana(ponderadoArr);
        const medianaAyudas = mediana(ayudasArr);


        chartLiderazgo = echarts.init(document.getElementById('chartLiderazgo'));
        // Colores más contrastados para los cuadrantes
        const COLOR_LIDERS = '#1a7cff';         // Azul fuerte
        const COLOR_ESPECIALISTES = '#ff3b30';  // Rojo fuerte
        const COLOR_FORMIGUES = '#ffb800';      // Amarillo fuerte
        const COLOR_ALTRES = '#7c3aed';         // Morado fuerte
        const COLOR_SENSE_AWARDS = '#b0b0b0';   // Gris

        // Separar puntos por cuadrante, añadiendo "Sense awards"
        const seriesCuadrantes = [
            { name: 'Líders',         color: COLOR_LIDERS,         items: [] },
            { name: 'Especialistes',  color: COLOR_ESPECIALISTES,  items: [] },
            { name: 'Dinamitzadors',  color: COLOR_FORMIGUES,      items: [] },
            { name: 'Perfils en Creixement',         color: COLOR_ALTRES,         items: [] },
            { name: 'Sense ajuts',   color: COLOR_SENSE_AWARDS,   items: [] }
        ];
        for (const d of datosLiderazgo) {
            const item = {
                value: [d.ponderado, d.ayudas],
                nombre: d.nombre, uuid: d.uuid,
                ponderado: d.ponderado, ayudas: d.ayudas, desglose: d.desglose
            };
            if ((d.ponderado === 0 || d.ponderado == null) && (d.ayudas === 0 || d.ayudas == null)) {
                seriesCuadrantes[4].items.push(item); // Sense awards
            } else if (d.ponderado >= medianaPonderado && d.ayudas >= medianaAyudas) {
                seriesCuadrantes[0].items.push(item);
            } else if (d.ponderado < medianaPonderado && d.ayudas >= medianaAyudas) {
                seriesCuadrantes[1].items.push(item);
            } else if (d.ponderado >= medianaPonderado && d.ayudas < medianaAyudas) {
                seriesCuadrantes[2].items.push(item);
            } else {
                seriesCuadrantes[3].items.push(item);
            }
        }

        const labelFormatter = function(params) {
            const nombre = (params.data.nombre ?? '').trim();
            const parts = nombre.split(/\s+/);
            if (parts.length <= 1) return parts[0] ?? '';
            const apellidos = parts.slice(-2).join(' ');
            const inicials = parts.slice(0, -2).map(p => p[0] + '.').join('');
            return (inicials ? inicials + ' ' : '') + apellidos;
        };

        chartLiderazgo.setOption({
            grid: { left: 60, right: 30, top: 60, bottom: 50 },
            legend: {
                data: seriesCuadrantes.map(s => ({ name: s.name, icon: 'circle' })),
                top: 8,
                textStyle: { fontSize: 12 },
                selectedMode: true
            },
            toolbox: {
                right: 10,
                top: 5,
                feature: {
                    dataZoom: {
                        yAxisIndex: 0,
                        title: { zoom: 'Zoom rectangular (arrossega per seleccionar)', back: 'Desfer zoom' },
                        brushStyle: {
                            borderWidth: 2,
                            borderColor: '#1a7cff',
                            color: 'rgba(26,124,255,0.2)'
                        }
                    },
                    restore: { title: 'Restablir zoom' }
                }
            },
            tooltip: {
                trigger: 'item',
                formatter: function(params) {
                    const d = params.data;
                    if (d.ponderado === 0 && d.ayudas === 0) {
                        return `<b>${d.nombre}</b><br><i>Sense ajuts</i>`;
                    }
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
                { type: 'inside', xAxisIndex: 0, filterMode: 'none' },
                { type: 'inside', yAxisIndex: 0, filterMode: 'none' }
            ],
            xAxis: {
                name: "Import ponderat (€)",
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
            series: seriesCuadrantes.map(s => ({
                name: s.name,
                type: 'scatter',
                symbolSize: 18,
                data: s.items,
                itemStyle: { color: s.color },
                emphasis: {
                    focus: 'self',
                    itemStyle: { borderColor: '#222', borderWidth: 2 },
                    label: { show: false }
                },
                label: {
                    show: true,
                    position: 'top',
                    formatter: labelFormatter,
                    fontSize: 10,
                    color: '#555',
                    fontWeight: 600
                },
                labelLayout: { hideOverlap: true }
            }))
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
            ALTRES: [],
            SENSE_AWARDS: []
        };
        for (const d of datosLiderazgo) {
            const x = d.ponderado;
            const y = d.ayudas;
            if ((x === 0 || x == null) && (y === 0 || y == null)) {
                cuadrantes.SENSE_AWARDS.push(d);
            } else if (x >= medianaPonderado && y >= medianaAyudas) {
                cuadrantes.LIDERS.push(d);
            } else if (x < medianaPonderado && y >= medianaAyudas) {
                cuadrantes.ESPECIALISTES.push(d);
            } else if (x >= medianaPonderado && y < medianaAyudas) {
                cuadrantes.FORMIGUES.push(d);
            } else {
                cuadrantes.ALTRES.push(d);
            }
        }
        // Render HTML grid de cuadrantes com a targetes de chips
        const container = document.getElementById('chartQuadrantsPersona');
        if (container) {
            const quadrantDefs = [
                { key: 'ESPECIALISTES', label: 'Especialistes d\'Alt Impacte', color: COLOR_ESPECIALISTES, bg: COLOR_ESPECIALISTES + '18', border: COLOR_ESPECIALISTES + '66' },
                { key: 'LIDERS',        label: 'Líders Consolidats',           color: COLOR_LIDERS,        bg: COLOR_LIDERS        + '18', border: COLOR_LIDERS        + '66' },
                { key: 'ALTRES',        label: 'Perfils en Creixement',        color: COLOR_ALTRES,        bg: COLOR_ALTRES        + '18', border: COLOR_ALTRES        + '66' },
                { key: 'FORMIGUES',     label: 'Dinamitzadors',                color: COLOR_FORMIGUES,     bg: COLOR_FORMIGUES     + '18', border: COLOR_FORMIGUES     + '66' },
                { key: 'SENSE_AWARDS',  label: 'Sense ajuts',                 color: COLOR_SENSE_AWARDS,  bg: COLOR_SENSE_AWARDS  + '18', border: COLOR_SENSE_AWARDS  + '66' }
            ];

            function abreviarNom(nom) {
                const parts = (nom ?? '').trim().split(/\s+/);
                if (parts.length <= 1) return parts[0] ?? '';
                const apellidos = parts.slice(-2).join(' ');
                const inicials = parts.slice(0, -2).map(p => p[0] + '.').join('');
                return (inicials ? inicials + ' ' : '') + apellidos;
            }

            container.innerHTML = `<div style="display:grid;grid-template-columns:1fr 1fr;gap:12px;">` +
                quadrantDefs.map(q => {
                    const persones = cuadrantes[q.key] ?? [];
                    const chips = persones.map(p => {
                        const nombreEscapado = p.nombre.replace(/'/g, "\\'");
                        const uuidEscapado = p.uuid.replace(/'/g, "\\'");
                        return `<span
                            title="${p.nombre}"
                            onclick="aplicarSeleccionPersona ({persona:'${nombreEscapado}',personaUuid:'${uuidEscapado}'})"
                            style="color:${q.color}"
                            class="chip-persona-clicable">
                        ${p.nombre}</span>`;
                    }).join('');
                    return `<div style="background:${q.bg};border:1.5px solid ${q.border};border-radius:14px;padding:12px;">
                        <div style="font-size:12px;font-weight:700;color:${q.color};margin-bottom:6px;">
                            ${q.label} <span style="font-weight:400;opacity:0.55">(${persones.length})</span>
                        </div>
                        <div>${chips || `<span style=\"font-size:11px;opacity:0.4\">Cap investigador</span>`}</div>
                    </div>`;
                }).join('') +
            `</div>`;
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
 * @param {URLSearchParams} params - Parámetros ya construidos por el llamador.
 * @param {AbortSignal} [signal]
 * @returns {Promise<Array<{anio:number,importeTotal:number}>>}
 */
async function cargarSerieProyectosAnio(params, signal) {
    const res = await fetch(apiUrl(`/awards/stats/proyectos-anio?${params.toString()}`), signal ? { signal } : {});
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
    if (!chartImporteAnio) {
        chartImporteAnio = echarts.init(document.getElementById('chartImporteAnio'));
    }
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
    // Dispose all existing pie instances
    chartComparativaPies.forEach(c => c.dispose());
    chartComparativaPies.clear();

    const piesContainer = document.getElementById('comparativaPiesContainer');
    piesContainer.innerHTML = '';

    if (!departamentosComparativa.length) {
        piesContainer.innerHTML = '<p class="text-slate-400 text-sm text-center col-span-full py-8">Afegeix departaments per comparar</p>';
        return;
    }

    const { desde, hasta, modoAnio } = obtenerFiltrosActuales();

    await Promise.all(departamentosComparativa.map(async dep => {
        // Create wrapper card
        const wrapper = document.createElement('div');
        wrapper.className = 'bg-white border border-slate-100 rounded-xl shadow-sm p-3 flex flex-col items-center';
        const safeId = `comparePie-${dep.uuid.replace(/[^a-zA-Z0-9]/g, '_')}`;
        wrapper.innerHTML = `
            <div class="text-xs font-bold text-slate-600 text-center mb-1 w-full truncate" title="${dep.nombre}">${dep.nombre}</div>
            <div id="${safeId}" class="w-full" style="height:220px;"></div>`;
        piesContainer.appendChild(wrapper);

        const pieDiv = document.getElementById(safeId);
        const chart = echarts.init(pieDiv);
        chartComparativaPies.set(dep.uuid, chart);

        // Fetch persona-resumen for this dept
        const params = new URLSearchParams({ desde: String(desde), hasta: String(hasta), modoAnio, deptUuid: dep.uuid });
        let filas = [];
        try {
            const res = await fetch(apiUrl(`/awards/stats/persona-resumen?${params.toString()}`));
            if (res.ok) filas = await res.json();
        } catch (_) { /* network error: show empty */ }

        // Build agrupadoPorPersona map
        const porPersona = new Map();
        filas.forEach(f => {
            const uuid = f['PersonaUuid'] ?? f.personaUuid ?? '';
            const nombre = f['Persona'] ?? f.persona ?? 'N/D';
            const key = uuid || nombre;
            if (!porPersona.has(key)) porPersona.set(key, { importePonderado: 0 });
            porPersona.get(key).importePonderado += Number(f['Importe_Ponderado (€)'] ?? 0);
        });

        const grups = calcularQuartilesGrups(porPersona);
        const grupsPoblats = grups.filter(g => g.items.length > 0);

        if (!grupsPoblats.length) {
            chart.setOption({ title: { text: 'Sense dades', left: 'center', top: 'center', textStyle: { color: '#aaa', fontSize: 12 } } });
            return;
        }

        chart.setOption({
            tooltip: {
                trigger: 'item',
                formatter: p => `<b>${p.name}</b><br>Persones: <b>${p.value}</b><br>${p.percent.toFixed(1)}%`
            },
            legend: {
                orient: 'horizontal',
                bottom: 0,
                left: 'center',
                textStyle: { fontSize: 9 },
                itemWidth: 8, itemHeight: 8,
                formatter: name => name.split('·')[0].trim()
            },
            series: [{
                name: 'Quartil',
                type: 'pie',
                radius: ['30%', '60%'],
                center: ['50%', '44%'],
                avoidLabelOverlap: true,
                label: {
                    show: true,
                    formatter: p => p.value > 0 ? `${p.value}` : '',
                    fontSize: 12,
                    fontWeight: 700
                },
                labelLine: { show: true },
                data: grupsPoblats.map(g => ({
                    name: g.label,
                    value: g.items.length,
                    itemStyle: { color: g.color }
                }))
            }]
        });
    }));
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

async function carregarAmbit() {
    const ambit = document.getElementById('compareAmbitSelect').value;
    if (!ambit) return;
    try {
        const res = await fetch(apiUrl(`/persons/departamentos-by-ambit?ambit=${encodeURIComponent(ambit)}`));
        if (!res.ok) return;
        const deptos = await res.json();
        // Reemplaça la selecció actual pels departaments de l'àmbit
        departamentosComparativa = deptos.map(d => ({ uuid: d.uuid, nombre: d.nombre }));
        renderDepartamentosComparativaSeleccionados();
        await actualizarGraficoComparativaDepartamentos();
    } catch (_) { /* network error */ }
}

async function carregarAmbits() {
    const select = document.getElementById('compareAmbitSelect');
    try {
        const res = await fetch(apiUrl('/persons/ambits'));
        if (!res.ok) return;
        const ambits = await res.json();
        ambits.forEach(a => {
            const opt = document.createElement('option');
            opt.value = a;
            opt.textContent = a;
            select.appendChild(opt);
        });
    } catch (_) { /* network error */ }
}

function configurarModeComparativa() {
    const btnDepto = document.getElementById('btnModeDepto');
    const btnAmbit = document.getElementById('btnModeAmbit');
    const panelDepto = document.getElementById('compareModeDepartament');
    const panelAmbit = document.getElementById('compareModeAmbit');

    btnDepto.addEventListener('click', () => {
        btnDepto.className = 'px-3 py-1 rounded-full text-xs font-semibold bg-indigo-600 text-white';
        btnAmbit.className = 'px-3 py-1 rounded-full text-xs font-semibold bg-slate-100 text-slate-600 hover:bg-slate-200';
        panelDepto.classList.remove('hidden');
        panelAmbit.classList.add('hidden');
    });

    btnAmbit.addEventListener('click', () => {
        btnAmbit.className = 'px-3 py-1 rounded-full text-xs font-semibold bg-indigo-600 text-white';
        btnDepto.className = 'px-3 py-1 rounded-full text-xs font-semibold bg-slate-100 text-slate-600 hover:bg-slate-200';
        panelAmbit.classList.remove('hidden');
        panelDepto.classList.add('hidden');
    });

    document.getElementById('btnCarregarAmbit').addEventListener('click', carregarAmbit);
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
    if (currentLoadController) currentLoadController.abort();
    const controller = new AbortController();
    currentLoadController = controller;
    const requestSeq = ++currentLoadSeq;

    try {
        mostrarEstadoCargando();
        let data = [];
        let serieProyectos = [];
        // Leer el valor actual del select
        const modoAwardsDept = document.getElementById('selectAwardsDept')?.value || 'miembros';
            if (modoAwardsDept === 'gestionados' && deptUuid) {
                // Awards gestionados por el departamento
                const params = new URLSearchParams({ gestionadosPorDept: 'managed' });
                appendFilterParams(params, { deptUuid });
                if (persona) params.set('persona', persona);
                appendFilterParams(params, { categoria, tipus });
                const ckey = _cacheKey(params, modoAwardsDept);
                if (_requestCache.has(ckey)) {
                    data = _requestCache.get(ckey).data;
                } else {
                    const res = await fetch(apiUrl(`/awards/stats/persona-resumen?${params.toString()}`), { signal: controller.signal });
                    if (!res.ok) throw new Error(`HTTP ${res.status}`);
                    data = await res.json();
                    _requestCache.set(ckey, { data });
                }
            } else {
            // Awards de miembros (lógica original)
            const params = new URLSearchParams({
                desde: String(desde),
                hasta: String(hasta),
                modoAnio
            });
            if (persona) params.set('persona', persona);
            appendFilterParams(params, { deptUuid, categoria, tipus });
            if (modoAwardsDept === 'gestionados') params.set('gestionadosPorDept', 'managed');
            const ckey = _cacheKey(params, modoAwardsDept);
            if (_requestCache.has(ckey)) {
                const cached = _requestCache.get(ckey);
                data = cached.data;
                serieProyectos = cached.serieProyectos;
            } else {
                const [resumenRes, serieProyectos_] = await Promise.all([
                    fetch(apiUrl(`/awards/stats/persona-resumen?${params.toString()}`), { signal: controller.signal }),
                    cargarSerieProyectosAnio(params, controller.signal)
                ]);
                if (!resumenRes.ok) throw new Error(`HTTP ${resumenRes.status}`);
                data = await resumenRes.json();
                serieProyectos = serieProyectos_;
                _requestCache.set(ckey, { data, serieProyectos });
            }
        }
        if (requestSeq !== currentLoadSeq) return;
        filasResumenActual = data;
        personaTopSeleccionada = null;
        renderizarChipPersona();
        const seccionEvol = document.getElementById('seccionEvolucionPersonaDept');
        if (seccionEvol) {
            seccionEvol.classList.add('hidden');
            if (window.tablaEvolucionPersonaDept) window.tablaEvolucionPersonaDept.clearData();
        }
        renderTabla(data, modoAnio, desde, hasta);
        renderTablaCrecimiento(data, desde, hasta);
        renderGraficos(data);
        renderGraficoImportePorProyecto(serieProyectos);
        estado.className = 'p-4 text-sm text-emerald-600 font-semibold';
    } catch (error) {
        if (error && error.name === 'AbortError') return;
        if (requestSeq !== currentLoadSeq) return;
        estado.textContent = `Error en carregar dades: ${error.message}`;
        estado.className = 'p-4 text-sm text-red-600';
        renderTabla([], modoAnio, desde, hasta);
        renderTablaCrecimiento([], desde, hasta);
        renderTablaEvolucionPersonaDept([], desde, hasta);
        renderGraficos([]);
        renderGraficoImportePorProyecto([]);
    } finally {
        if (requestSeq === currentLoadSeq) ocultarOverlayCargando();
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
    searchInput.placeholder = 'Cercar departament...';
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
        if (!_inicializando) programarRefresco();
    });
}


// El select oculto ya no dispara el refresco, lo hace el sistema de chips
//document.getElementById('departamentoSelect').addEventListener('change', programarRefresco);
document.getElementById('personaInput').addEventListener('input', programarRefresco);
document.getElementById('modoAnioSelect').addEventListener('change', programarRefresco);
// El select oculto ya no dispara el refresco, lo hace el sistema de chips
document.getElementById('btnAddDepartamentoCompare').addEventListener('click', agregarDepartamentoComparativa);
configurarModeComparativa();

document.addEventListener('fullscreenchange', () => {
    if (chartImporteAnio) chartImporteAnio.resize();
    if (chartProyectosAnio) chartProyectosAnio.resize();
    chartComparativaPies.forEach(c => c.resize());
});

window.addEventListener('resize', () => {
    if (chartImporteAnio) chartImporteAnio.resize();
    if (chartProyectosAnio) chartProyectosAnio.resize();
    chartComparativaPies.forEach(c => c.resize());
});

async function init() {
    inicializarTablas();
    configurarSlider();
    await cargarDepartamentos();
    await carregarAmbits();
    await cargarCategorias();
    await cargarTipus();
    await actualizarGraficoComparativaDepartamentos();
    _inicializando = false;  // A partir d'aquí, els chips i el slider disparen refresc
    cargarDatos();
}

// Tabs logic: keep filters always visible, switch content, and show dept tab only if a department is selected
document.addEventListener('DOMContentLoaded', function() {
    const tabResumenBtn = document.getElementById('tabResumenBtn');
    const tabDeptBtn = document.getElementById('tabDeptBtn');
    const tabComparativaBtn = document.getElementById('tabComparativaBtn');
    const tabResumen = document.getElementById('tabResumen');
    const tabDept = document.getElementById('tabDept');
    const tabComparativa = document.getElementById('tabComparativa');
    const tabNav = tabDeptBtn.parentElement;

    const ALL_TABS = [
        { btn: tabResumenBtn,    panel: tabResumen },
        { btn: tabDeptBtn,       panel: tabDept },
        { btn: tabComparativaBtn, panel: tabComparativa }
    ];

    function activarTab(activeBtn) {
        ALL_TABS.forEach(({ btn, panel }) => {
            const isActive = btn === activeBtn;
            panel.classList.toggle('hidden', !isActive);
            btn.classList.toggle('text-indigo-700', isActive);
            btn.classList.toggle('border-indigo-600', isActive);
            btn.classList.toggle('text-slate-500', !isActive);
            btn.classList.toggle('border-transparent', !isActive);
        });

        // Mover seccionAwardsPersona al tab activo
        const seccionAwards = document.getElementById('seccionAwardsPersona');
        if (seccionAwards) {
            if (activeBtn === tabDeptBtn) {
                const placeholder = document.getElementById('awardsPlaceholderDept');
                if (placeholder) placeholder.appendChild(seccionAwards);
            } else if (activeBtn === tabResumenBtn) {
                // Devolver a tabResumen (al final, antes del cierre del div)
                tabResumen.appendChild(seccionAwards);
            }
            // En tabComparativa no se mueve: permanece donde estaba
        }

        if (activeBtn === tabDeptBtn) {
            setTimeout(resizeDeptCharts, 100);
        }
        if (activeBtn === tabResumenBtn) {
            setTimeout(() => { if (chartProyectosAnio && typeof chartProyectosAnio.resize === 'function') chartProyectosAnio.resize(); }, 100);
        }
    }

    function resizeDeptCharts() {
        // Redimensiona todos los gráficos ECharts de la pestaña depto
        [
            chartImporteAnio, chartProyectosAnio,
            chartLiderazgo, chartPareto,
            chartCuartilesPie, chartCuartilesIngresos,
            chartEvolucionPersonaDept
        ].forEach(c => { if (c && typeof c.resize === 'function') c.resize(); });
    }

    function updateDeptTabVisibility() {
        // departamentosSeleccionados es global en persona-resumen.js
        const hasDept = departamentosSeleccionados && departamentosSeleccionados.length > 0;
        if (hasDept) {
            tabDeptBtn.classList.remove('hidden');
        } else {
            tabDeptBtn.classList.add('hidden');
            // Si la pestaña activa era Dept o Comparativa, volver a Resum
            if (!tabResumen.classList.contains('hidden') === false) {
                activarTab(tabResumenBtn);
            }
            if (!tabDept.classList.contains('hidden') || !tabComparativa.classList.contains('hidden')) {
                activarTab(tabResumenBtn);
            }
        }
    }

    tabResumenBtn.addEventListener('click', () => activarTab(tabResumenBtn));
    tabDeptBtn.addEventListener('click', () => activarTab(tabDeptBtn));
    tabComparativaBtn.addEventListener('click', () => activarTab(tabComparativaBtn));

    // Hook into chips rendering to update tab visibility
    const origRenderizarChipsDepartamentos = window.renderizarChipsDepartamentos;
    window.renderizarChipsDepartamentos = function() {
        if (origRenderizarChipsDepartamentos) origRenderizarChipsDepartamentos.apply(this, arguments);
        updateDeptTabVisibility();
    };
    // Llamar al cargar
    updateDeptTabVisibility();
});

function extraerDatosDeGraficos() {
    let contextoGráficos = "";

    // 1. Extraer datos del gráfico de Evolución (Persona vs Depto)
    if (chartEvolucionPersonaDept) {
        const options = chartEvolucionPersonaDept.getOption();
        const años = options.xAxis[0].data;
        const valoresDepto = options.series.find(s => s.name.includes('Dept'))?.data || [];
        contextoGráficos += `\n- Tendencia Temporal: En los años ${años.join(', ')}, la media del departamento ha sido [${valoresDepto.join(', ')}] €.`;
    }

    // 2. Extraer datos del gráfico de Cuartiles (Distribución)
    if (chartCuartilesPie) {
        const options = chartCuartilesPie.getOption();
        const distribucion = options.series[0].data.map(d => `${d.name}: ${d.value} personas`).join(', ');
        contextoGráficos += `\n- Distribución Actual: ${distribucion}.`;
    }

    return contextoGráficos;
}

function extraerDatosPareto() {
    // Buscamos el gráfico de Pareto (asumiendo que se llama chartPareto)
    if (!chartPareto) return "Datos de Pareto no disponibles.";

    const options = chartPareto.getOption();
    const nombres = options.xAxis[0].data; // Investigadores ordenados de mayor a menor
    const acumulado = options.series.find(s => s.type === 'line').data; // La curva de Pareto
    
    // Encontramos el "Punto 80": ¿Cuánta gente suma el 80% de los ingresos?
    const indice80 = acumulado.findIndex(val => val >= 80);
    const numPersonas80 = indice80 + 1;
    const porcentajePersonas = ((numPersonas80 / nombres.length) * 100).toFixed(1);

    return `Análisis de Pareto: El 80% de los ingresos del departamento es generado por el ${porcentajePersonas}% de los investigadores (${numPersonas80} personas de un total de ${nombres.length}).`;
}


// ---------------------------------------------------------------------------
// Construeix el prompt per a l'analisi IA del departament
// ---------------------------------------------------------------------------
function buildPromptDepartament(datosRaw, tablaInvestigadores,nombreDepartamento) {
    const datosDeGraficos = extraerDatosDeGraficos();
    const datosPareto = extraerDatosPareto();
    return `
Realitza una analisi academica exhaustiva de l'estructura i rendiment d'un departament universitari a partir de les seguents dades quantitatives i qualitatives.
L'analisi ha d'adoptar un enfocament propi d'avaluacio institucional en educacio superior, integrant criteris de productivitat cientifica, sostenibilitat organitzativa i competitivitat en captacio de recursos.

Dades del departament:
- Nom del departament: ${nombreDepartamento}
- Mida total de l'equip investigador: ${datosRaw.size}
- Analisi de Pareto: ${datosPareto}
- Distribucio de rendiment: ${datosDeGraficos}
- Llistat d'investigadors amb edat i categoria de rendiment:
  ${tablaInvestigadores}
________________________________________
Instruccions d'analisi:
1. Estructura de productivitat
   - Avalua el grau de concentracio de la produccio cientifica i captacio de recursos.
   - Determina si el model respon a una distribucio eficient o presenta riscos estructurals.
2. Analisi de capital huma
   - Examina la distribucio per edats i la seva relacio amb el rendiment.
   - Identifica possibles problemes de relleu generacional, acumulacio de seniority o manca de desenvolupament de talent jove.
3. Avaluacio de la sostenibilitat
   - Analitza la viabilitat del model actual a mitja i llarg termini (3-10 anys).
   - Considera riscos derivats de dependencia, envelliment o baixa productivitat estructural.
4. Competitivitat academica
   - Valora la capacitat del departament per competir en convocatories nacionals i internacionals.
   - Avalua l'equilibri entre excellencia (top performers) i base productiva.
5. Diagnostic organitzatiu
   - Identifica ineficiencies internes (desigualtat de carregues, baixa contribucio, falta d'incentius).
   - Analitza si existeix una estructura de "doble velocitat" o segmentacio interna.
6. Projeccio evolutiva
   - Descriu escenaris probables (optimista, tendencial, negatiu).
   - Explica com evolucionara la productivitat mitjana del departament.
7. Recomanacions estrategiques
   - Propo mesures basades en evidencia per a:
     millorar la productivitat del grup de baix rendiment,
     escalar el grup mitja,
     assegurar la successio del lideratge cientific,
     optimitzar l'assignacio de recursos i la governanca.
________________________________________
Format de resposta:
1. Genera primer un apartat anomenat "### 🧠 Raonament i Càlculs" on analitzis en veu alta i pas a pas les dades (ex: si el 80% dels fons els porta el 10% del personal, què implica això estratègicament?).
2. Després d'aquest raonament, genera l'informe final amb:
   - Estil acadèmic (tipus informe o avaluació ANECA/ERC).
   - Argumentació basada en les dades proporcionades.
   - Ús de conceptes com: concentració de productivitat, massa crítica, eficiència organitzativa, pipeline de talent, sostenibilitat científica.
   - Conclusió sintètica amb diagnòstic global del departament.
    `;
}

// ---------------------------------------------------------------------------
// Mostra el prompt en una finestra flotant (modal)
// ---------------------------------------------------------------------------
function mostrarPromptModal() {
    const datosRaw = window.agrupadoPorPersona || window.dataMap;
    if (!datosRaw) {
        alert("Encara no hi ha dades carregades. Selecciona un departament primer.");
        return;
    }

    const grupos = calcularQuartilesGrups(datosRaw);
    let tablaInvestigadores = 'Investigadors del departament (nom, edat, grup):\n';
    for (const grupo of grupos) {
        for (const persona of grupo.items) {
            const nombre = persona.nom || persona.nombre || persona.persona || 'N/D';
            let edad = persona.edad ?? persona.Edad ?? null;
            if (typeof edad !== 'number' || isNaN(edad)) edad = 'N/D';
            tablaInvestigadores += `- ${nombre} (edat: ${edad}) -> ${grupo.label}\n`;
        }
    }

    let nombreDepartamento = "Departament desconegut";
    if (window.departamentosSeleccionados && window.departamentosSeleccionados.length > 0) {
        const uuidDept = window.departamentosSeleccionados[0];
        const deptCatalogo = window.departamentosCatalogo.find(d => d.uuid === uuidDept);
        if (deptCatalogo) {
            nombreDepartamento = deptCatalogo.nombre;
        }
    }

    const prompt = buildPromptDepartament(datosRaw, tablaInvestigadores, nombreDepartamento);

    let modal = document.getElementById('promptModal');
    if (!modal) {
        modal = document.createElement('div');
        modal.id = 'promptModal';
        modal.style.cssText = [
            'position:fixed', 'inset:0', 'z-index:9999',
            'background:rgba(15,23,42,0.55)', 'backdrop-filter:blur(2px)',
            'display:flex', 'align-items:center', 'justify-content:center',
            'padding:1rem'
        ].join(';');
        modal.innerHTML = `
            <div style="background:#fff;border-radius:1rem;box-shadow:0 8px 40px rgba(0,0,0,0.18);width:100%;max-width:760px;max-height:85vh;display:flex;flex-direction:column;overflow:hidden;">
                <div style="padding:0.85rem 1.1rem;border-bottom:1px solid #e2e8f0;display:flex;align-items:center;justify-content:space-between;background:#f8fafc;">
                    <span style="font-size:0.8rem;font-weight:700;color:#4338ca;display:flex;align-items:center;gap:0.4rem;">
                        <i class="fa-regular fa-message"></i> Prompt enviat al model IA
                    </span>
                    <div style="display:flex;gap:0.5rem;">
                        <button id="promptModalCopyBtn" title="Copiar al portapapers"
                            style="font-size:0.72rem;font-weight:600;padding:0.3rem 0.8rem;border:1px solid #c7d2fe;border-radius:0.5rem;background:#eef2ff;color:#4338ca;cursor:pointer;display:flex;align-items:center;gap:0.3rem;">
                            <i class="fa-regular fa-copy"></i> Copiar
                        </button>
                        <button id="promptModalCloseBtn" title="Tancar"
                            style="font-size:0.72rem;font-weight:600;padding:0.3rem 0.8rem;border:1px solid #e2e8f0;border-radius:0.5rem;background:#f1f5f9;color:#64748b;cursor:pointer;display:flex;align-items:center;gap:0.3rem;">
                            <i class="fa-solid fa-xmark"></i> Tancar
                        </button>
                    </div>
                </div>
                <div style="flex:1;overflow-y:auto;padding:1rem;">
                    <pre id="promptModalContent" style="font-family:'Fira Mono','Consolas',monospace;font-size:0.72rem;line-height:1.6;white-space:pre-wrap;word-break:break-word;color:#334155;margin:0;background:#f8fafc;border:1px solid #e2e8f0;border-radius:0.5rem;padding:0.9rem;"></pre>
                </div>
            </div>
        `;
        document.body.appendChild(modal);

        modal.addEventListener('click', (e) => {
            if (e.target === modal) modal.style.display = 'none';
        });
        modal.querySelector('#promptModalCloseBtn').addEventListener('click', () => {
            modal.style.display = 'none';
        });
        modal.querySelector('#promptModalCopyBtn').addEventListener('click', async () => {
            const text = document.getElementById('promptModalContent').textContent;
            try {
                await navigator.clipboard.writeText(text);
                const btn = modal.querySelector('#promptModalCopyBtn');
                btn.innerHTML = '<i class="fa-solid fa-check"></i> Copiat!';
                btn.style.background = '#dcfce7';
                btn.style.borderColor = '#86efac';
                btn.style.color = '#15803d';
                setTimeout(() => {
                    btn.innerHTML = '<i class="fa-regular fa-copy"></i> Copiar';
                    btn.style.background = '#eef2ff';
                    btn.style.borderColor = '#c7d2fe';
                    btn.style.color = '#4338ca';
                }, 2000);
            } catch(err) {
                alert("No s'ha pogut copiar al portapapers.");
            }
        });
    }

    document.getElementById('promptModalContent').textContent = prompt;
    modal.style.display = 'flex';
}


async function analizarDepartamentoConVLLM() {
    const aiDeptOutput = document.getElementById('ai-dept-output');
    
    // Variables para el cronómetro
    let tiempoInicio = performance.now();
    let intervaloTimer;

    if (aiDeptOutput) {
        aiDeptOutput.textContent = "Generant informe automàtic... (0.0s)";
        
        // Actualizar el UI cada 100ms
        intervaloTimer = setInterval(() => {
            const tiempoActual = performance.now();
            const segundosTranscurridos = ((tiempoActual - tiempoInicio) / 1000).toFixed(1);
            aiDeptOutput.textContent = `Generant informe automàtic... (${segundosTranscurridos}s)`;
        }, 100);
    }

    const VLLM_CONFIG = {
        model: "openai/gpt-oss-20b",
        apiBase: "https://ymir.uab.cat:8014/v1/chat/completions",
        apiKey: "EMPTY"
    };

    const datosRaw = window.agrupadoPorPersona || window.dataMap;
    if (!datosRaw) {
        clearInterval(intervaloTimer); // Detener timer si no hay datos
        return;
    }

    const grupos = calcularQuartilesGrups(datosRaw);
    let tablaInvestigadores = 'Investigadores del departamento (nombre, edad, grupo):\n';
    for (const grupo of grupos) {
        for (const persona of grupo.items) {
            const nombre = persona.nom || persona.nombre || persona.persona || 'N/D';
            let edad = persona.edad ?? persona.Edad ?? null;
            if (typeof edad !== 'number' || isNaN(edad)) edad = 'N/D';
            tablaInvestigadores += `- ${nombre} (edad: ${edad}) → ${grupo.label}\n`;
        }
    }

    const prompt = buildPromptDepartament(datosRaw, tablaInvestigadores);

    try {
        const response = await fetch(VLLM_CONFIG.apiBase, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${VLLM_CONFIG.apiKey}`
            },
            body: JSON.stringify({
                model: VLLM_CONFIG.model,
                messages: [
                    { role: "system", content: "Ets un analista de dades expert en investigació universitària. Pensa sempre pas a pas i estructura el teu raonament logicament abans d'emetre una conclusió final." },
                    { role: "user", content: prompt }
                ],
                temperature: 0.3, 
                max_tokens: 15000
            })
        });

        // ¡Petición terminada! Detenemos el cronómetro
        clearInterval(intervaloTimer);

        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }

        const data = await response.json();
        const result = data.choices[0].message.content;
        
        // Calculamos el tiempo total exacto
        const tiempoTotal = ((performance.now() - tiempoInicio) / 1000).toFixed(2);

        // Agregamos un pequeño pie de página al resultado indicando el tiempo
        const resultadoConTiempo = result + `\n\n---\n*⏱️ Informe generat en ${tiempoTotal} segons.*`;

        const outputElement = document.getElementById('ai-dept-output');
        if (outputElement) {
            const zeroMd = document.createElement('zero-md');

            const template = document.createElement('template');
            template.setAttribute('data-merge', 'append');
            template.innerHTML = `
                <style>
                    :host { font-size: 13px; line-height: 1.4; }
                    h1, h2, h3, h4, h5, h6 {
                        font-size: 1.05em !important;
                        margin: 0.5em 0 0.2em 0 !important;
                    }
                    p { 
                        margin: 0 0 0.5em 0 !important; 
                    }
                    ul, ol { 
                        margin: 0 0 0.5em 0 !important; 
                        padding-left: 1.4em !important; 
                    }
                    li { 
                        margin: 0.2em 0 !important; /* Un pelín más de aire entre los elementos de la lista */
                    }
                    hr { margin: 0.4em 0 !important; }
                    li p {
                        margin-top: 0 !important;
                        margin-bottom: 0.1em !important; /* Casi pegado a la sub-lista */
                    }

                    /* 2. Asegurar que la sub-lista no añada margen por arriba */
                    li ul, li ol {
                        margin-top: 0 !important;
                        margin-bottom: 0.4em !important;
                    }

                    /* 3. Juntar más los elementos de la sub-lista */
                    li li {
                        margin: 0.1em 0 !important;
                    }
                </style>
            `;
            zeroMd.appendChild(template);

            const script = document.createElement('script');
            script.type = 'text/markdown';
            script.text = resultadoConTiempo; // Usamos el resultado con el tiempo inyectado
            zeroMd.appendChild(script);

            outputElement.innerHTML = '';
            outputElement.appendChild(zeroMd);
        }

        return result;

    } catch (error) {
        clearInterval(intervaloTimer); // Asegurarnos de detenerlo en caso de error
        console.error("Error llamando a vLLM:", error);
        if (aiDeptOutput) {
            aiDeptOutput.textContent = "Error al generar l'anàlisi de la IA.";
        }
        return "Error al generar l'anàlisi de la IA.";
    }
}

init();