/**
 * @description basic script to YouTube page
 * @author halcyon
 * @version 1.1.0
 * @license MIT
 */
try {
    if (!window.injected) {
        const getLocalizedText = (key) => {
            const languages = {
                'zh': { 'download': '下载', 'downloads': '下载', 'extension': 'LitePipe 设置', 'chat': '聊天室', 'about': '关于' },
                'zt': { 'download': '下載', 'downloads': '下載', 'extension': 'LitePipe 設置', 'chat': '聊天室', 'about': '關於' },
                'en': { 'download': 'Download', 'downloads': 'Downloads', 'extension': 'LitePipe Settings', 'chat': 'Chat', 'about': 'About' },
                'ja': { 'download': 'ダウンロード', 'downloads': 'ダウンロード', 'extension': 'LitePipe 設定', 'chat': 'チャット', 'about': '詳細' },
                'ko': { 'download': '다운로드', 'downloads': '다운로드', 'extension': '플러그인', 'chat': '채팅', 'about': '정보' },
                'fr': { 'download': 'Télécharger', 'downloads': 'Téléchargements', 'extension': 'Paramètres LitePipe', 'chat': 'Chat', 'about': 'À propos' },
                'ru': { 'download': 'Скачать', 'downloads': 'Загрузки', 'extension': 'Настройки LitePipe', 'chat': 'Чат', 'about': 'О программе' },
                'tr': { 'download': 'İndir', 'downloads': 'İndirilenler', 'extension': 'LitePipe Ayarları', 'chat': 'Sohbet', 'about': 'Hakkında' },
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
                    } catch (e) {}
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
            history[name] = function() {
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
                    node.mute();
                    node.seekTo(node.getDuration() / 2);
                    node.addEventListener('onStateChange', s => { if (s === 1) node.pauseVideo(); });
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

            const selectors = [
                '.ytm-settings-item-chevron',
                '.chevron',
                '[class*="chevron"]',
                '[id*="chevron"]',
                'yt-icon:last-child',
                '.yt-spec-icon-shape:last-child',
                'svg:last-child'
            ];
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

        setInterval(() => {
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
                                        container.innerHTML = `<div style="display:flex;justify-content:space-between;padding:12px;border-bottom:1px solid var(--yt-spec-10-percent-layer)"><b>${getLocalizedText('chat')}</b><span onclick="this.parentElement.parentElement.style.display='none';document.body.style.overflow=''">✕</span></div><iframe src="https://www.youtube.com/live_chat?v=${vid}&embed_domain=${location.hostname}" style="flex:1;border:none"></iframe>`;
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

            if (getPageClass(location.href) === 'select_site') {
                const settings = document.querySelector('ytm-settings');
                if (settings) {
                    const base = settings.firstElementChild;
                    if (base) {
                        const aboutBtn = createCustomSettingBtn(base, 'aboutButton', 'about', 'M444-288h72v-240h-72v240Zm35.79-312q15.21 0 25.71-10.29t10.5-25.5q0-15.21-10.29-25.71t-25.5-10.5q-15.21 0-25.71 10.29t-10.5 25.5q0 15.21 10.29 25.71t25.5 10.5Zm.49 504Q401-96 331-126t-122.5-82.5Q156-261 126-330.96t-30-149.5Q96-560 126-629.5q30-69.5 82.5-122T330.96-834q69.96-30 149.5-30t149.04 30q69.5 30 122 82.5T834-629.28q30 69.73 30 149Q864-401 834-331t-82.5 122.5Q699-156 629.28-126q-69.73 30-149 30Zm-.28-72q130 0 221-91t91-221q0-130-91-221t-221-91q-130 0-221 91t-91 221q0 130 91 221t221 91Zm0-312Z', () => android.about());
                        if (aboutBtn) settings.appendChild(aboutBtn);

                        const dlBtn = createCustomSettingBtn(base, 'downloadButton', 'downloads', 'M480-336 288-528l51-51 105 105v-342h72v342l105-105 51 51-192 192ZM263.72-192Q234-192 213-213.15T192-264v-72h72v72h432v-72h72v72q0 29.7-21.16 50.85Q725.68-192 695.96-192H263.72Z', () => android.download());
                        if (dlBtn) settings.insertBefore(dlBtn, settings.firstElementChild);

                        const extBtn = createCustomSettingBtn(base, 'extensionButton', 'extension', 'M497-120l-33-124q-15-7-30-16t-28-20l-116 50-70-121 98-88q-2-10-3-20t-1-20q0-10 1-20t3-20l-98-88 70-121 116 50q13-11 28-20t30-16l33-124h140l33 124q15 7 30 16t28 20l116-50 70 121-98 88q2 10 3 20t1 20q0 10-1 20t-3 20l98 88-70 121-116-50q-13 11-28 20t-30 16l-33 124H497Zm70-227q55 0 94-39t39-94q0-55-39-94t-94-39q-55 0-94 39t-39 94q0 55 39 94t94 39Z', () => android.extension());
                        if (extBtn) settings.insertBefore(extBtn, settings.firstElementChild);
                    }
                }
            }
        }, 1000);

        let longPressTimer;
        let lastUrl;
        const handleLongPress = (url) => {
            if (!url || url === lastUrl) return;
            lastUrl = url;
            android.showVideoOptions(url);
            setTimeout(() => { lastUrl = null; }, 1000);
        };

        const findLink = (el) => {
            let curr = el;
            while (curr && curr !== document) {
                if (curr.tagName === 'A' && curr.href) return curr.href;
                if (curr.getAttribute('href')) return new URL(curr.getAttribute('href'), location.origin).href;
                curr = curr.parentElement;
            }
            return null;
        };

        document.addEventListener('touchstart', e => {
            const url = findLink(e.target);
            if (url && (url.includes('/watch') || url.includes('/shorts/'))) {
                clearTimeout(longPressTimer);
                longPressTimer = setTimeout(() => handleLongPress(url), 600);
            }
        }, { passive: true });

        document.addEventListener('touchend', () => clearTimeout(longPressTimer), { passive: true });
        document.addEventListener('touchmove', () => clearTimeout(longPressTimer), { passive: true });
        document.addEventListener('contextmenu', e => {
            const url = findLink(e.target);
            if (url && (url.includes('/watch') || url.includes('/shorts/'))) {
                e.preventDefault();
                handleLongPress(url);
            }
        }, true);

        window.injected = true;
    }
} catch (e) { console.error(e); }
