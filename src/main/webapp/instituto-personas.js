// JS para la página "Personas por Instituto"

let tabla = null;
let pieChart = null;

function formatearFecha(valor) {
    if (!valor) return '-';
    try {
        const d = new Date(valor);
        if (isNaN(d.getTime())) return valor;
        return d.toLocaleDateString();
    } catch (e) {
        return valor;
    }
}

async function cargarDepartamentos() {
    const sel = document.getElementById('departamentoSelect');
    sel.innerHTML = '<option value="">Cargando...</option>';
    try {
        const res = await fetch('/api/persons/institutos');
        const data = await res.json();
        sel.innerHTML = '<option value="">-- Selecciona un instituto --</option>';
        data.forEach(d => {
            const opt = document.createElement('option');
            opt.value = d.uuid;
            opt.textContent = d.nombre;
            sel.appendChild(opt);
        });
    } catch (e) {
        sel.innerHTML = '<option value="">Error cargando</option>';
    }
}

function inicializarSlider() {
    const slider = document.getElementById('sliderAnios');
    noUiSlider.create(slider, {
        start: [2018, 2025],
        connect: true,
        step: 1,
        range: { 'min': 2000, 'max': 2026 },
        format: {
            to: v => Math.round(v),
            from: v => Number(v)
        }
    });

    const valorRango = document.getElementById('valorRango');
    slider.noUiSlider.on('update', (vals) => {
        valorRango.textContent = `${vals[0]} — ${vals[1]}`;
    });
}

async function cargarReporte() {
    const sel = document.getElementById('departamentoSelect');
    const orgUuid = sel.value;
    if (!orgUuid) {
        alert('Selecciona un instituto.');
        return;
    }
    const modo = document.getElementById('modoReporte').value || 'report';
    let url;
    if (modo === 'latest') {
        const [desde, hasta] = document.getElementById('sliderAnios').noUiSlider.get();
        const startDate = `${desde}-01-01`;
        const endDate = `${hasta}-12-31`;
        url = `/api/persons/associations/latest?orgUuid=${encodeURIComponent(orgUuid)}&startDate=${encodeURIComponent(startDate)}&endDate=${encodeURIComponent(endDate)}`;
    } else {
        const [desde, hasta] = document.getElementById('sliderAnios').noUiSlider.get();
        // convertir a fechas ISO simples (usamos primer/último día)
        const startDate = `${desde}-01-01`;
        const endDate = `${hasta}-12-31`;
        url = `/api/persons/associations/report?orgUuid=${encodeURIComponent(orgUuid)}&startDate=${encodeURIComponent(startDate)}&endDate=${encodeURIComponent(endDate)}`;
    }

    const btn = document.getElementById('btnRefrescar');
    btn.disabled = true;
    btn.textContent = 'Cargando...';

    try {
        const res = await fetch(url);
        const data = await res.json();
        renderTabla(data);
    } catch (e) {
        console.error(e);
        alert('Error cargando datos.');
    } finally {
        btn.disabled = false;
        btn.textContent = 'Refrescar';
    }
}

function renderTabla(rows) {
    const tableEl = document.getElementById('tablaPersonas');
    if (!tabla) {
        tabla = new Tabulator(tableEl, {
            layout: 'fitColumns',
            height: '60vh',
            placeholder: 'No hay datos para los filtros.',
            columns: [
                { title: 'Nombre', field: 'nombre', headerFilter: 'input' },
                { title: 'Empleo', field: 'empleo', headerFilter: 'input' },
                { title: 'Inicio', field: 'inicio_asociacion_IBB', formatter: (cell) => formatearFecha(cell.getValue()) },
                { title: 'Fin', field: 'fin_asociacion_IBB', formatter: (cell) => formatearFecha(cell.getValue()) }
            ]
        });
    }
    tabla.setData(rows || []);
    renderKpiAndPie(rows || []);
}

function renderKpiAndPie(rows) {
    // KPI: total personas
    const total = (rows || []).length;
    const kpiEl = document.getElementById('kpiTotalValue');
    if (kpiEl) kpiEl.textContent = total.toString();

    // Agrupar por empleo
    const counts = {};
    (rows || []).forEach(r => {
        const key = r.empleo || 'Desconegut';
        counts[key] = (counts[key] || 0) + 1;
    });

    const pieData = Object.keys(counts).map(k => ({ name: k, value: counts[k] }));

    // Inicializar gráfico si hace falta
    const pieDom = document.getElementById('pieChart');
    if (!pieDom) return;
    if (!pieChart) {
        pieChart = echarts.init(pieDom);
    }

    const option = {
        tooltip: { trigger: 'item' },
        legend: { orient: 'horizontal', bottom: 0 },
        series: [
            {
                name: 'Empleo',
                type: 'pie',
                radius: ['40%', '70%'],
                avoidLabelOverlap: false,
                itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 1 },
                label: { show: false, position: 'center' },
                emphasis: { label: { show: true, fontSize: 14, fontWeight: 'bold' } },
                radius: '65%',
                data: pieData
            }
        ]
    };

    pieChart.setOption(option);
}

window.addEventListener('DOMContentLoaded', async () => {
    await cargarDepartamentos();
    inicializarSlider();

    document.getElementById('btnRefrescar').addEventListener('click', cargarReporte);
});