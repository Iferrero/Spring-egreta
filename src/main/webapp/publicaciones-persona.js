let refrescoTimer = null;
let tablaPublicaciones = null;
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

    programarRefresco();
}

function toggleDepartamentoSeleccionado(uuid) {
    if (departamentosSeleccionados.includes(uuid)) {
        quitarDepartamentoSeleccionado(uuid);
    } else {
        // Mantener un solo departamento, igual que en persona-resumen.
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
        programarRefresco();
    });
}

function inicializarTabla() {
    tablaPublicaciones = new Tabulator('#tablaPublicacionesPersona', {
        layout: 'fitDataStretch',
        maxHeight: '65vh',
        reactiveData: false,
        placeholder: 'No hi ha resultats per als filtres seleccionats.',
        columns: [
            { title: 'Persona', field: 'persona', sorter: 'string', headerFilter: 'input', headerFilterPlaceholder: 'Buscar persona...' }
        ]
    });
}

function construirTablaPivot(data) {
    const rowsRaw = Array.isArray(data) ? data : [];
    const tipos = Array.from(new Set(
        rowsRaw
            .map(item => String(item.tipoPublicacion || item.tipo_publicacion || 'Sense tipus').trim() || 'Sense tipus')
    )).sort((a, b) => a.localeCompare(b, 'ca', { sensitivity: 'base' }));

    const fieldByTipo = new Map();
    tipos.forEach((tipo, index) => {
        fieldByTipo.set(tipo, `tipo_${index}`);
    });

    const porPersona = new Map();
    rowsRaw.forEach(item => {
        const personaUuid = String(item.personaUuid || item.person_uuid || '').trim();
        const personaNombre = String(item.persona || item.nombre || '').trim();
        const persona = personaNombre || personaUuid || '-';
        const tipo = String(item.tipoPublicacion || item.tipo_publicacion || 'Sense tipus').trim() || 'Sense tipus';
        const total = Number(item.totalPublicaciones ?? item.num_publicaciones ?? 0);
        const key = personaUuid || `nom:${persona.toLowerCase()}`;

        if (!porPersona.has(key)) {
            const base = { persona, personaUuid, __total: 0 };
            tipos.forEach(t => {
                base[fieldByTipo.get(t)] = 0;
            });
            porPersona.set(key, base);
        }

        const row = porPersona.get(key);
        const field = fieldByTipo.get(tipo);
        row[field] = (Number(row[field] || 0) + total);
        row.__total = (Number(row.__total || 0) + total);
    });

    const columns = [
        { title: 'Persona', field: 'persona', sorter: 'string', headerFilter: 'input', headerFilterPlaceholder: 'Buscar persona...' },
        ...tipos.map(tipo => ({
            title: tipo,
            field: fieldByTipo.get(tipo),
            sorter: 'number',
            hozAlign: 'right',
            bottomCalc: 'sum'
        })),
        {
            title: 'Total',
            field: '__total',
            sorter: 'number',
            hozAlign: 'right',
            bottomCalc: 'sum'
        }
    ];

    const rows = Array.from(porPersona.values())
        .sort((a, b) => String(a.persona || '').localeCompare(String(b.persona || ''), 'ca', { sensitivity: 'base' }));

    return { columns, rows };
}

async function cargarDatos() {
    const estado = document.getElementById('estado');
    if (!tablaPublicaciones) return;

    const { desde, hasta, deptUuid } = obtenerFiltrosActuales();
    const params = new URLSearchParams({
        desde: String(desde),
        hasta: String(hasta)
    });

    deptUuid.forEach(dep => params.append('deptUuid', dep));

    mostrarOverlayCargando();
    estado.textContent = 'Recarregant dades...';
    estado.className = 'p-4 text-sm text-indigo-600 font-semibold';

    try {
        const response = await apiFetch(`/pure/stats/persona-resumen?${params.toString()}`);
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }

        const data = await response.json();
        const { columns, rows } = construirTablaPivot(data);
        tablaPublicaciones.setColumns(columns);
        tablaPublicaciones.setData(rows);

        const totalPersonas = rows.length;
        const totalPublicaciones = (Array.isArray(data) ? data : [])
            .reduce((acc, item) => acc + Number(item.totalPublicaciones ?? item.num_publicaciones ?? 0), 0);
        estado.textContent = `Resultats: ${totalPersonas} persones · ${totalPublicaciones} publicacions`;
        estado.className = 'p-4 text-sm text-emerald-600 font-semibold';
    } catch (error) {
        tablaPublicaciones.setData([]);
        estado.textContent = `Error en carregar dades: ${error.message}`;
        estado.className = 'p-4 text-sm text-red-600';
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

async function init() {
    inicializarTabla();
    const min = 2015;
    const max = new Date().getFullYear();
    configurarSlider(min, max, 2021, 2025);
    await cargarDepartamentos();
    await cargarDatos();
}

init();
