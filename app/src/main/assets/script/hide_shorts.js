(function () {
    if (window.hideShorts?.syncPreferences) {
        window.hideShorts.syncPreferences();
        return;
    }

    const HIDDEN_ATTR = "data-lite-hide-shorts";
    const DISPLAY_ATTR = "data-lite-hide-shorts-display";
    const closestSelectors = [
        "ytm-reel-shelf-renderer",
        "ytm-pivot-bar-item-renderer",
        "ytm-video-with-context-renderer",
        "ytm-rich-section-renderer",
        "grid-shelf-view-model",
    ];
    const shortsSelectors = [
        "ytm-shorts-lockup-view-model",
        ".pivot-bar-item-tab.pivot-shorts",
        "a[href^=\"/shorts/\"]",
        "grid-shelf-view-model",
    ];
    let enabled = false;

    function readEnabled() {
        try {
            return !!JSON.parse(lite.getPreferences() || "{}").enable_hide_shorts;
        } catch {
            return false;
        }
    }

    function hideContainer(container) {
        if (!(container instanceof HTMLElement) || container.getAttribute(HIDDEN_ATTR) === "1") {
            return;
        }
        container.setAttribute(HIDDEN_ATTR, "1");
        container.setAttribute(DISPLAY_ATTR, container.style.display || "");
        container.style.display = "none";
    }

    function hideElement(element) {
        if (!(element instanceof Element)) return;
        for (const selector of closestSelectors) {
            const container = element.closest(selector);
            if (container) {
                hideContainer(container);
                return;
            }
        }
    }

    function scan() {
        if (!enabled) return;
        for (const selector of shortsSelectors) {
            document.querySelectorAll(selector).forEach(hideElement);
        }
    }

    function restore() {
        document.querySelectorAll(`[${HIDDEN_ATTR}="1"]`).forEach((element) => {
            if (!(element instanceof HTMLElement)) return;
            const previous = element.getAttribute(DISPLAY_ATTR);
            if (previous) {
                element.style.display = previous;
            } else {
                element.style.removeProperty("display");
            }
            element.removeAttribute(HIDDEN_ATTR);
            element.removeAttribute(DISPLAY_ATTR);
        });
    }

    document.addEventListener("animationstart", (event) => {
        if (!enabled || event.animationName !== "nodeInserted" || !(event.target instanceof Element)) {
            return;
        }
        const element = event.target;
        if (
            element.matches("ytm-shorts-lockup-view-model")
            || element.matches(".pivot-bar-item-tab.pivot-shorts")
            || element.matches("a[href^=\"/shorts/\"]")
            || element.matches("grid-shelf-view-model")
        ) {
            hideElement(element);
        }
    }, false);

    function syncPreferences() {
        const next = readEnabled();
        if (enabled == next) {
            if (enabled) scan();
            return;
        }
        enabled = next;
        if (enabled) {
            scan();
            return;
        }
        restore();
    }

    window.addEventListener("litePreferencesChanged", syncPreferences, true);
    window.hideShorts = { syncPreferences };
    window.hideShortsInjected = true;
    syncPreferences();
})();
