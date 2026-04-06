/** * @description basic script to YouTube page */
try {
    if (!window.injected) {
        const getLocalizedText = (key) => {
            const languages = {
                'zh': { 'download': '下载', 'downloads': '下载', 'extension': 'LitePipe 设置', 'chat': '聊天室', 'about': '关于', 'pip': '画中画' },
                'zt': { 'download': '下載', 'downloads': '下載', 'extension': 'LitePipe 設置', 'chat': '聊天室', 'about': '關於', 'pip': '畫中畫' },
                'en': { 'download': 'Download', 'downloads': 'Downloads', 'extension': 'LitePipe Settings', 'chat': 'Chat', 'about': 'About', 'pip': 'PiP' },
                'ja': { 'download': 'ダウンロード', 'downloads': 'ダウンロード', 'extension': 'LitePipe 設定', 'chat': 'チャット', 'about': '詳細', 'pip': 'PiP' },
                'ko': { 'download': '다운로드', 'downloads': '다운로드', 'extension': 'LitePipe 플러그인', 'chat': '채팅', 'about': '정보', 'pip': 'PiP' },
                'fr': { 'download': 'Télécharger', 'downloads': 'Téléchargements', 'extension': 'Paramètres LitePipe', 'chat': 'Chat', 'about': 'À propos', 'pip': 'PiP' },
                'ru': { 'download': 'Скачать', 'downloads': 'Загрузки', 'extension': 'Настройки LitePipe', 'chat': 'Чат', 'about': 'О программе', 'pip': 'PiP' },
                'tr': { 'download': 'İndir', 'downloads': 'İndirilenler', 'extension': 'LitePipe Ayarları', 'chat': 'Sohbet', 'about': 'Hakkında', 'pip': 'PiP' },
            };
            const lang = (document.documentElement.lang || 'en').toLowerCase();
            let keyLang = lang.substring(0, 2);
            if (lang.includes('tw') || lang.includes('hk') || lang.includes('mo') || lang.includes('hant')) {
                keyLang = 'zt';
            }
            const entry = languages[keyLang] || languages['en'];
            return entry[key] || languages['en'][key] || key;
        };

        const getPageClass = (url) => {
            try {
                const u = new URL(url.toLowerCase());
                if (!u.hostname.includes('youtube.com')) return 'unknown';
                const segments = u.pathname.split('/').filter(Boolean);
                if (segments.length === 0) return 'home';
                const s0 = segments[0];
                if (s0 === 'shorts') return 'shorts';
                if (s0 === 'watch') return 'watch';
                if (s0 === 'channel') return 'channel';
                if (s0 === 'gaming') return 'gaming';
                if (s0 === 'feed' && segments.length > 1) return segments[1];
                if (s0 === 'select_site') return 'select_site';
                if (s0.startsWith('@')) return '@';
                return segments.join('/');
            } catch (e) { return 'unknown'; }
        };

        if (!window.originalFetch) {
            window.originalFetch = fetch;
            window.fetch = async (...args) => {
                const request = args[0] instanceof Request ? args[0] : new Request(...args);
                if (request.url.includes('youtubei/v1/player') && request.method === 'POST') {
                    try {
                        const cloned = request.clone();
                        const text = await cloned.text();
                        if (text) {
                            const json = JSON.parse(text);
                            const poToken = json?.serviceIntegrityDimensions?.poToken;
                            const visitorData = json?.context?.client?.visitorData;
                            if (poToken) android.setPoToken(poToken, visitorData);
                        }
                    } catch (e) { }
                }
                return window.originalFetch(...args);
            };
        }

        window.addEventListener('onProgressChangeFinish', () => {
            const currentPageClass = getPageClass(location.href);
            if (currentPageClass && window.pageClass !== currentPageClass) {
                window.pageClass = currentPageClass;
                window.dispatchEvent(new Event('onPageClassChange'));
            }
            android.finishRefresh();
        });

        const getVideoId = (url) => {
            const match = url.match(/^.*((youtu.be\/)|(v\/)|(\/u\/\w\/)|(embed\/)|(watch\?))\??v?=?([^#&?]*).*/);
            return (match && match[7].length == 11) ? match[7] : null;
        };

        const stopNativePlayer = (target) => {
            if (!target) return;
            try {
                target.mute?.();
                target.setVolume?.(0);
                const video = target.querySelector?.('video') || document.querySelector('video');
                if (video) {
                    video.muted = true;
                    video.volume = 0;
                }
            } catch (e) { }
        };

        window.addEventListener('onRefresh', () => location.reload());

        window.addEventListener('doUpdateVisitedHistory', () => {
            const pc = getPageClass(location.href);
            android.setRefreshLayoutEnabled(['home', 'subscriptions', 'library', '@'].includes(pc));
            android.finishRefresh();
        });

        const handlePlayerVisibility = () => {
            if (getPageClass(location.href) === 'watch') android.play(location.href);
            else android.hidePlayer();
        };

        window.addEventListener('popstate', handlePlayerVisibility);
        const wrapState = (name) => {
            const orig = history[name];
            history[name] = function () {
                orig.apply(this, arguments);
                handlePlayerVisibility();
            };
        };
        wrapState('pushState');
        wrapState('replaceState');

        window.changePlayerHeight = () => {
            if (getPageClass(location.href) !== 'watch') return;
            const p = document.querySelector('#movie_player');
            if (p) android.setPlayerHeight(p.clientHeight);
        };

        const ro = new ResizeObserver(window.changePlayerHeight);
        document.addEventListener('animationstart', (e) => {
            if (e.animationName !== 'nodeInserted') return;
            const node = e.target;
            const pc = getPageClass(location.href);
            if (node.id === 'movie_player') {
                if (pc === 'watch') {
                    stopNativePlayer(node);
                    node.seekTo(node.getDuration() / 2);
                    node.addEventListener('onStateChange', s => {
                        if (s === 1) {
                            node.pauseVideo();
                            stopNativePlayer(node);
                        }
                    });
                }
                ro.disconnect();
                ro.observe(node);
            } else if (pc === 'watch') {
                if (node.id === 'player') node.style.visibility = 'hidden';
                else if (node.id === 'player-container-id') node.style.backgroundColor = 'black';
                else if (node.classList.contains('watch-below-the-player')) {
                    ['touchmove', 'touchend'].forEach(ev => {
                        node.addEventListener(ev, evt => evt.stopPropagation(), { passive: false, capture: true });
                    });
                }
            }
        }, false);

        const removeChevrons = (parent) => {
            const selectors = ['.ytm-settings-item-chevron', '.chevron', '[class*="chevron"]', '[id*="chevron"]', 'yt-icon:last-child', '.yt-spec-icon-shape:last-child', 'svg:last-child'];
            selectors.forEach(s => {
                const elements = parent.querySelectorAll(s);
                elements.forEach(el => {
                    const icons = parent.querySelectorAll('yt-icon, .yt-spec-icon-shape, svg');
                    if (icons.length > 1 && Array.from(icons).indexOf(el) > 0) {
                        el.remove();
                    }
                });
            });
            const allIcons = parent.querySelectorAll('yt-icon, .yt-spec-icon-shape, svg');
            for (let i = 1; i < allIcons.length; i++) {
                allIcons[i].style.display = 'none';
            }
        };

        const createCustomSettingBtn = (baseItem, id, textKey, iconD, clickFn) => {
            if (document.getElementById(id)) return null;
            const btn = baseItem.cloneNode(true);
            btn.id = id;
            btn.removeAttribute('href');
            const textEl = btn.querySelector('.yt-core-attributed-string');
            if (textEl) textEl.innerText = getLocalizedText(textKey);
            const ns = 'http://www.w3.org/2000/svg';
            const svg = document.createElementNS(ns, 'svg');
            svg.setAttribute('viewBox', '0 -960 960 960');
            svg.setAttribute('width', '24');
            svg.setAttribute('height', '24');
            svg.style.marginRight = '16px';
            svg.style.fill = 'currentColor';
            svg.style.flexShrink = '0';
            const path = document.createElementNS(ns, 'path');
            path.setAttribute('d', iconD);
            svg.appendChild(path);
            const oldIcon = btn.querySelector('yt-icon, .ytm-settings-item-icon, img, .ytm-avatar, .yt-spec-icon-shape, svg');
            if (oldIcon) oldIcon.parentNode.replaceChild(svg, oldIcon);
            else {
                const content = btn.querySelector('.ytm-settings-item-content') || btn;
                content.insertBefore(svg, content.firstChild);
            }
            removeChevrons(btn);
            btn.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                clickFn();
            }, true);
            return btn;
        };

        const pipIconD = "M160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h640q33 0 56.5 23.5T880-720v480q0 33-23.5 56.5T800-160H160Zm0-80h640v-480H160v480Zm360-120h240v-160H520v160Zm-360 120v-480 480Z";

        setInterval(() => {
            const prefsJson = android.getPreferences ? android.getPreferences() : '{}';
            const prefs = JSON.parse(prefsJson);


            if (prefs.hide_shorts) {
                const shortsSelectors = [
                    'ytm-reel-shelf-renderer',
                    'ytm-shorts-lockup-view-model',
                    'ytm-pivot-bar-item-renderer[aria-label*="Shorts"]',
                    'a[href*="/shorts/"]',
                    'ytm-shorts-shelf-renderer',
                    '.reel-shelf-header',
                    'ytm-item-section-renderer:has(ytm-reel-shelf-renderer)',
                    'ytm-shorts'
                ];
                shortsSelectors.forEach(selector => {
                    document.querySelectorAll(selector).forEach(el => {
                        el.style.setProperty('display', 'none', 'important');
                    });
                });
            }

            if (getPageClass(location.href) === 'watch') {
                const ad = document.querySelector('.ad-showing video');
                if (ad) ad.currentTime = ad.duration;
            }

            const moviePlayer = document.querySelector('#movie_player');
            const isLive = moviePlayer?.getPlayerResponse()?.playabilityStatus?.liveStreamability && location.href.includes('/watch');

            if (isLive) {
                if (!document.getElementById('chatButton')) {
                    const saveBtn = document.querySelector('.ytSpecButtonViewModelHost.slim_video_action_bar_renderer_button');
                    if (saveBtn) {
                        const chatBtn = saveBtn.cloneNode(true);
                        chatBtn.id = 'chatButton';
                        const txt = chatBtn.querySelector('.yt-spec-button-shape-next__button-text-content');
                        if (txt) txt.innerText = getLocalizedText('chat');
                        const svg = chatBtn.querySelector('svg');
                        if (svg) {
                            svg.setAttribute("viewBox", "0 -960 960 960");
                            const path = svg.querySelector('path');
                            if (path) path.setAttribute("d", "M240-384h336v-72H240v72Zm0-132h480v-72H240v72Zm0-132h480v-72H240v72ZM96-96v-696q0-29.7 21.15-50.85Q138.3-864 168-864h624q29.7 0 50.85 21.15Q864-821.7 864-792v480q0 29.7-21.15 50.85Q821.7-240 792-240H240L96-96Zm114-216h582v-480H168v522l42-42Zm-42 0v-480 480Z");
                        }
                        chatBtn.onclick = () => {
                            let container = document.getElementById('live_chat_container');
                            if (container) {
                                container.style.display = container.style.display === 'none' ? 'flex' : 'none';
                                document.body.style.overflow = container.style.display === 'none' ? '' : 'hidden';
                            } else {
                                const panel = document.querySelector('#panel-container') || document.querySelector('.watch-below-the-player');
                                if (panel) {
                                    container = document.createElement('div');
                                    container.id = 'live_chat_container';
                                    container.style.cssText = 'position:fixed;top:calc(56.25vw + 48px);bottom:0;left:0;right:0;z-index:4;display:flex;flex-direction:column;background:var(--yt-spec-brand-background-solid);overflow:hidden;';
                                    const vid = getVideoId(location.href);
                                    if (vid) {
                                        container.innerHTML = `${getLocalizedText('chat')}✕`;
                                        panel.insertBefore(container, panel.firstChild);
                                        document.body.style.overflow = 'hidden';
                                    }
                                }
                            }
                        };
                        saveBtn.parentElement?.insertBefore(chatBtn, saveBtn);
                    }
                }
            } else if (getPageClass(location.href) === 'watch' && !document.getElementById('downloadButton')) {
                const saveBtn = document.querySelector('.ytSpecButtonViewModelHost.slim_video_action_bar_renderer_button');
                if (saveBtn) {
                    const dlBtn = saveBtn.cloneNode(true);
                    dlBtn.id = 'downloadButton';
                    const txt = dlBtn.querySelector('.yt-spec-button-shape-next__button-text-content');
                    if (txt) txt.innerText = getLocalizedText('download');
                    const svg = dlBtn.querySelector('svg');
                    if (svg) {
                        svg.setAttribute("viewBox", "0 -960 960 960");
                        const path = svg.querySelector('path');
                        if (path) path.setAttribute("d", "M480-328.46 309.23-499.23l42.16-43.38L450-444v-336h60v336l98.61-98.61 42.16 43.38L480-328.46ZM252.31-180Q222-180 201-201q-21-21-21-51.31v-108.46h60v108.46q0 4.62 3.85 8.46 3.84 3.85 8.46 3.85h455.38q4.62 0 8.46-3.85 3.85-3.84 3.85-8.46v-108.46h60v108.46Q780-222 759-201q-21 21-51.31 21H252.31Z");
                    }
                    dlBtn.onclick = () => android.download(location.href);
                    saveBtn.parentElement?.insertBefore(dlBtn, saveBtn);
                }
            }

            if (getPageClass(location.href) === 'watch' && !document.getElementById('pipButton') && prefs.enable_pip !== false) {
                const saveBtn = document.querySelector('.ytSpecButtonViewModelHost.slim_video_action_bar_renderer_button');
                if (saveBtn) {
                    const pipBtn = saveBtn.cloneNode(true);
                    pipBtn.id = 'pipButton';
                    const txt = pipBtn.querySelector('.yt-spec-button-shape-next__button-text-content');
                    if (txt) txt.innerText = getLocalizedText('pip');
                    const svg = pipBtn.querySelector('svg');
                    if (svg) {
                        svg.setAttribute("viewBox", "0 -960 960 960");
                        const path = svg.querySelector('path');
                        if (path) path.setAttribute("d", pipIconD);
                    }
                    pipBtn.onclick = () => android.pip();
                    const dlBtn = document.getElementById('downloadButton');
                    if (dlBtn && dlBtn.parentElement) {
                        dlBtn.parentElement.insertBefore(pipBtn, dlBtn.nextSibling);
                    } else {
                        saveBtn.parentElement?.insertBefore(pipBtn, saveBtn);
                    }
                }
            }

            if (getPageClass(location.href) === 'watch') {
                const actionBarContainer = document.querySelector('ytm-slim-video-action-bar-renderer') || document.querySelector('.slim-video-action-bar-actions') || document.querySelector('.ytSpecButtonViewModelHost.slim_video_action_bar_renderer_button')?.parentNode;
                if (actionBarContainer) {
                    const btnSelectors = ['.ytSpecButtonViewModelHost', 'ytm-toggle-button-renderer', 'ytm-button-renderer', '.slim_video_action_bar_renderer_button'];
                    actionBarContainer.querySelectorAll(btnSelectors.join(', ')).forEach((btn) => {
                        if (btn.closest && btn.closest('ytm-segmented-like-dislike-button-renderer')) return;
                        const label = (btn.getAttribute('aria-label') || btn.textContent || '').toLowerCase().trim();
                        const id = btn.id || '';
                        let hide = false;
                        if (prefs.action_bar_show_share === false && label.includes('share')) hide = true;
                        if (prefs.action_bar_show_remix === false && label.includes('remix')) hide = true;
                        if (prefs.action_bar_show_download === false && (label.includes('download') || id === 'downloadButton')) hide = true;
                        if (prefs.action_bar_show_thanks === false && label.includes('thanks')) hide = true;
                        if (prefs.action_bar_show_clip === false && label.includes('clip')) hide = true;
                        if (prefs.action_bar_show_save === false && (label.includes('save') || label.includes('playlist'))) hide = true;
                        if (prefs.action_bar_show_report === false && label.includes('report')) hide = true;
                        if (prefs.action_bar_show_ask_ai === false && (label.includes('ask') || label.includes('ai'))) hide = true;
                        if (prefs.enable_pip === false && id === 'pipButton') hide = true;
                        if (hide) {
                            btn.style.setProperty('display', 'none', 'important');
                        } else {
                            btn.style.removeProperty('display');
                            if (id.endsWith('Button')) {
                                btn.style.setProperty('display', 'flex', 'important');
                            }
                        }
                    });
                }

                if (prefs.hide_comments) {
                    const commentSelectors = [
                        'ytm-item-section-renderer[section-identifier="comments-entry-point"]',
                        'ytm-comments-entry-point-header-renderer',
                        'ytm-comment-thread-renderer',
                        'ytm-comments-entry-point-teaser-renderer',
                        '#comments',
                        '.comment-section',
                        'ytm-comments-renderer'
                    ];
                    commentSelectors.forEach(selector => {
                        document.querySelectorAll(selector).forEach(el => {
                            if (el) {
                                el.style.setProperty('display', 'none', 'important');
                                el.style.setProperty('visibility', 'hidden', 'important');
                            }
                        });
                    });
                }

                if (prefs.hide_recommendations) {
                    const recommendationSelectors = [
                        '.watch-below-the-player > ytm-item-section-renderer:not([section-identifier="comments-entry-point"])',
                        'ytm-watch > ytm-item-section-renderer:not([section-identifier="comments-entry-point"])',
                        'ytm-single-column-watch-next-results-renderer ytm-item-section-renderer:not([section-identifier="comments-entry-point"])',
                        'ytm-related-videos-renderer'
                    ];
                    recommendationSelectors.forEach(selector => {
                        document.querySelectorAll(selector).forEach(el => {
                            if (el) {
                                el.style.setProperty('display', 'none', 'important');
                                el.style.setProperty('visibility', 'hidden', 'important');
                            }
                        });
                    });
                }
            }

            if (getPageClass(location.href) === 'select_site') {
                const settings = document.querySelector('ytm-settings');
                if (settings) {
                    const base = settings.firstElementChild;
                    if (base) {
                        const aboutBtn = createCustomSettingBtn(base, 'aboutButton', 'about', '...', () => android.about());
                        if (aboutBtn) settings.appendChild(aboutBtn);
                        const dlBtn = createCustomSettingBtn(base, 'downloadButton', 'downloads', '...', () => android.download());
                        if (dlBtn) settings.insertBefore(dlBtn, settings.firstElementChild);
                        const extBtn = createCustomSettingBtn(base, 'extensionButton', 'extension', '...', () => android.extension());
                        if (extBtn) settings.insertBefore(extBtn, settings.firstElementChild);
                    }
                }
            }
        }, 1000);

        // ... Keep existing event listeners for Tap, Long Press, Skip, etc.
        let longPressTimer;
        let lastUrl;
        const findLinkElement = (el) => {
            let curr = el;
            while (curr && curr !== document) {
                if (curr.tagName === 'A' && curr.href) return curr;
                if (curr.getAttribute('href')) return curr;
                curr = curr.parentElement;
            }
            return null;
        };
        const handleLongPress = (url, title) => {
            if (!url || (url === lastUrl && !title)) return;
            lastUrl = url;
            android.showVideoOptions(url, title || null);
            setTimeout(() => { lastUrl = null; }, 1000);
        };
        document.addEventListener('touchstart', e => {
            const link = findLinkElement(e.target);
            if (link) {
                const url = link.href || new URL(link.getAttribute('href'), location.origin).href;
                if (url && (url.includes('/watch') || url.includes('/shorts/') || url.includes('list='))) {
                    clearTimeout(longPressTimer);
                    longPressTimer = setTimeout(() => {
                        let title = '';
                        const titleEl = link.querySelector('h3, span#video-title, #video-title');
                        if (titleEl) title = titleEl.innerText;
                        handleLongPress(url, title);
                    }, 600);
                }
            }
        }, { passive: true });
        document.addEventListener('touchend', () => clearTimeout(longPressTimer), { passive: true });
        document.addEventListener('touchmove', () => clearTimeout(longPressTimer), { passive: true });

        window.injected = true;
    }
} catch (e) {
    console.error(e);
}