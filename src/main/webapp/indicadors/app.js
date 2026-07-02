// Variables globals per als gràfics
let chartRD, chartNacAltres, chartMarcEuropeu, chartIntAltres, chartEducatius, chartPublic, chartPrivat, chartFacturacioDirecta;
let chartRhGenPie, chartRhGenBar;
let chartScopus, chartWos, chartOpenAlex; // Outputs - Publicacions
let chartTesisNatural, chartTesisAcademic, chartDirectorsGen; // Outputs - Tesis
let activeTopTab = 'recursos-economics';
let activeSidebarTab = 'portada';
let yearSlider = null;
let worldMap = null;
let markersLayer = null;

let icreaData = {
    total: 26
};

let ipsGenderData = {
    hombres: 5884,
    mujeres: 3226,
    otros: 0
};

function fetchIcreaStats() {
    const uuid = document.getElementById('selectDept').value;
    
    let url = '/api/persons/stats/icrea';
    if (uuid && uuid !== 'all') {
        url += `?deptUuid=${uuid}`;
    }
    
    fetch(url)
        .then(response => {
            if (!response.ok) {
                throw new Error('Network response was not ok');
            }
            return response.json();
        })
        .then(data => {
            if (data && typeof data.total !== 'undefined') {
                icreaData.total = data.total;
                const kpiIcrea = document.getElementById('kpiIcrea');
                if (kpiIcrea) kpiIcrea.textContent = icreaData.total;
                const kpiOutcIcrea = document.getElementById('kpiOutcIcrea');
                if (kpiOutcIcrea) kpiOutcIcrea.textContent = icreaData.total;
            }
        })
        .catch(error => {
            console.error('Error fetching ICREA stats:', error);
        });
}

function fetchIpsGenderStats() {
    if (!yearSlider) return;
    const [startYear, endYear] = yearSlider.noUiSlider.get().map(v => Math.round(v));
    const uuid = document.getElementById('selectDept').value;
    
    let url = `/api/persons/stats/ips-gender-distribution?desde=${startYear}&hasta=${endYear}`;
    if (uuid && uuid !== 'all') {
        url += `&deptUuid=${uuid}`;
    }
    
    fetch(url)
        .then(response => {
            if (!response.ok) {
                throw new Error('Network response was not ok');
            }
            return response.json();
        })
        .then(data => {
            if (data) {
                ipsGenderData.hombres = data.hombres || 0;
                ipsGenderData.mujeres = data.mujeres || 0;
                ipsGenderData.otros = data.otros || 0;
                
                updateRhChartsOnly();
            }
        })
        .catch(error => {
            console.error('Error fetching IP gender stats:', error);
        });
}

function updateRhChartsOnly() {
    if (!yearSlider) return;
    const [startYear, endYear] = yearSlider.noUiSlider.get().map(v => Math.round(v));
    const dept = document.getElementById('selectDept').value;
    const years = [];
    for (let y = startYear; y <= endYear; y++) {
        years.push(y);
    }
    
    const donaVal = ipsGenderData.mujeres;
    const homeVal = ipsGenderData.hombres;
    const totalIps = donaVal + homeVal;
    
    // Actualizar gráfico de sectores de Recursos Humanos (Pie)
    const rhPieOption = {
        tooltip: {
            trigger: 'item',
            formatter: function(params) {
                const pct = params.percent.toFixed(1).replace('.', ',') + '%';
                return `<div class="font-bold text-xs text-slate-700">${params.name}</div>
                        <div class="text-xs text-slate-500">Total: <span class="font-semibold text-slate-700">${params.value} (${pct})</span></div>`;
            }
        },
        color: ['#0f2c8a', '#008cff'], // Dona (blau fosc), Home (blau clar)
        series: [{
            name: 'Gènere',
            type: 'pie',
            radius: '70%',
            center: ['50%', '50%'],
            label: {
                show: true,
                position: 'outside',
                formatter: function(params) {
                    const pct = params.percent.toFixed(1).replace('.', ',') + '%';
                    return params.name + '\n' + params.value + ' (' + pct + ')';
                },
                color: '#475569',
                fontSize: 10,
                fontWeight: 600
            },
            data: [
                { value: donaVal, name: 'Dona' },
                { value: homeVal, name: 'Home' }
            ],
            itemStyle: {
                borderColor: '#fff',
                borderWidth: 2
            }
        }]
    };
    chartRhGenPie.setOption(rhPieOption, true);
    
    // Actualizar gráfico de barres agrupadas de evolución de género (Bar)
    const rhDonaData = [];
    const rhHomeData = [];
    
    const uabTotalIps = 5909;
    const scale = dept === 'all' ? 1.0 : (totalIps > 0 ? (totalIps / uabTotalIps) : 0.0);
    
    years.forEach(y => {
        const seed = (y) => {
            const x = Math.sin(y) * 100;
            return x - Math.floor(x);
        };
        const baseTotal = 1700 + Math.round(seed(y) * 350);
        
        const is2026 = (y === 2026);
        const factor = is2026 ? 0.08 : 1.0;
        
        const totalYear = Math.round(baseTotal * scale * factor);
        const pctDona = totalIps > 0 ? (donaVal / totalIps) : 0.35;
        
        rhDonaData.push(Math.round(totalYear * pctDona));
        rhHomeData.push(Math.round(totalYear * (1.0 - pctDona)));
    });
    
    const rhBarOption = {
        tooltip: {
            trigger: 'axis',
            axisPointer: { type: 'shadow' }
        },
        legend: {
            show: true,
            left: 10,
            top: 5,
            icon: 'circle',
            itemWidth: 10,
            itemHeight: 10,
            textStyle: { color: '#475569', fontWeight: 600, fontSize: 11 },
            data: ['Dona', 'Home']
        },
        grid: {
            top: 35,
            bottom: 25,
            left: 45,
            right: 10
        },
        xAxis: {
            type: 'category',
            data: years,
            axisLine: { lineStyle: { color: '#94a3b8' } },
            axisTick: { show: false },
            axisLabel: { color: '#64748b', fontSize: 10, fontWeight: 600 }
        },
        yAxis: {
            type: 'value',
            splitLine: { lineStyle: { type: 'dashed', color: '#e2e8f0' } },
            axisLabel: {
                color: '#64748b',
                fontSize: 10,
                fontWeight: 500,
                formatter: function(val) {
                    return val.toString();
                }
            }
        },
        color: ['#0f2c8a', '#008cff'], // Dona, Home
        series: [
            {
                name: 'Dona',
                type: 'bar',
                data: rhDonaData,
                barGap: '15%',
                barWidth: '35%',
                itemStyle: { borderRadius: [2, 2, 0, 0] }
            },
            {
                name: 'Home',
                type: 'bar',
                data: rhHomeData,
                barWidth: '35%',
                itemStyle: { borderRadius: [2, 2, 0, 0] }
            }
        ]
    };
    chartRhGenBar.setOption(rhBarOption, true);
}

let powertableData = [];

function fetchEconomicsStats() {
    if (!yearSlider) return;
    const uuid = document.getElementById('selectDept').value;
    const [startYear, endYear] = yearSlider.noUiSlider.get().map(v => Math.round(v));
    
    let url = `/api/awards/stats/powertable?desde=${startYear}&hasta=${endYear}`;
    if (uuid && uuid !== 'all') {
        url += `&collaboratorUuid=${uuid}`;
    }
    
    fetch(url)
        .then(response => {
            if (!response.ok) {
                throw new Error('Network response was not ok');
            }
            return response.json();
        })
        .then(data => {
            powertableData = data || [];
            updateEconomicsChartsFromPowertable();
        })
        .catch(error => {
            console.error('Error fetching economics stats:', error);
        });
}

