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
                'zh': { 'loop': '循环播放', 'download': '下载', 'ok': '确定', 'video': '视频', 'cover': '封面', 'extension': '插件', 'share': '分享' },
                'en': { 'loop': 'Loop Play', 'download': 'Download', 'ok': 'OK', 'video': 'Video', 'cover': 'Cover', 'extension': 'Extension', 'share': 'Share' },
                'ja': { 'loop': 'ループ再生', 'download': 'ダウンロード', 'ok': 'はい', 'video': 'ビデオ', 'cover': 'カバー', 'extension': 'プラグイン', 'share': '共有' },
                'ko': { 'loop': '반복 재생', 'download': '시모타코', 'ok': '확인', 'video': '비디오', 'cover': '커버', 'extension': '플러그인', 'share': '공유' },
                'fr': { 'loop': 'Lecture en boucle', 'download': 'Télécharger', 'ok': "D'accord", 'video': 'vidéo', 'cover': 'couverture', 'extension': 'extension', 'share': 'partager', 'downloading': 'Téléchargement en cours' },
                'ru': { 'loop': 'Повторение', 'download': 'Скачать', 'ok': 'ОК', 'video': 'видео', 'cover': 'обложка', 'extension': 'расширение', 'share': 'поделиться', 'downloading': 'Загрузка' },
                'tr': { 'loop': 'Döngü', 'download': 'İndir', 'ok': 'Tamam', 'video': 'Vide', 'cover': 'Kapak', 'extension': 'Uzantı', 'share': 'Paylaş', 'downloading': 'Yükleniyor' },
            };
            const lang = (document.body.lang || 'en').substring(0, 2).toLowerCase();
            return languages[lang] ? languages[lang][key] : languages['en'][key];
        };

        // Determine the type of YouTube page based on the URL
        const getPageClass = (url) => {
            url = url.toLowerCase();
            if (url.startsWith('https://m.youtube.com/shorts')) return 'shorts';
            if (url.startsWith('https://m.youtube.com/watch')) return 'watch';
            if (url.startsWith('https://m.youtube.com/feed/subscriptions')) return 'subscriptions';
            if (url.startsWith('https://m.youtube.com/feed/library')) return 'library';
            if (url.startsWith('https://m.youtube.com/channel')) return 'channel';
            if (url.startsWith('https://m.youtube.com/@')) return '@';
            if (url.startsWith('https://m.youtube.com/select_site')) return 'select_site';
            if (url.startsWith('https://m.youtube.com')) return 'home';
            return 'unknown';
        };

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
                console.log('should play video');
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
            if (getPageClass(location.href) === 'watch') {
                if (node.id === 'movie_player') {
                    node.setPlaybackQualityRange('tiny', 'tiny');
                    node.mute();
                    node.seekTo(node.getDuration() / 2);
                    node.addEventListener('onStateChange', (state) => {
                        if (state === 1) node.pauseVideo();
                    });
                    ro.disconnect();
                    ro.observe(node);
                } else if (node.id === 'player') {
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
            if (getPageClass(location.href) === 'watch') {
                const video = document.querySelector('.ad-showing video');
                if (video) video.currentTime = video.duration;
            }
            // Judge if the video can skip to previous or next
            if (getPageClass(location.href) === 'watch') {
                const btn = document.querySelectorAll('.player-middle-controls-prev-next-button');
                if (btn && btn.length >= 2) {
                    android.canSkipToPrevious(!btn[0].getAttribute('aria-disabled') !== 'true');
                    android.canSkipToNext(!btn[1].getAttribute('aria-disabled') !== 'true');
                }
            }
            // Add download button on watching page
            if (getPageClass(location.href) === 'watch' && !document.getElementById('downloadButton')) {
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
                    saveButton.parentElement.insertBefore(downloadButton, saveButton);
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
                        settings.insertBefore(extensionButton, button);
                    }
                }
            }

        }, 500);

        // Extract poToken
        const originalFetch = window.fetch;
        window.fetch = async function (input, init) {
        try {
            const request = input instanceof Request
            ? input
            : new Request(input, init);

            const url = request.url;
            const method = request.method;

            if (url.includes('/youtubei/v1/player') && method === 'POST') {
            const cloned = request.clone();
            const text = await cloned.text();

            if (text) {
                const json = JSON.parse(text);
                const poToken = json?.serviceIntegrityDimensions?.poToken;
                const visitorData = json?.context?.client?.visitorData;
                android.setPoToken(poToken, visitorData);
            }
            }
        } catch (e) {console.warn('fetch hook error', e);}
        return originalFetch.call(this, input, init);
        };

        const addTapEvent = (el, handler) => {
            let startX, startY;

            el.addEventListener('pointerdown', e => {
                startX = e.clientX;
                startY = e.clientY;
            });

            el.addEventListener('pointerup', e => {
                const dx = Math.abs(e.clientX - startX);
                const dy = Math.abs(e.clientY - startY);

                if (dx < 10 && dy < 10) {
                handler(e);
                }
            });
        }
        // Poster
        addTapEvent(document, e => {
            const renderer = e.target.closest('ytm-post-multi-image-renderer');
            if (renderer) {
                const urls = [...renderer.querySelectorAll('ytm-backstage-image-renderer')].map(el => el?.data?.image?.thumbnails?.at(-1)?.url)
                android.onPosterLongPress(JSON.stringify(urls));
            }
        });

        // Mark script as totally injected
        window.injected = true;
    }
} catch (error) {
    console.error('Error in injected script:', error);
    throw error;
}
