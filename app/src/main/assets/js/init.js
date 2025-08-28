/**
 * @description basic script to YouTube page
 * @author halcyon
 * @version 1.0.0
 * @license MIT
 * @interface {remember_last_position: true, remember_quality: true, remember_playback_speed: false}
 */
try {
    // Prevent repeated injection of the script
    if (!window.injected) {

        // Get user preferences
        const prefs = JSON.parse(localStorage.getItem('preferences')) || {
            remember_last_position: true,
            remember_quality: true,
            remember_playback_speed: false
        };

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

        // Extract video ID from the URL
        const getVideoId = (url) => {
            try {
                const match = url.match(/watch\?v=([^&#]+)/);
                return match ? match[1] : null;
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

        // Observe changes in video ID and dispatch event
        const observeVideoId = () => {
            const currentVideoId = getVideoId(location.href);
            if (currentVideoId && window.videoId !== currentVideoId) {
                window.videoId = currentVideoId;
                window.dispatchEvent(new Event('onVideoIdChange'));
            }
        };

        window.addEventListener('onProgressChangeFinish', observeVideoId);

        window.addEventListener('onVideoIdChange', () => {
            if (getPageClass(location.href) === 'watch') {
                android.infoVideoDetails(location.href)
            }
        });

        // Observe page type changes and dispatch event
        const observePageClass = () => {
            const currentPageClass = getPageClass(location.href);
            if (currentPageClass && window.pageClass !== currentPageClass) {
                window.pageClass = currentPageClass;
                window.dispatchEvent(new Event('onPageClassChange'));
            }
        };

        window.addEventListener('onProgressChangeFinish', observePageClass);

        window.addEventListener('onPageClassChange', () => {
            if (window.pageClass !== 'watch') android.hidePlayback();
        });

        // Observe changes in shorts ID and dispatch event
        const observeShortsId = () => {
            const currentShortsId = getShortsId(location.href);
            if (currentShortsId && window.shortsId !== currentShortsId) {
                window.shortsId = currentShortsId;
                window.dispatchEvent(new Event('onShortsIdChange'));
            }
        };

        window.addEventListener('onProgressChangeFinish', observeShortsId);

        // Override fetch to block ads in YouTube player API responses
        if (!window.originalFetch) {
            window.originalFetch = fetch;
            fetch = async (...args) => {
                const request = args[0];
                if (request && request.url && request.url.includes('youtubei/v1/player')) {
                    try {
                        const response = await window.originalFetch(...args);
                        if (!response.ok) {
                            return response;
                        }
                        const data = await response.json();
                        // Keys to remove for ad blocking
                        const adKeys = ['playerAds', 'adPlacements', 'adBreakHeartbeatParams', 'adSlots'];
                        adKeys.forEach(key => {
                            if (data.hasOwnProperty(key)) {
                                delete data[key];
                            }
                        });
                        return new Response(JSON.stringify(data), {
                            status: response.status,
                            headers: response.headers,
                        });
                    } catch (error) {
                        console.error('Error in fetch override:', error);
                        return window.originalFetch(...args);
                    }
                }
                return window.originalFetch(...args);
            };
        }

        // Check and save playback speed and video quality setting regularly
        const saveQualityOrSpeed = (player) => {
            // Listen to speed change event
            if (prefs.remember_playback_speed) {
                player.addEventListener('onPlaybackRateChange', (data) => {
                    localStorage.setItem('playback_speed', data);
                });
            }
            // Listen to quality change event
            if (prefs.remember_quality) {
                player.addEventListener('onPlaybackQualityChange', (data) => {
                    localStorage.setItem('video_quality', data);
                });
            }
        }


        // Set video quality based on saved preference
        const setQualityOrSpeed = (player) => {
            if (player && getPageClass(location.href) === 'watch') {
                // Resume speed
                if (prefs.remember_playback_speed) {
                    const targetSpeed = parseFloat(localStorage.getItem('playback_speed')) || 1;
                    if (!isNaN(targetSpeed)) {
                        player.setPlaybackRate(targetSpeed);
                    }
                }
                // Resume quality
                if (prefs.remember_quality) {
                    const targetQuality = localStorage.getItem('video_quality') || 'default';
                    const availableQualities = player.getAvailableQualityLevels();
                    if (availableQualities.includes(targetQuality)) {
                        player.setPlaybackQualityRange(targetQuality);
                    }
                }
            }
        }

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

        // Listen fullscreenchange event
        document.addEventListener("fullscreenchange", () => {
          if (document.fullscreenElement) {
            // Enter fullscreen
            // Make the video element zoomable

          } else {
            // Exit fullscreen
          }
        });

        // MutationObserver to handle dynamic DOM changes
        const observer = new MutationObserver((mutationsList) => {
            for (const mutation of mutationsList) {
                if (mutation.type === 'childList') {
                    mutation.addedNodes.forEach((node) => {
                        if (node.nodeType !== 1) return;

                        const videoId = getVideoId(location.href);
                        if (videoId) {
                            const progressKey = `progress-${videoId}`;
                            const expirationDays = 3;
                            const expirationTime = Date.now() + expirationDays * 24 * 60 * 60 * 1000;

                            // Save playback progress for video stream
                            if (node.classList.contains('video-stream')) {
                                node.addEventListener('timeupdate', () => {
                                    if (node.currentTime > 0) {
                                        const progressData = {
                                            time: node.currentTime,
                                            expiration: expirationTime
                                        };
                                        localStorage.setItem(progressKey, JSON.stringify(progressData));
                                    }
                                });
                            }

                            // Handle video player initialization
                            if (node.id === 'movie_player') {
                                window.lastPlayerState = -1;
                                node.addEventListener('onStateChange', (data) => {
                                    if ([1, 3].includes(data) && window.lastPlayerState === -1 && getPageClass(location.href) === 'watch') {
                                        // Dispatch global event to notify video start
                                        window.dispatchEvent(new Event("onVideoStart"));
                                        // Resume playback from saved progress
                                        const progressData = JSON.parse(localStorage.getItem(progressKey) || '{}');
                                        const savedTime = progressData.time || 0;
                                        const expiration = progressData.expiration || 0;
                                        if (Date.now() < expiration && savedTime > 5 && node.getDuration() - savedTime > 5) {
                                            if (prefs.remember_last_position) {
                                                node.seekTo(savedTime);
                                            }
                                        } else {
                                            localStorage.removeItem(progressKey);
                                        }

                                        // Save and resume playback speed and video quality
                                        setQualityOrSpeed(node);
                                        saveQualityOrSpeed(node);

                                        // Show and sync playback controls with Android
                                        const title = node.getPlayerResponse().videoDetails.title;
                                        const author = node.getPlayerResponse().videoDetails.author;
                                        const thumbnail = `https://img.youtube.com/vi/${getVideoId(location.href)}/sddefault.jpg`;
                                        const duration = node.getDuration();
                                        android.showPlayback(title, author, thumbnail, duration);

                                        const player = node.querySelector('.video-stream');
                                        player.addEventListener('timeupdate', () => {
                                            const currentTimeMs = Math.round(node.getCurrentTime() * 1000);
                                            const isPlaying = (node.getPlayerState() === 1);
                                            android.updatePlayback(currentTimeMs, node.getPlaybackRate(), isPlaying);
                                        });

                                        // Add playback control event listeners
                                        window.addEventListener('play', () => node.playVideo());
                                        window.addEventListener('pause', () => node.pauseVideo());
                                        window.addEventListener('skipToNext', () => node.nextVideo());
                                        window.addEventListener('skipToPrevious', () => node.previousVideo());
                                        window.addEventListener('seek', e => node.seekTo(parseInt(e.detail.time)));
                                    }
                                    window.lastPlayerState = data;
                                });
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
                                    const video = document.querySelector("#movie_player");
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
                                            path.setAttribute("d", "M358.15-160H200q-16.85 0-28.42-11.58Q160-183.15 160-200v-158.15q34.15-10 57.08-37.81Q240-423.77 240-460q0-36.23-22.92-64.04-22.93-27.81-57.08-37.81V-720q0-16.85 11.58-28.42Q183.15-760 200-760h160q10.77-34.31 37.85-54.85 27.07-20.54 62.15-20.54t62.15 20.54Q549.23-794.31 560-760h160q16.85 0 28.42 11.58Q760-736.85 760-720v160q34.31 10.77 54.85 37.85 20.54 27.07 20.54 62.15t-20.54 62.15Q794.31-370.77 760-360v160q0 16.85-11.58 28.42Q736.85-160 720-160H561.85q-10.77-36.15-38.81-58.08Q495-240 460-240t-63.04 21.92q-28.04 21.93-38.81 58.08ZM200-200h131.92q17.08-39.85 52.77-59.92Q420.38-280 460-280q39.62 0 75.31 20.08Q571-239.85 588.08-200H720v-195.38h18.46q28.77-4.62 42.85-23.7 14.07-19.07 14.07-40.92t-14.07-40.92q-14.08-19.08-42.85-23.7H720V-720H524.62v-18.46q-4.62-28.77-23.7-42.85-19.07-14.07-40.92-14.07t-40.92 14.07q-19.08 14.08-23.7 42.85V-720H200v131.08q37.08 17.69 58.54 52.77Q280-501.08 280-460q0 40.85-21.46 75.92-21.46 35.08-58.54 53V-200Zm260-260Z");
                                        }
                                    }
                                    extensionButton.addEventListener('click', () => {
                                        android.extension();
                                    });
                                    settings.insertBefore(extensionButton, button);
                                }
                            }
                        }
                    });
                }
            }
        });

        // Start observing DOM changes
        observer.observe(document.documentElement, {
            childList: true,
            subtree: true
        });

        // Mark script as totally injected
        window.injected = true;
    }
} catch (error) {
    console.error('Error in injected script:', error);
    throw error;
}