function updateEconomicsChartsFromPowertable() {
    if (!yearSlider) return;
    const [startYear, endYear] = yearSlider.noUiSlider.get().map(v => Math.round(v));
    const years = [];
    for (let y = startYear; y <= endYear; y++) {
        years.push(y);
    }
    
    const dataFacturacio = [];
    const dataRD = [];
    const dataNacAltres = [];
    const dataPublic = [];
    const dataPrivat = [];
    const dataMarcEuropeu = [];
    const dataIntAltres = [];
    const dataEducatius = [];

    years.forEach(y => {
        // Projectes R+D
        const entriesRd = powertableData.filter(d => d.anio === y && d.tipo === 'Projectes R+D');
        const totalRd = entriesRd.reduce((sum, d) => sum + (d.import || 0.0), 0.0);
        dataRD.push(totalRd / 1000000.0);  

        // Altres Nacionals 
        const entriesNacAltres = powertableData.filter(d => d.anio === y && d.tipo === 'Accions complementàries i altres ajuts');
        const totalNacAltres = entriesNacAltres.reduce((sum, d) => sum + (d.import || 0.0), 0.0);
        dataNacAltres.push(totalNacAltres / 1000000.0);

        //Contractes i convenis - Públic
        const entriesPublic = powertableData.filter(d => d.anio === y && d.tipo === 'Concessió conveni - Pública');
        const totalPublic = entriesPublic.reduce((sum, d) => sum + (d.import || 0.0), 0.0);
        dataPublic.push(totalPublic / 1000000.0);

        // Contractes i convenis - Privat
        const entriesPrivat = powertableData.filter(d => d.anio === y && d.tipo === 'Concessió conveni - Privada');      
        const totalPrivat = entriesPrivat.reduce((sum, d) => sum + (d.import || 0.0), 0.0);
        dataPrivat.push(totalPrivat / 1000000.0);


        // Facturació Directa (Prestació de Serveis)
        const entriesFact = powertableData.filter(d => d.anio === y && d.tipo === 'Prestació de Serveis');
        const totalFact = entriesFact.reduce((sum, d) => sum + (d.import || 0.0), 0.0);
        dataFacturacio.push(totalFact / 1000000.0);

        // Programa Marc Europeu (Projectes vinculats a Funding Opportunities de tipus Marc Europeu)
        const entriesMarc = powertableData.filter(d => d.anio === y && d.tipo === "Programa Marc Europeu");
        const totalMarc = entriesMarc.reduce((sum, d) => sum + (d.import || 0.0), 0.0);
        dataMarcEuropeu.push(totalMarc / 1000000.0);

        // Altres Internacionals (Altres ajuts internacionals + Beques Internacionals + Projectes d'investigació Internacionals no-Marc)
        const entriesAltres = powertableData.filter(d => d.anio === y && (d.tipo === 'Altres ajuts int' || d.tipo === 'Beques Internacionals' ));
        const totalAltres = entriesAltres.reduce((sum, d) => sum + (d.import || 0.0), 0.0);
        dataIntAltres.push(totalAltres / 1000000.0);

        // Educatius Internacionals (Projectes Educatius Internacionals)
        const entriesEdu = powertableData.filter(d => d.anio === y && d.tipo === 'Projectes Educatius Internacionals');
        const totalEdu = entriesEdu.reduce((sum, d) => sum + (d.import || 0.0), 0.0);
        dataEducatius.push(totalEdu / 1000000.0);
    });
    
    const colorOrange = '#f16529'; // Facturació Directa
    const colorVerd = '#008037';    // Projectes Internacionals (UAB verd)
    const colorBlau = '#008cff';      // Projectes Nacionals
    const colorPurple = '#4b2c70';     // Contractes i convenis    
    
    try { chartRD.setOption(getBaseBarOption(years, dataRD, colorBlau)); } catch (e) { console.error("Error setting option for chartRD:", e); }
    try { chartNacAltres.setOption(getBaseBarOption(years, dataNacAltres, colorBlau)); } catch (e) { console.error("Error setting option for chartNacAltres:", e); }
    try { chartPublic.setOption(getBaseBarOption(years, dataPublic, colorPurple)); } catch (e) { console.error("Error setting option for chartPublic:", e); }
    try { chartPrivat.setOption(getBaseBarOption(years, dataPrivat, colorPurple)); } catch (e) { console.error("Error setting option for chartPrivat:", e); }
    try { chartFacturacioDirecta.setOption(getBaseBarOption(years, dataFacturacio, colorOrange, true), true); } catch (e) { console.error("Error setting option for chartFacturacioDirecta:", e); }
    try { chartMarcEuropeu.setOption(getBaseBarOption(years, dataMarcEuropeu, colorVerd, true), true); } catch (e) { console.error("Error setting option for chartMarcEuropeu:", e); }
    try { chartIntAltres.setOption(getBaseBarOption(years, dataIntAltres, colorVerd, true), true); } catch (e) { console.error("Error setting option for chartIntAltres:", e); }
    try { chartEducatius.setOption(getBaseBarOption(years, dataEducatius, colorVerd, true), true); } catch (e) { console.error("Error setting option for chartEducatius:", e); }

    // Update Research Projects KPIs dynamically from DB categories and leadership status
    try {
        const nacEntries = powertableData.filter(d => 
            d.anio >= startYear && d.anio <= endYear &&
            d.categoria && (d.categoria.toLowerCase().includes('nacionals') || d.categoria === 'Ajuts nacionals')
        );
        const intEntries = powertableData.filter(d => 
            d.anio >= startYear && d.anio <= endYear &&
            d.categoria && (d.categoria.toLowerCase().includes('internacionals') || d.categoria === 'Ajuts internacionals')
        );
        const totalNac = nacEntries.reduce((sum, d) => sum + (d.ajuts || 0), 0);
        const totalInt = intEntries.reduce((sum, d) => sum + (d.ajuts || 0), 0);
        
        const kpiNacEl = document.getElementById('kpiActPrNac');
        const kpiIntEl = document.getElementById('kpiActPrInt');
        if (kpiNacEl) kpiNacEl.textContent = totalNac;
        if (kpiIntEl) kpiIntEl.textContent = totalInt;

        // Grans projectes (Programa Marc Europeu) segmented by UAB leadership
        const marcEntries = powertableData.filter(d => 
            d.anio >= startYear && d.anio <= endYear &&
            d.tipo === 'Programa Marc Europeu'
        );
        const totalPart = marcEntries
            .filter(d => d.esLider === false)
            .reduce((sum, d) => sum + (d.ajuts || 0), 0);
        const totalLid = marcEntries
            .filter(d => d.esLider === true)
            .reduce((sum, d) => sum + (d.ajuts || 0), 0);
            
        const kpiPartEl = document.getElementById('kpiActPrPart');
        const kpiLidEl = document.getElementById('kpiActPrLid');
        if (kpiPartEl) kpiPartEl.textContent = totalPart;
        if (kpiLidEl) kpiLidEl.textContent = totalLid;

        // Contractes i convenis (con empresas / entidades públicas)
        const ccEmpEntries = powertableData.filter(d => 
            d.anio >= startYear && d.anio <= endYear &&
            d.tipo && d.tipo.toLowerCase().includes('privada')
        );
        const ccPubEntries = powertableData.filter(d => 
            d.anio >= startYear && d.anio <= endYear &&
            d.tipo && (d.tipo.toLowerCase().includes('pública') || d.tipo.toLowerCase().includes('publica'))
        );
        const totalCcEmp = ccEmpEntries.reduce((sum, d) => sum + (d.ajuts || 0), 0);
        const totalCcPub = ccPubEntries.reduce((sum, d) => sum + (d.ajuts || 0), 0);
        
        const kpiCcEmpEl = document.getElementById('kpiActCcEmp');
        const kpiCcPubEl = document.getElementById('kpiActCcPub');
        if (kpiCcEmpEl) kpiCcEmpEl.textContent = totalCcEmp;
        if (kpiCcPubEl) kpiCcPubEl.textContent = totalCcPub;
    } catch (err) {
        console.error("Error updating activities KPIs:", err);
    }
}

