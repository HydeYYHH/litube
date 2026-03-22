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

        const NativeHttpBridge = (() => {
            const androidBridge = window.android;
            if (!androidBridge || typeof androidBridge.enqueueNativeHttpRequest !== 'function') {
                return {
                    install() {},
                };
            }

            const MAX_NATIVE_BODY_BYTES = 4 * 1024 * 1024;
            const BODYLESS_METHODS = new Set(['GET', 'HEAD']);
            const BRIDGE_METHODS = new Set(['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS']);
            const ALLOWED_DOMAINS = [
                'youtube.com',
                'youtube.googleapis.com',
                'googlevideo.com',
                'ytimg.com',
                'googleusercontent.com',
                'apis.google.com',
                'gstatic.com',
            ];
            const FETCH_DESTINATION = 'empty';
            const OriginalFetch = typeof window.fetch === 'function'
                ? window.fetch.bind(window)
                : null;
            const OriginalXMLHttpRequest = window.XMLHttpRequest;
            const pendingRequests = new Map();
            let requestSequence = 0;

            const hasHeader = (headers, name) => Object.keys(headers)
                .some(headerName => headerName.toLowerCase() === name.toLowerCase());
            const setHeaderIfMissing = (headers, name, value) => {
                if (!value || hasHeader(headers, name)) return;
                headers[name] = value;
            };
            const appendHeaderSource = (headers, source) => {
                if (!source) return;
                if (source instanceof Headers) {
                    source.forEach((value, key) => {
                        headers[key] = value;
                    });
                    return;
                }
                if (Array.isArray(source)) {
                    for (const entry of source) {
                        if (!Array.isArray(entry) || entry.length < 2) continue;
                        const [key, value] = entry;
                        if (key == null || value == null) continue;
                        headers[String(key)] = String(value);
                    }
                    return;
                }
                if (typeof source === 'object') {
                    for (const [key, value] of Object.entries(source)) {
                        if (value == null) continue;
                        headers[key] = Array.isArray(value)
                            ? value.join(', ')
                            : String(value);
                    }
                }
            };
            const mergeHeaders = (...sources) => {
                const headers = {};
                sources.forEach(source => appendHeaderSource(headers, source));
                return headers;
            };
            const bytesToBase64 = (bytes) => {
                if (!bytes || bytes.length === 0) return '';
                let binary = '';
                const chunkSize = 0x8000;
                for (let index = 0; index < bytes.length; index += chunkSize) {
                    const chunk = bytes.subarray(index, index + chunkSize);
                    binary += String.fromCharCode(...chunk);
                }
                return btoa(binary);
            };
            const base64ToBytes = (base64) => {
                if (!base64) return new Uint8Array(0);
                const binary = atob(base64);
                const bytes = new Uint8Array(binary.length);
                for (let index = 0; index < binary.length; index += 1) {
                    bytes[index] = binary.charCodeAt(index);
                }
                return bytes;
            };
            const textEncoder = new TextEncoder();
            const textDecoder = new TextDecoder();
            const decodeUtf8 = (bytes) => textDecoder.decode(bytes);
            const encodeUtf8 = (text) => textEncoder.encode(text || '');
            const createAbortError = () => {
                try {
                    return new DOMException('The operation was aborted.', 'AbortError');
                } catch (error) {
                    const abortError = new Error('The operation was aborted.');
                    abortError.name = 'AbortError';
                    return abortError;
                }
            };
            const getSiteKey = (hostname) => {
                const parts = hostname.toLowerCase().split('.').filter(Boolean);
                if (parts.length <= 2) return parts.join('.');
                return parts.slice(-2).join('.');
            };
            const computeFetchSite = (url) => {
                if (url.origin === location.origin) return 'same-origin';
                return getSiteKey(url.hostname) === getSiteKey(location.hostname)
                    ? 'same-site'
                    : 'cross-site';
            };
            const isGoogleAccountsHost = (hostname) => {
                const lowerHostname = (hostname || '').toLowerCase();
                return lowerHostname === 'accounts.google'
                    || lowerHostname === 'accounts.google.com'
                    || lowerHostname.startsWith('accounts.google.')
                    || lowerHostname === 'accounts.youtube.com';
            };
            const isAllowedUrl = (urlValue) => {
                try {
                    if (isGoogleAccountsHost(location.hostname)) return false;
                    const url = new URL(urlValue, location.href);
                    if (!['http:', 'https:'].includes(url.protocol)) return false;
                    const hostname = url.hostname.toLowerCase();
                    if (isGoogleAccountsHost(hostname)) return false;
                    return ALLOWED_DOMAINS.some(domain =>
                        hostname === domain || hostname.endsWith(`.${domain}`));
                } catch (error) {
                    return false;
                }
            };
            const shouldIncludeCookies = (credentials, urlValue) => {
                if (credentials === 'omit') return false;
                if (credentials === 'include') return true;
                try {
                    return new URL(urlValue, location.href).origin === location.origin;
                } catch (error) {
                    return false;
                }
            };
            const applySyntheticHeaders = (headers, requestLike) => {
                const targetUrl = new URL(requestLike.url, location.href);
                setHeaderIfMissing(headers, 'accept', '*/*');
                setHeaderIfMissing(headers, 'accept-language',
                    Array.isArray(navigator.languages) && navigator.languages.length > 0
                        ? navigator.languages.join(',')
                        : navigator.language);
                setHeaderIfMissing(headers, 'user-agent', navigator.userAgent);
                setHeaderIfMissing(headers, 'origin', location.origin);
                setHeaderIfMissing(headers, 'referer', location.href);
                setHeaderIfMissing(headers, 'sec-fetch-dest', FETCH_DESTINATION);
                setHeaderIfMissing(headers, 'sec-fetch-mode', requestLike.mode || 'cors');
                setHeaderIfMissing(headers, 'sec-fetch-site', computeFetchSite(targetUrl));
                setHeaderIfMissing(headers, 'sec-ch-dpr', String(window.devicePixelRatio || 1));
                setHeaderIfMissing(headers, 'sec-ch-viewport-width',
                    String(window.innerWidth || document.documentElement?.clientWidth || 0));
                if (navigator.deviceMemory) {
                    setHeaderIfMissing(headers, 'device-memory', String(navigator.deviceMemory));
                }
                if (navigator.userAgentData?.brands?.length) {
                    setHeaderIfMissing(
                        headers,
                        'sec-ch-ua',
                        navigator.userAgentData.brands
                            .map(brand => `"${brand.brand}";v="${brand.version}"`)
                            .join(', '),
                    );
                    setHeaderIfMissing(
                        headers,
                        'sec-ch-ua-mobile',
                        navigator.userAgentData.mobile ? '?1' : '?0',
                    );
                    if (navigator.userAgentData.platform) {
                        setHeaderIfMissing(
                            headers,
                            'sec-ch-ua-platform',
                            `"${navigator.userAgentData.platform}"`,
                        );
                    }
                }
                if (targetUrl.hostname.endsWith('.youtube.com') || targetUrl.hostname === 'youtube.com') {
                    setHeaderIfMissing(headers, 'x-origin', location.origin);
                }
            };
            const serializeRequestBody = async (request) => {
                if (BODYLESS_METHODS.has(request.method.toUpperCase())) return '';
                const buffer = await request.clone().arrayBuffer();
                if (buffer.byteLength > MAX_NATIVE_BODY_BYTES) return null;
                const bytes = new Uint8Array(buffer);
                return bytesToBase64(bytes);
            };
            const createFetchRequest = (input, init) => {
                try {
                    return new Request(input, init);
                } catch (error) {
                    return null;
                }
            };
            const createXhrRequest = (url, method, headers, body, credentials) => {
                try {
                    const absoluteUrl = new URL(url, location.href).toString();
                    const sameOrigin = new URL(absoluteUrl).origin === location.origin;
                    const normalizedMethod = String(method || 'GET').toUpperCase();
                    return new Request(absoluteUrl, {
                        body: BODYLESS_METHODS.has(normalizedMethod) ? undefined : body,
                        credentials,
                        headers: new Headers(headers),
                        method: normalizedMethod,
                        mode: sameOrigin ? 'same-origin' : 'cors',
                        redirect: 'follow',
                    });
                } catch (error) {
                    return null;
                }
            };
            const buildBridgeRequestMetadata = (request, ...additionalHeaderSources) => {
                if (!request) return null;
                const method = request.method.toUpperCase();
                if (!BRIDGE_METHODS.has(method) || !isAllowedUrl(request.url)) return null;
                return {
                    url: request.url,
                    method,
                    headers: mergeHeaders(request.headers, ...additionalHeaderSources),
                };
            };
            const shouldBridgeRequest = (metadata) => {
                if (!metadata) return false;
                return !BODYLESS_METHODS.has(metadata.method) || hasHeader(metadata.headers, 'range');
            };
            const buildNativePayload = async (request, metadata) => {
                if (!metadata) return null;
                let bodyBase64;
                try {
                    bodyBase64 = await serializeRequestBody(request);
                } catch (error) {
                    return null;
                }
                if (bodyBase64 == null) return null;
                applySyntheticHeaders(metadata.headers, request);
                return {
                    ...metadata,
                    bodyBase64,
                    includeCookies: shouldIncludeCookies(request.credentials, request.url),
                };
            };
            const nextRequestId = () => `lite-native-http-${Date.now()}-${++requestSequence}`;
            const resolvePendingRequest = (result) => {
                const requestId = result?.requestId;
                if (!requestId) return;
                const pending = pendingRequests.get(requestId);
                if (!pending) return;
                pendingRequests.delete(requestId);
                pending.cleanup?.();
                pending.resolve(result);
            };
            window.__liteNativeHttp = window.__liteNativeHttp || {};
            window.__liteNativeHttp.onNativeResult = (encodedResult) => {
                try {
                    resolvePendingRequest(JSON.parse(decodeUtf8(base64ToBytes(encodedResult))));
                } catch (error) {}
            };
            const dispatchNativeRequest = (payload, signal, onStart) => new Promise((resolve) => {
                const requestId = nextRequestId();
                onStart?.(requestId);
                const pending = {
                    resolve,
                    cleanup: null,
                };
                if (signal) {
                    if (signal.aborted) {
                        resolve({
                            requestId,
                            intercepted: true,
                            error: 'aborted',
                            aborted: true,
                        });
                        return;
                    }
                    const onAbort = () => {
                        pendingRequests.delete(requestId);
                        androidBridge.cancelNativeHttpRequest?.(requestId);
                        resolve({
                            requestId,
                            intercepted: true,
                            error: 'aborted',
                            aborted: true,
                        });
                    };
                    signal.addEventListener('abort', onAbort, { once: true });
                    pending.cleanup = () => signal.removeEventListener('abort', onAbort);
                }
                pendingRequests.set(requestId, pending);
                androidBridge.enqueueNativeHttpRequest(requestId, JSON.stringify(payload));
            });
            const executeNativePayload = (payload, {
                signal = null,
                onStart = null,
            } = {}) => {
                if (!payload) return Promise.resolve(null);
                return dispatchNativeRequest(payload, signal, onStart);
            };
            const resultHasTextBody = (result) => !result?.binaryBody;
            const getResultText = (result) => resultHasTextBody(result)
                ? (result?.bodyText || '')
                : decodeUtf8(base64ToBytes(result?.bodyBase64 || ''));
            const decorateFetchResponse = (response, result) => new Proxy(response, {
                get(target, property) {
                    if (property === 'redirected') return Boolean(result.redirected);
                    if (property === 'url') return result.url || '';
                    if (property === 'clone') {
                        return () => decorateFetchResponse(target.clone(), result);
                    }
                    const value = Reflect.get(target, property, target);
                    return typeof value === 'function' ? value.bind(target) : value;
                },
            });
            const buildFetchResponse = (result) => {
                const responseBody = resultHasTextBody(result)
                    ? (result.bodyText || '')
                    : base64ToBytes(result.bodyBase64 || '');
                const response = new Response(responseBody, {
                    headers: result.headers || {},
                    status: result.status,
                    statusText: result.statusText || '',
                });
                return decorateFetchResponse(response, result);
            };
            const performNativeFetch = async (input, init) => {
                const request = createFetchRequest(input, init);
                if (!request || request.keepalive) {
                    return null;
                }
                const headerSources = [input instanceof Request ? input.headers : null, init?.headers];
                const metadata = buildBridgeRequestMetadata(request, ...headerSources);
                if (!shouldBridgeRequest(metadata)) return null;
                const payload = await buildNativePayload(request, metadata);
                const result = await executeNativePayload(payload, { signal: request.signal });
                if (!result) return null;
                if (!result?.intercepted) return null;
                if (result.aborted) throw createAbortError();
                if (result.error) throw new TypeError(result.error);
                return buildFetchResponse(result);
            };
            const createEventTarget = () => document.createDocumentFragment();
            const dispatchXhrEvent = (xhr, type, init = {}) => {
                const event = typeof ProgressEvent === 'function'
                    && ['loadstart', 'progress', 'load', 'loadend'].includes(type)
                    ? new ProgressEvent(type, init)
                    : new Event(type);
                xhr._eventTarget.dispatchEvent(event);
                const handler = xhr[`on${type}`];
                if (typeof handler === 'function') {
                    handler.call(xhr, event);
                }
            };
            const normalizeArrayBuffer = (bytes) => bytes.buffer.slice(
                bytes.byteOffset,
                bytes.byteOffset + bytes.byteLength,
            );
            const contentTypeFromHeaders = (headers) => {
                if (!headers) return '';
                return headers['content-type']
                    || headers['Content-Type']
                    || '';
            };
            const syncWrapperFromDelegate = (wrapper, delegate) => {
                wrapper.readyState = delegate.readyState;
                wrapper.responseURL = delegate.responseURL;
                wrapper.status = delegate.status;
                wrapper.statusText = delegate.statusText;
                wrapper.response = delegate.response;
                try {
                    wrapper.responseText = delegate.responseText;
                } catch (error) {
                    wrapper.responseText = '';
                }
            };
            const NativeXMLHttpRequest = function () {
                this.readyState = 0;
                this.response = null;
                this.responseText = '';
                this.responseType = '';
                this.responseURL = '';
                this.responseXML = null;
                this.status = 0;
                this.statusText = '';
                this.timeout = 0;
                this.withCredentials = false;
                this.onreadystatechange = null;
                this.onload = null;
                this.onerror = null;
                this.onabort = null;
                this.onloadend = null;
                this.onloadstart = null;
                this.ontimeout = null;
                this.onprogress = null;
                this.upload = createEventTarget();
                this._eventTarget = createEventTarget();
                this._headers = {};
                this._method = 'GET';
                this._url = '';
                this._async = true;
                this._delegate = null;
                this._responseHeaders = '';
                this._nativeRequestId = null;
                this._aborted = false;
                this._overrideMimeType = null;
            };
            NativeXMLHttpRequest.UNSENT = 0;
            NativeXMLHttpRequest.OPENED = 1;
            NativeXMLHttpRequest.HEADERS_RECEIVED = 2;
            NativeXMLHttpRequest.LOADING = 3;
            NativeXMLHttpRequest.DONE = 4;
            NativeXMLHttpRequest.prototype.UNSENT = 0;
            NativeXMLHttpRequest.prototype.OPENED = 1;
            NativeXMLHttpRequest.prototype.HEADERS_RECEIVED = 2;
            NativeXMLHttpRequest.prototype.LOADING = 3;
            NativeXMLHttpRequest.prototype.DONE = 4;
            NativeXMLHttpRequest.prototype.addEventListener = function (...args) {
                this._eventTarget.addEventListener(...args);
            };
            NativeXMLHttpRequest.prototype.removeEventListener = function (...args) {
                this._eventTarget.removeEventListener(...args);
            };
            NativeXMLHttpRequest.prototype.dispatchEvent = function (event) {
                return this._eventTarget.dispatchEvent(event);
            };
            NativeXMLHttpRequest.prototype.open = function (method, url, async = true) {
                this._method = String(method || 'GET').toUpperCase();
                this._url = new URL(url, location.href).toString();
                this._async = async !== false;
                this._headers = {};
                this._delegate = null;
                this._aborted = false;
                this._nativeRequestId = null;
                this._responseHeaders = '';
                this.response = null;
                this.responseText = '';
                this.responseURL = '';
                this.status = 0;
                this.statusText = '';
                this.readyState = NativeXMLHttpRequest.OPENED;
                dispatchXhrEvent(this, 'readystatechange');
            };
            NativeXMLHttpRequest.prototype.setRequestHeader = function (name, value) {
                this._headers[name] = value;
            };
            NativeXMLHttpRequest.prototype.overrideMimeType = function (mimeType) {
                this._overrideMimeType = mimeType;
            };
            NativeXMLHttpRequest.prototype.getAllResponseHeaders = function () {
                if (this._delegate) return this._delegate.getAllResponseHeaders();
                return this.readyState >= NativeXMLHttpRequest.HEADERS_RECEIVED
                    ? this._responseHeaders
                    : '';
            };
            NativeXMLHttpRequest.prototype.getResponseHeader = function (name) {
                if (this._delegate) return this._delegate.getResponseHeader(name);
                const lines = this.getAllResponseHeaders().split(/\r?\n/).filter(Boolean);
                const lowerName = name.toLowerCase();
                for (const line of lines) {
                    const separatorIndex = line.indexOf(':');
                    if (separatorIndex < 0) continue;
                    if (line.substring(0, separatorIndex).trim().toLowerCase() === lowerName) {
                        return line.substring(separatorIndex + 1).trim();
                    }
                }
                return null;
            };
            NativeXMLHttpRequest.prototype.abort = function () {
                this._aborted = true;
                if (this._delegate) {
                    this._delegate.abort();
                    return;
                }
                if (this._nativeRequestId) {
                    const requestId = this._nativeRequestId;
                    const pending = pendingRequests.get(requestId);
                    pendingRequests.delete(requestId);
                    pending?.cleanup?.();
                    pending?.resolve({
                        requestId,
                        intercepted: true,
                        error: 'aborted',
                        aborted: true,
                    });
                    androidBridge.cancelNativeHttpRequest?.(requestId);
                    this._nativeRequestId = null;
                }
                if (this.readyState !== NativeXMLHttpRequest.UNSENT
                    && this.readyState !== NativeXMLHttpRequest.DONE) {
                    this.readyState = NativeXMLHttpRequest.DONE;
                    dispatchXhrEvent(this, 'readystatechange');
                    dispatchXhrEvent(this, 'abort');
                    dispatchXhrEvent(this, 'loadend');
                }
            };
            NativeXMLHttpRequest.prototype._wireDelegate = function (delegate) {
                delegate.onreadystatechange = (event) => {
                    syncWrapperFromDelegate(this, delegate);
                    dispatchXhrEvent(this, 'readystatechange');
                    if (delegate.readyState === NativeXMLHttpRequest.DONE) {
                        this._responseHeaders = delegate.getAllResponseHeaders();
                    }
                };
                delegate.onloadstart = (event) => dispatchXhrEvent(this, 'loadstart', event);
                delegate.onprogress = (event) => dispatchXhrEvent(this, 'progress', event);
                delegate.onload = (event) => dispatchXhrEvent(this, 'load', event);
                delegate.onerror = (event) => dispatchXhrEvent(this, 'error', event);
                delegate.onabort = (event) => dispatchXhrEvent(this, 'abort', event);
                delegate.ontimeout = (event) => dispatchXhrEvent(this, 'timeout', event);
                delegate.onloadend = (event) => dispatchXhrEvent(this, 'loadend', event);
            };
            NativeXMLHttpRequest.prototype._sendWithDelegate = function (body) {
                const delegate = new OriginalXMLHttpRequest();
                this._delegate = delegate;
                this._wireDelegate(delegate);
                delegate.open(this._method, this._url, this._async);
                delegate.responseType = this.responseType;
                delegate.timeout = this.timeout;
                delegate.withCredentials = this.withCredentials;
                if (this._overrideMimeType) {
                    delegate.overrideMimeType(this._overrideMimeType);
                }
                for (const [name, value] of Object.entries(this._headers)) {
                    delegate.setRequestHeader(name, value);
                }
                delegate.send(body);
            };
            NativeXMLHttpRequest.prototype._applyNativeResult = function (result) {
                const responseType = this.responseType || '';
                const contentType = contentTypeFromHeaders(result.headers);
                const text = resultHasTextBody(result) ? (result.bodyText || '') : null;
                let bytes = null;
                const getBytes = () => {
                    if (bytes) return bytes;
                    bytes = text != null
                        ? encodeUtf8(text)
                        : base64ToBytes(result.bodyBase64 || '');
                    return bytes;
                };
                const bodyLength = text != null
                    ? text.length
                    : getBytes().length;
                this.status = result.status;
                this.statusText = result.statusText || '';
                this.responseURL = result.url || this._url;
                this._responseHeaders = Object.entries(result.headers || {})
                    .map(([name, value]) => `${name}: ${value}`)
                    .join('\r\n');
                this.readyState = NativeXMLHttpRequest.HEADERS_RECEIVED;
                dispatchXhrEvent(this, 'readystatechange');
                this.readyState = NativeXMLHttpRequest.LOADING;
                dispatchXhrEvent(this, 'readystatechange');
                dispatchXhrEvent(this, 'progress', {
                    lengthComputable: bodyLength > 0,
                    loaded: bodyLength,
                    total: bodyLength,
                });
                if (responseType === 'arraybuffer') {
                    this.response = normalizeArrayBuffer(getBytes());
                    this.responseText = '';
                } else if (responseType === 'blob') {
                    this.response = new Blob([getBytes()], { type: contentType });
                    this.responseText = '';
                } else {
                    const responseText = text != null ? text : getResultText(result);
                    this.responseText = responseText;
                    if (responseType === 'json') {
                        try {
                            this.response = JSON.parse(responseText);
                        } catch (error) {
                            this.response = null;
                        }
                    } else {
                        this.response = responseText;
                    }
                }
                this.readyState = NativeXMLHttpRequest.DONE;
                dispatchXhrEvent(this, 'readystatechange');
                dispatchXhrEvent(this, 'load');
                dispatchXhrEvent(this, 'loadend');
            };
            NativeXMLHttpRequest.prototype.send = function (body = null) {
                const shouldAttemptNative = this._async
                    && this.timeout === 0
                    && this.responseType !== 'document';
                if (!shouldAttemptNative) {
                    this._sendWithDelegate(body);
                    return;
                }
                const credentials = this.withCredentials ? 'include' : 'same-origin';
                const request = createXhrRequest(
                    this._url,
                    this._method,
                    this._headers,
                    BODYLESS_METHODS.has(this._method) ? undefined : body,
                    credentials,
                );
                if (!request) {
                    this._sendWithDelegate(body);
                    return;
                }
                const metadata = buildBridgeRequestMetadata(request, this._headers);
                if (!shouldBridgeRequest(metadata)) {
                    this._sendWithDelegate(body);
                    return;
                }
                queueMicrotask(async () => {
                    const payload = await buildNativePayload(request, metadata);
                    if (!payload) {
                        this._sendWithDelegate(body);
                        return;
                    }
                    dispatchXhrEvent(this, 'loadstart');
                    const result = await executeNativePayload(payload, {
                        onStart: requestId => {
                            this._nativeRequestId = requestId;
                        },
                    });
                    if (this._aborted) return;
                    if (!result?.intercepted) {
                        this._sendWithDelegate(body);
                        return;
                    }
                    if (result.error) {
                        this.readyState = NativeXMLHttpRequest.DONE;
                        dispatchXhrEvent(this, 'readystatechange');
                        dispatchXhrEvent(this, result.aborted ? 'abort' : 'error');
                        dispatchXhrEvent(this, 'loadend');
                        this._nativeRequestId = null;
                        return;
                    }
                    this._applyNativeResult(result);
                    this._nativeRequestId = null;
                });
            };

            const install = () => {
                if (OriginalFetch) {
                    window.fetch = async function (input, init) {
                        const nativeResponse = await performNativeFetch(input, init);
                        if (nativeResponse) return nativeResponse;
                        return OriginalFetch(input, init);
                    };
                }
                if (typeof OriginalXMLHttpRequest === 'function') {
                    window.XMLHttpRequest = NativeXMLHttpRequest;
                }
            };

            return {
                install,
            };
        })();
        NativeHttpBridge.install();

        const DomLiteEngine = (() => {
            const timing = {
                requestAnimationFrame: typeof window.requestAnimationFrame === 'function'
                    ? window.requestAnimationFrame.bind(window)
                    : (callback) => window.setTimeout(callback, 16),
                setTimeout: window.setTimeout.bind(window),
            };
            const state = {
                pendingAdds: new Set(),
                flushScheduled: false,
                ghostCount: 0,
                lastGhostStrategy: 'none',
                lastFlipCount: 0,
                animationMode: 'full',
                currentPageClass: getPageClass(location.href),
                observerRootName: null,
                observerConnected: false,
                observerPauseReason: '',
            };
            let addObserver = null;
            let observerRoot = null;
            let wrappedApis = false;
            const ENTER_ANIMATION_DURATION_MS = 320;
            const ENTER_ANIMATION_TRANSLATE_Y_PX = 24;
            const ENTER_ANIMATION_START_SCALE = 0.94;
            const EXIT_ANIMATION_DURATION_MS = 320;
            const EXIT_ANIMATION_TRANSLATE_Y_PX = 18;
            const EXIT_ANIMATION_END_SCALE = 0.9;

            const isElementNode = (node) => node?.nodeType === Node.ELEMENT_NODE;
            const isGhostNode = (node) => node?.classList?.contains('lite-dom-ghost');
            const describeNode = (node) => {
                if (!node) return 'none';
                if (node.id) return `#${node.id}`;
                return node.tagName || node.nodeName || 'unknown';
            };
            const resolveObserverRoot = () => {
                const pageClass = getPageClass(location.href);
                const selectors = ['#card-list'];
                if (pageClass === 'watch') {
                    selectors.push('ytm-watch', 'ytm-app', 'main', 'body');
                } else if (pageClass === 'select_site') {
                    selectors.push('ytm-settings', 'ytm-app', 'main', 'body');
                } else {
                    selectors.push('ytm-app', 'main', 'body');
                }
                for (const selector of selectors) {
                    const node = document.querySelector(selector);
                    if (node) return node;
                }
                return document.body || document.documentElement;
            };
            const isObservedNode = (node) => {
                if (!isElementNode(node)) return false;
                if (!observerRoot) return true;
                return node === observerRoot || observerRoot.contains(node);
            };
            const shouldAnimateNode = (node) => {
                if (!isObservedNode(node) || isGhostNode(node)) return false;
                const tagName = node.tagName;
                if (!tagName) return false;
                return !['BODY', 'HTML', 'IFRAME', 'INPUT', 'TEXTAREA', 'VIDEO', 'SCRIPT', 'STYLE'].includes(tagName);
            };
            const shouldDeepCloneGhost = (node, rect) => {
                if (!rect) return false;
                const area = rect.width * rect.height;
                return area <= 120000 && node.childElementCount <= 12 && (node.textContent?.length || 0) <= 280;
            };

            const applyAddAnimation = (node) => {
                if (state.animationMode === 'batch-only' || !shouldAnimateNode(node)) return;
                node.style.transition = `opacity ${ENTER_ANIMATION_DURATION_MS}ms cubic-bezier(0.16, 1, 0.3, 1), transform ${ENTER_ANIMATION_DURATION_MS}ms cubic-bezier(0.16, 1, 0.3, 1)`;
                node.style.opacity = '0';
                node.style.transform = `translateY(${ENTER_ANIMATION_TRANSLATE_Y_PX}px) scale(${ENTER_ANIMATION_START_SCALE})`;
                timing.requestAnimationFrame(() => {
                    node.style.opacity = '';
                    node.style.transform = '';
                });
            };

            const createGhost = (node, rect, onCleanup) => {
                const ghost = node.cloneNode(true);
                ghost.classList.add('lite-dom-ghost');
                ghost.style.position = 'fixed';
                ghost.style.left = `${rect.left}px`;
                ghost.style.top = `${rect.top}px`;
                ghost.style.width = `${rect.width}px`;
                ghost.style.height = `${rect.height}px`;
                ghost.style.margin = '0';
                ghost.style.pointerEvents = 'none';
                ghost.style.zIndex = '2147483647';
                ghost.style.transform = 'translateY(0) scale(1)';
                ghost.style.transition = `opacity ${EXIT_ANIMATION_DURATION_MS}ms cubic-bezier(0.4, 0, 0.2, 1), transform ${EXIT_ANIMATION_DURATION_MS}ms cubic-bezier(0.4, 0, 0.2, 1)`;
                ghost.style.opacity = '1';
                document.body.appendChild(ghost);
                timing.requestAnimationFrame(() => {
                    ghost.style.opacity = '0';
                    ghost.style.transform = `translateY(-${EXIT_ANIMATION_TRANSLATE_Y_PX}px) scale(${EXIT_ANIMATION_END_SCALE})`;
                });
                timing.setTimeout(() => {
                    ghost.remove();
                    onCleanup();
                }, EXIT_ANIMATION_DURATION_MS + 20);
            };

            const registerRemoval = (node) => {
                if (state.animationMode === 'batch-only' || !shouldAnimateNode(node)) return;
                const rect = node.getBoundingClientRect?.();
                if (!rect || (!rect.width && !rect.height)) {
                    state.lastGhostStrategy = 'skipped-empty-rect';
                    return;
                }
                if (!shouldDeepCloneGhost(node, rect)) {
                    state.lastGhostStrategy = 'skipped-large-node';
                    return;
                }
                state.lastGhostStrategy = 'deep-clone';
                state.ghostCount += 1;
                const cleanupGhost = () => {
                    state.ghostCount = Math.max(0, state.ghostCount - 1);
                };
                createGhost(node, rect, cleanupGhost);
            };

            const animateReorder = (node, firstRect) => {
                if (state.animationMode === 'batch-only' || !shouldAnimateNode(node) || !firstRect) return;
                const lastRect = node.getBoundingClientRect?.();
                if (!lastRect) return;
                const dx = firstRect.left - lastRect.left;
                const dy = firstRect.top - lastRect.top;
                state.lastFlipCount = (dx || dy) ? 1 : 0;
                if (!state.lastFlipCount) return;
                node.style.transition = 'transform 140ms ease';
                node.style.transform = `translate(${dx}px, ${dy}px)`;
                timing.requestAnimationFrame(() => {
                    node.style.transform = '';
                });
                timing.setTimeout(() => {
                    if (node.isConnected) {
                        node.style.transition = '';
                    }
                }, 180);
            };

            const registerAddedNode = (node) => {
                if (!shouldAnimateNode(node)) return;
                state.pendingAdds.add(node);
                if (state.pendingAdds.size > 120) {
                    state.animationMode = 'batch-only';
                    if (addObserver) {
                        addObserver.disconnect();
                    }
                    state.observerConnected = false;
                    state.observerPauseReason = 'large-batch';
                }
                scheduleFlush();
            };

            const scheduleFlush = () => {
                if (state.flushScheduled) return;
                state.flushScheduled = true;
                timing.setTimeout(() => {
                    state.flushScheduled = false;
                    state.currentPageClass = getPageClass(location.href);
                    state.pendingAdds.forEach(applyAddAnimation);
                    state.pendingAdds.clear();
                }, 32);
            };

            const wrapDomApis = () => {
                if (wrappedApis) return;
                wrappedApis = true;
                const originalInsertBefore = Node.prototype.insertBefore;
                const originalRemoveChild = Node.prototype.removeChild;
                const originalElementRemove = Element.prototype.remove;

                Node.prototype.insertBefore = function (node, child) {
                    const shouldFlip = node?.parentNode === this && shouldAnimateNode(node);
                    const firstRect = shouldFlip ? node.getBoundingClientRect() : null;
                    const result = originalInsertBefore.call(this, node, child);
                    if (shouldFlip) {
                        animateReorder(node, firstRect);
                    }
                    return result;
                };

                Node.prototype.removeChild = function (node) {
                    registerRemoval(node);
                    return originalRemoveChild.call(this, node);
                };

                Element.prototype.remove = function () {
                    registerRemoval(this);
                    return originalElementRemove.call(this);
                };
            };

            const observeAddedNodes = () => {
                if (!document.documentElement) return;
                if (!addObserver) {
                    addObserver = new MutationObserver(mutations => {
                        for (const mutation of mutations) {
                            mutation.addedNodes.forEach(registerAddedNode);
                        }
                    });
                }
                if (state.animationMode === 'batch-only' && state.observerPauseReason) {
                    return;
                }
                const nextRoot = resolveObserverRoot();
                if (!nextRoot) return;
                if (observerRoot === nextRoot && state.observerConnected) return;
                if (addObserver) {
                    addObserver.disconnect();
                }
                observerRoot = nextRoot;
                state.observerRootName = describeNode(observerRoot);
                addObserver.observe(observerRoot, {
                    childList: true,
                    subtree: true,
                });
                state.observerConnected = true;
                state.observerPauseReason = '';
            };

            const debugSnapshot = () => ({
                pendingAdds: state.pendingAdds.size,
                flushScheduled: state.flushScheduled,
                ghostCount: state.ghostCount,
                lastGhostStrategy: state.lastGhostStrategy,
                lastFlipCount: state.lastFlipCount,
                animationMode: state.animationMode,
                currentPageClass: state.currentPageClass,
                observerRootName: state.observerRootName,
                observerConnected: state.observerConnected,
                observerPauseReason: state.observerPauseReason || null,
            });

            wrapDomApis();
            observeAddedNodes();

            return {
                debugSnapshot,
                updateCurrentPageClass(pageClass = getPageClass(location.href)) {
                    const pageChanged = state.currentPageClass !== pageClass;
                    state.currentPageClass = pageClass;
                    if (pageChanged && state.animationMode === 'batch-only') {
                        state.animationMode = 'full';
                    }
                    observeAddedNodes();
                },
            };
        })();

        const PageTaskDiagnostics = (() => {
            const state = {
                timerActive: false,
                nextTimerDelayMs: null,
                lastTimerReason: 'init',
                taskRuns: {
                    global: 0,
                    watch: 0,
                    settings: 0,
                    fallback: 0,
                },
            };

            return {
                updateTimerState(partialState) {
                    Object.assign(state, partialState);
                },
                markTimerTick(reason) {
                    state.lastTimerReason = reason;
                },
                incrementTaskRun(name) {
                    if (Object.prototype.hasOwnProperty.call(state.taskRuns, name)) {
                        state.taskRuns[name] += 1;
                    }
                },
                debugSnapshot() {
                    return {
                        timerActive: state.timerActive,
                        nextTimerDelayMs: state.nextTimerDelayMs,
                        lastTimerReason: state.lastTimerReason,
                        taskRuns: { ...state.taskRuns },
                    };
                },
            };
        })();

        if (window.__liteDomEnableDebug === true) {
            window.__liteDomDebug = Object.freeze({
                getSnapshot: () => ({
                    ...DomLiteEngine.debugSnapshot(),
                    ...PageTaskDiagnostics.debugSnapshot(),
                }),
            });
        }
        const TimerCoordinator = (() => {
            let timerId = null;
            let wakePending = false;
            let pendingWakeReason = 'wake';

            const clearScheduledTick = () => {
                if (timerId !== null) {
                    window.clearTimeout(timerId);
                    timerId = null;
                }
                PageTaskDiagnostics.updateTimerState({
                    timerActive: false,
                    nextTimerDelayMs: null,
                });
            };

            const schedule = (delayMs) => {
                clearScheduledTick();
                if (delayMs == null || document.visibilityState === 'hidden') return;
                PageTaskDiagnostics.updateTimerState({
                    timerActive: true,
                    nextTimerDelayMs: delayMs,
                });
                timerId = window.setTimeout(() => {
                    timerId = null;
                    tick('scheduled');
                }, delayMs);
            };

            const computeDelay = (pageClass, taskState) => {
                if (document.visibilityState === 'hidden') return null;
                if (pageClass === 'watch') {
                    if (taskState.adShowing) return 300;
                    if (taskState.needsRetry) return 700;
                    return null;
                }
                if (pageClass === 'select_site') {
                    return taskState.needsRetry ? 900 : null;
                }
                return null;
            };

            const tick = (reason = 'manual') => {
                wakePending = false;
                clearScheduledTick();
                const pageClass = getPageClass(location.href);
                DomLiteEngine.updateCurrentPageClass(pageClass);
                PageTaskDiagnostics.incrementTaskRun('global');
                if (pageClass === 'watch') {
                    PageTaskDiagnostics.incrementTaskRun('watch');
                } else if (pageClass === 'select_site') {
                    PageTaskDiagnostics.incrementTaskRun('settings');
                } else if (pageClass === 'unknown') {
                    PageTaskDiagnostics.incrementTaskRun('fallback');
                }
                PageTaskDiagnostics.markTimerTick(reason);
                const taskState = runTimedTasks(pageClass);
                schedule(computeDelay(pageClass, taskState));
            };

            return {
                tick,
                wake(reason = 'wake') {
                    if (document.visibilityState === 'hidden') {
                        wakePending = false;
                        clearScheduledTick();
                        return;
                    }
                    pendingWakeReason = reason;
                    if (wakePending) return;
                    wakePending = true;
                    window.setTimeout(() => tick(pendingWakeReason), 0);
                },
                handleVisibilityChange() {
                    if (document.visibilityState === 'hidden') {
                        wakePending = false;
                        clearScheduledTick();
                    } else {
                        tick('visibility');
                    }
                },
            };
        })();
        // Observe page type changes and dispatch event
        const observePageClass = () => {
            const currentPageClass = getPageClass(location.href);
            if (currentPageClass && window.pageClass !== currentPageClass) {
                window.pageClass = currentPageClass;
                DomLiteEngine.updateCurrentPageClass(currentPageClass);
                window.dispatchEvent(new Event('onPageClassChange'));
            }
        };

        window.addEventListener('onProgressChangeFinish', observePageClass);
        window.addEventListener('onPageClassChange', () => {
            TimerCoordinator.wake('page-class-change');
        });
        document.addEventListener('visibilitychange', () => {
            TimerCoordinator.handleVisibilityChange();
        });

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
            TimerCoordinator.wake('progress-finish');
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
        window.addEventListener('popstate', () => {
            handlePlayerVisibility();
            TimerCoordinator.tick('popstate');
        });

        // Override pushState to trigger player visibility changes
        const originalPushState = history.pushState;
        history.pushState = function (data, title, url) {
            originalPushState.call(this, data, title, url);
            handlePlayerVisibility();
            TimerCoordinator.tick('push-state');
        };

        // Override replaceState to trigger player visibility changes
        const originalReplaceState = history.replaceState;
        history.replaceState = function (data, title, url) {
            originalReplaceState.call(this, data, title, url);
            handlePlayerVisibility();
            TimerCoordinator.tick('replace-state');
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
                TimerCoordinator.wake('movie-player-ready');
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
                if (
                    node.id === 'player' ||
                    node.id === 'player-container-id' ||
                    node.classList.contains('watch-below-the-player') ||
                    node.classList.contains('ytSpecButtonViewModelHost')
                ) {
                    TimerCoordinator.wake('watch-dom-ready');
                }
            } else if (pageClass === 'select_site') {
                if (node.closest?.('ytm-settings')) {
                    TimerCoordinator.wake('settings-dom-ready');
                }
            }
        }, false);

        function runTimedTasks(pageClass) {
            const taskState = {
                adShowing: false,
                needsRetry: false,
            };
            // Skip ads
            if (pageClass === 'watch') {
                const video = document.querySelector('.ad-showing video');
                taskState.adShowing = Boolean(video);
                if (video) video.currentTime = video.duration;
            }
            // Add chat button on live page
            const isLive = document.querySelector('#movie_player')?.getPlayerResponse?.()?.playabilityStatus?.liveStreamability &&
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
                        } else {
                            taskState.needsRetry = true;
                        }
                    } else {
                        taskState.needsRetry = true;
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
            if (!isLive && pageClass === 'watch' && !document.getElementById('downloadButton')) {
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
                        downloadButton.addEventListener('click', () => {
                            // opt: fetch video details
                            android.download(location.href)
                        });
                        saveButton.parentElement?.insertBefore(downloadButton, saveButton);
                    } else {
                        taskState.needsRetry = true; // avoid using an incomplete clone
                    }
                } else {
                    taskState.needsRetry = true;
                }
            }

            // Add about button on settings page
            if (pageClass === 'select_site' && !document.getElementById('aboutButton')) {
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
                } else {
                    taskState.needsRetry = true;
                }
            }
            // Add download button on setting page
             if (pageClass === 'select_site' && !document.getElementById('downloadButton')) {
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
                } else {
                    taskState.needsRetry = true;
                }
            }

            // Add extension button on settings page
            if (pageClass === 'select_site' && !document.getElementById('extensionButton')) {
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
                } else {
                    taskState.needsRetry = true;
                }
            }

            return taskState;
        }
        TimerCoordinator.tick('init');

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
