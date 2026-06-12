
const API_SEARCH = window.apiUrl ? window.apiUrl('/external-organizations?buscar=') : '/api/external-organizations?buscar=';
const API_BATCH = window.apiUrl ? window.apiUrl('/external-organizations/by-uuids') : '/api/external-organizations/by-uuids';
const API_MERGE = window.apiUrl ? window.apiUrl('/external-organizations/merge') : '/api/external-organizations/merge';

const searchForm = document.getElementById('searchForm');
const searchInput = document.getElementById('searchInput');
const searchBtn = document.getElementById('searchBtn');
const resultsTable = document.getElementById('resultsTable');
const resultsBody = document.getElementById('resultsBody');
const mergeForm = document.getElementById('mergeForm');
const mergeBtn = document.getElementById('mergeBtn');
const loadingSpinner = document.getElementById('loadingSpinner');
const successMsg = document.getElementById('successMsg');
const errorMsg = document.getElementById('errorMsg');


let orgResults = [];
let targetUuid = null;
let sourceUuids = [];

function getUuidsFromUrl() {
  const params = new URLSearchParams(window.location.search);
  const uuids = params.get('uuids');
  return uuids ? uuids.split(',') : [];
}


searchForm.onsubmit = async (e) => {
  e.preventDefault();
  clearMessages();
  setLoading(true);
  try {
    const resp = await fetch(API_SEARCH + encodeURIComponent(searchInput.value));
    if (!resp.ok) throw new Error('Error buscando organizaciones');
    const data = await resp.json();
    orgResults = data.content || data || [];
    renderResults();
  } catch (err) {
    showError(err.message);
  } finally {
    setLoading(false);
  }
};

// Al cargar la página, si hay uuids en la URL, buscar solo esos
window.addEventListener('DOMContentLoaded', async () => {
  const clusterUuids = getUuidsFromUrl();
  if (clusterUuids.length) {
    clearMessages();
    setLoading(true);
    try {
      const resp = await fetch(API_BATCH, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ uuids: clusterUuids })
      });
      if (!resp.ok) throw new Error('Error buscando cluster');
      const data = await resp.json();
      orgResults = data || [];
      // Preseleccionar todos para merge
      sourceUuids = clusterUuids.slice();
      renderResults();
    } catch (err) {
      showError(err.message);
    } finally {
      setLoading(false);
    }
  }
});



function renderResults() {
  resultsBody.innerHTML = '';
  // Cabecera visual para Target y Fusionar
  const headerTr = document.createElement('tr');
  const thTarget = document.createElement('td');
  thTarget.textContent = 'Target';
  thTarget.style.fontWeight = 'bold';
  thTarget.style.fontSize = '13px';
  thTarget.style.color = '#555';
  thTarget.style.borderBottom = '1px solid #e5e7eb';
  const thMerge = document.createElement('td');
  thMerge.textContent = 'Fusionar';
  thMerge.style.fontWeight = 'bold';
  thMerge.style.fontSize = '13px';
  thMerge.style.color = '#555';
  thMerge.style.borderBottom = '1px solid #e5e7eb';
  const thInfo = document.createElement('td');
  thInfo.colSpan = 2;
  thInfo.textContent = '';
  thInfo.style.borderBottom = '1px solid #e5e7eb';
  headerTr.appendChild(thTarget);
  headerTr.appendChild(thMerge);
  headerTr.appendChild(thInfo);
  resultsBody.appendChild(headerTr);
  const clusterUuids = getUuidsFromUrl();
  if (!clusterUuids.length) {
    targetUuid = null;
    sourceUuids = [];
  }
  if (!orgResults.length) {
    resultsTable.style.display = 'none';
    mergeBtn.disabled = true;
    return;
  }
  resultsTable.style.display = '';
  const sorted = [...orgResults].sort((a, b) => {
    const wa = (a.workflow && (a.workflow.step || a.workflow)) || '';
    const wb = (b.workflow && (b.workflow.step || b.workflow)) || '';
    if (wa === 'approved' && wb !== 'approved') return -1;
    if (wa !== 'approved' && wb === 'approved') return 1;
    return 0;
  });
  if (clusterUuids.length && !targetUuid && sorted.length) {
    targetUuid = sorted[0].uuid;
  }
  sorted.forEach((org, idx) => {
    const tr = document.createElement('tr');
    // Target radio
    const tdTarget = document.createElement('td');
    tdTarget.style.verticalAlign = 'top';
    const radio = document.createElement('input');
    radio.type = 'radio';
    radio.name = 'targetOrg';
    radio.value = org.uuid;
    radio.onclick = () => {
      targetUuid = org.uuid;
      // Al cambiar el target, marcar todos los demás como source
      sourceUuids = sorted.map(o => o.uuid).filter(u => u !== targetUuid);
      renderResults();
      updateMergeBtn();
    };
    if (org.uuid === targetUuid) radio.checked = true;
    tdTarget.appendChild(radio);
    tr.appendChild(tdTarget);
    // Source checkbox
    const tdSource = document.createElement('td');
    tdSource.style.verticalAlign = 'top';
    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.value = org.uuid;
    if (org.uuid === targetUuid) {
      checkbox.disabled = true;
      checkbox.checked = false;
    } else {
      if (sourceUuids.includes(org.uuid)) checkbox.checked = true;
      checkbox.onchange = (ev) => {
        if (ev.target.checked) {
          if (!sourceUuids.includes(org.uuid)) sourceUuids.push(org.uuid);
        } else {
          sourceUuids = sourceUuids.filter(u => u !== org.uuid);
        }
        updateMergeBtn();
      };
    }
    tdSource.appendChild(checkbox);
    tr.appendChild(tdSource);
    // Info Pure style
    const tdInfo = document.createElement('td');
    tdInfo.colSpan = 2;
    tdInfo.style.paddingLeft = '8px';
    tdInfo.innerHTML = `
      <div style="font-weight:600;color:#004494;font-size:15px;line-height:1.2;margin-bottom:2px;">
        ${(org.displayName || (org.name && (org.name.ca_ES || org.name.es_ES || org.name.en_GB)) || '').replace(/</g,'&lt;').replace(/>/g,'&gt;')}
      </div>
      <div style="color:#222;font-size:13px;line-height:1.2;">
        ${org.address && org.address.city ? org.address.city + ', ' : ''}${org.address && org.address.country && org.address.country.term && org.address.country.term.en_GB ? org.address.country.term.en_GB : ''}
      </div>
      <div style="color:#888;font-size:12px;line-height:1.2;">
        ${org.type && org.type.term && org.type.term.en_GB ? org.type.term.en_GB : ''}
      </div>
      <div style="color:#888;font-size:12px;line-height:1.2;">
        ${org.workflow ? 'External organisation: ' + ((org.workflow.step || org.workflow) || '') : ''}
      </div>
    `;
    tr.appendChild(tdInfo);
    resultsBody.appendChild(tr);
  });
  updateMergeBtn();
}