function fetchMapConvenisStats() {
    if (!yearSlider) return;
    const uuid = document.getElementById('selectDept').value;
    const [startYear, endYear] = yearSlider.noUiSlider.get().map(v => Math.round(v));
    
    let url = `/api/awards/stats/map-convenis?desde=${startYear}&hasta=${endYear}`;
    if (uuid && uuid !== 'all') {
        url += `&collaboratorUuid=${uuid}`;
    }
    
    fetch(url)
        .then(response => {
            if (!response.ok) {
                throw new Error('Network response was not ok');
            }
            return response.json();
        })
        .then(data => {
            renderMapConvenis(data || []);
        })
        .catch(error => {
            console.error('Error fetching map convenis:', error);
        });
}

function fetchXarxesPlataformesStats() {
    if (!yearSlider) return;
    const uuid = document.getElementById('selectDept').value;
    const [startYear, endYear] = yearSlider.noUiSlider.get().map(v => Math.round(v));
    
    let url = `/api/awards/stats/xarxes-plataformes?desde=${startYear}&hasta=${endYear}`;
    if (uuid && uuid !== 'all') {
        url += `&collaboratorUuid=${uuid}`;
    }
    
    fetch(url)
        .then(response => {
            if (!response.ok) {
                throw new Error('Network response was not ok');
            }
            return response.json();
        })
        .then(count => {
            const kpiEl = document.getElementById('kpiActXpVal');
            if (kpiEl) {
                kpiEl.textContent = count;
            }
        })
        .catch(error => {
            console.error('Error fetching xarxes plataformes stats:', error);
        });
}

function addMarkerToMap(item) {
    if (!item.coords) return;
    
    // Parse coordinates "lat,lng"
    const [lat, lng] = item.coords.split(',').map(Number);
    if (isNaN(lat) || isNaN(lng)) return;
    
    // Add deterministic jitter in degrees to avoid overlaps in same city/funder
    let latOffset = 0;
    let lngOffset = 0;
    if (item.uuid) {
        let hash = 0;
        for (let i = 0; i < item.uuid.length; i++) {
            hash = item.uuid.charCodeAt(i) + ((hash << 5) - hash);
        }
        // 0.04 degrees maximum offset (approx 4.4km, visually visible when zoomed)
        latOffset = ((hash % 10) / 10 - 0.5) * 0.04;
        lngOffset = (((hash >> 4) % 10) / 10 - 0.5) * 0.04;
    }
    
    const markerLat = lat + latOffset;
    const markerLng = lng + lngOffset;
    
    // Create Leaflet circleMarker
    const marker = L.circleMarker([markerLat, markerLng], {
        radius: 6,
        fillColor: '#008cff',
        color: '#ffffff',
        weight: 1.5,
        opacity: 0.9,
        fillOpacity: 0.8
    });
    
    // Format amount
    const formattedAmount = new Intl.NumberFormat('ca-ES', { 
        style: 'currency', 
        currency: 'EUR',
        maximumFractionDigits: 0 
    }).format(item.amount || 0);
    
    // Tooltip HTML matching previous design
    const tooltipHtml = `
        <div class="map-tooltip-title">${item.title}</div>
        <div class="map-tooltip-funder">
            <i class="fa-solid fa-building mr-1"></i> ${item.funder}
        </div>
        <div class="map-tooltip-amount">${formattedAmount}</div>
    `;
    
    // Bind tooltip
    marker.bindTooltip(tooltipHtml, {
        direction: 'top',
        className: 'leaflet-custom-tooltip',
        offset: [0, -6]
    });
    
    // Add interactive hover states
    marker.on('mouseover', function () {
        this.setStyle({
            fillColor: '#008037', // UAB Green on hover
            radius: 9
        });
    });
    
    marker.on('mouseout', function () {
        this.setStyle({
            fillColor: '#008cff', // Restore blue
            radius: 6
        });
    });
    
    // Add to layer group
    marker.addTo(markersLayer);
}

function renderMapConvenis(data) {
    const mapContainer = document.getElementById('leaflet-map');
    if (!mapContainer) return;
    
    if (typeof L === 'undefined') {
        console.error("Leaflet library (L) is not loaded!");
        mapContainer.innerHTML = `
            <div style="color: #ef4444; padding: 40px; text-align: center; font-family: 'Inter', sans-serif; font-size: 14px; font-weight: 600; background-color: #fef2f2; border: 1px dashed #fca5a5; border-radius: 8px;">
                <i class="fa-solid fa-triangle-exclamation" style="font-size: 24px; margin-bottom: 12px; display: block;"></i>
                Error: No s'ha pogut carregar la llibreria de mapes Leaflet.js. <br/>
                <span style="font-size: 11px; font-weight: 400; color: #7f1d1d; margin-top: 8px; display: block;">
                    Si us plau, verifiqueu la connexió a internet o si el vostre navegador està bloquejant l'origen cdn.jsdelivr.net.
                </span>
            </div>
        `;
        return;
    }
    
    if (!worldMap) {
        // Initialize Leaflet map
        worldMap = L.map('leaflet-map', {
            scrollWheelZoom: true,
            minZoom: 1.5,
            maxZoom: 12
        }).setView([20, 0], 2);

        // Add CartoDB Positron basemap
        L.tileLayer('https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png', {
            attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors &copy; <a href="https://carto.com/attributions">CARTO</a>',
            subdomains: 'abcd',
            maxZoom: 20
        }).addTo(worldMap);

        // Initialize LayerGroup for markers
        markersLayer = L.layerGroup().addTo(worldMap);
    } else {
        // Clear old markers
        markersLayer.clearLayers();
    }
    
    // Read local storage geocode cache
    const GEO_CACHE_KEY = 'egreta_geo_cache';
    let geoCache = {};
    try {
        geoCache = JSON.parse(localStorage.getItem(GEO_CACHE_KEY)) || {};
    } catch (e) {
        console.error("Error reading geo cache:", e);
    }
    
    function saveGeoCache() {
        try {
            localStorage.setItem(GEO_CACHE_KEY, JSON.stringify(geoCache));
        } catch (e) {
            console.error("Error saving geo cache:", e);
        }
    }
    
    const pendingGeocodes = [];
    const itemsToRender = [];
    
    data.forEach(item => {
        if (item.coords) {
            itemsToRender.push(item);
        } else if (item.address) {
            // Construct search query string
            const addr = item.address;
            const street = addr.address1 && addr.address1 !== '-' ? addr.address1 : '';
            const city = addr.city || '';
            const country = (addr.country && addr.country.term) ? 
                (addr.country.term.ca_ES || addr.country.term.es_ES || addr.country.term.en_GB || '') : '';
            
            const queryParts = [];
            if (street) queryParts.push(street);
            if (city) queryParts.push(city);
            if (country) queryParts.push(country);
            const queryStr = queryParts.join(', ').trim();
            
            if (queryStr) {
                if (geoCache[queryStr]) {
                    if (geoCache[queryStr] !== 'NOT_FOUND') {
                        item.coords = geoCache[queryStr];
                        itemsToRender.push(item);
                    }
                } else {
                    pendingGeocodes.push({ item, queryStr });
                }
            }
        }
    });
    
    // Render immediate coordinates first
    itemsToRender.forEach(addMarkerToMap);
    
    // Sequential geocoding for pending items (1 request per second to respect Nominatim policy)
    /*if (pendingGeocodes.length > 0) {
        let index = 0;
        
        function nextGeocode() {
            if (index >= pendingGeocodes.length) {
                saveGeoCache();
                return;
            }
            
            const { item, queryStr } = pendingGeocodes[index];
            index++;
            
            // Check cache again just in case there are duplicates in the current list
            if (geoCache[queryStr]) {
                if (geoCache[queryStr] !== 'NOT_FOUND') {
                    item.coords = geoCache[queryStr];
                    addMarkerToMap(item);
                }
                nextGeocode();
                return;
            }
            
            // Fetch from OpenStreetMap Nominatim
            const url = `https://nominatim.openstreetmap.org/search?format=json&limit=1&q=${encodeURIComponent(queryStr)}`;
            
            fetch(url, {
                headers: {
                    'Accept': 'application/json',
                    'User-Agent': 'SpringEgretaDashboard/1.0'
                }
            })
            .then(res => res.json())
            .then(results => {
                if (results && results.length > 0) {
                    const lat = parseFloat(results[0].lat);
                    const lon = parseFloat(results[0].lon);
                    const coords = `${lat},${lon}`;
                    
                    geoCache[queryStr] = coords;
                    item.coords = coords;
                    addMarkerToMap(item);
                    saveGeoCache(); // Save incrementally
                } else {
                    // Cache negative result to prevent re-requesting this unresolvable address
                    geoCache[queryStr] = 'NOT_FOUND';
                    saveGeoCache();
                }
                
                // Sleep 1 second before next request to Nominatim to comply with policy
                setTimeout(nextGeocode, 1000);
            })
            .catch(err => {
                console.error("Geocoding failed for: " + queryStr, err);
                // Sleep 2 seconds before retrying on error
                setTimeout(nextGeocode, 2000);
            });
        }
        
        nextGeocode();
    }*/
}

