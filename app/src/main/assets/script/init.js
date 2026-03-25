try {
    // Prevent repeated injection of the script
    if (!window.injected) {
        // Timer and interval optimization
        const st = setTimeout.bind(window), si = setInterval.bind(window);
        const ct = clearTimeout.bind(window), ci = clearInterval.bind(window);
        const THRESHOLD = 800, map = new Map();
        let fp = null, token = 0;

        const nextFrame = () => fp || (fp = new Promise(r =>
            requestAnimationFrame(() => { fp = null; r(++token); })
        ));

        const wrap = (setFn) => (fn, delay, ...args) => {
            if (typeof fn !== "function") return setFn(fn, delay, ...args);
            const s = { on: 1, last: 0, frame: 0 };
            const run = async () => {
                if (!s.on) return;
                if (s.last && Date.now() - s.last < THRESHOLD) {
                const t = await nextFrame();
                if (!s.on || s.frame === t) return;
                s.frame = t;
                }
                s.last = Date.now();
                fn(...args);
            };
            const id = setFn(run, delay);
            map.set(id, s);
            return id;
        };

        const clear = (clearFn) => (id) => {
            const s = map.get(id);
            if (s) s.on = 0, map.delete(id);
            clearFn(id);
        };

        window.setTimeout = wrap(st);
        window.setInterval = wrap(si);
        window.clearTimeout = clear(ct);
        window.clearInterval = clear(ci);

        // Utility to get localized text based on the page's language
        const getLocalizedText = (key) => {
            // Automatically translated by AI
            const languages = {
                'zh': { 'download': '下载', 'add_to_queue': '加入队列', 'extension': '扩展', 'chat': '聊天室', 'about': '关于' },
                'zt': { 'download': '下載', 'add_to_queue': '加入佇列', 'extension': '擴充功能', 'chat': '聊天室', 'about': '關於' },
                'en': { 'download': 'Download', 'add_to_queue': 'Add to queue', 'extension': 'Extension', 'chat': 'Chat', 'about': 'About' },
                'ja': { 'download': 'ダウンロード', 'add_to_queue': 'キューに追加', 'extension': '拡張機能', 'chat': 'チャット', 'about': 'このアプリについて' },
                'ko': { 'download': '다운로드', 'add_to_queue': '대기열에 추가', 'extension': '플러그인', 'chat': '채팅', 'about': '정보' },
                'fr': { 'download': 'Télécharger', 'add_to_queue': 'Ajouter à la file', 'extension': 'Extension', 'chat': 'Chat', 'about': 'À propos' },
                'ru': { 'download': 'Скачать', 'add_to_queue': 'Добавить в очередь', 'extension': 'Расширение', 'chat': 'Чат', 'about': 'О программе' },
                'tr': { 'download': 'İndir', 'add_to_queue': 'Kuyruğa ekle', 'extension': 'Uzantı', 'chat': 'Sohbet', 'about': 'Hakkında' },
            };
            const lang = (document.documentElement.lang || 'en').toLowerCase();
            let keyLang = lang.substring(0, 2);
            if (lang.includes('tw') || lang.includes('hk') || lang.includes('mo') || lang.includes('hant')) {
                keyLang = 'zt';
            }
            return languages[keyLang] ? languages[keyLang][key] : languages['en'][key];
        };

        // Determine the type of YouTube page based on the URL
        const getPageClass = (url) => {
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
        };

        const backoff = (() => {
            const delays = [16, 32, 64, 128, 256, 512, 1024, 2048];
            let tmr = null;
            let ver = 0;
            return (fn) => {
                clearTimeout(tmr);
                const v = ++ver;
                let k = 0;
                const run = () => {
                    if (v !== ver) return;
                    fn();
                    tmr = setTimeout(run, delays[k] ?? 2048);
                    k += 1;
                };
                run();
            };
        })();

        const bindListener = (obj, type, fn, options) => { 
            if (!obj?.addEventListener || !obj?.removeEventListener || typeof fn !== 'function') return;
            const capture = typeof options === 'boolean' ? options : !!options?.capture;
            obj.removeEventListener(type, fn, capture);
            obj.addEventListener(type, fn, options);
        }

        // Extract video ID from the URL
        const getVideoId = (url) => {
            try {
                function youtube_parser(url) {
                    var regExp = /^.*((youtu.be\/)|(v\/)|(\/u\/\w\/)|(embed\/)|(watch\?))\??v?=?([^#&?]*).*/;
                    var match = url.match(regExp);
                    return (match && match[7].length == 11) ? match[7] : false;
                }
                return youtube_parser(url);
            } catch (error) {
                console.error('Error extracting video ID:', error);
                return null;
            }
        };

        // Extract shorts ID from the URL
        const getShortsId = (url) => {
            try {
                const match = url.match(/shorts\/([^&#]+)/);
                return match ? match[1] : null;
            } catch (error) {
                console.error('Error extracting shorts ID:', error);
                return null;
            }
        };

        // Handle page refresh events
        bindListener(window, 'onRefresh', () => {
            window.location.reload();
        });

        // Notify Android when page loading is finished
        bindListener(window, 'onProgressChangeFinish', () => {
            lite.finishRefresh();
            backoff(run);
        });

        bindListener(window, 'doUpdateVisitedHistory', () => {
            backoff(run);
        });

        // Handle player visibility based on page type
        const handlePlayerVisibility = () => {
            const pageClass = getPageClass(location.href);
            if (pageClass === 'watch') {
                lite.play(location.href);
            } else {
                lite.hidePlayer();
            }
        };

        // Listen for popstate events
        bindListener(window, 'popstate', () => {
            handlePlayerVisibility();
            backoff(run);
        });

        // Override pushState to trigger player visibility changes
        const originalPushState = history.pushState;
        history.pushState = function (data, title, url) {
            originalPushState.call(this, data, title, url);
            handlePlayerVisibility();
            backoff(run);
        };

        // Override replaceState to trigger player visibility changes
        const originalReplaceState = history.replaceState;
        history.replaceState = function (data, title, url) {
            originalReplaceState.call(this, data, title, url);
            handlePlayerVisibility();
            backoff(run);
        };

        const WATCH_CONTENT_WRAPPER_OFFSET = 200;
        const WATCH_CONTENT_WRAPPER_MIN_HEIGHT = 60;
        const ro = typeof ResizeObserver === 'function'
            ? new ResizeObserver(() => {
                const pageClass = getPageClass(location.href);
                const player = document.querySelector('#movie_player');
                if (pageClass !== 'watch' || !player) return;
                lite.setPlayerHeight(player.clientHeight);
            })
            : null;
        let observedPlayer = null;

        const setPlaylistSaftHeight = (pageClass = getPageClass(location.href), player = document.querySelector('#movie_player')) => {
            const wrapper = document.querySelector('#content-wrapper');
            if (!wrapper) return;

            if (pageClass !== 'watch' || !player) {
                if (wrapper.dataset.maxheight === 'true') {
                    wrapper.style.maxHeight = '';
                    delete wrapper.dataset.maxheight;
                }
                return;
            }

            const viewportHeight = window.visualViewport?.height || window.innerHeight || document.documentElement.clientHeight || 0;
            const nextMaxHeight = `${Math.max(WATCH_CONTENT_WRAPPER_MIN_HEIGHT, Math.floor(viewportHeight - player.clientHeight - WATCH_CONTENT_WRAPPER_OFFSET))}px`;
            if (wrapper.style.maxHeight !== nextMaxHeight) {
                wrapper.style.maxHeight = nextMaxHeight;
            }
            wrapper.dataset.maxheight = 'true';
        };

        const parseTimestampSeconds = (rawValue) => {
            if (rawValue == null) return null;
            const normalized = `${rawValue}`.trim().toLowerCase();
            if (!normalized) return null;
            if (/^\d+$/.test(normalized)) return Number(normalized);
            if (/^\d+s$/.test(normalized)) return Number(normalized.slice(0, -1));

            let totalSeconds = 0;
            let matched = false;
            for (const part of normalized.matchAll(/(\d+)(h|m|s)/g)) {
                const amount = Number(part[1]);
                matched = true;
                if (part[2] === 'h') totalSeconds += amount * 3600;
                if (part[2] === 'm') totalSeconds += amount * 60;
                if (part[2] === 's') totalSeconds += amount;
            }
            if (!matched) return null;
            const consumed = Array.from(normalized.matchAll(/(\d+)(h|m|s)/g), (part) => part[0]).join('');
            return consumed === normalized ? totalSeconds : null;
        };
        const handleWatchTimestampClick = (event) => {
            if (getPageClass(location.href) !== 'watch') return;
            const link = event.target.closest('a');
            if (!link) return;
            const href = link.getAttribute('href') || link.href;
            if (!href || !href.includes('t=')) return;

            let targetUrl;
            try {
                targetUrl = new URL(link.href, location.href);
            } catch (error) {
                return;
            }
            if (getPageClass(targetUrl.toString()) !== 'watch') return;

            const currentVideoId = getVideoId(location.href);
            const targetVideoId = getVideoId(targetUrl.toString());
            if (!currentVideoId || currentVideoId !== targetVideoId) return;

            const timestampSeconds = parseTimestampSeconds(targetUrl.searchParams.get('t') ?? targetUrl.searchParams.get('start'));
            if (timestampSeconds == null) return;
            if (!lite.seekLoadedVideo?.(targetUrl.toString(), timestampSeconds * 1000)) return;

            event.preventDefault();
            event.stopImmediatePropagation();
        };

        bindListener(document, 'animationstart', (event) => {
            const target = event.target;
            if (
                event.animationName !== 'nodeInserted' ||
                !(target instanceof Element) ||
                !target.matches?.('ytm-watch, #content-wrapper, #movie_player, #player-container-id, .watch-below-the-player')
            ) return;

            const pageClass = getPageClass(location.href);
            const isWatch = pageClass === 'watch';
            const isShorts = pageClass === 'shorts';
            const player = document.querySelector('#movie_player');

            if (player) {
                if (isWatch) {
                    player.mute?.();
                    player.seekTo?.(player.getDuration?.() / 2);
                    bindListener(player, 'onStateChange', (state) => {
                        if (state === 1) player.pauseVideo?.();
                    });
                } else if (isShorts) {
                    player.unMute?.();
                }
            }

            if (ro && player !== observedPlayer) {
                ro.disconnect();
                if (player) {
                    ro.observe(player);
                    observedPlayer = player;
                } else {
                    observedPlayer = null;
                }
            }

            if (!isWatch) return;

            document.getElementById('player-container-id')?.style.setProperty('background-color', 'black');
            document.getElementById('player')?.style.setProperty('visibility', 'hidden');

            if (document.querySelector('#content-wrapper')) {
                setPlaylistSaftHeight();
            }

            document.querySelectorAll('.watch-below-the-player').forEach(node => {
                if (node.dataset.captured === 'true') return;

                ['touchmove', 'touchend'].forEach(type => {
                    bindListener(node, type, (event) => {
                        event.stopPropagation();
                    }, { passive: false, capture: true });
                });

                node.dataset.captured = 'true';
            });
        }, true);

        function run() {
            const pageClass = getPageClass(location.href);
            lite.setRefreshLayoutEnabled(['home', 'subscriptions', 'library', '@'].includes(pageClass));

            // Skip ads
            if (pageClass === 'watch') {
                const video = document.querySelector('.ad-showing video');
                if (video) video.currentTime = video.duration / 2;
            }
            // Add chat button on live page
            const isLive = document.querySelector('#movie_player')?.getPlayerResponse?.()?.playabilityStatus?.liveStreamability &&
                location.href.toLowerCase().startsWith('https://m.youtube.com/watch');
            
            if (!isLive) {
                const chatContainer = document.getElementById('live_chat_container');
                if (chatContainer) {
                    chatContainer.remove();
                    document.body.style.overflow = '';
                    document.documentElement.style.overflow = '';
                }
                document.getElementById('chatButton')?.remove();
            } else if (!document.getElementById('chatButton')) {
                const saveButton = document.querySelector('.ytSpecButtonViewModelHost.slim_video_action_bar_renderer_button');
                if (saveButton) {
                    const chatButton = saveButton.cloneNode(true);
                    chatButton.id = 'chatButton';
                    const textContent = chatButton.querySelector('.yt-spec-button-shape-next__button-text-content');
                    if (textContent) {
                        textContent.innerText = getLocalizedText('chat');
                    }
                    const svg = chatButton.querySelector('svg');
                    if (svg) {
                        svg.setAttribute("viewBox", "0 -960 960 960");
                        const path = svg.querySelector('path');
                        if (path) {
                            path.setAttribute("d", "M240-384h336v-72H240v72Zm0-132h480v-72H240v72Zm0-132h480v-72H240v72ZM96-96v-696q0-29.7 21.15-50.85Q138.3-864 168-864h624q29.7 0 50.85 21.15Q864-821.7 864-792v480q0 29.7-21.15 50.85Q821.7-240 792-240H240L96-96Zm114-216h582v-480H168v522l42-42Zm-42 0v-480 480Z");
                        }
                        bindListener(chatButton, 'click', () => {
                          let chatContainer = document.getElementById('live_chat_container');
                          if (chatContainer) {
                              if (chatContainer.style.display === 'none') {
                                  chatContainer.style.display = 'flex';
                                  document.body.style.overflow = 'hidden';
                                  document.documentElement.style.overflow = 'hidden';
                                  history.pushState({ chatOpen: true }, '', location.href + '#chat');
                              } else {
                                  chatContainer.style.display = 'none';
                                  document.body.style.overflow = '';
                                  document.documentElement.style.overflow = '';
                                  if (location.hash === '#chat') {
                                      history.back();
                                  }
                              }
                          } else {
                              const panelContainer = document.querySelector('#panel-container') || document.querySelector('.watch-below-the-player');
                              if (panelContainer) {
                                  chatContainer = document.createElement('div');
                                  chatContainer.id = 'live_chat_container';
                                  chatContainer.style.cssText = `
                                      position: fixed;
                                      top: calc(56.25vw + 48px);
                                      bottom: 0;
                                      left: 0;
                                      right: 0;
                                      z-index: 4;
                                      display: flex;
                                      flex-direction: column;
                                      box-shadow: 0 -2px 10px rgba(0,0,0,0.1);
                                      border-top-left-radius: 12px;
                                      border-top-right-radius: 12px;
                                      overflow: hidden;
                                  `;

                                  document.body.style.overflow = 'hidden';
                                  document.documentElement.style.overflow = 'hidden';
                                  history.pushState({ chatOpen: true }, '', location.href + '#chat');

                                  const header = document.createElement('div');
                                  header.style.cssText = `
                                      display: flex;
                                      justify-content: space-between;
                                      align-items: center;
                                      padding: 12px 16px;
                                      border-bottom: 1px solid var(--yt-spec-10-percent-layer);
                                      background-color: inherit;
                                      border-top-left-radius: 12px;
                                      border-top-right-radius: 12px;
                                  `;
                                  
                                  const title = document.createElement('h2');
                                  title.className = 'engagement-panel-section-list-header-title';
                                  title.innerText = getLocalizedText('chat');
                                  title.style.cssText = `
                                      font-family: "YouTube Sans", "Roboto", sans-serif;
                                      font-size: 1.8rem;
                                      font-weight: 600;
                                      color: var(--yt-spec-text-primary);
                                      margin: 0;
                                  `;
                                  
                                  const closeBtn = document.createElement('div');
                                  const closeSvg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
                                  closeSvg.setAttribute('viewBox', '0 0 24 24');
                                  closeSvg.setAttribute('width', '24');
                                  closeSvg.setAttribute('height', '24');
                                  closeSvg.setAttribute('fill', 'currentColor');
                                  closeSvg.style.display = 'block';
                                  const closePath = document.createElementNS('http://www.w3.org/2000/svg', 'path');
                                  closePath.setAttribute('d', 'M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z');
                                  closeSvg.appendChild(closePath);
                                  closeBtn.appendChild(closeSvg);
                                  closeBtn.style.cssText = 'cursor: pointer; color: var(--yt-spec-text-primary); padding: 4px;';
                                  closeBtn.onclick = (e) => {
                                      e.stopPropagation();
                                      chatContainer.style.display = 'none';
                                      document.body.style.overflow = '';
                                      document.documentElement.style.overflow = '';
                                      if (location.hash === '#chat') {
                                          history.back();
                                      }
                                  };
                                  
                                  header.appendChild(title);
                                  header.appendChild(closeBtn);
                                  chatContainer.appendChild(header);
                                  
                                  const videoId = getVideoId(location.href);
                                  if (videoId) {
                                      const iframe = document.createElement('iframe');
                                      iframe.id = 'chatIframe';
                                      const isDarkMode = document.documentElement.getAttribute('dark') === 'true' || 
                                                         window.matchMedia('(prefers-color-scheme: dark)').matches;
                                      chatContainer.style.backgroundColor = isDarkMode ? '#0f0f0f' : '#ffffff';
                                      iframe.src = `https://www.youtube.com/live_chat?v=${videoId}&embed_domain=${location.hostname}${isDarkMode ? '&dark_theme=1' : ''}`;
                                      iframe.style.cssText = 'width: 100%; height: 100%; border: none; flex: 1; background-color: transparent;';
                                      chatContainer.appendChild(iframe);
                                      panelContainer?.insertBefore(chatContainer, panelContainer.firstChild);

                                      bindListener(window, 'popstate', () => {
                                          if (chatContainer && chatContainer.style.display !== 'none' && !location.hash.includes('chat')) {
                                              chatContainer.style.display = 'none';
                                              document.body.style.overflow = '';
                                              document.documentElement.style.overflow = '';
                                          }
                                      });
                                  }
                              }
                          }
                        });
                        saveButton.parentElement?.insertBefore(chatButton, saveButton);
                    }
                }
            }
            // Add download and queue buttons
            const oldDownloadButton = document.getElementById('downloadButton');
            const oldQueueButton = document.getElementById('queueButton');
            const downloadButton = oldDownloadButton;
            const queueButton = oldQueueButton;
            if (isLive || pageClass !== 'watch') {
                if (oldDownloadButton) oldDownloadButton.remove();
                if (oldQueueButton) queueButton.remove();
            } else {
                const saveButton = document.querySelector('.ytSpecButtonViewModelHost.slim_video_action_bar_renderer_button');
                if (saveButton && saveButton.parentElement) {
                    const actionBar = saveButton.parentElement;
                    if (oldDownloadButton && (oldDownloadButton.parentElement !== actionBar || !oldDownloadButton.isConnected)) {
                        downloadButton.remove();
                    }
                    if (oldQueueButton && (oldQueueButton.parentElement !== actionBar || !oldQueueButton.isConnected)) {
                        queueButton.remove();
                    }
                    if (!actionBar.querySelector('#downloadButton')) {
                        const downloadButton = saveButton.cloneNode(true);
                        downloadButton.id = 'downloadButton';
                        const textContent = downloadButton.querySelector('.yt-spec-button-shape-next__button-text-content');
                        if (textContent) {
                            textContent.innerText = getLocalizedText('download');
                        }
                        const svg = downloadButton.querySelector('svg');
                        if (svg) {
                            svg.setAttribute("viewBox", "0 -960 960 960");
                            const path = svg.querySelector('path');
                            if (path) {
                                path.setAttribute("d", "M480-328.46 309.23-499.23l42.16-43.38L450-444v-336h60v336l98.61-98.61 42.16 43.38L480-328.46ZM252.31-180Q222-180 201-201q-21-21-21-51.31v-108.46h60v108.46q0 4.62 3.85 8.46 3.84 3.85 8.46 3.85h455.38q4.62 0 8.46-3.85 3.85-3.84 3.85-8.46v-108.46h60v108.46Q780-222 759-201q-21 21-51.31 21H252.31Z");
                            }
                            bindListener(downloadButton, 'click', () => {
                                lite.download(location.href)
                            });
                            actionBar.insertBefore(downloadButton, saveButton);
                        }
                    }
                    if (!actionBar.querySelector('#queueButton')) {
                        const queueButton = saveButton.cloneNode(true);
                        queueButton.id = 'queueButton';
                        const queueText = queueButton.querySelector('.yt-spec-button-shape-next__button-text-content');
                        if (queueText) {
                            queueText.innerText = getLocalizedText('add_to_queue');
                        }
                        const queueSvg = queueButton.querySelector('svg');
                        if (queueSvg) {
                            queueSvg.setAttribute("viewBox", "0 -960 960 960");
                            const queuePath = queueSvg.querySelector('path');
                            if (queuePath) {
                                queuePath.setAttribute("d", "M120-320v-80h280v80H120Zm0-160v-80h440v80H120Zm0-160v-80h440v80H120Zm520 480v-160H480v-80h160v-160h80v160h160v80H720v160h-80Z");
                            }
                            bindListener(queueButton, 'click', () => {
                                const playerDetails = globalThis.ytInitialPlayerResponse?.videoDetails ?? {};
                                const videoId = getVideoId(location.href);
                                const thumbnails = playerDetails.thumbnail?.thumbnails;
                                const thumbnailUrl = Array.isArray(thumbnails) && thumbnails.length > 0
                                    ? thumbnails[thumbnails.length - 1]?.url
                                    : document.querySelector('meta[property=\"og:image\"]')?.content ?? null;
                                if (videoId) {
                                    lite.addToQueue(JSON.stringify({
                                        videoId,
                                        url: location.href,
                                        title: playerDetails.title ?? document.title,
                                        author: playerDetails.author ?? '',
                                        thumbnailUrl
                                    }));
                                }
                            });
                            actionBar.insertBefore(queueButton, saveButton);
                        }
                    }
                }
            }

            if (pageClass !== 'select_site') return;

            const settings = document.querySelector('ytm-settings');
            const button = settings?.firstElementChild;
            if (!settings || !button || !button.querySelector('svg')) return;

            // Add about button on settings page
            if (!document.getElementById('aboutButton')) {
                const aboutButton = button.cloneNode(true);
                aboutButton.id = 'aboutButton';
                const textElement = aboutButton.querySelector('.yt-core-attributed-string');
                if (textElement) {
                    textElement.innerText = getLocalizedText('about');
                }
                const svg = aboutButton.querySelector('svg');
                if (svg) {
                    svg.setAttribute("viewBox", "0 -960 960 960");
                    const path = svg.querySelector('path');
                    if (path) {
                        path.setAttribute("d", "M444-288h72v-240h-72v240Zm35.79-312q15.21 0 25.71-10.29t10.5-25.5q0-15.21-10.29-25.71t-25.5-10.5q-15.21 0-25.71 10.29t-10.5 25.5q0 15.21 10.29 25.71t25.5 10.5Zm.49 504Q401-96 331-126t-122.5-82.5Q156-261 126-330.96t-30-149.5Q96-560 126-629.5q30-69.5 82.5-122T330.96-834q69.96-30 149.5-30t149.04 30q69.5 30 122 82.5T834-629.28q30 69.73 30 149Q864-401 834-331t-82.5 122.5Q699-156 629.28-126q-69.73 30-149 30Zm-.28-72q130 0 221-91t91-221q0-130-91-221t-221-91q-130 0-221 91t-91 221q0 130 91 221t221 91Zm0-312Z");
                    }
                }
                bindListener(aboutButton, 'click', () => {
                    lite.about();
                });
                const children = settings.children;
                const index = Math.max(0, children.length - 1);
                settings?.insertBefore(aboutButton, children[index]);
            }

            // Add download button on setting page
            if (!document.getElementById('downloadButton')) {
                const downloadButton = button.cloneNode(true);
                downloadButton.id = 'downloadButton';
                const textElement = downloadButton.querySelector('.yt-core-attributed-string');
                if (textElement) {
                    textElement.innerText = getLocalizedText('download');
                }
                const svg = downloadButton.querySelector('svg');
                if (svg) {
                    svg.setAttribute("viewBox", "0 -960 960 960");
                    const path = svg.querySelector('path');
                    if (path) {
                        path.setAttribute("d", "M480-336 288-528l51-51 105 105v-342h72v342l105-105 51 51-192 192ZM263.72-192Q234-192 213-213.15T192-264v-72h72v72h432v-72h72v72q0 29.7-21.16 50.85Q725.68-192 695.96-192H263.72Z");
                    }
                }
                bindListener(downloadButton, 'click', () => {
                    lite.download();
                });
                settings?.insertBefore(downloadButton, button);
            }

            // Add extension button on settings page
            if (!document.getElementById('extensionButton')) {
                const extensionButton = button.cloneNode(true);
                extensionButton.id = 'extensionButton';
                const textElement = extensionButton.querySelector('.yt-core-attributed-string');
                if (textElement) {
                    textElement.innerText = getLocalizedText('extension');
                }
                const svg = extensionButton.querySelector('svg');
                if (svg) {
                    svg.setAttribute("viewBox", "0 -960 960 960");
                    const path = svg.querySelector('path');
                    if (path) {
                        path.setAttribute("d", "M384-144H216q-29.7 0-50.85-21.15Q144-186.3 144-216v-168q40-2 68-29.5t28-66.5q0-39-28-66.5T144-576v-168q0-29.7 21.15-50.85Q186.3-816 216-816h168q0-40 27.77-68 27.78-28 68-28Q520-912 548-884.16q28 27.84 28 68.16h168q29.7 0 50.85 21.15Q816-773.7 816-744v168q40 0 68 27.77 28 27.78 28 68Q912-440 884.16-412q-27.84 28-68.16 28v168q0 29.7-21.15 50.85Q773.7-144 744-144H576q-2-40-29.38-68t-66.5-28q-39.12 0-66.62 28-27.5 28-29.5 68Zm-168-72h112q20-45 61.5-70.5T480-312q49 0 90.5 25.5T632-216h112v-240h72q9.6 0 16.8-7.2 7.2-7.2 7.2-16.8 0-9.6-7.2-16.8-7.2-7.2-16.8-7.2h-72v-240H504v-72q0-9.6-7.2-16.8-7.2-7.2-16.8-7.2-9.6 0-16.8 7.2-7.2 7.2-7.2 16.8v72H216v112q45 20 70.5 61.5T312-480q0 50.21-25.5 91.6Q261-347 216-328v112Zm264-264Z");
                    }
                }
                bindListener(extensionButton, 'click', () => {
                    lite.extension();
                });
                settings?.insertBefore(extensionButton, button);
            }
        }

        const addTapEvent = (el, handler) => {
            let startX, startY;

            bindListener(el, 'pointerdown', e => {
                startX = e.clientX;
                startY = e.clientY;
            }, { passive: false });

            bindListener(el, 'pointerup', e => {
                const dx = Math.abs(e.clientX - startX);
                const dy = Math.abs(e.clientY - startY);

                if (dx < 10 && dy < 10) {
                    handler(e);
                }
            }, { passive: false });
        };


        addTapEvent(document, e => {
            // Poster
            const renderer = e.target.closest('ytm-post-multi-image-renderer');
            if (renderer) lite.onPosterLongPress(JSON.stringify([...renderer.querySelectorAll('ytm-backstage-image-renderer')].map(el => el?.data?.image?.thumbnails?.at(-1)?.url)));
        });

        bindListener(document, 'click', handleWatchTimestampClick, true);
        
        bindListener(
            document,
            'click',
            e => {
                const a = e.target.closest('a');
                const logo = e.target.closest('ytm-home-logo');
                const nav = e.target.closest('ytm-pivot-bar-item-renderer');

                let href;
                if (nav?.data?.navigationEndpoint) {
                    href =
                        nav.data.navigationEndpoint.commandMetadata
                            ?.webCommandMetadata?.url;
                } else if (a?.href) {
                    href = a.getAttribute('href');
                } else if (logo) {
                    href = '/';
                }
                if (!href) return;
                const url = href.startsWith('http')
                    ? href
                    : 'https://m.youtube.com' + href;
                const c = getPageClass(url);
                if (c !== getPageClass(location.href)) {
                    e.preventDefault();
                    e.stopImmediatePropagation();
                    lite.openTab(url, c);
                }
            },
            true
        );

        // Mark script as totally injected
        window.injected = true;
    }
} catch (error) {
    console.error('Error in injected script:', error);
    throw error;
}