function updateMergeBtn() {
  mergeBtn.disabled = !(targetUuid && sourceUuids.length && sourceUuids.includes(targetUuid) === false);
}


// Permitir elegir si se usa Egreta o MongoDB local
let useEgreta = false;
const egretaCheckbox = document.getElementById('egretaCheckbox');
if (egretaCheckbox) {
  egretaCheckbox.onchange = (e) => {
    useEgreta = !!e.target.checked;
  };
}

mergeForm.onsubmit = async (e) => {
  e.preventDefault();
  clearMessages();
  if (!targetUuid || !sourceUuids.length) return;
  setLoading(true);
  try {
    // No permitir que el target esté en sources
    const filteredSources = sourceUuids.filter(u => u !== targetUuid);
    if (!filteredSources.length) throw new Error('Selecciona al menos una organización a fusionar (además del target)');
    const body = { targetId: targetUuid, sourceIds: filteredSources };
    if (useEgreta) body.egreta = true;
    const resp = await fetch(API_MERGE, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    if (!resp.ok) throw new Error('Error al fusionar: ' + (await resp.text()));
    const data = await resp.json();
    showSuccess('Fusión completada. Target: ' + data.targetId + (data.egreta ? ' (Egreta)' : ''));
    // Notificar al opener para que actualice su lista/grafo si es necesario
    if (window.opener && typeof window.opener.handleOrgsMerged === 'function') {
      window.opener.handleOrgsMerged(data.targetId, data.removed || filteredSources);
    }
    // Eliminar del cluster las organizaciones fusionadas (sources y target)
    const removedUuids = [data.targetId, ...(data.removed || [])];
    orgResults = orgResults.filter(org => !removedUuids.includes(org.uuid));
    // Actualizar sourceUuids y targetUuid
    sourceUuids = sourceUuids.filter(u => !removedUuids.includes(u));
    if (orgResults.length > 0) {
      targetUuid = orgResults[0].uuid;
    } else {
      targetUuid = null;
    }
    renderResults();
  } catch (err) {
    showError(err.message);
  } finally {
    setLoading(false);
  }
};

function setLoading(isLoading) {
  loadingSpinner.style.display = isLoading ? '' : 'none';
  searchBtn.disabled = isLoading;
  mergeBtn.disabled = isLoading;
}

function showSuccess(msg) {
  successMsg.textContent = msg;
  successMsg.style.display = '';
}
function showError(msg) {
  errorMsg.textContent = msg;
  errorMsg.style.display = '';
}
function clearMessages() {
  successMsg.style.display = 'none';
  errorMsg.style.display = 'none';
}