function populateDepartmentSelect() {
    Promise.all([
        fetch('/api/persons/departamentos').then(r => r.json()),
        fetch('/api/persons/institutos').then(r => r.json())
    ]).then(([depts, insts]) => {
        const select = document.getElementById('selectDept');
        if (!select) return;
        
        select.innerHTML = '<option value="all">Tot</option>';
        
        const sortedDepts = depts.sort((a, b) => (a.nombre || '').localeCompare(b.nombre || ''));
        sortedDepts.forEach(d => {
            const opt = document.createElement('option');
            opt.value = d.uuid;
            opt.textContent = d.nombre;
            select.appendChild(opt);
        });
        
        const sortedInsts = insts.sort((a, b) => (a.nombre || '').localeCompare(b.nombre || ''));
        sortedInsts.forEach(i => {
            const opt = document.createElement('option');
            opt.value = i.uuid;
            opt.textContent = i.nombre;
            select.appendChild(opt);
        });
        
        fetchIcreaStats();
        updateDashboard();
    }).catch(err => {
        console.error('Error populating department select:', err);
        fetchIcreaStats();
        updateDashboard();
    });
}

function getDeptMultiplier(dept) {
    if (!dept || dept === 'all') return 1.0;
    let hash = 0;
    for (let i = 0; i < dept.length; i++) {
        hash = dept.charCodeAt(i) + ((hash << 5) - hash);
    }
    const absHash = Math.abs(hash);
    return 0.3 + (absHash % 14) * 0.1;
}

// Dades reals de les estructures de recerca (amb valors de contingència per defecte)
let researchStructuresData = {
    departaments: 57,
    cers: 19,
    institutsPropis: 9,
    sgrs: 180,
    esfera: 167
};

function fetchResearchStructures() {
    fetch('/api/organizations/stats/research-structures')
        .then(response => {
            if (!response.ok) {
                throw new Error('Network response was not ok');
            }
            return response.json();
        })
        .then(data => {
            if (data) {
                researchStructuresData = data;
                updateDashboard();
            }
        })
        .catch(error => {
            console.error('Error fetching research structures stats:', error);
        });
}

// Els multiplicadors es calculen dinàmicament a partir del UUID de l'organització

// Generador de dades simulades segons l'any i el departament
function getMockData(year, dept) {
    const mult = getDeptMultiplier(dept);
    
    // Generador pseudo-aleatori determinista basat en l'any i multiplicador
    const seed = (year) => {
        const x = Math.sin(year) * 10000;
        return x - Math.floor(x);
    };

    return {
        // Competitius - Nacionals (en milions d'euros)
        rd: (0.08 + seed(year) * 0.06) * mult,
        nacAltres: (0.09 + seed(year + 1) * 0.05) * mult,
        
        // Competitius - Internacionals
        marcEuropeu: (0.12 + seed(year + 2) * 0.08) * mult,
        intAltres: (0.10 + seed(year + 3) * 0.06) * mult,
        educatius: (1.5 + seed(year + 4) * 2.0) * mult, // Educatius està en escala de milions més grans (e.g. 2M - 4M)
        
        // No competitius - Contractes i convenis
        public: (0.07 + seed(year + 5) * 0.04) * mult,
        privat: (0.08 + seed(year + 6) * 0.05) * mult,
        
        // No competitius - Facturació directa
        facturacio: (0.13 + seed(year + 7) * 0.07) * mult
    };
}

let openAlexRawData = {};

function fetchOpenAlexStats() {
    const url = 'https://api.openalex.org/works?filter=institutions.id:I123044942&group_by=publication_year';
    fetch(url)
        .then(response => {
            if (!response.ok) throw new Error('OpenAlex API error');
            return response.json();
        })
        .then(data => {
            const groups = data.group_by || [];
            openAlexRawData = {};
            groups.forEach(g => {
                openAlexRawData[g.key] = g.count;
            });
            updateOpenAlexChart();
        })
        .catch(err => {
            console.error('Error fetching from OpenAlex:', err);
            openAlexRawData = null;
        });
}

let scopusRawData = {};

function fetchScopusStats() {
    fetch('/api/pure/stats/scopus')
        .then(response => {
            if (!response.ok) throw new Error('Scopus API error');
            return response.json();
        })
        .then(data => {
            if (data && !data.error) {
                scopusRawData = data;
            } else {
                console.warn('Scopus API returned error or was not configured:', data ? data.error : 'empty');
                scopusRawData = null;
            }
            updateDashboard();
        })
        .catch(err => {
            console.error('Error fetching Scopus stats:', err);
            scopusRawData = null;
            updateDashboard();
        });
}

let thesisRawList = null;

function fetchThesisStats() {
    if (!yearSlider) return;
    const [startYear, endYear] = yearSlider.noUiSlider.get().map(v => Math.round(v));
    const dept = document.getElementById('selectDept').value;
    
    if (activeSidebarTab !== 'outputs' || activeTopTab !== 'tesis-llegides') {
        return;
    }
    
    if (thesisRawList === null) {
        updateThesisCharts();
    }
    
    let url = `/api/student-theses/stats/list-institute?orgUuid=${dept}&desde=${startYear}&hasta=${endYear}&filtrePersonal=periode`;
    
    fetch(url)
        .then(response => {
            if (!response.ok) throw new Error('Thesis API error');
            return response.json();
        })
        .then(data => {
            thesisRawList = data || [];
            updateThesisCharts();
        })
        .catch(err => {
            console.error('Error fetching thesis stats:', err);
            thesisRawList = null;
            updateThesisCharts();
        });
}

