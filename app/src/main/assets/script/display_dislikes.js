(function () {
  const preferences = JSON.parse(lite.getPreferences() || "{}");
  if (!preferences.enable_display_dislikes || window.returnDislikeInjected) return;

  const STATE_LIKED = "LIKED_STATE";
  const STATE_DISLIKED = "DISLIKED_STATE";
  const STATE_NEUTRAL = "NEUTRAL_STATE";

  const isMobile = location.hostname === "m.youtube.com";
  let likesValue = 0;
  let dislikesValue = 0;
  let previousState = STATE_NEUTRAL;
  let activeVideoId = null;
  let initToken = 0;
  let lastLikeButton = null;
  let lastDislikeButton = null;
  let activeDislikeObserver = null;
  let applyFrameId = 0;

  function isShorts() {
    return location.pathname.startsWith("/shorts");
  }

  function getButtons() {
    if (isShorts()) {
      const elements = document.querySelectorAll(
        isMobile ? "ytm-like-button-renderer" : "#like-button > ytd-like-button-renderer",
      );
      for (const element of elements) {
        const rect = element.getBoundingClientRect();
        if (rect.width > 0 && rect.height > 0) {
          return element;
        }
      }
      return null;
    }

    if (isMobile) {
      return (
        document.querySelector(".slim-video-action-bar-actions .segmented-buttons") ??
        document.querySelector(".slim-video-action-bar-actions")
      );
    }

    if (document.getElementById("menu-container")?.offsetParent === null) {
      return (
        document.querySelector("ytd-menu-renderer.ytd-watch-metadata > div") ??
        document.querySelector("ytd-menu-renderer.ytd-video-primary-info-renderer > div")
      );
    }

    return document.getElementById("menu-container")?.querySelector("#top-level-buttons-computed") ?? null;
  }

  function getLikeButton() {
    const buttons = getButtons();
    if (!buttons || !buttons.children || buttons.children.length === 0) return null;
    const firstChild = buttons.children[0];
    if (firstChild.tagName === "YTD-SEGMENTED-LIKE-DISLIKE-BUTTON-RENDERER") {
      return document.querySelector("#segmented-like-button") ?? firstChild.children[0] ?? null;
    }
    return buttons.querySelector("like-button-view-model") ?? firstChild;
  }

  function getDislikeButton() {
    try {
      const buttons = getButtons();
      if (!buttons || !buttons.children || buttons.children.length === 0) return null;
      const firstChild = buttons.children[0];
      if (firstChild.tagName === "YTD-SEGMENTED-LIKE-DISLIKE-BUTTON-RENDERER") {
        return document.querySelector("#segmented-dislike-button") ?? firstChild.children[1] ?? null;
      }
      return buttons.querySelector("dislike-button-view-model") ?? buttons.children[1] ?? null;
    } catch {
      return null;
    }
  }

  function getDislikeTextContainer() {
    const dislikeButton = getDislikeButton();
    if (!dislikeButton) return null;

    const existing =
      dislikeButton.querySelector(".button-renderer-text") ??
      dislikeButton.querySelector("#text") ??
      dislikeButton.getElementsByTagName("yt-formatted-string")[0] ??
      dislikeButton.querySelector("span[role='text']");
    if (existing) return existing;

    const button = dislikeButton.querySelector("button");
    if (!button) return null;

    const textSpan = document.createElement("span");
    textSpan.id = "text";
    textSpan.style.marginLeft = "6px";
    button.appendChild(textSpan);
    button.style.width = "auto";
    return textSpan;
  }

  function getVideoId() {
    const url = new URL(window.location.href);
    if (url.pathname.startsWith("/clip")) {
      return (
        document.querySelector("meta[itemprop='videoId']")?.content ??
        document.querySelector("meta[itemprop='identifier']")?.content ??
        null
      );
    }
    if (url.pathname.startsWith("/shorts/")) {
      return url.pathname.slice(8);
    }
    return url.searchParams.get("v");
  }

  function isVideoReady() {
    const videoId = getVideoId();
    if (!videoId) return false;
    if (isShorts()) return !!getButtons();
    return (
      document.querySelector(`ytd-watch-grid[video-id='${videoId}']`) !== null ||
      document.querySelector(`ytd-watch-flexy[video-id='${videoId}']`) !== null ||
      document.querySelector('#player[loading="false"]:not([hidden])') !== null
    );
  }

  function getNumberFormatter() {
    const locale =
      document.documentElement.lang ||
      navigator.language ||
      "en";
    return Intl.NumberFormat(locale, {
      notation: "compact",
      compactDisplay: "short",
    });
  }

  function roundDown(value) {
    if (value < 1000) return value;
    const int = Math.floor(Math.log10(value) - 2);
    const decimal = int + (int % 3 ? 1 : 0);
    return Math.floor(value / 10 ** decimal) * 10 ** decimal;
  }

  function formatCount(value) {
    return getNumberFormatter().format(roundDown(Math.max(0, value)));
  }

  function applyDislikeCount() {
    const container = getDislikeTextContainer();
    if (!container) return;
    const nextText = formatCount(dislikesValue);
    if (container.textContent !== nextText) {
      container.textContent = nextText;
    }
  }

  function scheduleApplyDislikeCount() {
    if (applyFrameId) return;
    applyFrameId = requestAnimationFrame(() => {
      applyFrameId = 0;
      applyDislikeCount();
    });
  }

  function isPressed(button) {
    const target = button?.querySelector("button, tp-yt-paper-button#button") ?? button;
    if (!target) return false;
    const ariaPressed = target.getAttribute("aria-pressed");
    if (ariaPressed != null) return ariaPressed === "true";
    const ariaLabel = target.getAttribute("aria-label");
    return ariaLabel === "true";
  }

  function readVoteState() {
    const likeButton = getLikeButton();
    const dislikeButton = getDislikeButton();
    if (!likeButton || !dislikeButton) return STATE_NEUTRAL;

    if (isMobile) {
      if (isPressed(likeButton)) return STATE_LIKED;
      if (isPressed(dislikeButton)) return STATE_DISLIKED;
      return STATE_NEUTRAL;
    }

    if (likeButton.classList.contains("style-default-active")) return STATE_LIKED;
    if (dislikeButton.classList.contains("style-default-active")) return STATE_DISLIKED;
    return STATE_NEUTRAL;
  }

  function canOptimisticallyUpdate() {
    return isMobile || !!document.querySelector("#avatar-btn");
  }

  function applyAction(action) {
    if (!canOptimisticallyUpdate()) return;

    if (action === "like") {
      if (previousState === STATE_LIKED) {
        likesValue -= 1;
        previousState = STATE_NEUTRAL;
      } else if (previousState === STATE_DISLIKED) {
        likesValue += 1;
        dislikesValue -= 1;
        previousState = STATE_LIKED;
      } else {
        likesValue += 1;
        previousState = STATE_LIKED;
      }
    } else if (action === "dislike") {
      if (previousState === STATE_DISLIKED) {
        dislikesValue -= 1;
        previousState = STATE_NEUTRAL;
      } else if (previousState === STATE_LIKED) {
        likesValue -= 1;
        dislikesValue += 1;
        previousState = STATE_DISLIKED;
      } else {
        dislikesValue += 1;
        previousState = STATE_DISLIKED;
      }
    }

    scheduleApplyDislikeCount();
    setTimeout(() => {
      previousState = readVoteState();
      scheduleApplyDislikeCount();
    }, 120);
  }

  function bindButtons() {
    const likeButton = getLikeButton();
    const dislikeButton = getDislikeButton();
    if (!likeButton || !dislikeButton) return false;

    if (likeButton !== lastLikeButton) {
      likeButton.addEventListener("click", () => applyAction("like"));
      likeButton.addEventListener("touchstart", () => applyAction("like"), { passive: true });
      lastLikeButton = likeButton;
    }

    if (dislikeButton !== lastDislikeButton) {
      dislikeButton.addEventListener("click", () => applyAction("dislike"));
      dislikeButton.addEventListener("touchstart", () => applyAction("dislike"), { passive: true });
      lastDislikeButton = dislikeButton;
      observeDislikeButton(dislikeButton);
    }

    return true;
  }

  function observeDislikeButton(dislikeButton) {
    if (activeDislikeObserver) {
      activeDislikeObserver.disconnect();
      activeDislikeObserver = null;
    }

    activeDislikeObserver = new MutationObserver(() => {
      scheduleApplyDislikeCount();
    });
    activeDislikeObserver.observe(dislikeButton, {
      childList: true,
      subtree: true,
      characterData: true,
    });
  }

  function fetchVotes(videoId, token) {
    fetch(`https://returnyoutubedislikeapi.com/votes?videoId=${videoId}`)
      .then((response) => (response.ok ? response.json() : null))
      .then((json) => {
        if (!json || token !== initToken || videoId !== activeVideoId) return;
        likesValue = json.likes ?? 0;
        dislikesValue = json.dislikes ?? 0;
        previousState = readVoteState();
        scheduleApplyDislikeCount();
      })
      .catch(() => {
      });
  }

  function resetBindings() {
    lastLikeButton = null;
    lastDislikeButton = null;
    if (activeDislikeObserver) {
      activeDislikeObserver.disconnect();
      activeDislikeObserver = null;
    }
  }

  function tryInitialize(token, retriesLeft) {
    if (token !== initToken) return;

    const videoId = getVideoId();
    if (!videoId) {
      activeVideoId = null;
      resetBindings();
      return;
    }

    if (!isVideoReady() || !bindButtons()) {
      if (retriesLeft > 0) {
        setTimeout(() => tryInitialize(token, retriesLeft - 1), 120);
      }
      return;
    }

    activeVideoId = videoId;
    previousState = readVoteState();
    fetchVotes(videoId, token);
  }

  function scheduleInitialize() {
    initToken += 1;
    resetBindings();
    const token = initToken;
    setTimeout(() => tryInitialize(token, 25), 0);
  }

  const originalPushState = history.pushState;
  history.pushState = function (...args) {
    const result = originalPushState.apply(this, args);
    scheduleInitialize();
    return result;
  };

  const originalReplaceState = history.replaceState;
  history.replaceState = function (...args) {
    const result = originalReplaceState.apply(this, args);
    scheduleInitialize();
    return result;
  };

  window.addEventListener("yt-navigate-finish", scheduleInitialize, true);
  window.addEventListener("popstate", scheduleInitialize, true);

  window.returnDislikeInjected = true;
  scheduleInitialize();
})();
