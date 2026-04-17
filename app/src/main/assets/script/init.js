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
                'zh': { 'download': '下载', 'add_to_queue': '加入队列', 'open_with': '打开方式', 'extension': '扩展', 'chat': '聊天室', 'about': '关于' },
                'zt': { 'download': '下載', 'add_to_queue': '加入佇列', 'open_with': '開啟方式', 'extension': '擴充功能', 'chat': '聊天室', 'about': '關於' },
                'en': { 'download': 'Download', 'add_to_queue': 'Add to queue', 'open_with': 'Open with', 'extension': 'Extension', 'chat': 'Chat', 'about': 'About' },
                'ja': { 'download': 'ダウンロード', 'add_to_queue': 'キューに追加', 'open_with': 'アプリで開く', 'extension': '拡張機能', 'chat': 'チャット', 'about': 'このアプリについて' },
                'ko': { 'download': '다운로드', 'add_to_queue': '대기열에 추가', 'open_with': '다른 앱으로 열기', 'extension': '플러그인', 'chat': '채팅', 'about': '정보' },
                'fr': { 'download': 'Télécharger', 'add_to_queue': 'Ajouter à la file', 'open_with': 'Ouvrir avec', 'extension': 'Extension', 'chat': 'Chat', 'about': 'À propos' },
                'ru': { 'download': 'Скачать', 'add_to_queue': 'Добавить в очередь', 'open_with': 'Открыть с помощью', 'extension': 'Расширение', 'chat': 'Чат', 'about': 'О программе' },
                'tr': { 'download': 'İndir', 'add_to_queue': 'Kuyruğa ekle', 'open_with': 'Birlikte aç', 'extension': 'Uzantı', 'chat': 'Sohbet', 'about': 'Hakkında' },
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

        // Polling optimization
        const backoff = () => {
            const delays = [128, 256, 512, 1024, 2048];
            let tmr = null;
            let ver = 0;
            return (fn) => {
                clearTimeout(tmr);
                const v = ++ver;
                let k = 0;
                const run = () => {
                    if (v !== ver) return;
                    const done = fn() === true;
                    if (done) return;
                    tmr = setTimeout(run, delays[k] ?? 2048);
                    k += 1;
                };
                run();
            };
        };

        const bindListener = (obj, type, fn, options) => {
            if (!obj?.addEventListener || !obj?.removeEventListener || typeof fn !== 'function') return;
            const capture = typeof options === 'boolean' ? options : !!options?.capture;
            obj.removeEventListener(type, fn, capture);
            obj.addEventListener(type, fn, options);
        };

        window.__liteActive = window.__liteActive !== false;
        const isLiteActive = () => window.__liteActive !== false;
        const requestRun = () => {
            if (!isLiteActive()) return;
            backoff()(() => {
                if (!isLiteActive()) return true;
                run();
            });
        };
        window.__liteSetActive = (active) => {
            const nextActive = active !== false;
            window.__liteActive = nextActive;
            if (nextActive) {
                requestRun();
            }
        };

        // Utility to resize icon inside root element
        const resizeIcon = (root, size = 24) => {
            if (!(root instanceof Element)) return;
            const px = `${size}px`;
            const icon = root.querySelector('c3-icon');
            const host = root.querySelector('.yt-icon-shape');
            const svg = root.querySelector('svg');
            if (icon instanceof Element) {
                icon.style.width = px;
                icon.style.height = px;
            }
            if (host instanceof Element) {
                host.style.width = px;
                host.style.height = px;
            }
            if (svg instanceof SVGElement) {
                svg.setAttribute('width', `${size}`);
                svg.setAttribute('height', `${size}`);
                svg.style.width = px;
                svg.style.height = px;
            }
        };

        // Extract video ID from the URL
        const getVideoId = (url) => {
            try {
                const u = new URL(url, location.href);
                const queryVideoId = u.searchParams.get('v');
                if (queryVideoId) return queryVideoId;

                const segments = u.pathname.split('/').filter(Boolean);
                if (u.hostname.includes('youtu.be') && segments.length > 0) {
                    return segments[0];
                }
                const shortsIndex = segments.indexOf('shorts');
                if (shortsIndex >= 0 && segments.length > shortsIndex + 1) {
                    return segments[shortsIndex + 1];
                }
                const embedIndex = segments.indexOf('embed');
                if (embedIndex >= 0 && segments.length > embedIndex + 1) {
                    return segments[embedIndex + 1];
                }
                return null;
            } catch (error) {
                console.error('Error extracting video ID:', error);
                return null;
            }
        };

        // Remove list parms from url
        const RemoveListParmsFromWatchUrl = (url) => {
            if (!url || !lite.isQueueEnabled?.()) return url;
            try {
                const u = new URL(url, location.href);
                if (getPageClass(u.toString()) !== 'watch' || !u.searchParams.has('list')) {
                    return url;
                }
                // Queue owns watch order.
                u.searchParams.delete('list');
                return u.toString();
            } catch (error) {
                return url;
            }
        };

        const normalizeQueueItem = (item) => {
            if (!item?.videoId || !item?.title || !item?.url) {
                return null;
            }
            const videoId = String(item.videoId).trim();
            const title = String(item.title).trim();
            const url = String(item.url).trim();
            if (!videoId || !title || !url) {
                return null;
            }
            const author = item.author ? String(item.author).trim() : null;
            const thumbnailUrl = item.thumbnailUrl
                ? String(item.thumbnailUrl).trim()
                : `https://img.youtube.com/vi/${videoId}/default.jpg`;
            return { videoId, url, title, author, thumbnailUrl };
        };

        const toQueuePayload = (item) => {
            const normalized = normalizeQueueItem(item);
            return normalized ? JSON.stringify(normalized) : null;
        };

        // Get queue item data from current video data
        const getQueueItem = () => {
            const videoData = document.querySelector('#movie_player')?.getVideoData?.();
            let author = videoData?.author;
            if (!author) {
                const selectors = [
                    '#owner-sub-count',
                    'ytm-slim-owner-renderer .slim-owner-subtitle',
                    'ytm-video-owner-renderer .video-owner-title',
                    '.slim-video-metadata .yt-core-attributed-string',
                ];
                for (const selector of selectors) {
                    const text = document.querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim();
                    if (text) {
                        author = text;
                        break;
                    }
                }
            }
            const item = normalizeQueueItem({
                videoId: videoData?.video_id,
                url: location.href,
                title: videoData?.title,
                author,
                thumbnailUrl: videoData?.video_id ? `https://img.youtube.com/vi/${videoData.video_id}/default.jpg` : null,
            });
            if (!item) {
                lite.showQueueItemUnavailable?.();
                return null;
            }
            return item;
        };

        let menuQueueItem = null;
        let mediaItemPressState = null;
        let shortsSpeedPressState = null;
        const MEDIA_ITEM_LONG_PRESS_MS = 500;
        const SHORTS_SPEED_LONG_PRESS_MS = 450;

        const findShortsSpeedSurfaceFromEvent = (event) => {
            const path = typeof event?.composedPath === 'function' ? event.composedPath() : null;
            if (Array.isArray(path)) {
                for (const node of path) {
                    if (node instanceof Element && (
                        node.id === 'player-shorts-container' ||
                        node.tagName === 'SHORTS-VIDEO'
                    )) {
                        return node;
                    }
                }
            }
            const target = event?.target;
            if (target instanceof Element) {
                return target.closest?.('#player-shorts-container, shorts-video') ?? null;
            }
            return null;
        };

        // Get normalized text from selector
        const getTextFromSelector = (root, selectors) => {
            if (!(root instanceof Element)) return null;
            for (const selector of selectors) {
                const element = root.querySelector(selector);
                const text = element?.textContent?.replace(/\s+/g, ' ').trim();
                if (text) return text;
            }
            return null;
        };

        const isShortsSpeedEvent = (event) => !!shortsSpeedPressState || !!findShortsSpeedSurfaceFromEvent(event);

        // Extract queue item data from media item element
        const findNearestMediaItemInfo = (origin) => {
            if (!(origin instanceof Element)) return null;
            let node = origin;
            while (node && node !== document.body) {
                const parent = node.parentElement;
                if (!parent) return null;
                const siblings = Array.from(parent.children);
                const originIndex = siblings.indexOf(node);
                let nearest = null;
                let nearestDistance = Number.POSITIVE_INFINITY;
                siblings.forEach((sibling, index) => {
                    const match = sibling.matches?.('.media-item-info')
                        ? sibling
                        : sibling.querySelector?.('.media-item-info');
                    if (!match) return;
                    const distance = Math.abs(index - originIndex);
                    if (distance < nearestDistance) {
                        nearest = match;
                        nearestDistance = distance;
                    }
                });
                if (nearest) return nearest;
                node = parent;
            }
            return null;
        };

        const getMediaMenuQueueItem = (info) => {
            const metadataLink = info?.querySelector('.media-item-metadata a[href]');
            const href = metadataLink?.getAttribute('href');
            const videoId = getVideoId(href);
            if (!href || !videoId) return null;

            let u;
            try {
                u = new URL(href, location.origin).toString();
            } catch (error) {
                return null;
            }

            const title = getTextFromSelector(info, [
                '.media-item-headline .yt-core-attributed-string',
                '.media-item-headline',
                '.media-item-title .yt-core-attributed-string',
                '.media-item-title',
                'h3 .yt-core-attributed-string',
                'h3',
                'a[title]',
                '.yt-core-attributed-string',
            ]) || videoId;
            const author = getTextFromSelector(info, [
                '.media-item-byline .yt-core-attributed-string',
                '.media-item-byline',
                '.secondary-text .yt-core-attributed-string',
                '.secondary-text',
                '.ytm-badge-and-byline-item-byline',
                '.media-item-metadata',
            ]) || 'Unknown author';

            return normalizeQueueItem({
                videoId,
                url: u,
                title,
                author,
                thumbnailUrl: `https://img.youtube.com/vi/${videoId}/default.jpg`
            });
        };

        const buildPlaylistDownloadPayload = () => {
            const runsText = (value) => {
                if (!value) return '';
                if (typeof value.simpleText === 'string') return value.simpleText;
                if (Array.isArray(value.runs)) {
                    return value.runs.map(run => run?.text ?? '').join('');
                }
                return '';
            };
            const normalizeRenderer = (renderer, index) => {
                if (!renderer?.videoId) return null;
                const relativeUrl = renderer.navigationEndpoint?.commandMetadata?.webCommandMetadata?.url
                    || renderer.navigationEndpoint?.watchEndpoint?.url
                    || renderer.navigationEndpoint?.browseEndpoint?.canonicalBaseUrl
                    || '';
                let resolvedUrl = `https://www.youtube.com/watch?v=${renderer.videoId}`;
                if (typeof relativeUrl === 'string' && relativeUrl.length > 0) {
                    try {
                        resolvedUrl = new URL(relativeUrl, location.origin).toString();
                    } catch (error) {
                        resolvedUrl = `https://www.youtube.com/watch?v=${renderer.videoId}`;
                    }
                }
                return {
                    playlistIndex: index,
                    videoId: renderer.videoId,
                    url: resolvedUrl,
                    title: runsText(renderer.title) || renderer.videoId,
                    author: runsText(renderer.shortBylineText || renderer.longBylineText),
                    thumbnailUrl: Array.isArray(renderer.thumbnail?.thumbnails) && renderer.thumbnail.thumbnails.length > 0
                        ? renderer.thumbnail.thumbnails[renderer.thumbnail.thumbnails.length - 1].url
                        : `https://img.youtube.com/vi/${renderer.videoId}/default.jpg`,
                    durationSeconds: (() => {
                        const durationText = runsText(renderer.lengthText);
                        if (!durationText) return 0;
                        let seconds = 0;
                        for (const part of durationText.trim().split(':')) {
                            const parsed = Number.parseInt(part.trim(), 10);
                            if (!Number.isFinite(parsed)) return 0;
                            seconds = seconds * 60 + parsed;
                        }
                        return seconds;
                    })(),
                    durationText: runsText(renderer.lengthText),
                    selected: renderer.selected === true
                };
            };
            const normalizeEntries = (entries) => {
                if (!Array.isArray(entries)) return [];
                return entries.map((entry, index) => {
                    const renderer = entry?.playlistPanelVideoRenderer || entry?.playlistVideoRenderer;
                    return normalizeRenderer(renderer, index);
                }).filter(Boolean);
            };
            const playlistRoot = globalThis.ytInitialData?.contents?.singleColumnWatchNextResults?.playlist?.playlist;
            const playlistId = playlistRoot?.playlistId || (() => {
                try {
                    return new URL(location.href, location.origin).searchParams.get('list') || '';
                } catch (error) {
                    return '';
                }
            })();
            let playlistTitle = runsText(playlistRoot?.title);
            let items = normalizeEntries(playlistRoot?.contents);
            if (items.length === 0) {
                const queue = [globalThis.ytInitialData];
                const visited = new WeakSet();
                while (queue.length > 0) {
                    const node = queue.shift();
                    if (!node || typeof node !== 'object' || visited.has(node)) continue;
                    visited.add(node);
                    if (Array.isArray(node)) {
                        const normalized = normalizeEntries(node);
                        if (normalized.length > 0) {
                            items = normalized;
                            break;
                        }
                        for (const child of node) {
                            if (child && typeof child === 'object') queue.push(child);
                        }
                        continue;
                    }
                    if (Array.isArray(node.contents)) {
                        const normalized = normalizeEntries(node.contents);
                        if (normalized.length > 0) {
                            playlistTitle = playlistTitle || runsText(node.title);
                            items = normalized;
                            break;
                        }
                    }
                    for (const child of Object.values(node)) {
                        if (child && typeof child === 'object') queue.push(child);
                    }
                }
            }
            if (items.length === 0) return null;
            return JSON.stringify({
                ok: true,
                playlistId,
                title: playlistTitle || document.title || '',
                items
            });
        };

        const enableBottomSheetMixDownloadItem = (item) => {
            if (!(item instanceof Element)) return false;
            if (item.dataset.liteMixDownloadReady === 'true') return true;

            const menuItemContainer = item.querySelector('.ytListItemViewModelContainer') || item;
            const textWrapper = item.querySelector('.ytListItemViewModelTextWrapper');
            const menuButton = item.querySelector('.ytListItemViewModelMainContainer');
            const label = getLocalizedText('download');

            if (menuItemContainer instanceof Element) {
                menuItemContainer.classList.remove('ytListItemViewModelDisabled');
            }
            if (textWrapper instanceof Element) {
                const menuText = textWrapper.querySelector('.ytListItemViewModelTitle');
                if (menuText instanceof Element) {
                    menuText.textContent = label;
                }
            }
            item.setAttribute('aria-disabled', 'false');
            item.setAttribute('tabindex', '0');
            item.setAttribute('role', 'listitem');
            item.setAttribute('aria-pressed', 'false');

            if (item.dataset.liteMixDownloadBound !== 'true') {
                bindListener(item, 'click', (event) => {
                    event.preventDefault();
                    event.stopImmediatePropagation();
                    const payload = buildPlaylistDownloadPayload();
                    if (payload) {
                        lite.downloadPlaylist?.(payload);
                    }
                }, true);
                if (menuButton instanceof Element && menuButton !== item) {
                    bindListener(menuButton, 'click', (event) => {
                        event.preventDefault();
                        event.stopImmediatePropagation();
                    }, true);
                }
                item.dataset.liteMixDownloadBound = 'true';
            }

            item.dataset.liteMixDownloadReady = 'true';
            return true;
        };

        const requestOpenTab = (nextUrl, nextPageClass) => {
            if (!isLiteActive()) return;
            lite.openTab(nextUrl, nextPageClass);
        };

        const isBottomSheetMenuItemCandidate = (element) => {
            if (!(element instanceof Element)) return false;
            if (element.matches?.('ytm-menu-service-item-renderer, yt-list-item-view-model, toggleable-list-item-view-model')) {
                return true;
            }
            const button = element.querySelector('button.menu-item-button')
                || element.querySelector('button.yt-list-item-view-model__button-or-anchor')
                || element.querySelector('button');
            const text = element.querySelector('.yt-core-attributed-string');
            return button instanceof Element && text instanceof Element;
        };

        const createQueueMenuIconSvg = () => {
            const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
            svg.setAttribute('viewBox', '0 -960 960 960');
            svg.setAttribute('width', '24');
            svg.setAttribute('height', '24');
            svg.setAttribute('aria-hidden', 'true');
            svg.innerHTML = '<path d="M120-320v-80h280v80H120Zm0-160v-80h440v80H120Zm0-160v-80h440v80H120Zm520 480v-160H480v-80h160v-160h80v160h160v80H720v160h-80Z"></path>';
            return svg;
        };


        // Add "Add to queue" button in bottom sheet
        const addBottomSheetQueueMenuItem = (queueMenuItem) => {
            if (!(queueMenuItem instanceof Element)) return false;

            const menuButton = queueMenuItem.querySelector('button.menu-item-button') || queueMenuItem.querySelector('button');
            if (!(menuButton instanceof Element)) {
                return false;
            }

            let menuText = queueMenuItem.querySelector('.yt-core-attributed-string')
                || queueMenuItem.querySelector('[role="text"]')
                || queueMenuItem.querySelector('.menu-item-text')
                || queueMenuItem.querySelector('.button-text');
            if (!(menuText instanceof Element)) {
                menuText = document.createElement('span');
                menuText.className = 'yt-core-attributed-string';
                menuText.setAttribute('role', 'text');
                menuButton.appendChild(menuText);
            }

            const menuSvg = queueMenuItem.querySelector('svg');
            const menuPath = menuSvg?.querySelector('path');
            if (menuSvg instanceof SVGElement) {
                menuSvg.setAttribute("viewBox", "0 -960 960 960");
                resizeIcon(queueMenuItem);
                if (menuPath instanceof SVGElement) {
                    menuPath.setAttribute("d", "M120-320v-80h280v80H120Zm0-160v-80h440v80H120Zm0-160v-80h440v80H120Zm520 480v-160H480v-80h160v-160h80v160h160v80H720v160h-80Z");
                }
            } else {
                const iconHost = queueMenuItem.querySelector('.yt-spec-button-shape-next__icon');
                if (iconHost instanceof Element && !iconHost.querySelector('svg')) {
                    iconHost.appendChild(createQueueMenuIconSvg());
                } else if (!queueMenuItem.querySelector('svg')) {
                    menuButton.prepend(createQueueMenuIconSvg());
                }
            }

            menuText.textContent = getLocalizedText('add_to_queue');
            menuButton.setAttribute('aria-label', getLocalizedText('add_to_queue'));
            bindListener(menuButton, 'click', () => {
                const payload = queueMenuItem.dataset.liteQueuePayload;
                if (payload) {
                    lite.addToQueue(payload);
                } else {
                    lite.showQueueItemUnavailable?.();
                }
            }, true);
            return true;
        };

        const getBottomSheetMenuContainer = (origin) => {
            if (!(origin instanceof Element)) return null;
            const queue = [origin];
            while (queue.length > 0) {
                const node = queue.shift();
                if (!(node instanceof Element)) continue;
                const directMenuItems = Array.from(node.children).filter(isBottomSheetMenuItemCandidate);
                if (directMenuItems.length > 0) return node;
                queue.push(...Array.from(node.children));
            }
            return null;
        };

        const getMenuTriggerFromEvent = (event) => {
            const path = typeof event?.composedPath === 'function' ? event.composedPath() : null;
            if (Array.isArray(path)) {
                for (const node of path) {
                    if (node instanceof Element && node.matches?.('.media-item-menu')) {
                        return node;
                    }
                }
            }
            if (!(event?.target instanceof Element)) return null;
            return event.target.closest?.('.media-item-menu') || null;
        };

        const captureMenuQueueItem = (event) => {
            const trigger = getMenuTriggerFromEvent(event);
            if (!trigger) return;
            const info = findNearestMediaItemInfo(trigger);
            menuQueueItem = getMediaMenuQueueItem(info);
            // Wait for menu mount.
            backoff()(() => {
                const bottomSheetLayout = document.querySelector('.bottom-sheet-media-menu-item')
                    || document.querySelector('bottom-sheet-layout');
                if (!(bottomSheetLayout instanceof Element)) return false;
                const menuContainer = getBottomSheetMenuContainer(bottomSheetLayout);
                if (!(menuContainer instanceof Element)) return false;
                const items = Array.from(menuContainer.children)
                    .filter(child => child instanceof Element && child.matches?.('[data-lite-queue-menu-item="true"]'));
                items.forEach((node, index) => {
                    if (index > 0) {
                        node.remove();
                    }
                });
                if (!menuQueueItem?.videoId) {
                    items.forEach(node => node.remove());
                    return true;
                }
                let queueMenuElement = items[0];
                if (!(queueMenuElement instanceof Element)) {
                    queueMenuElement = menuContainer.lastElementChild?.cloneNode(true);
                    if (!(queueMenuElement instanceof Element)) return false;
                    queueMenuElement.dataset.liteQueueMenuItem = 'true';
                }
                const payload = toQueuePayload(menuQueueItem);
                if (!payload) {
                    items.forEach(node => node.remove());
                    return true;
                }
                queueMenuElement.dataset.liteQueuePayload = payload;

                if (!addBottomSheetQueueMenuItem(queueMenuElement)) {
                    return false;
                }
                if (queueMenuElement.parentElement !== menuContainer || queueMenuElement !== menuContainer.firstElementChild) {
                    menuContainer.insertBefore(queueMenuElement, menuContainer.firstElementChild);
                }
                const item = queueMenuElement?.querySelector?.('ytm-menu-item') || queueMenuElement;
                const itemRect = item?.getBoundingClientRect?.();
                const menuItemHeight = Number.isFinite(itemRect?.height) ? itemRect.height : 0;
                if (!(Number.isFinite(menuItemHeight) && menuItemHeight > 0)) return;
                setPlaylistSaftHeight(getPageClass(location.href), document.querySelector('#movie_player'), menuItemHeight);
                return true;
            });
        };

        const clearMediaItemLongPress = () => {
            if (mediaItemPressState?.timerId) {
                clearTimeout(mediaItemPressState.timerId);
            }
            mediaItemPressState = null;
        };

        const findMediaItemFromEvent = (event) => {
            const path = typeof event?.composedPath === 'function' ? event.composedPath() : null;
            if (Array.isArray(path)) {
                for (const node of path) {
                    if (node instanceof Element && node.matches?.('.media-item-menu')) {
                        return null;
                    }
                    if (node instanceof Element && node.matches?.('ytm-media-item')) {
                        return node;
                    }
                }
            }
            if (event?.target instanceof Element) {
                if (event.target.closest?.('.media-item-menu')) {
                    return null;
                }
                const mediaItem = event.target.closest?.('ytm-media-item');
                if (mediaItem instanceof Element) {
                    return mediaItem;
                }
            }
            const point = event?.touches?.[0] || event?.changedTouches?.[0] || event;
            if (Number.isFinite(point?.clientX) && Number.isFinite(point?.clientY)
                    && typeof document.elementsFromPoint === 'function') {
                const hitElements = document.elementsFromPoint(point.clientX, point.clientY);
                for (const node of hitElements) {
                    if (!(node instanceof Element)) continue;
                    if (node.matches?.('.media-item-menu') || node.closest?.('.media-item-menu')) {
                        return null;
                    }
                    if (node.matches?.('ytm-media-item')) {
                        return node;
                    }
                    const mediaItem = node.closest?.('ytm-media-item');
                    if (mediaItem instanceof Element) {
                        return mediaItem;
                    }
                }
            }
            return null;
        };

        // Add long press to speedup gesture on shorts
        const getPoint = (event) => event?.touches?.[0] || event?.changedTouches?.[0] || event;

        const getMediaItemMenuPayload = (origin) => {
            const mediaItem = origin instanceof Element && origin.matches?.('ytm-media-item')
                ? origin
                : origin?.closest?.('ytm-media-item');
            if (!(mediaItem instanceof Element)) return null;
            const metadataLink = mediaItem?.querySelector('.media-item-metadata a[href]');
            const href = metadataLink?.getAttribute('href');
            const videoId = getVideoId(href);
            if (!href || !videoId) return null;

            const normalizedUrl = new URL(href, 'https://m.youtube.com');
            normalizedUrl.searchParams.delete('list');
            normalizedUrl.searchParams.delete('index');
            normalizedUrl.searchParams.delete('pp');

            return {
                videoId,
                url: normalizedUrl.toString(),
                title: getTextFromSelector(mediaItem, [
                    '.media-item-headline .yt-core-attributed-string',
                    '.media-item-headline',
                    'h3 .yt-core-attributed-string',
                    'h3',
                ]) || videoId,
                author: getTextFromSelector(mediaItem, [
                    'ytm-badge-and-byline-renderer span[dir="auto"]',
                    '.media-item-byline .yt-core-attributed-string',
                    '.media-item-byline',
                ]),
                thumbnailUrl: `https://img.youtube.com/vi/${videoId}/hqdefault.jpg`
            };
        };

        const emitMediaItemLongPress = () => {
            if (!mediaItemPressState || mediaItemPressState.triggered) return;
            const payload = mediaItemPressState.payload;
            if (!payload) {
                clearMediaItemLongPress();
                return;
            }
            mediaItemPressState.triggered = true;
            lite.showMediaItemMenu?.(JSON.stringify(payload));
        };

        const handleMediaItemPressStart = (event) => {
            const mediaItem = findMediaItemFromEvent(event);
            if (!(mediaItem instanceof Element)) {
                clearMediaItemLongPress();
                return;
            }
            if (mediaItemPressState) {
                return;
            }
            const payload = getMediaItemMenuPayload(mediaItem);
            if (!payload) {
                clearMediaItemLongPress();
                return;
            }
            clearMediaItemLongPress();
            const point = getPoint(event);
            mediaItemPressState = {
                mediaItem,
                payload,
                startX: point.clientX,
                startY: point.clientY,
                startAt: Date.now(),
                triggered: false,
                timerId: setTimeout(emitMediaItemLongPress, MEDIA_ITEM_LONG_PRESS_MS)
            };
        };

        const handleMediaItemPressMove = (event) => {
            if (!mediaItemPressState) return;
            const point = getPoint(event);
            const dx = point.clientX - mediaItemPressState.startX;
            const dy = point.clientY - mediaItemPressState.startY;
            if (Math.abs(dx) > 12 || Math.abs(dy) > 12) {
                clearMediaItemLongPress();
            }
        };

        const handleMediaItemPressEnd = () => {
            if (!mediaItemPressState) return;
            const duration = Date.now() - mediaItemPressState.startAt;
            if (!mediaItemPressState.triggered && duration >= MEDIA_ITEM_LONG_PRESS_MS) {
                emitMediaItemLongPress();
            }
            clearMediaItemLongPress();
        };

        const handleMediaItemContextMenu = (event) => {
            if (!mediaItemPressState) return;
            event.preventDefault();
            event.stopPropagation();
            emitMediaItemLongPress();
            clearMediaItemLongPress();
        };

        const clearShortsSpeedPress = () => {
            if (shortsSpeedPressState?.timerId) {
                clearTimeout(shortsSpeedPressState.timerId);
            }
            const player = shortsSpeedPressState?.player;
            const previousStyles = shortsSpeedPressState?.previousStyles;
            const activated = !!shortsSpeedPressState?.activated;
            if (player instanceof Element && previousStyles) {
                player.style.userSelect = previousStyles.userSelect ?? '';
                player.style.webkitUserSelect = previousStyles.webkitUserSelect ?? '';
                player.style.webkitTouchCallout = previousStyles.webkitTouchCallout ?? '';
            }
            shortsSpeedPressState = null;
            if (activated) {
                player?.setPlaybackRate?.(1);
                lite.hideHint?.();
            }
        };

        const startShortsSpeedPress = (player, event) => {
            if (!(player instanceof Element) || shortsSpeedPressState) return;
            const point = getPoint(event);
            shortsSpeedPressState = {
                player,
                previousStyles: {
                    userSelect: player.style.userSelect,
                    webkitUserSelect: player.style.webkitUserSelect,
                    webkitTouchCallout: player.style.webkitTouchCallout
                },
                startX: point.clientX,
                startY: point.clientY,
                activated: false,
                timerId: setTimeout(() => {
                    if (!shortsSpeedPressState) return;
                    shortsSpeedPressState.activated = true;
                    player?.setPlaybackRate?.(2);
                    lite.showHint?.('2x', -1);
                }, SHORTS_SPEED_LONG_PRESS_MS)
            };
            player.style.userSelect = 'none';
            player.style.webkitUserSelect = 'none';
            player.style.webkitTouchCallout = 'none';
        };

        const moveShortsSpeedPress = (event) => {
            if (!shortsSpeedPressState) return;
            const point = getPoint(event);
            const dx = Math.abs(point.clientX - shortsSpeedPressState.startX);
            const dy = Math.abs(point.clientY - shortsSpeedPressState.startY);
            if (dx > 12 || dy > 12) {
                clearShortsSpeedPress();
            }
        };

        const removeActionButtonBehavior = (button) => {
            if (!(button instanceof Element)) return;
            button.removeAttribute('href');
            button.removeAttribute('target');
            button.querySelectorAll('a[href]').forEach(anchor => {
                anchor.removeAttribute('href');
                anchor.removeAttribute('target');
            });
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

        bindListener(document, 'visibilitychange', () => {
            if (!isLiteActive()) return;
            requestRun();
        });

        // Notify Android when page loading is finished
        bindListener(window, 'onProgressChangeFinish', () => {
            lite.finishRefresh();
        });

        bindListener(document, 'click', captureMenuQueueItem, true);
        bindListener(document, 'pointerdown', handleMediaItemPressStart, true);
        bindListener(document, 'pointermove', handleMediaItemPressMove, true);
        bindListener(document, 'pointerup', handleMediaItemPressEnd, true);
        bindListener(document, 'touchstart', handleMediaItemPressStart, true);
        bindListener(document, 'touchmove', handleMediaItemPressMove, true);
        bindListener(document, 'touchend', handleMediaItemPressEnd, true);
        bindListener(document, 'contextmenu', handleMediaItemContextMenu, true);

        bindListener(window, 'doUpdateVisitedHistory', () => {
            if (!isLiteActive()) return;
            requestRun();
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
            if (!isLiteActive()) return;
            handlePlayerVisibility();
            requestRun();
        });

        const getHistoryNavigationTarget = (url) => {
            if (typeof url !== 'string') return null;
            const historyUrl = RemoveListParmsFromWatchUrl(url);
            try {
                const nextUrl = new URL(historyUrl, location.href).toString();
                const nextPageClass = getPageClass(nextUrl);
                return { historyUrl, nextUrl, nextPageClass };
            } catch (error) {
                return { historyUrl, nextUrl: historyUrl, nextPageClass: null };
            }
        };

        // Override pushState to trigger player visibility changes
        const originalPushState = history.pushState;
        history.pushState = function (data, title, url) {
            const pageClass = getPageClass(location.href);
            const target = getHistoryNavigationTarget(url);
            if (target?.nextPageClass && target.nextPageClass !== pageClass) {
                requestOpenTab(target.nextUrl, target.nextPageClass);
                return;
            }
            originalPushState.call(this, data, title, target ? target.historyUrl : url);
            if (!isLiteActive()) return;
            handlePlayerVisibility();
            requestRun();
        };

        // Override replaceState to trigger player visibility changes
        const originalReplaceState = history.replaceState;
        history.replaceState = function (data, title, url) {
            const pageClass = getPageClass(location.href);
            const target = getHistoryNavigationTarget(url);
            if (target?.nextPageClass && target.nextPageClass !== pageClass) {
                requestOpenTab(target.nextUrl, target.nextPageClass);
                return;
            }
            originalReplaceState.call(this, data, title, target ? target.historyUrl : url);
            if (!isLiteActive()) return;
            handlePlayerVisibility();
            requestRun();
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

        const setPlaylistSaftHeight = (
            pageClass = getPageClass(location.href),
            player = document.querySelector('#movie_player'),
            menuItemHeight = 0
        ) => {
            const wrapper = document.querySelector('#content-wrapper');
            if (!wrapper) return;

            const extraHeight = Number.isFinite(menuItemHeight) ? Math.max(0, menuItemHeight) : 0;
            const shouldCompensate = extraHeight > 0;
            if (!player) {
                if (wrapper.dataset.maxheight === 'true') {
                    wrapper.style.maxHeight = '';
                    delete wrapper.dataset.maxheight;
                }
                return;
            }

            if (pageClass !== 'watch' && !shouldCompensate) {
                if (wrapper.dataset.maxheight === 'true') {
                    wrapper.style.maxHeight = '';
                    delete wrapper.dataset.maxheight;
                }
                return;
            }

            const viewportHeight = window.visualViewport?.height || window.innerHeight || document.documentElement.clientHeight || 0;
            const playerHeight = player.clientHeight || 0;
            const baseHeight = Math.floor(viewportHeight - playerHeight - WATCH_CONTENT_WRAPPER_OFFSET);
            const nextHeight = Math.max(WATCH_CONTENT_WRAPPER_MIN_HEIGHT, baseHeight + extraHeight);
            const nextMaxHeight = `${nextHeight}px`;
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

            const videoId = getVideoId(location.href);
            const targetVideoId = getVideoId(targetUrl.toString());
            if (!videoId || videoId !== targetVideoId) return;

            const timestampSeconds = parseTimestampSeconds(targetUrl.searchParams.get('t') ?? targetUrl.searchParams.get('start'));
            if (timestampSeconds == null) return;
            if (!lite.seekLoadedVideo?.(targetUrl.toString(), timestampSeconds * 1000)) return;

            event.preventDefault();
            event.stopImmediatePropagation();
        };

        bindListener(document, 'animationstart', (event) => {
            const target = event.target;
            if (event.animationName !== 'nodeInserted' || !(target instanceof Element)) return;

            if (!target.matches?.('ytm-watch, #content-wrapper, #movie_player, #player-container-id, .watch-below-the-player')) return;

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
                backoff()(() => {
                    const path = document.querySelector('bottom-sheet-layout path[d*="M12 2a1 1"]');
                    const item = path?.closest?.('yt-list-item-view-model');
                    if (!(item instanceof Element)) return false;
                    return enableBottomSheetMixDownloadItem(item);
                });
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
            if (!isLiteActive()) return;
            const pageClass = getPageClass(location.href);
            lite.setRefreshLayoutEnabled(['home', 'subscriptions', 'library', '@'].includes(pageClass));
            const moviePlayer = document.querySelector('#movie_player');
            document.querySelectorAll('.yt-searchbox-suggestions-container').forEach(container => {
                if (container instanceof HTMLElement) {
                    if (pageClass === 'watch') {
                        container.style.display = 'none';
                    } else {
                        container.style.removeProperty('display');
                    }
                }
            });
            if (pageClass === 'shorts' && moviePlayer instanceof Element && moviePlayer.dataset.liteShortsSpeedGestureBound !== 'true') {
                moviePlayer.dataset.liteShortsSpeedGestureBound = 'true';
                bindListener(document, 'pointerdown', event => {
                    const surface = findShortsSpeedSurfaceFromEvent(event);
                    const player = document.querySelector('#movie_player');
                    if (!(surface instanceof Element) || !(player instanceof Element)) return;
                    startShortsSpeedPress(player, event);
                }, { passive: false, capture: true });
                bindListener(document, 'pointermove', event => {
                    if (!shortsSpeedPressState) return;
                    moveShortsSpeedPress(event);
                }, { passive: false, capture: true });
                bindListener(document, 'pointerup', event => {
                    if (!shortsSpeedPressState) return;
                    clearShortsSpeedPress();
                }, { passive: false, capture: true });
                bindListener(document, 'pointercancel', event => {
                    if (!shortsSpeedPressState) return;
                    clearShortsSpeedPress();
                }, { passive: false, capture: true });
                bindListener(document, 'touchstart', event => {
                    const surface = findShortsSpeedSurfaceFromEvent(event);
                    const player = document.querySelector('#movie_player');
                    if (!(surface instanceof Element) || !(player instanceof Element)) return;
                    startShortsSpeedPress(player, event);
                }, { passive: false, capture: true });
                bindListener(document, 'touchmove', event => {
                    if (!shortsSpeedPressState) return;
                    moveShortsSpeedPress(event);
                }, { passive: false, capture: true });
                bindListener(document, 'touchend', event => {
                    if (!shortsSpeedPressState) return;
                    clearShortsSpeedPress();
                }, { passive: false, capture: true });
                bindListener(document, 'touchcancel', event => {
                    if (!shortsSpeedPressState) return;
                    clearShortsSpeedPress();
                }, { passive: false, capture: true });
                bindListener(document, 'contextmenu', event => {
                    const surface = findShortsSpeedSurfaceFromEvent(event);
                    const player = document.querySelector('#movie_player');
                    if (!(surface instanceof Element) && !shortsSpeedPressState) return;
                    event.preventDefault();
                    event.stopImmediatePropagation();
                    if (player instanceof Element && !shortsSpeedPressState) {
                        startShortsSpeedPress(player, event);
                    }
                }, { passive: false, capture: true });
                bindListener(document, 'selectstart', event => {
                    if (!isShortsSpeedEvent(event)) return;
                    event.preventDefault();
                    event.stopImmediatePropagation();
                }, { passive: false, capture: true });
            } else if (pageClass !== 'shorts' && shortsSpeedPressState) {
                clearShortsSpeedPress();
            }

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
            const oldOpenWithButton = document.getElementById('openWithButton');
            const downloadButton = oldDownloadButton;
            const queueButton = oldQueueButton;
            const openWithButton = oldOpenWithButton;
            if (isLive || pageClass !== 'watch') {
                if (oldDownloadButton) oldDownloadButton.remove();
                if (oldQueueButton) queueButton.remove();
                if (oldOpenWithButton) openWithButton.remove();
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
                    if (oldOpenWithButton && (oldOpenWithButton.parentElement !== actionBar || !oldOpenWithButton.isConnected)) {
                        openWithButton.remove();
                    }
                    if (!actionBar.querySelector('#downloadButton')) {
                        const downloadButton = saveButton.cloneNode(true);
                        downloadButton.id = 'downloadButton';
                        removeActionButtonBehavior(downloadButton);
                        const textContent = downloadButton.querySelector('.yt-spec-button-shape-next__button-text-content');
                        if (textContent) {
                            textContent.innerText = getLocalizedText('download');
                        }
                        const svg = downloadButton.querySelector('svg');
                        if (svg) {
                            svg.setAttribute("viewBox", "0 -960 960 960");
                            const path = svg.querySelector('path');
                            if (path) {
                                path.setAttribute("d", "M480-336 288-528l51-51 105 105v-246h72v246l105-105 51 51-192 192ZM264-192q-30 0-51-21t-21-51v-72h72v72h432v-72h72v72q0 30-21 51t-51 21H264Z");
                            }
                            resizeIcon(downloadButton);
                            bindListener(downloadButton, 'click', (event) => {
                                event.preventDefault();
                                event.stopImmediatePropagation();
                                lite.download(location.href)
                            }, true);
                            actionBar.insertBefore(downloadButton, saveButton);
                        }
                    }
                    if (!actionBar.querySelector('#queueButton')) {
                        const queueButton = saveButton.cloneNode(true);
                        queueButton.id = 'queueButton';
                        removeActionButtonBehavior(queueButton);
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
                            resizeIcon(queueButton);
                            bindListener(queueButton, 'click', (event) => {
                                event.preventDefault();
                                event.stopImmediatePropagation();
                                const payload = toQueuePayload(getQueueItem());
                                if (payload) {
                                    lite.addToQueue(payload);
                                }
                            }, true);
                            actionBar.insertBefore(queueButton, saveButton);
                        }
                    }
                    if (!actionBar.querySelector('#openWithButton')) {
                        const openWithButton = saveButton.cloneNode(true);
                        openWithButton.id = 'openWithButton';
                        removeActionButtonBehavior(openWithButton);
                        const openWithText = openWithButton.querySelector('.yt-spec-button-shape-next__button-text-content');
                        if (openWithText) {
                            openWithText.innerText = getLocalizedText('open_with');
                        }
                        const openWithSvg = openWithButton.querySelector('svg');
                        if (openWithSvg) {
                            openWithSvg.setAttribute("viewBox", "0 -960 960 960");
                            const openWithPath = openWithSvg.querySelector('path');
                            if (openWithPath) {
                                openWithPath.setAttribute("d", "M648-96q-50 0-85-35t-35-85q0-9 4-29L295-390q-16 14-36.05 22-20.04 8-42.95 8-50 0-85-35t-35-85q0-50 35-85t85-35q23 0 43 8t36 22l237-145q-2-7-3-13.81-1-6.81-1-15.19 0-50 35-85t85-35q50 0 85 35t35 85q0 50-35 85t-85 35q-23 0-43-8t-36-22L332-509q2 7 3 13.81 1 6.81 1 15.19 0 8.38-1 15.19-1 6.81-3 13.81l237 145q16-14 36.05-22 20.04-8 42.95-8 50 0 85 35t35 85q0 50-35 85t-85 35Zm0-72q20.4 0 34.2-13.8Q696-195.6 696-216q0-20.4-13.8-34.2Q668.4-264 648-264q-20.4 0-34.2 13.8Q600-236.4 600-216q0 20.4 13.8 34.2Q627.6-168 648-168ZM216-432q20.4 0 34.2-14 13.8-14 13.8-34t-13.8-34q-13.8-14-34.2-14-20.4 0-34.2 14-13.8 14-13.8 34t13.8 34q13.8 14 34.2 14Zm466-277.8q14-13.8 14-34.2 0-20.4-13.8-34.2Q668.4-792 648-792q-20.4 0-34.2 13.8Q600-764.4 600-744q0 20.4 14 34.2 14 13.8 34 13.8t34-13.8ZM648-216ZM216-480Zm432-264Z");
                            }
                            resizeIcon(openWithButton);
                            bindListener(openWithButton, 'click', (event) => {
                                event.preventDefault();
                                event.stopImmediatePropagation();
                                lite.openWith?.(location.href);
                            }, true);
                            actionBar.insertBefore(openWithButton, saveButton);
                        }
                    }
                }
            }

            if (pageClass !== 'select_site') {
                return;
            }

            const settings = document.querySelector('ytm-settings');
            const button = settings?.firstElementChild;
            if (!settings || !button || !button.querySelector('svg')) {
                return;
            }

            // Add about button on settings page
            if (!document.getElementById('aboutButton')) {
                const aboutButton = button.cloneNode(true);
                aboutButton.id = 'aboutButton';
                const textElement = aboutButton.querySelector('.ytAttributedStringHost');
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
                const textElement = downloadButton.querySelector('.ytAttributedStringHost');
                if (textElement) {
                    textElement.innerText = getLocalizedText('download');
                }
                const svg = downloadButton.querySelector('svg');
                if (svg) {
                    svg.setAttribute("viewBox", "0 -960 960 960");
                    const path = svg.querySelector('path');
                    if (path) {
                        path.setAttribute("d", "M480-336 288-528l51-51 105 105v-246h72v246l105-105 51 51-192 192ZM264-192q-30 0-51-21t-21-51v-72h72v72h432v-72h72v72q0 30-21 51t-51 21H264Z");
                    }
                    resizeIcon(downloadButton);
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
                const textElement = extensionButton.querySelector('.ytAttributedStringHost');
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
                const nextUrl = RemoveListParmsFromWatchUrl(url);
                const c = getPageClass(nextUrl);
                const pageClass = getPageClass(location.href);
                if (nextUrl !== url && c === pageClass && c === 'watch') {
                    e.preventDefault();
                    e.stopImmediatePropagation();
                    location.href = nextUrl;
                    return;
                }
                if (c !== pageClass) {
                    e.preventDefault();
                    e.stopImmediatePropagation();
                    requestOpenTab(nextUrl, c);
                }
            },
            true
        );

        // Mark script as totally injected
        requestRun();
        window.injected = true;
    }
} catch (error) {
    console.error('Error in injected script:', error);
    throw error;
}