function updateThesisCharts() {
    if (!yearSlider) return;
    const [startYear, endYear] = yearSlider.noUiSlider.get().map(v => Math.round(v));
    const dept = document.getElementById('selectDept').value;
    const mult = getDeptMultiplier(dept);

    const pubYears = [];
    for (let y = startYear; y <= endYear; y++) {
        pubYears.push(y);
    }

    if (thesisRawList === null) {
        const baseTesisNaturalMap = {
            2020: 124, 2021: 142, 2022: 168, 2023: 151, 2024: 175, 2025: 192, 2026: 200
        };
        const baseTesisAcademicMap = {
            '2020-21': 130, '2021-22': 148, '2022-23': 159, '2023-24': 172, '2024-25': 185, '2025-26': 195
        };
        const baseDonaTesisMap = {
            2020: 85, 2021: 95, 2022: 110, 2023: 105, 2024: 120, 2025: 132, 2026: 140
        };
        const baseHomeTesisMap = {
            2020: 120, 2021: 130, 2022: 145, 2023: 135, 2024: 155, 2025: 168, 2026: 175
        };
        const baseAltresTesisMap = {
            2020: 15, 2021: 20, 2022: 18, 2023: 22, 2024: 25, 2025: 28, 2026: 30
        };

        const dataTesisNatural = pubYears.map(y => Math.round((baseTesisNaturalMap[y] || 150) * mult));
        
        const academicYears = pubYears.map(y => `${y}-${(y + 1).toString().slice(-2)}`);
        const dataTesisAcademic = academicYears.map(ay => Math.round((baseTesisAcademicMap[ay] || 160) * mult));

        const dataDonaTesis = pubYears.map(y => Math.round((baseDonaTesisMap[y] || 100) * mult));
        const dataHomeTesis = pubYears.map(y => Math.round((baseHomeTesisMap[y] || 140) * mult));
        const dataAltresTesis = pubYears.map(y => Math.round((baseAltresTesisMap[y] || 20) * mult));

        chartTesisNatural.setOption(getTesisBarOption(pubYears, dataTesisNatural, startYear, endYear, false));
        chartTesisAcademic.setOption(getTesisBarOption(academicYears, dataTesisAcademic, startYear, endYear, true));
        chartDirectorsGen.setOption(getDirectorsGenOption(pubYears, dataDonaTesis, dataHomeTesis, dataAltresTesis, startYear, endYear));
        return;
    }

    const dataTesisNatural = pubYears.map(y => {
        return thesisRawList.filter(t => t.any === y).length;
    });

    const academicYears = [];
    for (let y = startYear; y <= endYear; y++) {
        academicYears.push(`${y}-${(y + 1).toString().slice(-2)}`);
    }
    const dataTesisAcademic = academicYears.map(ay => {
        return thesisRawList.filter(t => {
            const year = t.any;
            const month = t.mes || 6;
            const thesisAy = month >= 9 
                ? `${year}-${(year + 1).toString().slice(-2)}` 
                : `${year - 1}-${year.toString().slice(-2)}`;
            return thesisAy === ay;
        }).length;
    });

    const dataDonaTesis = [];
    const dataHomeTesis = [];
    const dataAltresTesis = [];

    pubYears.forEach(y => {
        const thesesOfYear = thesisRawList.filter(t => t.any === y);
        let women = 0;
        let men = 0;
        let others = 0;

        thesesOfYear.forEach(t => {
            if (t.supervisorGenders && Array.isArray(t.supervisorGenders)) {
                t.supervisorGenders.forEach(g => {
                    if (!g) {
                        others++;
                    } else {
                        const gLower = g.toLowerCase().trim();
                        if (gLower === 'dona' || gLower === 'female' || gLower === 'mujer') {
                            women++;
                        } else if (gLower === 'home' || gLower === 'male' || gLower === 'hombre') {
                            men++;
                        } else {
                            others++;
                        }
                    }
                });
            }
        });

        dataDonaTesis.push(women);
        dataHomeTesis.push(men);
        dataAltresTesis.push(others);
    });

    chartTesisNatural.setOption(getTesisBarOption(pubYears, dataTesisNatural, startYear, endYear, false));
    chartTesisAcademic.setOption(getTesisBarOption(academicYears, dataTesisAcademic, startYear, endYear, true));
    chartDirectorsGen.setOption(getDirectorsGenOption(pubYears, dataDonaTesis, dataHomeTesis, dataAltresTesis, startYear, endYear));
}

function updateOpenAlexChart() {
    if (!yearSlider) return;
    const [startYear, endYear] = yearSlider.noUiSlider.get().map(v => Math.round(v));
    const dept = document.getElementById('selectDept').value;
    const mult = getDeptMultiplier(dept);
    const pubYears = [];
    for (let y = startYear; y <= endYear; y++) {
        pubYears.push(y);
    }
    
    let dataOpenAlex;
    if (openAlexRawData && Object.keys(openAlexRawData).length > 0) {
        dataOpenAlex = pubYears.map(y => Math.round((openAlexRawData[y] || 0) * mult));
    } else {
        dataOpenAlex = pubYears.map(y => getPublicationsData(y, dept, 'openalex'));
    }
    
    chartOpenAlex.setOption(getPublicationsChartOption(pubYears, dataOpenAlex, startYear, endYear));
}

// Generador de publicacions simulades (des del 2000 fins al 2026)
function getPublicationsData(year, dept, source) {
    const mult = getDeptMultiplier(dept);
    
    // Generador pseudo-aleatori determinista basat en l'any i font
    const seed = (y) => {
        const x = Math.sin(y * (source === 'scopus' ? 1.15 : source === 'wos' ? 1.25 : 1.35)) * 1000;
        return x - Math.floor(x);
    };

    // Creixement progressiu des del 2000 fins al 2020, després s'estabilitza o decau lleugerament
    const baseVal = 1000 + (year - 2000) * 260;
    const dropFactor = year >= 2021 ? (1.0 - (year - 2020) * 0.05) : 1.0;
    
    return Math.round((baseVal + seed(year) * 600) * mult * dropFactor);
}

// Inicialització de Gràfics
function initCharts() {
    chartRD = echarts.init(document.getElementById('chartRD'));
    chartNacAltres = echarts.init(document.getElementById('chartNacAltres'));
    chartMarcEuropeu = echarts.init(document.getElementById('chartMarcEuropeu'));
    chartIntAltres = echarts.init(document.getElementById('chartIntAltres'));
    chartEducatius = echarts.init(document.getElementById('chartEducatius'));
    chartPublic = echarts.init(document.getElementById('chartPublic'));
    chartPrivat = echarts.init(document.getElementById('chartPrivat'));
    chartFacturacioDirecta = echarts.init(document.getElementById('chartFacturacioDirecta'));
    chartRhGenPie = echarts.init(document.getElementById('chartRhGenPie'));
    chartRhGenBar = echarts.init(document.getElementById('chartRhGenBar'));

    // Outputs - Publicacions
    chartScopus = echarts.init(document.getElementById('chartScopus'));
    chartWos = echarts.init(document.getElementById('chartWos'));
    chartOpenAlex = echarts.init(document.getElementById('chartOpenAlex'));

    // Outputs - Tesis
    chartTesisNatural = echarts.init(document.getElementById('chartTesisNatural'));
    chartTesisAcademic = echarts.init(document.getElementById('chartTesisAcademic'));
    chartDirectorsGen = echarts.init(document.getElementById('chartDirectorsGen'));

    // Redimensionar gràfics al canviar la mida de la finestra
    window.addEventListener('resize', () => {
        chartRD.resize();
        chartNacAltres.resize();
        chartMarcEuropeu.resize();
        chartIntAltres.resize();
        chartEducatius.resize();
        chartPublic.resize();
        chartPrivat.resize();
        chartFacturacioDirecta.resize();
        chartRhGenPie.resize();
        chartRhGenBar.resize();

        // Outputs
        chartScopus.resize();
        chartWos.resize();
        chartOpenAlex.resize();
        
        // Tesis
        chartTesisNatural.resize();
        chartTesisAcademic.resize();
        chartDirectorsGen.resize();
    });
}

// Retorna l'opció base d'ECharts per als gràfics de barra compactes
function getBaseBarOption(years, data, color, isTall = false) {
    return {
        tooltip: {
            trigger: 'axis',
            axisPointer: { type: 'shadow' },
            formatter: function(params) {
                const val = params[0].value;
                const formattedVal = val >= 1.0 
                    ? val.toFixed(2).replace('.', ',') + ' M €' 
                    : (val * 1000000).toLocaleString('ca-ES', { maximumFractionDigits: 0 }) + ' €';
                return `<div class="font-bold text-xs text-slate-700">${params[0].name}</div>
                        <div class="text-xs text-slate-500">${params[0].seriesName}: <span class="font-semibold text-slate-700">${formattedVal}</span></div>`;
            }
        },
        grid: {
            top: 10,
            bottom: 25,
            left: 36,
            right: 5
        },
        xAxis: {
            type: 'category',
            data: years,
            axisLine: { lineStyle: { color: '#94a3b8' } },
            axisTick: { show: false },
            axisLabel: { 
                color: '#64748b', 
                fontSize: 10, 
                fontWeight: 600,
                interval: 0 // Mostrar tots els anys
            }
        },
        yAxis: {
            type: 'value',
            splitLine: { 
                lineStyle: { 
                    type: 'dashed', 
                    color: '#e2e8f0' 
                } 
            },
            axisLabel: {
                color: '#64748b',
                fontSize: 10,
                fontWeight: 500,
                formatter: function(value) {
                    // Si el valor és d'escala gran (com a Educatius) formatem directament en M
                    if (value >= 1.0) {
                        return value.toFixed(0) + ' M';
                    }
                    // Sinó, formatem com a "0,1 mil..." tal com al disseny conceptual
                    if (value === 0) return '0,0';
                    return value.toFixed(1).replace('.', ',') + ' mil...';
                }
            }
        },
        series: [{
            name: 'Import',
            type: 'bar',
            data: data,
            barWidth: '60%',
            itemStyle: {
                color: color,
                borderRadius: [3, 3, 0, 0]
            }
        }]
    };
}

