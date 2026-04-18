(function (global) {
  const REEL_API_RE = /\/youtubei\/v1\/reel\/reel_watch_sequence/i;

  const state = {
    installed: false,
    fetch: null,
    observer: null,
  };

  function isObject(value) {
    return value !== null && typeof value === 'object';
  }

  function safeJsonParse(text) {
    try {
      return JSON.parse(text);
    } catch {
      return null;
    }
  }

  function getUrl(input) {
    if (typeof input === 'string') return input;
    if (input && typeof input.url === 'string') return input.url;
    return '';
  }

  function hasAdField(node) {
    if (!isObject(node)) return false;
    if (Array.isArray(node)) return node.some(hasAdField);

    return !!(
      node.adClientParams ||
      node.adPlacements ||
      node.adSlots ||
      node.adBreakHeartbeatParams ||
      node.adSlotRenderer
    );
  }

  function isAdEntry(entry) {
    if (!isObject(entry)) return false;
    if (hasAdField(entry)) return true;

    const endpoint = entry.command || entry.navigationEndpoint || entry.reelWatchEndpoint;
    if (hasAdField(endpoint)) return true;
    if (hasAdField(entry.reelWatchEndpoint)) return true;
    if (hasAdField(entry.command?.reelWatchEndpoint)) return true;
    if (hasAdField(entry.ad)) return true;
    return false;
  }

  function filterShortsJson(data) {
    if (!isObject(data)) return data;

    if (Array.isArray(data.entries)) {
      const kept = [];
      for (const entry of data.entries) {
        if (!isAdEntry(entry)) {
          kept.push(entry);
          continue;
        }
      }
      data.entries = kept;
    }

    const player = data.playerResponse;
    if (isObject(player)) {
      delete player.adBreakHeartbeatParams;
      delete player.adSlots;
      delete player.adPlacements;
    }

    return data;
  }

  function shouldFilterUrl(url) {
    return !!url && REEL_API_RE.test(url);
  }

  function patchFetch() {
    if (typeof global.fetch !== 'function' || state.fetch) return;

    state.fetch = global.fetch.bind(global);
    global.fetch = async function (...args) {
      const response = await state.fetch(...args);
      const url = getUrl(args[0]);
      if (!shouldFilterUrl(url)) return response;

      const text = await response.clone().text();
      const json = safeJsonParse(text);
      if (!json) return response;

      const next = filterShortsJson(json);
      const body = JSON.stringify(next);
      if (body === text) return response;

      return new Response(body, {
        status: response.status,
        statusText: response.statusText,
        headers: response.headers,
      });
    };
  }

  function patchDom() {
    if (typeof MutationObserver !== 'function' || state.observer) return;

    const remove = (root) => {
      if (!root || typeof root.querySelectorAll !== 'function') return;
      root.querySelectorAll('ytm-reel-shelf-renderer, ytm-pivot-bar-item-renderer.pivot-shorts').forEach((node) => {
        if (node instanceof Element) node.style.display = 'none';
      });
    };

    const target = document.documentElement || document.body;
    if (!target) return;

    state.observer = new MutationObserver((records) => {
      for (const record of records) {
        for (const node of record.addedNodes) {
          if (node instanceof Element) remove(node);
        }
      }
    });

    state.observer.observe(target, { childList: true, subtree: true });
    remove(document);
  }

  function install(options = {}) {
    if (state.installed) return state;

    if (options.patchFetch !== false) patchFetch();
    if (options.patchDom === true) patchDom();

    state.installed = true;
    return state;
  }

  function uninstall() {
    if (state.fetch) {
      global.fetch = state.fetch;
      state.fetch = null;
    }

    if (state.observer) {
      state.observer.disconnect();
      state.observer = null;
    }

    state.installed = false;
  }

  const api = {
    install,
    uninstall,
    filterShortsJson,
    removeShortsNodes(root) {
      if (!root || typeof root.querySelectorAll !== 'function') return;
      root.querySelectorAll('ytm-reel-shelf-renderer, ytm-pivot-bar-item-renderer.pivot-shorts').forEach((node) => {
        if (node instanceof Element) node.style.display = 'none';
      });
    },
  };

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }

  global.removeShortsAds = api;

  if (global.document) {
    install({ patchDom: false });
  }
})(typeof window !== 'undefined' ? window : globalThis);
