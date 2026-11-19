(function (global) {
    'use strict';

    function escapeHtml(value) {
        if (value === null || value === undefined) return '';
        return String(value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function resolveBatchDurationForItem(item, globalDurationValue) {
        if (item && item.localDurationOverride !== null && item.localDurationOverride !== undefined) {
            return item.localDurationOverride;
        }
        if (globalDurationValue !== null && globalDurationValue !== undefined && globalDurationValue !== '') {
            return Number(globalDurationValue);
        }
        return null;
    }

    if (typeof document === 'undefined') {
        if (typeof module !== 'undefined' && module.exports) {
            module.exports = { escapeHtml, resolveBatchDurationForItem };
        }
        return;
    }

    const MAX_CONCURRENCY = 3;

    const state = {
        projects: [],
        selectedId: null,
        kpiStatusFilter: 'ALL',
        nextId: 1,
        rulesCache: null
    };

    const STATUS_RANK = { NO_OK: 0, AVIS: 1, EXTERN: 2, OK: 3, ERROR: 4 };
    const ROLE_RANK = { IP: 0, EQUIPO_INVESTIGACION: 1, EQUIPO_TRABAJO: 2 };

    const ROLE_LABEL = {
        IP: 'IP',
        EQUIPO_INVESTIGACION: 'EI',
        EQUIPO_TRABAJO: 'ET'
    };

    const MATCH_BADGE = {
        NIF_EXACTE: { label: 'NIF exacte', cls: 'bg-emerald-50 text-emerald-700 border-emerald-200' },
        NOM_EXACTE: { label: 'Nom exacte', cls: 'bg-sky-50 text-sky-700 border-sky-200' },
        NOM_APROXIMAT: { label: 'Nom aproximat', cls: 'bg-amber-50 text-amber-700 border-amber-200' },
        AMBIGU: { label: 'Ambigu', cls: 'bg-amber-100 text-amber-800 border-amber-300' },
        NO_TROBAT: { label: 'No trobat', cls: 'bg-slate-100 text-slate-600 border-slate-300' }
    };

    const STATUS_BADGE = {
        OK: { label: 'OK', cls: 'bg-emerald-100 text-emerald-800 border-emerald-300', icon: 'fa-circle-check' },
        AVIS: { label: 'AVÍS', cls: 'bg-amber-100 text-amber-800 border-amber-300', icon: 'fa-triangle-exclamation' },
        NO_OK: { label: 'NO OK', cls: 'bg-rose-100 text-rose-800 border-rose-300', icon: 'fa-circle-xmark' },
        EXTERN: { label: 'EXTERN', cls: 'bg-sky-100 text-sky-800 border-sky-300', icon: 'fa-building' },
        ERROR: { label: 'ERROR', cls: 'bg-rose-100 text-rose-900 border-rose-300', icon: 'fa-ban' }
    };

    const el = {};

    function cacheElements() {
        const ids = [
            'inputReferenceDate', 'selectDefaultDuration', 'btnRecalculateBatch', 'btnOpenRulesModal', 'btnExportExcel',
            'dropZone', 'pdfFileInput', 'btnSelectFiles', 'btnClearBatch',
            'progressContainer', 'progressBar', 'progressText', 'progressPercent',
            'batchErrorBanner', 'batchErrorText', 'btnDismissBatchError',
            'kpiProjectsCard', 'kpiOkCard', 'kpiAvisCard', 'kpiNoOkCard', 'kpiMembersCard',
            'kpiProjectsValue', 'kpiOkValue', 'kpiAvisValue', 'kpiNoOkValue', 'kpiMembersValue',
            'projectSearchInput', 'projectStatusFilter', 'projectListContainer', 'projectListCount',
            'emptyDetailState', 'errorDetailContainer', 'errorDetailFileName', 'errorDetailMessage',
            'projectDetailContainer', 'detailTitle', 'detailProjectCode', 'detailAcronym',
            'detailApplicantEntity', 'detailOverallBadge', 'detailExtractionBadge', 'detailProjectPeriod',
            'detailWarningsBox', 'detailDurationSelect', 'roleFilter', 'memberStatusFilter',
            'teamTableBody', 'teamEmptyRow',
            'btnToggleDiagnostic', 'diagnosticPanel', 'diagnosticWarnings', 'diagnosticPreview',
            'rulesModal', 'btnCloseRulesModal', 'btnCloseRulesModalFooter', 'rulesModalContent'
        ];
        ids.forEach(id => {
            el[id] = document.getElementById(id);
        });
    }

    function initDefaults() {
        const today = new Date().toISOString().slice(0, 10);
        if (el.inputReferenceDate && !el.inputReferenceDate.value) {
            el.inputReferenceDate.value = today;
        }
    }

    function bindEvents() {
        el.btnSelectFiles.addEventListener('click', (e) => {
            e.stopPropagation();
            el.pdfFileInput.click();
        });

        el.dropZone.addEventListener('click', () => el.pdfFileInput.click());

        el.pdfFileInput.addEventListener('change', (e) => {
            if (e.target.files && e.target.files.length > 0) {
                handleFiles(Array.from(e.target.files));
                el.pdfFileInput.value = '';
            }
        });

        el.dropZone.addEventListener('dragover', (e) => {
            e.preventDefault();
            el.dropZone.classList.add('drop-zone-active');
        });

        el.dropZone.addEventListener('dragleave', () => {
            el.dropZone.classList.remove('drop-zone-active');
        });

        el.dropZone.addEventListener('drop', (e) => {
            e.preventDefault();
            el.dropZone.classList.remove('drop-zone-active');
            const files = e.dataTransfer && e.dataTransfer.files ? Array.from(e.dataTransfer.files) : [];
            if (files.length > 0) {
                handleFiles(files);
            }
        });

        el.btnClearBatch.addEventListener('click', (e) => {
            e.stopPropagation();
            state.projects = [];
            state.selectedId = null;
            hideBatchError();
            renderAll();
        });

        el.btnDismissBatchError.addEventListener('click', hideBatchError);

        el.btnRecalculateBatch.addEventListener('click', recalculateBatch);
        el.btnExportExcel.addEventListener('click', exportExcel);

        el.projectSearchInput.addEventListener('input', renderProjectList);
        el.projectStatusFilter.addEventListener('change', () => {
            state.kpiStatusFilter = el.projectStatusFilter.value;
            renderKpis();
            renderProjectList();
        });

        [el.kpiProjectsCard, el.kpiOkCard, el.kpiAvisCard, el.kpiNoOkCard, el.kpiMembersCard].forEach(card => {
            card.addEventListener('click', () => {
                const f = card.getAttribute('data-filter') || 'ALL';
                state.kpiStatusFilter = f;
                el.projectStatusFilter.value = f;
                renderKpis();
                renderProjectList();
            });
        });

        el.roleFilter.addEventListener('change', renderDetail);
        el.memberStatusFilter.addEventListener('change', renderDetail);
        el.detailDurationSelect.addEventListener('change', onLocalDurationChange);

        el.btnToggleDiagnostic.addEventListener('click', () => {
            el.diagnosticPanel.classList.toggle('hidden');
        });

        el.btnOpenRulesModal.addEventListener('click', openRulesModal);
        el.btnCloseRulesModal.addEventListener('click', closeRulesModal);
        el.btnCloseRulesModalFooter.addEventListener('click', closeRulesModal);
        el.rulesModal.addEventListener('click', (e) => {
            if (e.target === el.rulesModal) closeRulesModal();
        });
    }

    async function handleFiles(files) {
        const refDate = el.inputReferenceDate.value || '';
        const durationOverride = el.selectDefaultDuration.value || '';
        let completed = 0;
        const total = files.length;

        showProgress(0, total);

        const queue = files.map(file => ({
            id: state.nextId++,
            file
        }));

        async function worker() {
            while (queue.length > 0) {
                const task = queue.shift();
                await uploadSinglePdf(task.id, task.file, refDate, durationOverride);
                completed++;
                showProgress(completed, total);
                renderAll();
            }
        }

        const workers = [];
        const poolSize = Math.min(MAX_CONCURRENCY, files.length);
        for (let i = 0; i < poolSize; i++) {
            workers.push(worker());
        }
        await Promise.all(workers);

        setTimeout(() => {
            el.progressContainer.classList.add('hidden');
        }, 500);
    }

    async function uploadSinglePdf(id, file, refDate, durationOverride) {
        const formData = new FormData();
        formData.append('file', file);
        if (refDate) formData.append('referenceDate', refDate);
        if (durationOverride) formData.append('durationYears', durationOverride);

        try {
            const resp = await global.apiFetch('/plan-nacional/validar', {
                method: 'POST',
                body: formData
            });
            const data = await safeJson(resp);
            if (!resp.ok) {
                const msg = (data && data.message) ? data.message : ('Error HTTP ' + resp.status);
                state.projects.push({
                    id,
                    fileName: file.name,
                    error: msg,
                    response: null,
                    localDurationOverride: null
                });
            } else {
                state.projects.push({
                    id,
                    fileName: data.fileName || file.name,
                    error: null,
                    response: data,
                    localDurationOverride: null
                });
            }
        } catch (err) {
            state.projects.push({
                id,
                fileName: file.name,
                error: err && err.message ? err.message : 'Error de connexió amb el servidor.',
                response: null,
                localDurationOverride: null
            });
        }

        if (state.selectedId === null) {
            state.selectedId = id;
        }
    }

    function toExtractedProposal(res) {
        return {
            fileName: res.fileName,
            projectCode: res.projectCode,
            acronym: res.acronym,
            title: res.title,
            durationYears: res.durationYears,
            expectedStartDate: res.expectedStartDate,
            expectedEndDate: res.expectedEndDate,
            applicantEntity: res.applicantEntity,
            extractionMethod: res.extractionMethod,
            warnings: res.extractionWarnings || [],
            rawTextPreview: res.rawTextPreview || '',
            members: (res.members || []).map(m => m.extractedMember)
        };
    }

    function showBatchError(msg) {
        if (!el.batchErrorBanner || !el.batchErrorText) return;
        el.batchErrorText.textContent = msg;
        el.batchErrorBanner.classList.remove('hidden');
    }

    function hideBatchError() {
        if (!el.batchErrorBanner) return;
        el.batchErrorBanner.classList.add('hidden');
        if (el.batchErrorText) el.batchErrorText.textContent = '';
    }

    async function reevaluateItem(item, refDate, durationYears) {
        if (!item || !item.response) return { ok: true };
        const payload = {
            proposal: toExtractedProposal(item.response),
            referenceDate: refDate || null,
            durationYears: durationYears || null
        };
        try {
            const resp = await global.apiFetch('/plan-nacional/validar-equipo', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const data = await safeJson(resp);
            if (!resp.ok || !data) {
                const msg = (data && data.message) ? data.message : ('Error HTTP ' + resp.status);
                return { ok: false, error: msg };
            }
            item.response = data;
            item.error = null;
            return { ok: true };
        } catch (err) {
            return { ok: false, error: (err && err.message) ? err.message : 'Error de connexió en recalcular.' };
        }
    }

    async function recalculateBatch() {
        const validItems = state.projects.filter(p => p.response);
        if (validItems.length === 0) return;

        const refDate = el.inputReferenceDate.value || null;
        const globalDur = el.selectDefaultDuration.value || '';

        hideBatchError();
        el.btnRecalculateBatch.disabled = true;
        showProgress(0, validItems.length);
        let done = 0;
        const failures = [];

        try {
            for (const item of validItems) {
                const effDur = resolveBatchDurationForItem(item, globalDur);
                const result = await reevaluateItem(item, refDate, effDur);
                if (!result.ok) {
                    failures.push(item.fileName + ': ' + result.error);
                }
                done++;
                showProgress(done, validItems.length);
            }
        } finally {
            el.btnRecalculateBatch.disabled = false;
            setTimeout(() => el.progressContainer.classList.add('hidden'), 400);
            if (failures.length > 0) {
                showBatchError('No s\'han pogut recalcular alguns projectes: ' + failures.join(' | '));
            }
            renderAll();
        }
    }

    async function onLocalDurationChange() {
        const item = getSelectedProject();
        if (!item || !item.response) return;
        const prevOverride = item.localDurationOverride;
        const val = el.detailDurationSelect.value;
        item.localDurationOverride = val ? Number(val) : null;
        const effDur = resolveBatchDurationForItem(item, el.selectDefaultDuration.value || '');
        hideBatchError();
        const result = await reevaluateItem(item, el.inputReferenceDate.value || null, effDur);
        if (!result.ok) {
            item.localDurationOverride = prevOverride;
            showBatchError('Error recalculant "' + item.fileName + '": ' + result.error);
        }
        renderAll();
    }

    function showProgress(done, total) {
        el.progressContainer.classList.remove('hidden');
        const pct = total > 0 ? Math.round((done / total) * 100) : 0;
        el.progressText.textContent = 'Validant ' + done + ' de ' + total + ' PDFs...';
        el.progressPercent.textContent = pct + '%';
        el.progressBar.style.width = pct + '%';
    }

    function getSelectedProject() {
        return state.projects.find(p => p.id === state.selectedId) || null;
    }

    function getProjectStatus(item) {
        if (item.error) return 'ERROR';
        return item.response ? item.response.overallStatus : 'ERROR';
    }

    function renderAll() {
        const hasAny = state.projects.length > 0;
        const hasValid = state.projects.some(p => p.response);
        el.btnClearBatch.disabled = !hasAny;
        el.btnRecalculateBatch.disabled = !hasValid;
        el.btnExportExcel.disabled = !hasValid;

        renderKpis();
        renderProjectList();
        renderDetail();
    }

    function renderKpis() {
        let okCount = 0;
        let avisCount = 0;
        let noOkCount = 0;
        let membersCount = 0;

        state.projects.forEach(p => {
            if (p.error || !p.response) return;
            if (p.response.overallStatus === 'OK') okCount++;
            else if (p.response.overallStatus === 'AVIS') avisCount++;
            else if (p.response.overallStatus === 'NO_OK') noOkCount++;
            membersCount += (p.response.summaryCounts ? p.response.summaryCounts.total : 0);
        });

        el.kpiProjectsValue.textContent = String(state.projects.length);
        el.kpiOkValue.textContent = String(okCount);
        el.kpiAvisValue.textContent = String(avisCount);
        el.kpiNoOkValue.textContent = String(noOkCount);
        el.kpiMembersValue.textContent = String(membersCount);

        [el.kpiProjectsCard, el.kpiOkCard, el.kpiAvisCard, el.kpiNoOkCard, el.kpiMembersCard].forEach(card => {
            const f = card.getAttribute('data-filter');
            const isActive = f === state.kpiStatusFilter && (card !== el.kpiMembersCard);
            card.classList.toggle('kpi-card-active', isActive);
        });
    }

    function renderProjectList() {
        const search = (el.projectSearchInput.value || '').trim().toLowerCase();
        const statusFilter = el.projectStatusFilter.value || 'ALL';

        const filtered = state.projects.filter(item => {
            const st = getProjectStatus(item);
            if (statusFilter !== 'ALL' && st !== statusFilter) return false;
            if (!search) return true;
            const hay = [
                item.fileName,
                item.response && item.response.projectCode,
                item.response && item.response.acronym,
                item.response && item.response.title
            ].filter(Boolean).join(' ').toLowerCase();
            return hay.includes(search);
        });

        el.projectListCount.textContent = String(filtered.length);

        if (filtered.length === 0) {
            el.projectListContainer.innerHTML = '<div class="text-center py-10 px-4 text-xs text-slate-400">Cap projecte coincideix amb el filtre.</div>';
            return;
        }

        el.projectListContainer.innerHTML = filtered.map(item => {
            const st = getProjectStatus(item);
            const badge = STATUS_BADGE[st] || STATUS_BADGE.ERROR;
            const activeCls = item.id === state.selectedId
                ? 'bg-emerald-50/70 border-emerald-500 ring-1 ring-emerald-500'
                : 'bg-white border-slate-200 hover:bg-slate-50';

            const res = item.response;
            const subtitle = item.error
                ? '<span class="text-rose-600 font-medium">' + escapeHtml(item.error) + '</span>'
                : '<span class="font-mono text-slate-600">' + escapeHtml(res.projectCode || 'Sense codi') + '</span>'
                  + (res.acronym ? ' · <span class="font-semibold text-emerald-800">' + escapeHtml(res.acronym) + '</span>' : '');

            const countsHtml = res && res.summaryCounts
                ? '<div class="flex items-center gap-1.5 text-[11px] font-semibold mt-1.5 tabular-nums">'
                  + '<span class="text-rose-700">' + res.summaryCounts.noOk + ' NO OK</span>'
                  + '<span class="text-slate-300">·</span>'
                  + '<span class="text-amber-700">' + res.summaryCounts.avis + ' AVÍS</span>'
                  + '<span class="text-slate-300">·</span>'
                  + '<span class="text-emerald-700">' + res.summaryCounts.ok + ' OK</span>'
                  + '</div>'
                : '';

            return '<div class="p-3 rounded-xl border transition cursor-pointer ' + activeCls + '" data-project-id="' + item.id + '">'
                + '<div class="flex items-start justify-between gap-2">'
                + '<div class="min-w-0 flex-1">'
                + '<p class="text-xs font-bold text-slate-900 truncate" title="' + escapeHtml(item.fileName) + '">' + escapeHtml(item.fileName) + '</p>'
                + '<p class="text-[11px] mt-0.5 truncate">' + subtitle + '</p>'
                + '</div>'
                + '<div class="flex items-center gap-1.5 shrink-0">'
                + '<span class="inline-flex items-center gap-1 px-2 py-0.5 rounded-lg text-[10px] font-extrabold border ' + badge.cls + '">'
                + '<i class="fa-solid ' + badge.icon + '"></i>' + badge.label + '</span>'
                + '<button type="button" data-remove-id="' + item.id + '" title="Treure del lot" '
                + 'class="w-6 h-6 rounded-lg text-slate-400 hover:text-rose-600 hover:bg-rose-50 flex items-center justify-center">'
                + '<i class="fa-solid fa-xmark text-xs"></i></button>'
                + '</div>'
                + '</div>'
                + countsHtml
                + '</div>';
        }).join('');

        el.projectListContainer.querySelectorAll('[data-project-id]').forEach(node => {
            node.addEventListener('click', () => {
                state.selectedId = Number(node.getAttribute('data-project-id'));
                renderProjectList();
                renderDetail();
            });
        });

        el.projectListContainer.querySelectorAll('[data-remove-id]').forEach(btn => {
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                const remId = Number(btn.getAttribute('data-remove-id'));
                state.projects = state.projects.filter(p => p.id !== remId);
                if (state.selectedId === remId) {
                    state.selectedId = state.projects.length > 0 ? state.projects[0].id : null;
                }
                renderAll();
            });
        });
    }

    function renderDetail() {
        const item = getSelectedProject();
        if (!item) {
            el.emptyDetailState.classList.remove('hidden');
            el.errorDetailContainer.classList.add('hidden');
            el.projectDetailContainer.classList.add('hidden');
            return;
        }

        el.emptyDetailState.classList.add('hidden');

        if (item.error || !item.response) {
            el.projectDetailContainer.classList.add('hidden');
            el.errorDetailContainer.classList.remove('hidden');
            el.errorDetailFileName.textContent = item.fileName || '--';
            el.errorDetailMessage.textContent = item.error || 'Error desconegut.';
            return;
        }

        el.errorDetailContainer.classList.add('hidden');
        el.projectDetailContainer.classList.remove('hidden');

        const res = item.response;
        el.detailProjectCode.textContent = res.projectCode || 'Sense codi';
        el.detailAcronym.textContent = res.acronym || res.fileName || '--';
        el.detailTitle.textContent = res.title || res.fileName || '--';
        el.detailApplicantEntity.textContent = res.applicantEntity || 'Entitat no especificada';
        el.detailProjectPeriod.textContent = 'Ref: ' + (res.referenceDate || '--')
                + ' · Durada: ' + res.effectiveDurationYears + ' anys'
                + ' · Fi prevista: ' + (res.effectiveProjectEndDate || '--');

        el.detailExtractionBadge.textContent = res.extractionMethod === 'VLLM_FALLBACK'
                ? 'IA fallback (reviseu)'
                : 'Parser AEI';

        const overallBadge = STATUS_BADGE[res.overallStatus] || STATUS_BADGE.AVIS;
        el.detailOverallBadge.className = 'inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-extrabold border ' + overallBadge.cls;
        el.detailOverallBadge.innerHTML = '<i class="fa-solid ' + overallBadge.icon + '"></i><span>' + overallBadge.label + '</span>';

        el.detailDurationSelect.value = item.localDurationOverride ? String(item.localDurationOverride) : '';

        const warnings = res.extractionWarnings || [];
        if (warnings.length > 0) {
            el.detailWarningsBox.classList.remove('hidden');
            el.detailWarningsBox.innerHTML = warnings.map(w => '<div><i class="fa-solid fa-circle-info mr-1.5"></i>' + escapeHtml(w) + '</div>').join('');
        } else {
            el.detailWarningsBox.classList.add('hidden');
            el.detailWarningsBox.innerHTML = '';
        }

        el.diagnosticWarnings.innerHTML = warnings.length > 0
                ? warnings.map(w => '<div>• ' + escapeHtml(w) + '</div>').join('')
                : '<span class="text-slate-400">Cap avís d\'extracció.</span>';
        el.diagnosticPreview.textContent = res.rawTextPreview || '(Sense text previsualitzat)';

        renderTeamTable(res.members || []);
    }

    function renderTeamTable(members) {
        const roleFilter = el.roleFilter.value || 'ALL';
        const statusFilter = el.memberStatusFilter.value || 'ALL';

        const filtered = members.filter(m => {
            const role = m.extractedMember ? m.extractedMember.role : '';
            if (roleFilter !== 'ALL' && role !== roleFilter) return false;
            if (statusFilter !== 'ALL' && m.status !== statusFilter) return false;
            return true;
        });

        filtered.sort((a, b) => {
            const ra = ROLE_RANK[a.extractedMember?.role] ?? 9;
            const rb = ROLE_RANK[b.extractedMember?.role] ?? 9;
            if (ra !== rb) return ra - rb;
            const sa = STATUS_RANK[a.status] ?? 9;
            const sb = STATUS_RANK[b.status] ?? 9;
            return sa - sb;
        });

        // Eliminar files anteriors preservant teamEmptyRow
        Array.from(el.teamTableBody.querySelectorAll('tr.team-row')).forEach(tr => tr.remove());

        if (filtered.length === 0) {
            el.teamEmptyRow.classList.remove('hidden');
            return;
        }
        el.teamEmptyRow.classList.add('hidden');

        const rowsHtml = filtered.map(m => buildMemberRowHtml(m)).join('');
        el.teamEmptyRow.insertAdjacentHTML('beforebegin', rowsHtml);

        el.teamTableBody.querySelectorAll('[data-toggle-contracts]').forEach(btn => {
            btn.addEventListener('click', () => {
                const target = document.getElementById(btn.getAttribute('data-toggle-contracts'));
                if (target) target.classList.toggle('hidden');
            });
        });

        el.teamTableBody.querySelectorAll('[data-open-egreta]').forEach(btn => {
            btn.addEventListener('click', () => {
                const url = btn.getAttribute('data-open-egreta');
                if (url) {
                    window.open(url, 'EgretaPersonEditor', 'width=1240,height=840,resizable=yes,scrollbars=yes');
                }
            });
        });
    }

    function buildMemberRowHtml(m) {
        const ext = m.extractedMember || {};
        const roleLabel = ROLE_LABEL[ext.role] || ext.role || '--';
        const matchBadge = MATCH_BADGE[m.matchConfidence] || MATCH_BADGE.NO_TROBAT;
        const statusBadge = STATUS_BADGE[m.status] || STATUS_BADGE.AVIS;

        const egretaHtml = buildEgretaCellHtml(m);
        const actionHtml = m.egretaEditorUrl
            ? '<button type="button" data-open-egreta="' + escapeHtml(m.egretaEditorUrl) + '" '
              + 'class="inline-flex items-center gap-1 px-2.5 py-1 bg-emerald-50 hover:bg-emerald-100 border border-emerald-200 text-emerald-700 rounded-lg font-bold transition">'
              + '<i class="fa-solid fa-pen-to-square"></i> Editor Egreta</button>'
            : '<span class="text-slate-300">—</span>';

        return '<tr class="team-row hover:bg-slate-50/70 align-top">'
            + '<td class="py-3 px-4"><span class="px-2 py-0.5 rounded-md bg-slate-100 text-slate-700 font-bold">' + escapeHtml(roleLabel) + '</span></td>'
            + '<td class="py-3 px-4 space-y-1">'
            + '<div class="font-bold text-slate-900">' + escapeHtml(ext.fullName || '--') + '</div>'
            + '<div class="text-[11px] text-slate-500 flex flex-wrap items-center gap-1.5">'
            + (ext.nif ? '<span class="font-mono bg-slate-100 px-1.5 py-0.5 rounded">' + escapeHtml(ext.nif) + '</span>' : '')
            + (ext.entity ? '<span>' + escapeHtml(ext.entity) + '</span>' : '')
            + '</div>'
            + '<div><span class="inline-block px-2 py-0.5 rounded-md border text-[10px] font-semibold ' + matchBadge.cls + '">' + escapeHtml(matchBadge.label) + '</span></div>'
            + '</td>'
            + '<td class="py-3 px-4">' + egretaHtml + '</td>'
            + '<td class="py-3 px-4">'
            + '<span class="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg text-[11px] font-extrabold border ' + statusBadge.cls + '">'
            + '<i class="fa-solid ' + statusBadge.icon + '"></i>' + escapeHtml(statusBadge.label) + '</span>'
            + '</td>'
            + '<td class="py-3 px-4 text-slate-700 max-w-xs">' + escapeHtml(m.reasonMessage || '--') + '</td>'
            + '<td class="py-3 px-4 text-right whitespace-nowrap">' + actionHtml + '</td>'
            + '</tr>';
    }

    function buildEgretaCellHtml(m) {
        if (m.matchConfidence === 'AMBIGU' && Array.isArray(m.ambiguousCandidates) && m.ambiguousCandidates.length > 0) {
            const list = m.ambiguousCandidates.map(c =>
                '<div class="text-[11px] bg-amber-50 border border-amber-200 rounded-lg p-1.5">'
                + '<span class="font-bold text-slate-800">' + escapeHtml(c.fullName) + '</span>'
                + ' — <span>' + escapeHtml(c.activeEmploymentType || '') + '</span>'
                + (c.department ? ' <span class="text-slate-500">(' + escapeHtml(c.department) + ')</span>' : '')
                + '</div>'
            ).join('');
            return '<div class="space-y-1">' + list + '</div>';
        }

        if (!m.matchedPersonUuid) {
            return '<span class="text-slate-400">Sense coincidència a Egreta</span>';
        }

        const c = m.primaryContract || m.latestHistoricalContract;
        const isHist = !m.primaryContract && !!m.latestHistoricalContract;
        const contractBlock = c
            ? '<div class="mt-1 text-[11px] ' + (isHist ? 'text-rose-700' : 'text-slate-600') + '">'
              + '<div class="font-semibold">' + (isHist ? 'Darrer històric: ' : '') + escapeHtml(c.employmentType || c.contractType || 'Sense categoria') + '</div>'
              + (c.contractType ? '<div class="text-slate-500">' + escapeHtml(c.contractType) + '</div>' : '')
              + (c.organizationName ? '<div class="text-slate-500">' + escapeHtml(c.organizationName) + '</div>' : '')
              + '<div class="font-mono text-[10px] text-slate-500">' + escapeHtml(c.startDate || '?') + ' → ' + escapeHtml(c.endDate || 'Indefinit') + '</div>'
              + '</div>'
            : '<div class="text-[11px] text-rose-600 mt-1">Sense contractes registrats</div>';

        let extraHtml = '';
        if (Array.isArray(m.activeContracts) && m.activeContracts.length > 1) {
            const extraId = 'contracts-' + Math.random().toString(36).slice(2, 9);
            const items = m.activeContracts.map(ac =>
                '<div class="border-l-2 border-slate-300 pl-2">'
                + '<span class="font-semibold">' + escapeHtml(ac.employmentType || '--') + '</span>'
                + (ac.organizationName ? ' · ' + escapeHtml(ac.organizationName) : '')
                + ' <span class="font-mono text-[10px] text-slate-500">(' + escapeHtml(ac.startDate || '?') + ' → ' + escapeHtml(ac.endDate || 'Obert') + ')</span>'
                + '</div>'
            ).join('');
            extraHtml = '<button type="button" data-toggle-contracts="' + extraId + '" '
                + 'class="mt-1 text-[11px] font-semibold text-emerald-700 hover:underline">'
                + 'Veure ' + m.activeContracts.length + ' associacions actives</button>'
                + '<div id="' + extraId + '" class="hidden mt-1.5 space-y-1 text-[11px] text-slate-600 bg-slate-50 p-2 rounded-lg">' + items + '</div>';
        }

        return '<div>'
            + '<div class="font-bold text-slate-800">' + escapeHtml(m.matchedFullName || '--') + '</div>'
            + contractBlock
            + extraHtml
            + '</div>';
    }

    async function openRulesModal() {
        el.rulesModal.classList.remove('hidden');
        if (state.rulesCache) {
            renderRulesModal(state.rulesCache);
            return;
        }
        try {
            const resp = await global.apiFetch('/plan-nacional/reglas');
            if (!resp.ok) throw new Error('HTTP ' + resp.status);
            state.rulesCache = await resp.json();
            renderRulesModal(state.rulesCache);
        } catch (_) {
            el.rulesModalContent.innerHTML = '<p class="text-rose-600">No s\'han pogut carregar els criteris des del servidor.</p>';
        }
    }

    function renderRulesModal(catalog) {
        const rulesHtml = (catalog.rules || []).map(r =>
            '<tr class="border-b border-slate-100">'
            + '<td class="py-2 px-3 font-bold text-slate-800">' + escapeHtml(r.title) + '</td>'
            + '<td class="py-2 px-3">' + escapeHtml(r.ipRule) + '</td>'
            + '<td class="py-2 px-3">' + escapeHtml(r.eiRule) + '</td>'
            + '<td class="py-2 px-3">' + escapeHtml(r.etRule) + '</td>'
            + '</tr>'
        ).join('');

        el.rulesModalContent.innerHTML =
            '<div class="flex flex-wrap gap-3 text-xs">'
            + '<span class="px-3 py-1 rounded-lg bg-emerald-50 text-emerald-800 font-bold">Durada per defecte: ' + escapeHtml(catalog.defaultDurationYears) + ' anys</span>'
            + '<span class="px-3 py-1 rounded-lg bg-amber-50 text-amber-800 font-bold">Llindar emèrit darrer any: ' + escapeHtml(catalog.emeritWarningMonths) + ' mesos</span>'
            + '</div>'
            + '<div class="overflow-x-auto border border-slate-200 rounded-xl">'
            + '<table class="w-full text-left border-collapse text-xs">'
            + '<thead><tr class="bg-slate-50 border-b border-slate-200 text-slate-600 font-bold">'
            + '<th class="py-2 px-3">Categoria / Situació</th><th class="py-2 px-3">IP</th><th class="py-2 px-3">Equip Inv. (EI)</th><th class="py-2 px-3">Equip Treball (ET)</th>'
            + '</tr></thead><tbody>' + rulesHtml + '</tbody></table></div>';
    }

    function closeRulesModal() {
        el.rulesModal.classList.add('hidden');
    }

    function exportExcel() {
        if (typeof XLSX === 'undefined') {
            alert('La llibreria SheetJS (XLSX) no està disponible.');
            return;
        }
        const summaryRows = [];
        const detailRows = [];

        state.projects.forEach(item => {
            if (!item.response) {
                summaryRows.push({
                    'Fitxer PDF': item.fileName,
                    'Codi projecte': '',
                    'Acrònim': '',
                    'Títol': '',
                    'Durada efectiva (anys)': '',
                    'Data referència': el.inputReferenceDate.value || '',
                    'Fi prevista projecte': '',
                    'Semàfor global': 'ERROR',
                    'Total membres': 0,
                    'OK': 0,
                    'Avisos': 0,
                    'NO OK': 0,
                    'Externs': 0,
                    'Mètode extracció': '',
                    'Incidències principals': item.error || 'Error de lectura'
                });
                return;
            }

            const r = item.response;
            const c = r.summaryCounts || { total: 0, ok: 0, avis: 0, noOk: 0, extern: 0 };
            const issues = (r.members || [])
                .filter(m => m.status === 'NO_OK' || m.status === 'AVIS')
                .slice(0, 5)
                .map(m => (m.extractedMember?.fullName || '?') + ': ' + (m.reasonMessage || m.status))
                .join(' | ');

            summaryRows.push({
                'Fitxer PDF': r.fileName || item.fileName,
                'Codi projecte': r.projectCode || '',
                'Acrònim': r.acronym || '',
                'Títol': r.title || '',
                'Durada efectiva (anys)': r.effectiveDurationYears,
                'Data referència': r.referenceDate || '',
                'Fi prevista projecte': r.effectiveProjectEndDate || '',
                'Semàfor global': r.overallStatus,
                'Total membres': c.total,
                'OK': c.ok,
                'Avisos': c.avis,
                'NO OK': c.noOk,
                'Externs': c.extern,
                'Mètode extracció': r.extractionMethod || '',
                'Incidències principals': issues
            });

            (r.members || []).forEach(m => {
                const ext = m.extractedMember || {};
                const ct = m.primaryContract || m.latestHistoricalContract || {};
                detailRows.push({
                    'Fitxer PDF': r.fileName || item.fileName,
                    'Codi projecte': r.projectCode || '',
                    'Acrònim': r.acronym || '',
                    'Rol PDF': ext.role || '',
                    'Nom complet PDF': ext.fullName || '',
                    'NIF PDF': ext.nif || '',
                    'Entitat PDF': ext.entity || '',
                    'Concordança': m.matchConfidence || '',
                    'Nom Egreta': m.matchedFullName || '',
                    'UUID Egreta': m.matchedPersonUuid || '',
                    'PureID': m.matchedPureId || '',
                    'Categoria classificada': m.employmentCategory || '',
                    'Categoria laboral Egreta': ct.employmentType || '',
                    'Tipus contracte': ct.contractType || '',
                    'Departament': ct.organizationName || '',
                    'Data inici': ct.startDate || '',
                    'Data fi': ct.endDate || '',
                    'Estat': m.status || '',
                    'Codi motiu': m.reasonCode || '',
                    'Motiu detallat': m.reasonMessage || ''
                });
            });
        });

        const wb = XLSX.utils.book_new();
        XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(summaryRows), 'Resum_Projectes');
        XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(detailRows), 'Detall_Equips');
        const dateTag = (el.inputReferenceDate.value || new Date().toISOString().slice(0, 10));
        XLSX.writeFile(wb, 'validacio_plan_nacional_' + dateTag + '.xlsx');
    }

    async function safeJson(resp) {
        try {
            return await resp.json();
        } catch (_) {
            return null;
        }
    }

    document.addEventListener('DOMContentLoaded', () => {
        cacheElements();
        initDefaults();
        bindEvents();
        renderAll();
    });
})(typeof window !== 'undefined' ? window : globalThis);