// Opció per als gràfics de publicacions que grisegen els anys fora del rang del slider
function getPublicationsChartOption(years, data, activeStart, activeEnd) {
    const seriesData = years.map((y, idx) => {
        const isInside = y >= activeStart && y <= activeEnd;
        return {
            value: data[idx],
            itemStyle: {
                color: isInside ? '#008cff' : '#cbd5e1',
                borderRadius: [3, 3, 0, 0]
            }
        };
    });

    return {
        tooltip: {
            trigger: 'axis',
            axisPointer: { type: 'shadow' }
        },
        grid: {
            top: 15,
            bottom: 25,
            left: 36,
            right: 5
        },
        xAxis: {
            type: 'category',
            data: years,
            axisLine: { lineStyle: { color: '#94a3b8' } },
            axisTick: { show: false },
            axisLabel: { color: '#64748b', fontSize: 10, fontWeight: 600 }
        },
        yAxis: {
            type: 'value',
            splitLine: { lineStyle: { type: 'dashed', color: '#e2e8f0' } },
            axisLabel: { color: '#64748b', fontSize: 10, fontWeight: 500 }
        },
        series: [{
            name: 'Publicacions',
            type: 'bar',
            data: seriesData,
            barWidth: '60%'
        }]
    };
}

// Opció per als gràfics de tesis llegides amb números a sobre
function getTesisBarOption(categories, data, activeStart, activeEnd, isAcademic = false) {
    const seriesData = categories.map((cat, idx) => {
        let yearVal = isAcademic ? parseInt(cat.split('-')[0]) : parseInt(cat);
        const isInside = yearVal >= activeStart && yearVal <= activeEnd;
        return {
            value: data[idx],
            itemStyle: {
                color: isInside ? '#008cff' : '#cbd5e1',
                borderRadius: [3, 3, 0, 0]
            }
        };
    });

    return {
        tooltip: {
            trigger: 'axis',
            axisPointer: { type: 'shadow' }
        },
        grid: {
            top: 25,
            bottom: 25,
            left: 36,
            right: 5
        },
        xAxis: {
            type: 'category',
            data: categories,
            axisLine: { lineStyle: { color: '#94a3b8' } },
            axisTick: { show: false },
            axisLabel: { color: '#64748b', fontSize: 10, fontWeight: 600 }
        },
        yAxis: {
            type: 'value',
            splitLine: { lineStyle: { type: 'dashed', color: '#e2e8f0' } },
            axisLabel: { color: '#64748b', fontSize: 10, fontWeight: 500 }
        },
        series: [{
            name: 'Tesis',
            type: 'bar',
            data: seriesData,
            barWidth: '55%',
            label: {
                show: true,
                position: 'top',
                color: '#475569',
                fontSize: 10,
                fontWeight: 600,
                formatter: '{c}'
            }
        }]
    };
}

// Opció per a directors de tesis per gènere (barres agrupades)
function getDirectorsGenOption(years, donaData, homeData, altresData, activeStart, activeEnd) {
    const buildSeriesData = (data, color) => {
        return years.map((y, idx) => {
            const isInside = y >= activeStart && y <= activeEnd;
            return {
                value: data[idx],
                itemStyle: {
                    color: isInside ? color : '#cbd5e1',
                    borderRadius: [2, 2, 0, 0]
                }
            };
        });
    };

    return {
        tooltip: {
            trigger: 'axis',
            axisPointer: { type: 'shadow' }
        },
        legend: {
            show: true,
            left: 10,
            top: 5,
            icon: 'circle',
            itemWidth: 10,
            itemHeight: 10,
            textStyle: { color: '#475569', fontWeight: 600, fontSize: 11 },
            data: ['Dona', 'Home']
        },
        grid: {
            top: 35,
            bottom: 25,
            left: 45,
            right: 10
        },
        xAxis: {
            type: 'category',
            data: years,
            axisLine: { lineStyle: { color: '#94a3b8' } },
            axisTick: { show: false },
            axisLabel: { color: '#64748b', fontSize: 10, fontWeight: 600 }
        },
        yAxis: {
            type: 'value',
            splitLine: { lineStyle: { type: 'dashed', color: '#e2e8f0' } },
            axisLabel: { color: '#64748b', fontSize: 10, fontWeight: 500 }
        },
        color: ['#0f2c8a', '#008cff'],
        series: [
            {
                name: 'Dona',
                type: 'bar',
                data: buildSeriesData(donaData, '#0f2c8a'),
                barGap: '15%',
                barWidth: '35%'
            },
            {
                name: 'Home',
                type: 'bar',
                data: buildSeriesData(homeData, '#008cff'),
                barWidth: '35%'
            }
        ]
    };
}

