let refrescoTimer = null;

function greenColorScaleGenerator(values) {
    const numericValues = values
        .map(v => Number(v))
        .filter(v => Number.isFinite(v));

    if (!numericValues.length) {
        return () => "hsl(142, 45%, 95%)";
    }

    const min = Math.min(...numericValues);
    const max = Math.max(...numericValues);

    return function (x) {
        const value = Number(x);
        if (!Number.isFinite(value) || max === min) {
            return "hsl(142, 45%, 92%)";
        }

        const ratio = (value - min) / (max - min);
        const lightness = 94 - (ratio * 48);
        return `hsl(142, 55%, ${lightness}%)`;
    };
}

function programarRefresco() {
    clearTimeout(refrescoTimer);
    refrescoTimer = setTimeout(cargarMatriz, 350);
}

function normalizarCategoria(value) {
    if (value == null) return "Sense categoria";

    if (typeof value === "string") {
        const clean = value.trim();
        return clean || "Sense categoria";
    }

    if (Array.isArray(value)) {
        for (const item of value) {
            const resolved = normalizarCategoria(item);
            if (resolved !== "Sense categoria") return resolved;
        }
        return "Sense categoria";
    }

    if (typeof value === "object") {
        const term = value.term;
        if (term && typeof term === "object") {
            if (typeof term.ca_ES === "string" && term.ca_ES.trim()) return term.ca_ES.trim();
            if (typeof term.en_GB === "string" && term.en_GB.trim()) return term.en_GB.trim();
        }

        if (typeof value.ca_ES === "string" && value.ca_ES.trim()) return value.ca_ES.trim();
        if (typeof value.en_GB === "string" && value.en_GB.trim()) return value.en_GB.trim();

        const asText = String(value).trim();
        return asText && asText !== "[object Object]" ? asText : "Sense categoria";
    }

    const asText = String(value).trim();
    return asText || "Sense categoria";
}

function cargarPivot(data) {
    const renderers = $.pivotUtilities.renderers;
    const aggregators = $.pivotUtilities.aggregators;

    const normalized = data.map(d => ({
        categoria: normalizarCategoria(d.categoria),
        tipo: d.tipo || "(Sense tipus)",
        anio: Number(d.anio || 0),
        ajuts: Number(d.ajuts || 0),
        import: Number(d.import || 0)
    }));

    $("#pivot-container").pivotUI(normalized, {
        rows: ["tipo"],
        cols: ["anio"],
        vals: ["import"],
        renderers,
        aggregators,
        rendererName: "Heatmap",
        aggregatorName: "Sum",
        rendererOptions: {
            heatmap: {
                colorScaleGenerator: greenColorScaleGenerator
            }
        },
        hiddenAttributes: [ "ajuts"],
        unusedAttrsVertical: false,
        menuLimit: 500
    }, true);
}

function configurarSlider() {
    const ahora = new Date().getFullYear();
    const min = 2010;
    const max = ahora + 1;
    const slider = document.getElementById('sliderAnios');
    const valorRango = document.getElementById('valorRango');

    noUiSlider.create(slider, {
        start: [ahora - 4, ahora],
        connect: true,
        step: 1,
        range: { min, max },
        format: {
            to: value => Math.round(value),
            from: value => Number(value)
        }
    });

    slider.noUiSlider.on('update', function (values) {
        valorRango.textContent = `${values[0]} - ${values[1]}`;
        programarRefresco();
    });
}

async function cargarMatriz() {
    const [desde, hasta] = document.getElementById('sliderAnios').noUiSlider.get();
    const modoAnio = document.getElementById('modoAnioSelect').value || 'awardDate';
    
    document.getElementById('loading').classList.remove('hidden');
    document.getElementById('wrapper').classList.add('hidden');

    try {
        const res = await fetch(apiUrl(`/awards/stats/powertable?desde=${desde}&hasta=${hasta}&modoAnio=${encodeURIComponent(modoAnio)}`));
        const data = await res.json();

        if (!data.length) {
            mostrarError(`No hi ha dades per al període ${desde} - ${hasta}`);
            return;
        }

        const anios = [...new Set(data.map(d => d.anio))].sort();
        document.getElementById('activeRange').innerText = `Matriu generada: ${anios[0]} - ${anios[anios.length-1]}`;
        document.getElementById('loading').classList.add('hidden');
        document.getElementById('wrapper').classList.remove('hidden');

        requestAnimationFrame(() => cargarPivot(data));

    } catch (e) { mostrarError("Error en la càrrega de dades."); }
}

function mostrarError(m) {
    document.getElementById('loading').innerHTML = `
        <i class="fa-solid fa-circle-exclamation text-amber-400 text-xl mb-2"></i>
        <p class="text-slate-500 font-bold text-xxs uppercase">${m}</p>
    `;
}

configurarSlider();
document.getElementById('modoAnioSelect').addEventListener('change', programarRefresco);
cargarMatriz();
