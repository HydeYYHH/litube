(function () {
    const prefs = JSON.parse(android.getPreferences());
    if (!prefs.enable_custom_nav_bar) {
        const oldStyle = document.getElementById('hide-pivot-bar-style');
        if (oldStyle) oldStyle.remove();
        window.hidePivotBarInjected = false;
        return;
    }

    if (window.hidePivotBarInjected) return;

    const style = document.createElement('style');
    style.id = 'hide-pivot-bar-style';
    style.innerHTML = `
        ytm-pivot-bar-renderer {
            display: none !important;
        }
        body {
            padding-bottom: 0 !important;
            margin-bottom: 0 !important;
        }
        :root {
            --safe-area-inset-bottom: 0px !important;
        }
    `;
    document.head.appendChild(style);

    window.hidePivotBarInjected = true;
})();