// Actualitza tots els gràfics segons els filtres seleccionats
function updateDashboard() {
    if (!yearSlider) return;

    // Obtener anys del control lliscant
    const [startYear, endYear] = yearSlider.noUiSlider.get().map(v => Math.round(v));
    const dept = document.getElementById('selectDept').value;

    // Generar array d'anys per a l'eix X
    const years = [];
    for (let y = startYear; y <= endYear; y++) {
        years.push(y);
    }

    // --- CODIC PER AL BLOC DE RECURSOS ECONÒMICS ---

    fetchEconomicsStats();
    fetchMapConvenisStats();

    // --- CODIC PER AL BLOC DE RECURSOS HUMANS ---
    const mult = getDeptMultiplier(dept);
    const losuVal = Math.round(1214 * mult);
    document.getElementById('kpiLosu').textContent = losuVal;
    document.getElementById('kpiIcrea').textContent = icreaData.total;

    // Actualitzar gràfics de gènere de Recursos Humans
    fetchIpsGenderStats();

    // --- CODIC PER AL BLOC D'ESTRUCTURES DE RECERCA ---
    const deptsVal = Math.round(researchStructuresData.departaments * mult);
    const cersVal = Math.round(researchStructuresData.cers * mult);
    const instVal = Math.round(researchStructuresData.institutsPropis * mult);
    const sgrsVal = Math.round(researchStructuresData.sgrs * mult);
    const esferaVal = Math.round(researchStructuresData.esfera * mult);

    document.getElementById('kpiDepts').textContent = deptsVal;
    document.getElementById('kpiCers').textContent = cersVal;
    document.getElementById('kpiInstPropis').textContent = instVal;
    document.getElementById('kpiSgrs').textContent = sgrsVal;
    document.getElementById('kpiEsfera').textContent = esferaVal;

    // --- CODIC PER AL BLOC D'ACTIVITATS (PROJECTES DE RECERCA) ---
    // (Updated dynamically from powertable data in updateEconomicsChartsFromPowertable)

    // --- CODIC PER AL BLOC DE CONTRACTES I CONVENIS (ACTIVITATS) ---
    // (Updated dynamically from powertable data in updateEconomicsChartsFromPowertable)

    // (Dynamics of world map are handled interactively from coordinates in renderMapConvenis)

    // --- CODIC PER AL BLOC DE PARTICIPACIÓ AMB XARXES I PLATAFORMES (ACTIVITATS) ---
    fetchXarxesPlataformesStats();

    // --- CODIC PER AL BLOC DE OUTPUTS (PUBLICACIONS) ---
    const pubYears = [];
    for (let y = startYear; y <= endYear; y++) {
        pubYears.push(y);
    }
    
    let dataScopus;
    if (scopusRawData && Object.keys(scopusRawData).length > 0) {
        dataScopus = pubYears.map(y => Math.round((scopusRawData[y] || 0) * mult));
    } else {
        dataScopus = pubYears.map(y => getPublicationsData(y, dept, 'scopus'));
    }
    
    const dataWos = pubYears.map(y => getPublicationsData(y, dept, 'wos'));

    chartScopus.setOption(getPublicationsChartOption(pubYears, dataScopus, startYear, endYear));
    chartWos.setOption(getPublicationsChartOption(pubYears, dataWos, startYear, endYear));
    updateOpenAlexChart();

    // --- CODIC PER AL BLOC DE OUTPUTS (TESIS LLEGIDES) ---
    fetchThesisStats();

    // --- CODIC PER AL BLOC DE OUTCOMES (DESENVOLUPAMENT PROFESSIONAL) ---
    const baseTrams = 1132;
    const kpiTramsVal = Math.round(baseTrams * mult);
    const kpiPctVal = (69.79 * (1.0 + (mult - 1.0) * 0.05)).toFixed(2).replace('.', ',');

    document.getElementById('kpiOutcTrams').textContent = kpiTramsVal;
    document.getElementById('kpiOutcIcrea').textContent = icreaData.total;
    document.getElementById('kpiOutcPct').textContent = kpiPctVal + '%';

    // --- CODIC PER AL BLOC DE OUTCOMES (COL·LABORACIONS) ---
    const baseLoyaltyData = [
        { name: "ROCHE DIAGNOSTICS SL", ajuts: 18 },
        { name: "CAIXABANK, S.A.", ajuts: 14 },
        { name: "REPSOL S.A.", ajuts: 12 },
        { name: "TELEFONICA S.A.", ajuts: 10 },
        { name: "SANOFI-AVENTIS, S.A.", ajuts: 9 },
        { name: "ESTEVE PHARMACEUTICALS S.A.", ajuts: 8 },
        { name: "ALMIRALL S.A.", ajuts: 7 },
        { name: "GRIFOLS S.A.", ajuts: 7 },
        { name: "SEAT S.A.", ajuts: 6 },
        { name: "INDRA SISTEMAS S.A.", ajuts: 5 }
    ];

    let loyaltyHTML = '';
    let loyaltyTotal = 0;
    baseLoyaltyData.forEach(item => {
        const scaledAjuts = Math.max(1, Math.round(item.ajuts * mult));
        loyaltyTotal += scaledAjuts;
        loyaltyHTML += `<tr>
            <td class="text-left font-semibold">${item.name}</td>
            <td class="text-right font-bold">${scaledAjuts}</td>
        </tr>`;
    });
    document.getElementById('loyaltyTableBody').innerHTML = loyaltyHTML;

    const baseColEmp = 422;
    const kpiColEmpVal = Math.round(baseColEmp * mult);
    document.getElementById('kpiOutcColEmp').textContent = kpiColEmpVal;
    document.getElementById('loyaltyTableTotal').textContent = loyaltyTotal;

    const colMarkers = document.querySelectorAll('.map-marker-col');
    colMarkers.forEach((marker, idx) => {
        const seed = Math.sin(idx + mult * 3) * 10;
        const rand = seed - Math.floor(seed);
        if (rand > 0.40 / mult) {
            marker.style.display = 'block';
            marker.style.transform = `translate(-50%, -50%) scale(${0.8 + rand * 0.4})`;
        } else {
            marker.style.display = 'none';
        }
    });

    // --- CODIC PER AL BLOC DE IMPACT (CONTRIBUCIÓ AL DESENVOLUPAMENT ECONÒMIC) ---
    const spinoffByYear = { 2020: 1, 2021: 2, 2022: 1, 2023: 1, 2024: 1, 2025: 0, 2026: 1 };
    const startupByYear = { 2020: 6, 2021: 8, 2022: 7, 2023: 9, 2024: 8, 2025: 7, 2026: 5 };
    const ebtsByYear = { 2020: 2, 2021: 3, 2022: 2, 2023: 3, 2024: 4, 2025: 3, 2026: 2 };

    let spinoffSum = 0;
    let startupSum = 0;
    let ebtsSum = 0;

    for (let y = startYear; y <= endYear; y++) {
        spinoffSum += spinoffByYear[y] || 0;
        startupSum += startupByYear[y] || 0;
        ebtsSum += ebtsByYear[y] || 0;
    }

    document.getElementById('kpiImpSpinoff').textContent = spinoffSum;
    document.getElementById('kpiImpStartup').textContent = startupSum;
    document.getElementById('kpiImpEbts').textContent = ebtsSum;
}

// Inicialització de noUiSlider per al rang d'anys (ampliat fins a 2026 per la vista de Recursos Humans)
function initSlider() {
    yearSlider = document.getElementById('sliderAnios');
    
    noUiSlider.create(yearSlider, {
        start: [2021, 2025],
        connect: true,
        step: 1,
        range: {
            'min': 2020,
            'max': 2026
        },
        format: {
            to: v => Math.round(v),
            from: v => Number(v)
        }
    });

    // Actualitzar etiquetes en arrossegar
    yearSlider.noUiSlider.on('update', (values) => {
        document.getElementById('sliderYearStart').textContent = values[0];
        document.getElementById('sliderYearEnd').textContent = values[1];
    });

    // Actualitzar gràfics en deixar anar
    yearSlider.noUiSlider.on('change', () => {
        updateDashboard();
    });
}

// Auxiliar per actualitzar l'estat actiu dels botons de navegació superiors
function updateTopNavButtons() {
    const btns = document.querySelectorAll('.topnav-btn');
    btns.forEach(btn => {
        const onclickAttr = btn.getAttribute('onclick') || '';
        if (onclickAttr.includes(activeTopTab)) {
            btn.classList.add('active');
        } else {
            btn.classList.remove('active');
        }
    });
}

// Commuta les pestanyes del menú superior
function switchTopTab(tabId) {
    activeTopTab = tabId;
    updateTopNavButtons();
    renderActiveView();
}

// Commuta les pestanyes del menú lateral esquerre
function switchSidebarTab(tabId) {
    activeSidebarTab = tabId;

    // Establir la pestanya superior per defecte segons el menú lateral seleccionat
    if (tabId === 'inputs') {
        const inputTabs = ['recursos-economics', 'recursos-humans', 'estructures-recerca', 'recursos-digitals'];
        if (!inputTabs.includes(activeTopTab)) {
            activeTopTab = 'recursos-economics';
        }
    } else if (tabId === 'activitats') {
        const activitatsTabs = ['projectes-recerca', 'contractes-convenis', 'tesis-doctorals', 'xarxes-plataformes'];
        if (!activitatsTabs.includes(activeTopTab)) {
            activeTopTab = 'projectes-recerca';
        }
    } else if (tabId === 'outputs') {
        const outputsTabs = ['publicacions-recerca', 'tesis-llegides', 'innovacions'];
        if (!outputsTabs.includes(activeTopTab)) {
            activeTopTab = 'publicacions-recerca';
        }
    } else if (tabId === 'outcomes') {
        const outcomesTabs = ['reconeixement-academic', 'desenvolupament-professional', 'collaboracions', 'acords-transferencia'];
        if (!outcomesTabs.includes(activeTopTab)) {
            activeTopTab = 'desenvolupament-professional';
        }
    } else if (tabId === 'impact') {
        const impactTabs = ['beneficis-societat', 'contribucio-al', 'influencia-politiques', 'benefici-reputacional'];
        if (!impactTabs.includes(activeTopTab)) {
            activeTopTab = 'contribucio-al';
        }
    }

    // Actualitzar botons de la barra lateral
    const btns = document.querySelectorAll('.sidebar-btn');
    btns.forEach(btn => {
        const text = btn.querySelector('.sidebar-btn-content').textContent.trim().toLowerCase();
        if (text === tabId.toLowerCase()) {
            btn.classList.add('active');
        } else {
            btn.classList.remove('active');
        }
    });

    updateTopNavButtons();
    renderActiveView();
}

