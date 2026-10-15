/**
 * cv-visual.js - Gestió del cercador, CV Visual i emulació del CVN FECYT
 */

let currentCvData = null;
let currentPersonUuid = null;
let currentViewMode = 'fecyt'; // 'fecyt' | 'visual'
let searchDebounceTimeout = null;
let timelineChartInstance = null;
let quartilesChartInstance = null;

// Inicialització en carregar la pàgina
document.addEventListener('DOMContentLoaded', () => {
    initSearch();
    initUrlParams();
    window.addEventListener('resize', handleResize);
});

function handleResize() {
    if (timelineChartInstance) timelineChartInstance.resize();
    if (quartilesChartInstance) quartilesChartInstance.resize();
}

/**
 * Revisa si hi ha un paràmetre `?uuid=...` a la URL
 */
function initUrlParams() {
    const urlParams = new URLSearchParams(window.location.search);
    const uuid = urlParams.get('uuid');
    const mode = urlParams.get('mode');
    if (mode === 'visual' || mode === 'fecyt') {
        currentViewMode = mode;
    }
    if (uuid && uuid.trim()) {
        loadPersonCv(uuid.trim());
    }
}

/**
 * Commuta entre el format oficial CVN FECYT i el Dashboard Visual Interactiu
 */
function setViewMode(mode) {
    currentViewMode = mode;
    const btnFecyt = document.getElementById('btnViewFecyt');
    const btnVisual = document.getElementById('btnViewVisual');
    const fecytContainer = document.getElementById('fecytContainer');
    const visualContainer = document.getElementById('visualDashboardContainer');

    if (mode === 'fecyt') {
        btnFecyt.className = 'px-4 py-2 rounded-lg text-xs font-bold transition flex items-center gap-2 bg-white text-emerald-800 shadow-sm border border-slate-200';
        btnVisual.className = 'px-4 py-2 rounded-lg text-xs font-bold transition flex items-center gap-2 text-slate-600 hover:text-slate-900';
        fecytContainer.classList.remove('hidden');
        visualContainer.classList.add('hidden');
    } else {
        btnVisual.className = 'px-4 py-2 rounded-lg text-xs font-bold transition flex items-center gap-2 bg-white text-indigo-800 shadow-sm border border-slate-200';
        btnFecyt.className = 'px-4 py-2 rounded-lg text-xs font-bold transition flex items-center gap-2 text-slate-600 hover:text-slate-900';
        visualContainer.classList.remove('hidden');
        fecytContainer.classList.add('hidden');
        setTimeout(() => handleResize(), 100);
    }
}

/**
 * Inicialitza la lògica del cercador reactiu (Omnibox)
 */
function initSearch() {
    const searchInput = document.getElementById('searchInput');
    const clearBtn = document.getElementById('clearSearchBtn');
    const dropdown = document.getElementById('searchResultsDropdown');

    if (!searchInput) return;

    searchInput.addEventListener('input', (e) => {
        const query = e.target.value.trim();
        if (query.length > 0) {
            clearBtn.classList.remove('hidden');
        } else {
            clearBtn.classList.add('hidden');
            dropdown.classList.add('hidden');
        }

        clearTimeout(searchDebounceTimeout);
        if (query.length >= 2) {
            searchDebounceTimeout = setTimeout(() => {
                fetchPersonSuggestions(query);
            }, 250);
        } else {
            dropdown.classList.add('hidden');
        }
    });

    clearBtn.addEventListener('click', () => {
        searchInput.value = '';
        clearBtn.classList.add('hidden');
        dropdown.classList.add('hidden');
        searchInput.focus();
    });

    // Tancar dropdown en fer clic fora
    document.addEventListener('click', (e) => {
        if (!searchInput.contains(e.target) && !dropdown.contains(e.target)) {
            dropdown.classList.add('hidden');
        }
    });

    // Permetre cercar en prémer Enter si hi ha resultats
    searchInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            const firstResult = dropdown.querySelector('.search-result-item');
            if (firstResult) {
                firstResult.click();
            }
        }
    });
}

function triggerQuickSearch(query) {
    const searchInput = document.getElementById('searchInput');
    if (!searchInput) return;
    searchInput.value = query;
    document.getElementById('clearSearchBtn').classList.remove('hidden');
    searchInput.focus();
    fetchPersonSuggestions(query);
}

/**
 * Consulta el backend per suggeriments de persones
 */
