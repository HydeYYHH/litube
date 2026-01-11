/**
 * @description basic script to YouTube page
 * @author halcyon
 * @version 1.0.0
 * @license MIT
 */
try {
    // Prevent repeated injection of the script
    if (!window.injected) {
        // Utility to get localized text based on the page's language
        const getLocalizedText = (key) => {
            // Automatically translated by AI
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

        // Extract poToken
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
                            if (poToken) {
                                android.setPoToken(poToken, visitorData);
                            }
                        }
                    } catch (e) {
                        console.warn('poToken extraction error', e);
                    }
                }
                return window.originalFetch(...args);
            };
        }

        // Observe page type changes and dispatch event
        const observePageClass = () => {
            const currentPageClass = getPageClass(location.href);
            if (currentPageClass && window.pageClass !== currentPageClass) {
                window.pageClass = currentPageClass;
                window.dispatchEvent(new Event('onPageClassChange'));
            }
        };

        window.addEventListener('onProgressChangeFinish', observePageClass);

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
        window.addEventListener('onRefresh', () => {
            window.location.reload();
        });

        // Notify Android when page loading is finished
        window.addEventListener('onProgressChangeFinish', () => {
            android.finishRefresh();
        });

        // Enable/disable refresh layout based on page type
        window.addEventListener('doUpdateVisitedHistory', () => {
            const pageClass = getPageClass(location.href);
            if (['home', 'subscriptions', 'library', '@'].includes(pageClass)) {
                android.setRefreshLayoutEnabled(true);
            } else {
                android.setRefreshLayoutEnabled(false);
            }
        });


        // Handle player visibility based on page type
        const handlePlayerVisibility = () => {
            const pageClass = getPageClass(location.href);
            if (pageClass === 'watch') {
                android.play(location.href);
            } else {
                android.hidePlayer();
            }
        };

        // Listen for popstate events
        window.addEventListener('popstate', handlePlayerVisibility);

        // Override pushState to trigger player visibility changes
        const originalPushState = history.pushState;
        history.pushState = function (data, title, url) {
            originalPushState.call(this, data, title, url);
            handlePlayerVisibility();
        };

        // Override replaceState to trigger player visibility changes
        const originalReplaceState = history.replaceState;
        history.replaceState = function (data, title, url) {
            originalReplaceState.call(this, data, title, url);
            handlePlayerVisibility();
        };


        // Set player dynamic height
        window.changePlayerHeight = () => {
            if (getPageClass(location.href) !== 'watch') return;
            const player = document.querySelector('#movie_player');
            if (!player) return;
            android.setPlayerHeight(player.clientHeight);
        }

        const ro = new ResizeObserver(window.changePlayerHeight);

        document.addEventListener('animationstart', (e) => {
            if (e.animationName !== 'nodeInserted') return;
            const node = e.target;
            const pageClass = getPageClass(location.href);

            if (node.id === 'movie_player') {
                if (pageClass === 'watch') {
                    node.mute();
                    node.seekTo(node.getDuration() / 2);
                    node.addEventListener('onStateChange', (state) => {
                        if (state === 1) node.pauseVideo();
                    });
                } else if (pageClass === 'shorts') {
                    node.unMute();
                }
                ro.disconnect();
                ro.observe(node);
            } else if (pageClass === 'watch') {
                if (node.id === 'player') {
                    node.style.visibility = 'hidden';
                } else if (node.id === 'player-container-id') {
                    node.style.backgroundColor = 'black';
                } else if (node.classList.contains('watch-below-the-player')) {
                    ['touchmove', 'touchend'].forEach(event => {
                        node.addEventListener(event, e => {
                            e.stopPropagation();
                        }, { passive: false, capture: true });
                    });
                }
            }
        }, false);

        setInterval(() => {
            // Skip ads
            const pageClass = getPageClass(location.href);
            if (pageClass === 'watch') {
                const video = document.querySelector('.ad-showing video');
                if (video) video.currentTime = video.duration;
            }
            // Add chat button on live page
            const isLive = document.querySelector('#movie_player')?.getPlayerResponse()?.playabilityStatus?.liveStreamability &&
                location.href.toLowerCase().startsWith('https://m.youtube.com/watch');
            
            if (isLive) {
                 if (!document.getElementById('chatButton')) {
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
                        } else return;
                        chatButton.addEventListener('click', () => {
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

                                          window.addEventListener('popstate', () => {
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
            } else {
                const chatContainer = document.getElementById('live_chat_container');
                if (chatContainer) {
                    chatContainer.remove();
                    document.body.style.overflow = '';
                    document.documentElement.style.overflow = '';
                }
                const chatButton = document.getElementById('chatButton');
                if (chatButton) chatButton.remove();
            }
            // Add download button on watching page
            if (!isLive && getPageClass(location.href) === 'watch' && !document.getElementById('downloadButton')) {
                const saveButton = document.querySelector('.ytSpecButtonViewModelHost.slim_video_action_bar_renderer_button');
                if (saveButton) {
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
                    } else return; // fix: avoid clone incomplete node
                    downloadButton.addEventListener('click', () => {
                        // opt: fetch video details
                        android.download(location.href)
                    });
                    saveButton.parentElement?.insertBefore(downloadButton, saveButton);
                }
            }

            // Add about button on settings page
            if (getPageClass(location.href) === 'select_site' && !document.getElementById('aboutButton')) {
                const settings = document.querySelector('ytm-settings');
                if (settings) {
                    const button = settings.firstElementChild;
                    if (button && button.querySelector('svg')) {
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
                        aboutButton.addEventListener('click', () => {
                            android.about();
                        });
                        const children = settings.children;
                        const index = Math.max(0, children.length - 1);
                        settings?.insertBefore(aboutButton, children[index]);
                    }
                }
            }
            // Add download button on setting page
             if (getPageClass(location.href) === 'select_site' && !document.getElementById('downloadButton')) {
                const settings = document.querySelector('ytm-settings');
                if (settings) {
                    const button = settings.firstElementChild;
                    if (button && button.querySelector('svg')) {
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
                        downloadButton.addEventListener('click', () => {
                            android.download();
                        });
                        settings?.insertBefore(downloadButton, button);
                    }
                }
            }

            // Add extension button on settings page
            if (getPageClass(location.href) === 'select_site' && !document.getElementById('extensionButton')) {
                const settings = document.querySelector('ytm-settings');
                if (settings) {
                    const button = settings.firstElementChild;
                    if (button && button.querySelector('svg')) {
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
                        extensionButton.addEventListener('click', () => {
                            android.extension();
                        });
                        settings?.insertBefore(extensionButton, button);
                    }
                }
            }

        }, 500);

        const addTapEvent = (el, handler) => {
            let startX, startY;

            el.addEventListener('pointerdown', e => {
                startX = e.clientX;
                startY = e.clientY;
            }, { passive: false });

            el.addEventListener('pointerup', e => {
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
            if (renderer) android.onPosterLongPress(JSON.stringify([...renderer.querySelectorAll('ytm-backstage-image-renderer')].map(el => el?.data?.image?.thumbnails?.at(-1)?.url)));
        });
        
        document.addEventListener(
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
                    android.openTab(url, c);
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
