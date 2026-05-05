/**
 * egreta-modal.js
 * Obre l'editor d'Egreta en una finestra emergent del navegador (window.open),
 * de manera que el SSO funciona correctament.
 *
 * Ús:
 *  - Afegeix `data-egreta-id="<pureId>"` a qualsevol element clicable.
 *  - O crida directament: window.abrirEgretaModal(url)
 */
(function () {
    const EGRETA_BASE =
        'https://egreta.uab.cat/admin/editor/dk/atira/pure/modules/unifiedprojectmodel/external/model/award/editor/awardeditor.xhtml?scheme=&type=&id=';

    /* Referència a la finestra emergent (reutilitzar si ja està oberta) */
    let egretaWin = null;

    /* ── API pública ── */
    function abrirEgretaModal(url) {
        const w = Math.round(Math.min(screen.availWidth  * 0.88, 1400));
        const h = Math.round(Math.min(screen.availHeight * 0.88,  900));
        const left = Math.round((screen.availWidth  - w) / 2 + (screen.availLeft || 0));
        const top  = Math.round((screen.availHeight - h) / 2 + (screen.availTop  || 0));
        const features = `width=${w},height=${h},left=${left},top=${top},resizable=yes,scrollbars=yes,status=yes,toolbar=no,menubar=no,location=yes`;

        if (egretaWin && !egretaWin.closed) {
            egretaWin.location.href = url;
            egretaWin.focus();
        } else {
            egretaWin = window.open(url, 'egretaEditor', features);
        }
    }

    function cerrarEgretaModal() {
        if (egretaWin && !egretaWin.closed) {
            egretaWin.close();
        }
    }

    window.abrirEgretaModal  = abrirEgretaModal;
    window.cerrarEgretaModal = cerrarEgretaModal;

    /* ── Delegació de clics sobre data-egreta-id ── */
    document.addEventListener('click', function (e) {
        const btn = e.target.closest('[data-egreta-id]');
        if (!btn) return;
        const pureId = btn.getAttribute('data-egreta-id');
        abrirEgretaModal(EGRETA_BASE + encodeURIComponent(pureId));
    });
})();
