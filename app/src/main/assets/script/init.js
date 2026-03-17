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
                'zh': { 'download': '下载', 'extension': '扩展', 'chat': '聊天室', 'about': '关于' },
                'zt': { 'download': '下載', 'extension': '擴充功能', 'chat': '聊天室', 'about': '關於' },
                'en': { 'download': 'Download', 'extension': 'Extension', 'chat': 'Chat', 'about': 'About' },
                'ja': { 'download': 'ダウンロード', 'extension': '拡張機能', 'chat': 'チャット', 'about': 'このアプリについて' },
                'ko': { 'download': '다운로드', 'extension': '플러그인', 'chat': '채팅', 'about': '정보' },
                'fr': { 'download': 'Télécharger', 'extension': 'Extension', 'chat': 'Chat', 'about': 'À propos' },
                'ru': { 'download': 'Скачать', 'extension': 'Расширение', 'chat': 'Чат', 'about': 'О программе' },
                'tr': { 'download': 'İndir', 'extension': 'Uzantı', 'chat': 'Sohbet', 'about': 'Hakkında' },
            };
            const lang = (document.documentElement.lang || 'en').toLowerCase();
            let keyLang = lang.substring(0, 2);
            if (lang.includes('tw') || lang.includes('hk') || lang.includes('mo') || lang.includes('hant')) {
                keyLang = 'zt';
            }
            return languages[keyLang] ? languages[keyLang][key] : languages['en'][key];
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
                            if (path) path.setAttribute("d", "M240-384h336v-72H240v72Zm0-132h480v-72H240v72Zm0-132h480v-72H240v72ZM96-96v-696q0-29.7 21.15-50.85Q138.3-864 168-864h624q29.7 0 50.85 21.15Q864-792v480q0 29.7-21.15 50.85Q821.7-240 792-240H240L96-96Zm114-216h582v-480H168v522l42-42Zm-42 0v-480 480Z");
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
                if (settings && !document.getElementById('aboutButton')) {
                    const btn = settings.firstElementChild;
                    if (btn) {
                        const aboutBtn = btn.cloneNode(true);
                        aboutBtn.id = 'aboutButton';
                        aboutBtn.querySelector('.yt-core-attributed-string').innerText = getLocalizedText('about');
                        aboutBtn.onclick = () => android.about();
                        settings.appendChild(aboutBtn);

                        const extBtn = btn.cloneNode(true);
                        extBtn.id = 'extensionButton';
                        extBtn.querySelector('.yt-core-attributed-string').innerText = getLocalizedText('extension');
                        extBtn.onclick = () => android.extension();
                        settings.insertBefore(extBtn, aboutBtn);

                        const dlBtn = btn.cloneNode(true);
                        dlBtn.id = 'downloadButton';
                        dlBtn.querySelector('.yt-core-attributed-string').innerText = getLocalizedText('download');
                        dlBtn.onclick = () => android.download();
                        settings.insertBefore(dlBtn, extBtn);
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
                // Specifically for some YouTube elements that might not be <a> but have data-href or handle click
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
