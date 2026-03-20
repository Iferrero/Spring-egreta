(function () {
    function getPageTitle() {
        const h1 = document.querySelector('h1');
        if (h1 && h1.textContent && h1.textContent.trim()) {
            return h1.textContent.trim().replace(/\s+/g, ' ');
        }

        const docTitle = (document.title || '').trim();
        if (!docTitle) return 'Aplicació UAB';

        const primaryTitle = docTitle.split('|')[0].trim();
        return primaryTitle || docTitle;
    }

    function getPageSubtitle() {
        const subtitle = document.querySelector('header p');
        if (subtitle && subtitle.textContent && subtitle.textContent.trim()) {
            return subtitle.textContent.trim().replace(/\s+/g, ' ');
        }
        return '';
    }

    function injectUabBrand() {
        const body = document.body;
        if (!body || document.querySelector('.uab-brandbar')) return;
        const pageTitle = getPageTitle();
        const pageSubtitle = getPageSubtitle();

        const brandBar = document.createElement('div');
        brandBar.className = 'uab-brandbar';
        brandBar.innerHTML = `
            <a class="uab-brandbar__link" href="https://www.uab.cat/" target="_blank" rel="noopener noreferrer" aria-label="Universitat Autònoma de Barcelona">
                <img
                    class="uab-brandbar__logo"
                    src="https://www.uab.cat/Xcelerate/WAI/img/UAB-2linies-verd.svg"
                    alt="Logotip de la Universitat Autònoma de Barcelona"
                    loading="eager"
                    decoding="async"
                />
            </a>
            <div class="uab-brandbar__text">
                <span class="uab-brandbar__title text-2xl font-extrabold text-[#2a3037] tracking-tight" aria-label="Títol de la pàgina">${pageTitle}</span>
                ${pageSubtitle ? `<span class="uab-brandbar__subtitle" aria-label="Subtítol de la pàgina">${pageSubtitle}</span>` : ''}
            </div>
        `;

        body.insertBefore(brandBar, body.firstChild);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', injectUabBrand);
    } else {
        injectUabBrand();
    }
})();