async function fetchPersonSuggestions(query) {
    const spinner = document.getElementById('searchSpinner');
    const dropdown = document.getElementById('searchResultsDropdown');
    const resultsList = document.getElementById('resultsList');

    spinner.classList.remove('hidden');

    try {
        const res = await apiFetch(`/persons/search/active?q=${encodeURIComponent(query)}`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();

        if (!data || data.length === 0) {
            resultsList.innerHTML = `
                <div class="px-4 py-3 text-xs text-slate-500 text-center">
                    No s'ha trobat cap persona coincident amb "<strong>${escapeHtml(query)}</strong>"
                </div>
            `;
            dropdown.classList.remove('hidden');
            return;
        }

        resultsList.innerHTML = data.map(item => {
            const initials = getInitials(item.nombre || 'Investigador');
            return `
                <div class="search-result-item px-4 py-3 hover:bg-emerald-50/60 cursor-pointer flex items-center justify-between gap-3 transition"
                     onclick="selectPerson('${item.uuid}', '${escapeHtml(item.nombre)}')">
                    <div class="flex items-center gap-3">
                        <div class="w-8 h-8 rounded-xl bg-emerald-100 text-emerald-800 text-xs font-bold flex items-center justify-center shrink-0">
                            ${initials}
                        </div>
                        <div>
                            <div class="text-sm font-bold text-slate-800">${escapeHtml(item.nombre)}</div>
                            <div class="text-[11px] text-slate-400 font-mono">UUID: ${escapeHtml(item.uuid)}</div>
                        </div>
                    </div>
                    <div class="text-xs font-semibold text-emerald-700 bg-emerald-50 px-2.5 py-1 rounded-lg border border-emerald-200 shrink-0">
                        Veure CV <i class="fa-solid fa-chevron-right text-[10px] ml-1"></i>
                    </div>
                </div>
            `;
        }).join('');

        dropdown.classList.remove('hidden');
    } catch (err) {
        console.error('Error cercant persones:', err);
        resultsList.innerHTML = `
            <div class="px-4 py-3 text-xs text-red-600 text-center">
                Error en consultar el cercador: ${err.message}
            </div>
        `;
        dropdown.classList.remove('hidden');
    } finally {
        spinner.classList.add('hidden');
    }
}

/**
 * Selecciona una persona del dropdown
 */
function selectPerson(uuid, nombre) {
    document.getElementById('searchResultsDropdown').classList.add('hidden');
    document.getElementById('searchInput').value = nombre;
    loadPersonCv(uuid);
}

/**
 * Carrega les dades completes del CV de la persona
 */
async function loadPersonCv(uuid) {
    if (!uuid) return;
    currentPersonUuid = uuid;

    // Actualitzar URL sense recarregar
    const newUrl = new URL(window.location.href);
    newUrl.searchParams.set('uuid', uuid);
    window.history.pushState({ uuid }, '', newUrl.toString());

    // Estats de vista
    document.getElementById('cvEmptyState').classList.add('hidden');
    document.getElementById('cvMainContainer').classList.add('hidden');
    document.getElementById('cvLoadingState').classList.remove('hidden');

    try {
        const res = await apiFetch(`/persons/${encodeURIComponent(uuid)}/cv-data`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();

        if (data.error) {
            throw new Error(data.error);
        }

        currentCvData = data;
        renderCvData(data);

        document.getElementById('cvLoadingState').classList.add('hidden');
        document.getElementById('cvMainContainer').classList.remove('hidden');

        setViewMode(currentViewMode);

        // Scroll suau cap al contingut
        document.getElementById('cvMainContainer').scrollIntoView({ behavior: 'smooth' });
    } catch (err) {
        console.error('Error carregant CV:', err);
        document.getElementById('cvLoadingState').classList.add('hidden');
        document.getElementById('cvEmptyState').classList.remove('hidden');
        alert(`No s'ha pogut carregar el CV de la persona seleccionada: ${err.message}`);
    }
}

/**
 * Renderitza totes les seccions del CV (CVN FECYT + Dashboard Visual)
 */
function renderCvData(data) {
    const profile = data.profile || {};
    const kpis = data.kpis || {};
    const charts = data.charts || {};

    const fullName = profile.fullName || 'Persona investigadora';

    // Etiqueta de selecció
    const statusTag = document.getElementById('statusSelectionTag');
    const selectedLabel = document.getElementById('selectedPersonLabel');
    selectedLabel.textContent = fullName;
    statusTag.classList.remove('hidden');

    // -------------------------------------------------------------------------
    // A. RENDERITZAT MODEL CVN FECYT
    // -------------------------------------------------------------------------
    renderFecytCvn(data);

    // -------------------------------------------------------------------------
    // B. RENDERITZAT DASHBOARD VISUAL INTERACTIU
    // -------------------------------------------------------------------------
    // 1. Capçalera de Perfil
    document.getElementById('profileFullName').textContent = fullName;
    document.getElementById('profileAvatar').textContent = getInitials(fullName);
    document.getElementById('profileCategory').textContent = profile.category || 'Investigador/a';
    document.getElementById('profileDepartment').querySelector('span').textContent = profile.primaryDepartment || 'Universitat Autònoma de Barcelona';

    const activeBadge = document.getElementById('profileActiveBadge');
    if (profile.isActive) {
        activeBadge.classList.remove('hidden');
    } else {
        activeBadge.classList.add('hidden');
    }

    // Identificadors
    const orcidLink = document.getElementById('profileOrcidLink');
    if (profile.orcid && profile.orcid.trim()) {
        orcidLink.href = `https://orcid.org/${encodeURIComponent(profile.orcid.trim())}`;
        document.getElementById('profileOrcidText').textContent = profile.orcid.trim();
        orcidLink.classList.remove('hidden');
    } else {
        orcidLink.classList.add('hidden');
    }

    const scopusLink = document.getElementById('profileScopusLink') || document.getElementById('profileScopusBadge');
    if (scopusLink && profile.scopusIds && profile.scopusIds.length > 0) {
        const sId = profile.scopusIds[0];
        document.getElementById('profileScopusText').textContent = sId;
        if (scopusLink.tagName === 'A') {
            scopusLink.href = `https://www.scopus.com/authid/detail.uri?authorId=${encodeURIComponent(sId)}`;
        }
        scopusLink.classList.remove('hidden');
    } else if (scopusLink) {
        scopusLink.classList.add('hidden');
    }

    document.getElementById('profilePureId').textContent = profile.pureId || profile.uuid.substring(0, 8);

    // 2. Scorecards KPI
    document.getElementById('kpiTotalPubs').textContent = (kpis.totalPublications || 0).toLocaleString();
    document.getElementById('kpiTotalArticles').textContent = (kpis.totalArticles || 0).toLocaleString();
    
    const pctQ1 = kpis.percentageQ1 || 0;
    document.getElementById('kpiPercentQ1').textContent = `${pctQ1}%`;
    document.getElementById('kpiCountQ1Label').textContent = `(${kpis.countQ1 || 0} en Q1)`;
    document.getElementById('kpiProgressBarQ1').style.width = `${Math.min(100, pctQ1)}%`;

    document.getElementById('kpiTotalFunding').textContent = formatCurrency(kpis.totalFundingAmount || 0);
    document.getElementById('kpiAwardsIp').textContent = (kpis.totalAwardsAsIp || 0).toLocaleString();

    document.getElementById('kpiTotalTheses').textContent = (kpis.totalTheses || 0).toLocaleString();

    // 2.1. Panell de Mètriques JCR
    const d1Count = kpis.countD1 || 0;
    const d1Pct = kpis.percentageD1 || 0;
    const q1Count = kpis.countQ1 || 0;
    const q1Pct = kpis.percentageQ1 || 0;
    const q2Count = kpis.countQ2 || 0;
    const q2Pct = kpis.percentageQ2 || 0;
    const avgJif = kpis.avgJif || 0;
    const maxJif = kpis.maxJif || 0;
    const maxJifJournal = kpis.maxJifJournal || '';
    const indexedJcr = kpis.totalIndexedJcr || 0;
    const indexedJcrPct = kpis.percentageIndexedJcr || 0;

    const elD1Count = document.getElementById('kpiD1Count');
    if (elD1Count) elD1Count.textContent = d1Count.toLocaleString();
    const elD1Pct = document.getElementById('kpiD1Pct');
    if (elD1Pct) elD1Pct.textContent = `${d1Pct}%`;

    const elQ1Count = document.getElementById('kpiQ1Count');
    if (elQ1Count) elQ1Count.textContent = q1Count.toLocaleString();
    const elQ1Pct = document.getElementById('kpiQ1Pct');
    if (elQ1Pct) elQ1Pct.textContent = `${q1Pct}%`;

    const elQ2Count = document.getElementById('kpiQ2Count');
    if (elQ2Count) elQ2Count.textContent = q2Count.toLocaleString();
    const elQ2Pct = document.getElementById('kpiQ2Pct');
    if (elQ2Pct) elQ2Pct.textContent = `${q2Pct}%`;

    const elAvgJif = document.getElementById('kpiAvgJif');
    if (elAvgJif) elAvgJif.textContent = avgJif > 0 ? avgJif.toFixed(3) : '0.000';

    const elMaxJif = document.getElementById('kpiMaxJif');
    if (elMaxJif) elMaxJif.textContent = maxJif > 0 ? maxJif.toFixed(3) : '0.000';

    const elMaxJifJournal = document.getElementById('kpiMaxJifJournal');
    if (elMaxJifJournal) {
        elMaxJifJournal.textContent = maxJifJournal ? maxJifJournal : '--';
        elMaxJifJournal.title = maxJifJournal || '';
    }

    const elJcrIdxCount = document.getElementById('kpiJcrIndexedCount');
    if (elJcrIdxCount) elJcrIdxCount.textContent = indexedJcr.toLocaleString();
    const elJcrIdxPct = document.getElementById('kpiJcrIndexedPct');
    if (elJcrIdxPct) elJcrIdxPct.textContent = `${indexedJcrPct}%`;

    // Categories temàtiques principals
    const catListEl = document.getElementById('jcrTopCategoriesList');
    if (catListEl) {
        const topCats = kpis.topJcrCategories || [];
        if (topCats.length > 0) {
            catListEl.innerHTML = topCats.map(c => `
                <span class="px-2 py-0.5 rounded-md bg-white/10 text-indigo-200 border border-white/10 font-medium">
                    ${escapeHtml(c.category)} <strong class="text-white ml-0.5">(${c.count})</strong>
                </span>
            `).join('');
        } else {
            catListEl.innerHTML = '<span class="text-slate-400 text-xs italic">Sense categories JCR registrades</span>';
        }
    }

    // 3. Comptadors de Pestanyes
    document.getElementById('tabCountPubs').textContent = (data.publications || []).length;
    document.getElementById('tabCountAwards').textContent = (data.awards || []).length;
    document.getElementById('tabCountTheses').textContent = (data.theses || []).length;
    document.getElementById('tabCountActivities').textContent = (data.activities || []).length;

    // 4. Renderitzar Gràfics
    renderTimelineChart(charts.timeline);
    renderQuartilesChart(charts.quartiles);

    // 5. Poblar filtres i llistes interactives
    populatePubTypesFilter(data.publications || []);
    renderFilteredPublications();
    renderAwardsList(data.awards || []);
    renderThesesList(data.theses || []);
    renderActivitiesList(data.activities || []);
}

/**
 * Renderitza la vista estructurada segons el model CVN FECYT
 */
function renderFecytCvn(data) {
    const profile = data.profile || {};
    const kpis = data.kpis || {};
    const pubs = data.publications || [];
    const awards = data.awards || [];
    const theses = data.theses || [];
    const activities = data.activities || [];

    // Data d'emissió
    document.getElementById('fecytCurrentDate').textContent = new Date().toLocaleDateString('ca-ES', {
        day: '2-digit', month: '2-digit', year: 'numeric'
    });

    // Secció A: Dades Personals
    document.getElementById('fecytFullName').textContent = profile.fullName || '--';
    document.getElementById('fecytOrcid').textContent = profile.orcid || 'No informat';
    const fecytScopusEl = document.getElementById('fecytScopus');
    if (profile.scopusIds && profile.scopusIds.length > 0) {
        fecytScopusEl.innerHTML = profile.scopusIds.map(s => `<a href="https://www.scopus.com/authid/detail.uri?authorId=${encodeURIComponent(s)}" target="_blank" class="text-indigo-600 hover:underline font-mono">${escapeHtml(s)}</a>`).join(', ');
    } else {
        fecytScopusEl.textContent = 'No informat';
    }
    document.getElementById('fecytPureId').textContent = profile.pureId || profile.uuid || '--';

    // Secció B: Situació Professional
    document.getElementById('fecytCategory').textContent = profile.category || 'Investigador/a';
    document.getElementById('fecytDepartment').textContent = profile.primaryDepartment || 'Universitat Autònoma de Barcelona';

    // Secció C: Resum Indicadors (FECYT)
    const totalPubs = kpis.totalPublications || 0;
    const totalArticles = kpis.totalArticles || 0;
    const countD1 = kpis.countD1 || 0;
    const pctD1 = kpis.percentageD1 || 0;
    const countQ1 = kpis.countQ1 || 0;
    const pctQ1 = kpis.percentageQ1 || 0;
    const countQ2 = kpis.countQ2 || 0;
    const pctQ2 = kpis.percentageQ2 || 0;
    const avgJif = kpis.avgJif || 0;
    const maxJif = kpis.maxJif || 0;
    const maxJifJournal = kpis.maxJifJournal || '';
    const indexedJcr = kpis.totalIndexedJcr || 0;
    const indexedJcrPct = kpis.percentageIndexedJcr || 0;

    document.getElementById('fecytIndTotalPubs').textContent = totalPubs.toLocaleString();
    document.getElementById('fecytIndTotalPubsDetail').textContent = `${totalArticles} articles en revistes científiques`;

    const elFecytD1 = document.getElementById('fecytIndD1Count');
    if (elFecytD1) elFecytD1.textContent = countD1.toLocaleString();
    const elFecytD1Pct = document.getElementById('fecytIndD1Pct');
    if (elFecytD1Pct) elFecytD1Pct.textContent = `${pctD1}% dels articles (Primer Decil / Top 10%)`;

    document.getElementById('fecytIndQ1Count').textContent = countQ1.toLocaleString();
    document.getElementById('fecytIndQ1Pct').textContent = `${pctQ1}% sobre el total d'articles publicats`;

    const elFecytQ2 = document.getElementById('fecytIndQ2Count');
    if (elFecytQ2) elFecytQ2.textContent = countQ2.toLocaleString();
    const elFecytQ2Pct = document.getElementById('fecytIndQ2Pct');
    if (elFecytQ2Pct) elFecytQ2Pct.textContent = `${pctQ2}% sobre el total d'articles publicats`;

    const elFecytAvgJif = document.getElementById('fecytIndAvgJif');
    if (elFecytAvgJif) elFecytAvgJif.textContent = avgJif > 0 ? avgJif.toFixed(3) : '0.000';
    const elFecytMaxJif = document.getElementById('fecytIndMaxJif');
    if (elFecytMaxJif) elFecytMaxJif.textContent = maxJif > 0 ? `Màxim assolit: ${maxJif.toFixed(3)}${maxJifJournal ? ' (' + maxJifJournal + ')' : ''}` : 'No disponible';

    const elFecytIdx = document.getElementById('fecytIndIndexedJcr');
    if (elFecytIdx) elFecytIdx.textContent = indexedJcr.toLocaleString();
    const elFecytIdxPct = document.getElementById('fecytIndIndexedJcrPct');
    if (elFecytIdxPct) elFecytIdxPct.textContent = `${indexedJcrPct}% del total d'articles`;

    document.getElementById('fecytIndAwardsIp').textContent = (kpis.totalAwardsAsIp || 0).toLocaleString();
    document.getElementById('fecytIndAwardsTotal').textContent = `${kpis.totalAwards || 0} projectes/ajuts totals registrats`;

    document.getElementById('fecytIndFunding').textContent = formatCurrency(kpis.totalFundingAmount || 0);
    document.getElementById('fecytIndTheses').textContent = (kpis.totalTheses || 0).toLocaleString();

    // Secció D: Detall normalitzat
    document.getElementById('fecytCountPubsBadge').textContent = `${pubs.length} publicacions registrades`;
    document.getElementById('fecytCountAwardsBadge').textContent = `${awards.length} projectes / ajuts`;
    document.getElementById('fecytCountThesesBadge').textContent = `${theses.length} tesis dirigides`;
    document.getElementById('fecytCountActivitiesBadge').textContent = `${activities.length} activitats`;

    // D.1. Publicacions
    const fecytPubsList = document.getElementById('fecytPubsList');
    if (pubs.length === 0) {
        fecytPubsList.innerHTML = '<div class="text-xs text-slate-400 py-2">Sense publicacions registrades.</div>';
    } else {
        fecytPubsList.innerHTML = pubs.map((p, idx) => {
            let citationParts = [];
            if (p.authors) citationParts.push(escapeHtml(p.authors));
            if (p.title) citationParts.push(`"${escapeHtml(p.title)}"`);
            if (p.journal) citationParts.push(`<em>${escapeHtml(p.journal)}</em>`);
            
            let volPages = [];
            if (p.volume) volPages.push(`vol. ${escapeHtml(p.volume)}`);
            if (p.issue) volPages.push(`no. ${escapeHtml(p.issue)}`);
            if (p.pages) volPages.push(`pp. ${escapeHtml(p.pages)}`);
            if (volPages.length > 0) citationParts.push(volPages.join(', '));

            if (p.year > 0) citationParts.push(`<strong>${p.year}</strong>`);

            const fullCitation = citationParts.join('. ') + '.';

            // Badges JCR detallats
            let jcrBadges = [];
            if (p.isD1) {
                jcrBadges.push(`<span class="px-2 py-0.5 text-[10px] font-bold rounded border bg-amber-100 text-amber-900 border-amber-300"><i class="fa-solid fa-crown mr-1"></i>D1 (Top 10%)</span>`);
            }
            if (p.quartile && p.quartile !== 'Sense Quartil' && p.quartile !== '-') {
                let badgeColor = 'bg-emerald-50 text-emerald-800 border-emerald-200';
                if (p.quartile === 'Q2') badgeColor = 'bg-blue-50 text-blue-800 border-blue-200';
                if (p.quartile === 'Q3') badgeColor = 'bg-amber-50 text-amber-800 border-amber-200';
                if (p.quartile === 'Q4') badgeColor = 'bg-red-50 text-red-800 border-red-200';
                jcrBadges.push(`<span class="px-2 py-0.5 text-[10px] font-bold rounded border ${badgeColor}">JCR: ${escapeHtml(p.quartile)}</span>`);
            }
            if (p.jifFormatted) {
                jcrBadges.push(`<span class="px-2 py-0.5 text-[10px] font-bold rounded border bg-indigo-50 text-indigo-800 border-indigo-200">JIF: ${escapeHtml(p.jifFormatted)}</span>`);
            }
            if (p.percentile) {
                jcrBadges.push(`<span class="px-2 py-0.5 text-[10px] font-medium rounded border bg-teal-50 text-teal-800 border-teal-200">Percentil: ${p.percentile}%</span>`);
            }
            if (p.jcrRank) {
                jcrBadges.push(`<span class="px-2 py-0.5 text-[10px] font-medium rounded border bg-slate-100 text-slate-700 border-slate-200">Posició: ${escapeHtml(p.jcrRank)}</span>`);
            }
            if (p.jcrCategory) {
                jcrBadges.push(`<span class="px-2 py-0.5 text-[10px] font-medium rounded border bg-purple-50 text-purple-800 border-purple-200">Àrea: ${escapeHtml(p.jcrCategory)}${p.jcrEdition ? ' (' + escapeHtml(p.jcrEdition) + ')' : ''}</span>`);
            }

            const doiPart = p.doi ? `
                <a href="${p.doi.startsWith('http') ? p.doi : 'https://doi.org/' + p.doi}" target="_blank" rel="noopener noreferrer" class="text-emerald-700 hover:underline font-mono text-[11px]">
                    DOI: ${escapeHtml(p.doi)}
                </a>
            ` : '';

            return `
                <div class="cvn-item-box flex items-start gap-3">
                    <span class="font-bold text-slate-400 font-mono shrink-0">[${idx + 1}]</span>
                    <div class="flex-1">
                        <div class="text-slate-800">${fullCitation}</div>
                        <div class="flex flex-wrap items-center gap-2 mt-1.5 text-xs">
                            <span class="text-slate-500 font-medium">${escapeHtml(p.type || 'Article')}</span>
                            ${jcrBadges.join(' ')}
                            ${p.openAccess ? '<span class="text-amber-700 bg-amber-50 border border-amber-200 px-1.5 py-0.2 rounded text-[10px] font-medium">Open Access</span>' : ''}
                            ${doiPart}
                        </div>
                    </div>
                </div>
            `;
        }).join('');
    }

    // D.2. Projectes
    const fecytAwardsList = document.getElementById('fecytAwardsList');
    if (awards.length === 0) {
        fecytAwardsList.innerHTML = '<div class="text-xs text-slate-400 py-2">Sense projectes registrats.</div>';
    } else {
        fecytAwardsList.innerHTML = awards.map((a, idx) => {
            const roleLabel = a.isIp ? '<strong class="text-purple-700">Investigador/a Principal (IP)</strong>' : `<span class="text-slate-700">${escapeHtml(a.role)}</span>`;
            const amountStr = a.amount > 0 ? `<strong>Import concedit:</strong> ${formatCurrency(a.amount)}` : '';
            const datesStr = (a.startDate || a.endDate) ? `<strong>Període:</strong> ${a.startDate || ''} - ${a.endDate || 'Vigent'}` : '';

            return `
                <div class="cvn-item-box flex items-start gap-3">
                    <span class="font-bold text-slate-400 font-mono shrink-0">[${idx + 1}]</span>
                    <div class="flex-1">
                        <div class="font-bold text-slate-900 text-sm mb-1">${escapeHtml(a.title)}</div>
                        <div class="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-x-4 gap-y-1 text-xs text-slate-600">
                            <div><strong>Referència:</strong> ${escapeHtml(a.awardId || a.uuid || 'N/D')}</div>
                            <div><strong>Entitat finançadora:</strong> ${escapeHtml(a.funder || 'UAB')}</div>
                            <div><strong>Tipus d'ajut:</strong> ${escapeHtml(a.type)}</div>
                            <div><strong>Participació:</strong> ${roleLabel}</div>
                            ${datesStr ? `<div>${datesStr}</div>` : ''}
                            ${amountStr ? `<div>${amountStr}</div>` : ''}
                        </div>
                    </div>
                </div>
            `;
        }).join('');
    }

    // D.3. Tesis
    const fecytThesesList = document.getElementById('fecytThesesList');
    if (theses.length === 0) {
        fecytThesesList.innerHTML = '<div class="text-xs text-slate-400 py-2">Sense tesis doctorals registrades.</div>';
    } else {
        fecytThesesList.innerHTML = theses.map((t, idx) => {
            return `
                <div class="cvn-item-box flex items-start gap-3">
                    <span class="font-bold text-slate-400 font-mono shrink-0">[${idx + 1}]</span>
                    <div class="flex-1">
                        <div class="font-bold text-slate-900 text-sm mb-0.5">"${escapeHtml(t.title)}"</div>
                        <div class="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-slate-600 mt-1">
                            <div><strong>Doctorand/a:</strong> ${escapeHtml(t.studentName)}</div>
                            <div><strong>Universitat:</strong> Universitat Autònoma de Barcelona</div>
                            <div><strong>Rol:</strong> ${escapeHtml(t.role || 'Director/a')}</div>
                            <div><strong>Any de lectura:</strong> ${t.year > 0 ? t.year : 'Defensada'}</div>
                        </div>
                    </div>
                </div>
            `;
        }).join('');
    }

    // D.4. Activitats
    const fecytActivitiesList = document.getElementById('fecytActivitiesList');
    if (activities.length === 0) {
        fecytActivitiesList.innerHTML = '<div class="text-xs text-slate-400 py-2">Sense activitats addicionals registrades.</div>';
    } else {
        fecytActivitiesList.innerHTML = activities.map((act, idx) => {
            const catStr = act.category ? `<span class="px-2 py-0.5 rounded text-[10px] font-bold bg-emerald-50 text-emerald-800 border border-emerald-200">${escapeHtml(act.category)}</span>` : '';
            const roleStr = act.role ? `<span class="text-xs font-semibold text-slate-700">(${escapeHtml(act.role)})</span>` : '';
            const recogStr = act.recognition ? `<span class="px-2 py-0.5 rounded text-[10px] font-semibold bg-indigo-50 text-indigo-700 border border-indigo-200">${escapeHtml(act.recognition)}</span>` : '';
            const descStr = (act.description && act.description !== act.title) ? `<div class="mt-1 text-xs text-slate-600"><strong>Descripció:</strong> ${escapeHtml(act.description)}</div>` : '';
            const dateStr = act.date ? `<div><strong>Periodo:</strong> <span class="text-slate-800">${escapeHtml(act.date)}</span></div>` : (act.year > 0 ? `<div><strong>Año:</strong> ${act.year}</div>` : '');

            const eventBlock = (act.eventName || act.congress) ? `
                <div class="mt-2.5 pt-2 border-t border-slate-200/70 text-xs">
                    <div class="text-[11px] font-bold text-slate-800 uppercase tracking-wider mb-0.5">Evento relacionado</div>
                    <div class="font-bold text-slate-900 text-xs">${escapeHtml(act.eventName || act.congress)}</div>
                    ${act.eventDate ? `<div class="text-slate-600 text-[11px]">${escapeHtml(act.eventDate)}</div>` : ''}
                    ${act.eventLocation ? `<div class="text-slate-600 text-[11px]">${escapeHtml(act.eventLocation)}</div>` : ''}
                    ${act.invitation ? `<div class="text-slate-500 text-[11px] mt-0.5">Tipo de inscripción (al evento): <span class="font-medium text-slate-700">${escapeHtml(act.invitation)}</span></div>` : ''}
                </div>
            ` : (act.place ? `<div class="text-xs text-slate-600 mt-1"><strong>Lloc / Seu:</strong> <span class="text-slate-800">${escapeHtml(act.place)}</span></div>` : '');

            return `
                <div class="cvn-item-box flex items-start gap-3">
                    <span class="font-bold text-slate-400 font-mono shrink-0">[${idx + 1}]</span>
                    <div class="flex-1">
                        <div class="flex flex-wrap items-center gap-2 mb-1">
                            <strong class="text-slate-900 text-sm font-bold">${escapeHtml(act.title)}</strong>
                            ${roleStr}
                            ${catStr}
                            <span class="px-2 py-0.5 rounded text-[10px] font-bold bg-slate-100 text-slate-700">${escapeHtml(act.type || 'Activitat')}</span>
                            ${recogStr}
                        </div>
                        <div class="text-xs text-slate-600 mt-0.5">
                            ${dateStr}
                        </div>
                        ${descStr}
                        ${eventBlock}
                        <div class="text-[10px] text-slate-400 mt-2">
                            Actividad: ${escapeHtml(act.type || '')} ${act.category ? ' &rsaquo; ' + escapeHtml(act.category) : ''}
                        </div>
                    </div>
                </div>
            `;
        }).join('');
    }
}

/**
 * Gràfic ECharts: Evolució temporal (Publicacions i Finançament)
 */
function renderTimelineChart(timeline) {
    const chartDom = document.getElementById('chartTimeline');
    if (!chartDom) return;

    if (timelineChartInstance) {
        timelineChartInstance.dispose();
    }
    timelineChartInstance = echarts.init(chartDom);

    if (!timeline || !timeline.years || timeline.years.length === 0) {
        timelineChartInstance.setOption({
            title: {
                text: 'Sense dades temporals suficients',
                left: 'center',
                top: 'middle',
                textStyle: { color: '#94a3b8', fontSize: 13 }
            }
        });
        return;
    }

    const option = {
        tooltip: {
            trigger: 'axis',
            axisPointer: { type: 'cross', crossStyle: { color: '#999' } }
        },
        legend: {
            data: ['Publicacions', 'Projectes I+D', 'Finançament (€)'],
            bottom: 0,
            textStyle: { fontSize: 11, color: '#64748b' }
        },
        grid: {
            top: '12%',
            left: '3%',
            right: '4%',
            bottom: '15%',
            containLabel: true
        },
        xAxis: [
            {
                type: 'category',
                data: timeline.years,
                axisPointer: { type: 'shadow' },
                axisLabel: { color: '#64748b', fontSize: 11 }
            }
        ],
        yAxis: [
            {
                type: 'value',
                name: 'Quantitat',
                min: 0,
                axisLabel: { color: '#64748b' },
                splitLine: { lineStyle: { type: 'dashed', color: '#f1f5f9' } }
            },
            {
                type: 'value',
                name: 'Euros (€)',
                min: 0,
                axisLabel: {
                    formatter: value => value >= 1000 ? `${(value / 1000).toFixed(0)}k€` : `${value}€`,
                    color: '#64748b'
                },
                splitLine: { show: false }
            }
        ],
        series: [
            {
                name: 'Publicacions',
                type: 'bar',
                data: timeline.publications,
                itemStyle: { color: '#10b981', borderRadius: [4, 4, 0, 0] }
            },
            {
                name: 'Projectes I+D',
                type: 'bar',
                data: timeline.awards,
                itemStyle: { color: '#6366f1', borderRadius: [4, 4, 0, 0] }
            },
            {
                name: 'Finançament (€)',
                type: 'line',
                yAxisIndex: 1,
                data: timeline.funding,
                smooth: true,
                itemStyle: { color: '#f59e0b' },
                lineStyle: { width: 3, color: '#f59e0b' }
            }
        ]
    };

    timelineChartInstance.setOption(option);
}

/**
 * Gràfic ECharts: Distribució per Quartils JCR (Doughnut)
 */
function renderQuartilesChart(quartiles) {
    const chartDom = document.getElementById('chartQuartiles');
    if (!chartDom) return;

    if (quartilesChartInstance) {
        quartilesChartInstance.dispose();
    }
    quartilesChartInstance = echarts.init(chartDom);

    if (!quartiles) {
        quartilesChartInstance.setOption({
            title: {
                text: 'Sense dades de quartils',
                left: 'center',
                top: 'middle',
                textStyle: { color: '#94a3b8', fontSize: 13 }
            }
        });
        return;
    }

    const dataSeries = [
        { value: quartiles['Q1'] || 0, name: 'Q1', itemStyle: { color: '#059669' } },
        { value: quartiles['Q2'] || 0, name: 'Q2', itemStyle: { color: '#2563eb' } },
        { value: quartiles['Q3'] || 0, name: 'Q3', itemStyle: { color: '#d97706' } },
        { value: quartiles['Q4'] || 0, name: 'Q4', itemStyle: { color: '#dc2626' } },
        { value: quartiles['Sense Quartil'] || 0, name: 'Sense JCR', itemStyle: { color: '#cbd5e1' } }
    ].filter(item => item.value > 0);

    const option = {
        tooltip: {
            trigger: 'item',
            formatter: '{b}: <strong>{c}</strong> ({d}%)'
        },
        legend: {
            bottom: '0',
            left: 'center',
            textStyle: { fontSize: 11, color: '#64748b' }
        },
        series: [
            {
                name: 'Quartil JCR',
                type: 'pie',
                radius: ['45%', '70%'],
                center: ['50%', '45%'],
                avoidLabelOverlap: false,
                itemStyle: {
                    borderRadius: 6,
                    borderColor: '#fff',
                    borderWidth: 2
                },
                label: {
                    show: false,
                    position: 'center'
                },
                emphasis: {
                    label: {
                        show: true,
                        fontSize: 14,
                        fontWeight: 'bold',
                        formatter: '{b}\n{c} ({d}%)'
                    }
                },
                data: dataSeries
            }
        ]
    };

    quartilesChartInstance.setOption(option);
}

/**
 * Poblega el selector de tipus de publicació
 */
function populatePubTypesFilter(publications) {
    const filterSelect = document.getElementById('filterPubType');
    if (!filterSelect) return;

    const types = new Set();
    publications.forEach(p => {
        if (p.type && p.type.trim()) types.add(p.type.trim());
    });

    filterSelect.innerHTML = '<option value="ALL">Tots els tipus</option>';
    Array.from(types).sort().forEach(type => {
        const opt = document.createElement('option');
        opt.value = type;
        opt.textContent = type;
        filterSelect.appendChild(opt);
    });
}

/**
 * Renderitza la llista de publicacions amb filtres locals
 */
function renderFilteredPublications() {
    if (!currentCvData || !currentCvData.publications) return;

    const typeFilter = document.getElementById('filterPubType').value;
    const quartileFilter = document.getElementById('filterPubQuartile').value;
    const container = document.getElementById('pubsListContainer');
    const countLabel = document.getElementById('filteredPubCount');

    let filtered = currentCvData.publications;

    if (typeFilter !== 'ALL') {
        filtered = filtered.filter(p => p.type === typeFilter);
    }

    if (quartileFilter !== 'ALL') {
        filtered = filtered.filter(p => p.quartile === quartileFilter);
    }

    countLabel.textContent = `Mostrant ${filtered.length} de ${currentCvData.publications.length}`;

    if (filtered.length === 0) {
        container.innerHTML = `
            <div class="py-8 text-center text-xs text-slate-400">
                No hi ha publicacions amb els filtres seleccionats.
            </div>
        `;
        return;
    }

    container.innerHTML = filtered.map(pub => {
        let qBadgeClass = 'no-q-badge';
        if (pub.quartile === 'Q1') qBadgeClass = 'q1-badge';
        else if (pub.quartile === 'Q2') qBadgeClass = 'q2-badge';
        else if (pub.quartile === 'Q3') qBadgeClass = 'q3-badge';
        else if (pub.quartile === 'Q4') qBadgeClass = 'q4-badge';

        const doiLink = pub.doi ? `
            <a href="${pub.doi.startsWith('http') ? pub.doi : 'https://doi.org/' + pub.doi}" target="_blank" rel="noopener noreferrer"
               class="inline-flex items-center gap-1 text-[11px] font-semibold text-emerald-700 hover:text-emerald-900 bg-emerald-50 px-2 py-0.5 rounded border border-emerald-200">
                <i class="fa-solid fa-arrow-up-right-from-square text-[10px]"></i> DOI
            </a>
        ` : '';

        const oaBadge = pub.openAccess ? `
            <span class="inline-flex items-center gap-1 text-[11px] font-medium text-amber-700 bg-amber-50 px-2 py-0.5 rounded border border-amber-200" title="Accés Obert">
                <i class="fa-solid fa-lock-open text-[10px]"></i> Open Access
            </span>
        ` : '';

        let jcrBadges = [];
        if (pub.isD1) {
            jcrBadges.push(`<span class="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[10px] font-bold bg-amber-100 text-amber-900 border border-amber-300 shadow-2xs"><i class="fa-solid fa-crown text-amber-600 text-[10px]"></i> D1 (Top 10%)</span>`);
        }
        if (pub.jifFormatted) {
            jcrBadges.push(`<span class="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[10px] font-bold bg-indigo-50 text-indigo-800 border border-indigo-200" title="Factor d'Impacte JIF (${pub.jcrYear || 'any'})"><i class="fa-solid fa-chart-line text-[10px]"></i> JIF: ${escapeHtml(pub.jifFormatted)}</span>`);
        }
        if (pub.percentile) {
            jcrBadges.push(`<span class="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-semibold bg-teal-50 text-teal-800 border border-teal-200" title="Percentil JIF">P${pub.percentile}%</span>`);
        }
        if (pub.jcrRank) {
            jcrBadges.push(`<span class="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-medium bg-slate-100 text-slate-700 border border-slate-200" title="Posició en la categoria">${escapeHtml(pub.jcrRank)}</span>`);
        }
        if (pub.jcrCategory) {
            jcrBadges.push(`<span class="inline-flex items-center px-2 py-0.5 rounded text-[10px] font-medium bg-purple-50 text-purple-800 border border-purple-200 truncate max-w-[220px]" title="Categoria JCR">${escapeHtml(pub.jcrCategory)}${pub.jcrEdition ? ' (' + escapeHtml(pub.jcrEdition) + ')' : ''}</span>`);
        }
        if (pub.jci) {
            jcrBadges.push(`<span class="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-medium bg-cyan-50 text-cyan-800 border border-cyan-200" title="Journal Citation Indicator">JCI: ${pub.jci}</span>`);
        }

        return `
            <div class="py-3.5 flex flex-col sm:flex-row sm:items-start justify-between gap-3 hover:bg-slate-50/70 rounded-xl px-2 transition border-b border-slate-100/80 last:border-0">
                <div class="flex-1">
                    <div class="flex flex-wrap items-center gap-1.5 mb-1.5">
                        <span class="px-2 py-0.5 rounded text-[10px] font-bold ${qBadgeClass}">
                            ${pub.quartile || 'Sense JCR'}
                        </span>
                        ${jcrBadges.join(' ')}
                        <span class="text-xs font-bold text-slate-500 ml-1">${pub.year > 0 ? pub.year : 'S/D'}</span>
                        <span class="text-xs text-slate-400">·</span>
                        <span class="text-xs font-medium text-slate-500">${escapeHtml(pub.type || 'Publicació')}</span>
                        ${oaBadge}
                    </div>
                    <h4 class="text-sm font-bold text-slate-900 leading-snug">
                        ${escapeHtml(pub.title || 'Sense títol')}
                    </h4>
                    <p class="text-xs text-slate-500 mt-1 italic line-clamp-2">
                        ${escapeHtml(pub.authors || 'Sense autors')}
                    </p>
                    ${pub.journal ? `<p class="text-xs font-semibold text-slate-700 mt-0.5"><i class="fa-solid fa-book-journal-whills text-slate-400 mr-1"></i> ${escapeHtml(pub.journal)}</p>` : ''}
                </div>
                <div class="shrink-0 flex sm:flex-col items-end gap-1.5 pt-1">
                    ${doiLink}
                </div>
            </div>
        `;
    }).join('');
}

/**
 * Renderitza la llista de projectes i ajuts
 */
function renderAwardsList(awards) {
    const container = document.getElementById('awardsListContainer');
    if (!container) return;

    if (!awards || awards.length === 0) {
        container.innerHTML = `
            <div class="py-8 text-center text-xs text-slate-400">
                No s'han trobat projectes o ajuts registrats per a aquesta persona.
            </div>
        `;
        return;
    }

    container.innerHTML = awards.map(award => {
        const isIp = award.isIp;
        const roleBadge = isIp
            ? `<span class="px-2.5 py-0.5 rounded-full text-[11px] font-bold bg-purple-50 text-purple-700 border border-purple-200"><i class="fa-solid fa-crown mr-1"></i> ${escapeHtml(award.role)}</span>`
            : `<span class="px-2.5 py-0.5 rounded-full text-[11px] font-medium bg-slate-100 text-slate-700">${escapeHtml(award.role)}</span>`;

        const amountFormatted = award.amount > 0 ? formatCurrency(award.amount) : 'Import no especificat';

        return `
            <div class="py-4 flex flex-col md:flex-row md:items-center justify-between gap-4 hover:bg-slate-50/70 rounded-xl px-2 transition">
                <div class="flex-1">
                    <div class="flex flex-wrap items-center gap-2 mb-1.5">
                        ${roleBadge}
                        <span class="text-xs font-bold text-slate-500">${award.year > 0 ? award.year : ''}</span>
                        ${award.awardId ? `<span class="text-xs font-mono text-slate-500 bg-slate-100 px-2 py-0.5 rounded">${escapeHtml(award.awardId)}</span>` : ''}
                    </div>
                    <h4 class="text-sm font-bold text-slate-900 leading-snug">
                        ${escapeHtml(award.title)}
                    </h4>
                    <div class="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-slate-500 mt-1.5">
                        <span><i class="fa-solid fa-layer-group text-slate-400 mr-1"></i> ${escapeHtml(award.type)}</span>
                        ${award.funder ? `<span><i class="fa-solid fa-landmark text-slate-400 mr-1"></i> ${escapeHtml(award.funder)}</span>` : ''}
                        ${award.startDate ? `<span><i class="fa-regular fa-calendar text-slate-400 mr-1"></i> ${award.startDate} - ${award.endDate || 'Vigent'}</span>` : ''}
                    </div>
                </div>
                <div class="shrink-0 text-right">
                    <div class="text-sm font-black text-slate-900">${amountFormatted}</div>
                </div>
            </div>
        `;
    }).join('');
}

/**
 * Renderitza la llista de tesis doctorals dirigides
 */
function renderThesesList(theses) {
    const container = document.getElementById('thesesListContainer');
    if (!container) return;

    if (!theses || theses.length === 0) {
        container.innerHTML = `
            <div class="py-8 text-center text-xs text-slate-400">
                No s'han trobat tesis doctorals registrades dirigides per aquesta persona.
            </div>
        `;
        return;
    }

    container.innerHTML = theses.map(thesis => {
        return `
            <div class="py-4 flex flex-col sm:flex-row sm:items-center justify-between gap-3 hover:bg-slate-50/70 rounded-xl px-2 transition">
                <div class="flex-1">
                    <div class="flex flex-wrap items-center gap-2 mb-1">
                        <span class="px-2.5 py-0.5 rounded-full text-[11px] font-bold bg-teal-50 text-teal-700 border border-teal-200">
                            <i class="fa-solid fa-user-tie mr-1"></i> ${escapeHtml(thesis.role || 'Director/a')}
                        </span>
                        <span class="text-xs font-bold text-slate-500">${thesis.year > 0 ? thesis.year : 'Defensada'}</span>
                    </div>
                    <h4 class="text-sm font-bold text-slate-900 leading-snug">
                        ${escapeHtml(thesis.title)}
                    </h4>
                    <p class="text-xs text-slate-600 mt-1">
                        Doctorand/a: <strong class="text-slate-800">${escapeHtml(thesis.studentName)}</strong>
                    </p>
                </div>
            </div>
        `;
    }).join('');
}

/**
 * Renderitza la llista d'activitats i mèrits
 */
function renderActivitiesList(activities) {
    const container = document.getElementById('activitiesListContainer');
    if (!container) return;

    if (!activities || activities.length === 0) {
        container.innerHTML = `
            <div class="py-8 text-center text-xs text-slate-400">
                No s'han trobat activitats o mèrits registrats.
            </div>
        `;
        return;
    }

    container.innerHTML = activities.map(act => {
        const catBadge = act.category ? `
            <span class="inline-flex items-center px-2.5 py-0.5 rounded-full text-[11px] font-bold bg-emerald-50 text-emerald-800 border border-emerald-200">
                ${escapeHtml(act.category)}
            </span>
        ` : '';

        const roleBadge = act.role ? `
            <span class="inline-flex items-center px-2.5 py-0.5 rounded-full text-[11px] font-semibold bg-amber-50 text-amber-800 border border-amber-200">
                ${escapeHtml(act.role)}
            </span>
        ` : '';

        const congressBadge = act.congress ? `
            <span class="inline-flex items-center gap-1 text-xs font-semibold text-indigo-900 bg-indigo-50 px-2.5 py-0.5 rounded-lg border border-indigo-200">
                <i class="fa-solid fa-users-rectangle text-indigo-600"></i> ${escapeHtml(act.congress)}
            </span>
        ` : '';

        const locationText = act.eventLocation || act.place;
        const placeBadge = locationText ? `
            <span class="inline-flex items-center gap-1 text-xs text-slate-700 bg-slate-100 px-2.5 py-0.5 rounded-lg border border-slate-200">
                <i class="fa-solid fa-location-dot text-emerald-600"></i> ${escapeHtml(locationText)}
            </span>
        ` : '';

        const dateBadge = (act.date || act.year > 0) ? `
            <span class="inline-flex items-center gap-1 text-xs text-slate-600 bg-slate-100 px-2.5 py-0.5 rounded-lg border border-slate-200">
                <i class="fa-regular fa-calendar text-slate-500"></i> ${escapeHtml(act.date || act.year)}
            </span>
        ` : '';

        const recogBadge = act.recognition ? `
            <span class="inline-flex items-center gap-1 text-xs font-semibold text-indigo-700 bg-indigo-50 px-2.5 py-0.5 rounded-lg border border-indigo-200">
                <i class="fa-solid fa-award text-indigo-500"></i> ${escapeHtml(act.recognition)}
            </span>
        ` : '';

        const descText = (act.description && act.description !== act.title) ? `
            <p class="text-xs text-slate-600 mt-2 leading-relaxed">
                ${escapeHtml(act.description)}
            </p>
        ` : '';

        const eventBlock = (act.eventName || act.congress) ? `
            <div class="mt-2.5 p-3 rounded-xl bg-indigo-50/40 border border-indigo-100 text-xs">
                <div class="text-[10px] font-bold text-indigo-900 uppercase tracking-wider mb-0.5">Evento relacionado</div>
                <div class="font-bold text-slate-900 text-xs">${escapeHtml(act.eventName || act.congress)}</div>
                ${act.eventDate ? `<div class="text-slate-600 text-[11px] mt-0.5">${escapeHtml(act.eventDate)}</div>` : ''}
                ${act.eventLocation ? `<div class="text-slate-600 text-[11px]">${escapeHtml(act.eventLocation)}</div>` : ''}
                ${act.invitation ? `<div class="text-slate-500 text-[11px] mt-1">Tipo de inscripción (al evento): <span class="font-medium text-slate-700">${escapeHtml(act.invitation)}</span></div>` : ''}
            </div>
        ` : '';

        return `
            <div class="py-4 hover:bg-slate-50/70 rounded-xl px-2 transition border-b border-slate-100 last:border-0">
                <div class="flex flex-wrap items-center gap-2 mb-1.5">
                    ${catBadge}
                    <span class="px-2.5 py-0.5 rounded-full text-[11px] font-bold bg-slate-100 text-slate-700">
                        ${escapeHtml(act.type || 'Activitat')}
                    </span>
                    ${roleBadge}
                    ${recogBadge}
                    ${dateBadge}
                    ${placeBadge}
                </div>
                <h4 class="text-sm font-bold text-slate-900 leading-snug">
                    ${escapeHtml(act.title)}
                </h4>
                ${descText}
                ${eventBlock}
                <div class="text-[10px] text-slate-400 mt-2">
                    Actividad: ${escapeHtml(act.type || '')} ${act.category ? ' &rsaquo; ' + escapeHtml(act.category) : ''}
                </div>
            </div>
        `;
    }).join('');
}

/**
 * Canvi de pestanyes en mode Dashboard
 */
function switchTab(tabId) {
    const tabs = ['pubs', 'awards', 'theses', 'activities'];
    tabs.forEach(t => {
        const btn = document.getElementById(`tabBtn${capitalize(t)}`);
        const content = document.getElementById(`tabContent${capitalize(t)}`);
        if (!btn || !content) return;

        if (t === tabId) {
            btn.classList.add('border-emerald-600', 'text-emerald-800');
            btn.classList.remove('border-transparent', 'text-slate-500');
            content.classList.remove('hidden');
        } else {
            btn.classList.remove('border-emerald-600', 'text-emerald-800');
            btn.classList.add('border-transparent', 'text-slate-500');
            content.classList.add('hidden');
        }
    });
}

/**
 * Descàrrega de l'informe oficial en Word (.docx)
 */
function descarregarWord() {
    if (!currentPersonUuid) return;
    const url = apiUrl(`/persons/reports/word/person?personUuid=${encodeURIComponent(currentPersonUuid)}`);
    window.open(url, '_blank');
}

/**
 * Copiar enllaç directe al perfil
 */
function copiarEnllacPerfil() {
    if (!currentPersonUuid) return;
    const fullUrl = `${window.location.origin}${window.location.pathname}?uuid=${encodeURIComponent(currentPersonUuid)}&mode=${currentViewMode}`;
    navigator.clipboard.writeText(fullUrl).then(() => {
        const textSpan = document.getElementById('btnCopyText');
        const oldText = textSpan.textContent;
        textSpan.textContent = 'Enllaç Copiat!';
        setTimeout(() => {
            textSpan.textContent = oldText;
        }, 2000);
    }).catch(err => {
        prompt('Copia aquest enllaç directe:', fullUrl);
    });
}

// -----------------------------------------------------------------------------
// Utilitats
// -----------------------------------------------------------------------------

function formatCurrency(val) {
    if (typeof val !== 'number') val = parseFloat(val) || 0;
    return new Intl.NumberFormat('ca-ES', { style: 'currency', currency: 'EUR', maximumFractionDigits: 0 }).format(val);
}

function getInitials(name) {
    if (!name) return 'UAB';
    const parts = name.trim().split(/[\s,]+/);
    if (parts.length >= 2) {
        return (parts[0][0] + parts[1][0]).toUpperCase();
    }
    return name.substring(0, 2).toUpperCase();
}

function capitalize(s) {
    return s.charAt(0).toUpperCase() + s.slice(1);
}

function escapeHtml(str) {
    if (!str) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}