// Mostra la vista activa segons les dues pestanyes seleccionades
function renderActiveView() {
    const dashboardPanel = document.getElementById('dashboardPanel');
    const placeholderPanel = document.getElementById('placeholderPanel');
    const portadaPanel = document.getElementById('portadaPanel');
    const rhPanel = document.getElementById('rhPanel');
    const erPanel = document.getElementById('erPanel');
    const actPrPanel = document.getElementById('actPrPanel');
    const actCcPanel = document.getElementById('actCcPanel');
    const actXpPanel = document.getElementById('actXpPanel');
    const outPrPanel = document.getElementById('outPrPanel');
    const outTlPanel = document.getElementById('outTlPanel');
    const outcDevPanel = document.getElementById('outcDevPanel');
    const outcColPanel = document.getElementById('outcColPanel');
    const impContPanel = document.getElementById('impContPanel');
    const impRepPanel = document.getElementById('impRepPanel');

    const placeholderTitle = document.getElementById('placeholderTitle');
    const topnav = document.querySelector('.uab-topnav');
    const filtersBar = document.querySelector('.filters-bar');
    const filterSelect = document.querySelector('.filter-select');

    const topnavInputs = document.getElementById('topnavInputs');
    const topnavActivitats = document.getElementById('topnavActivitats');
    const topnavOutputs = document.getElementById('topnavOutputs');
    const topnavOutcomes = document.getElementById('topnavOutcomes');
    const topnavImpact = document.getElementById('topnavImpact');

    // Controlar visibilitat de la bombolla d'ajuda de Portada
    const tooltip = document.querySelector('.sidebar-tooltip');
    if (tooltip) {
        if (activeSidebarTab === 'portada') {
            tooltip.classList.remove('hidden');
        } else {
            tooltip.classList.add('hidden');
        }
    }

    // Amagar tots els panels per defecte
    const panels = [
        portadaPanel, dashboardPanel, rhPanel, erPanel, actPrPanel, actCcPanel, actXpPanel,
        outPrPanel, outTlPanel, outcDevPanel, outcColPanel, impContPanel, impRepPanel, placeholderPanel
    ];
    panels.forEach(p => { if (p) p.classList.add('hidden'); });

    // Amagar tots els topnavs per defecte
    const topnavs = [topnavInputs, topnavActivitats, topnavOutputs, topnavOutcomes, topnavImpact];
    topnavs.forEach(t => { if (t) t.classList.add('hidden'); });

    if (activeSidebarTab === 'portada') {
        portadaPanel.classList.remove('hidden');
        topnav.classList.add('hidden');
        filtersBar.classList.add('hidden');
    } else {
        topnav.classList.remove('hidden');
        filtersBar.classList.remove('hidden');

        // Mostrar topnav de la pestanya lateral activa
        if (activeSidebarTab === 'inputs') {
            topnavInputs.classList.remove('hidden');
            if (activeTopTab === 'recursos-economics') {
                dashboardPanel.classList.remove('hidden');
                setTimeout(() => {
                    try { chartRD.resize(); } catch (e) { console.error("Error resizing chartRD:", e); }
                    try { chartNacAltres.resize(); } catch (e) { console.error("Error resizing chartNacAltres:", e); }
                    try { chartMarcEuropeu.resize(); } catch (e) { console.error("Error resizing chartMarcEuropeu:", e); }
                    try { chartIntAltres.resize(); } catch (e) { console.error("Error resizing chartIntAltres:", e); }
                    try { chartEducatius.resize(); } catch (e) { console.error("Error resizing chartEducatius:", e); }
                    try { chartPublic.resize(); } catch (e) { console.error("Error resizing chartPublic:", e); }
                    try { chartPrivat.resize(); } catch (e) { console.error("Error resizing chartPrivat:", e); }
                    try { chartFacturacioDirecta.resize(); } catch (e) { console.error("Error resizing chartFacturacioDirecta:", e); }
                }, 50);
            } else if (activeTopTab === 'recursos-humans') {
                rhPanel.classList.remove('hidden');
                setTimeout(() => {
                    try { chartRhGenPie.resize(); } catch (e) { console.error("Error resizing chartRhGenPie:", e); }
                    try { chartRhGenBar.resize(); } catch (e) { console.error("Error resizing chartRhGenBar:", e); }
                }, 50);
            } else if (activeTopTab === 'estructures-recerca') {
                erPanel.classList.remove('hidden');
            } else {
                placeholderPanel.classList.remove('hidden');
                placeholderTitle.textContent = `Recursos digitals — Inputs`;
            }
        } else if (activeSidebarTab === 'activitats') {
            topnavActivitats.classList.remove('hidden');
            if (activeTopTab === 'projectes-recerca') {
                actPrPanel.classList.remove('hidden');
            } else if (activeTopTab === 'contractes-convenis') {
                actCcPanel.classList.remove('hidden');
                setTimeout(() => {
                    if (!worldMap) {
                        fetchMapConvenisStats();
                    } else {
                        worldMap.invalidateSize();
                        fetchMapConvenisStats();
                    }
                }, 50);
            } else if (activeTopTab === 'xarxes-plataformes') {
                actXpPanel.classList.remove('hidden');
            } else {
                placeholderPanel.classList.remove('hidden');
                let topText = '';
                if (activeTopTab === 'tesis-doctorals') topText = 'Tesis doctorals en desenvolupament';
                placeholderTitle.textContent = `${topText} — Activitats`;
            }
        } else if (activeSidebarTab === 'outputs') {
            topnavOutputs.classList.remove('hidden');
            if (activeTopTab === 'publicacions-recerca') {
                outPrPanel.classList.remove('hidden');
                setTimeout(() => {
                    chartScopus.resize();
                    chartWos.resize();
                    chartOpenAlex.resize();
                }, 50);
            } else if (activeTopTab === 'tesis-llegides') {
                outTlPanel.classList.remove('hidden');
                fetchThesisStats();
                setTimeout(() => {
                    chartTesisNatural.resize();
                    chartTesisAcademic.resize();
                    chartDirectorsGen.resize();
                }, 50);
            } else {
                placeholderPanel.classList.remove('hidden');
                placeholderTitle.textContent = `Innovacions — Outputs`;
            }
        } else if (activeSidebarTab === 'outcomes') {
            topnavOutcomes.classList.remove('hidden');
            if (activeTopTab === 'desenvolupament-professional') {
                outcDevPanel.classList.remove('hidden');
            } else if (activeTopTab === 'collaboracions') {
                outcColPanel.classList.remove('hidden');
            } else {
                placeholderPanel.classList.remove('hidden');
                let topText = activeTopTab === 'reconeixement-academic' ? 'Reconeixement acadèmic' : 'Acords de transferència signats';
                placeholderTitle.textContent = `${topText} — Outcomes`;
            }
        } else if (activeSidebarTab === 'impact') {
            topnavImpact.classList.remove('hidden');
            if (activeTopTab === 'contribucio-al') {
                impContPanel.classList.remove('hidden');
            } else if (activeTopTab === 'benefici-reputacional') {
                impRepPanel.classList.remove('hidden');
            } else {
                placeholderPanel.classList.remove('hidden');
                let topText = activeTopTab === 'beneficis-societat' ? 'Beneficis per a la societat' : 'Influència en noves polítiques';
                placeholderTitle.textContent = `${topText} — Impact`;
            }
        }

        // Lògica de visibilitat de filtres
        filtersBar.classList.remove('hidden');
        filterSelect.classList.remove('hidden');
    }
}

// Inicialització general en carregar la pàgina
document.addEventListener('DOMContentLoaded', () => {
    initCharts();
    initSlider();
    fetchResearchStructures();
    populateDepartmentSelect();
    fetchOpenAlexStats();
    fetchScopusStats();
    updateDashboard();
    renderActiveView();

    // Escoltar canvis al selector de departament
    document.getElementById('selectDept').addEventListener('change', () => {
        fetchIcreaStats();
        updateDashboard();
    });
});
