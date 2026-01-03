/*
 * Based on ExoPlayer's DefaultHttpDataSource.
 */

package com.hhst.youtubelite.player.engine.datasource;

import static androidx.media3.datasource.DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS;
import static androidx.media3.datasource.DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getAndroidUserAgent;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getIosUserAgent;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTvHtml5UserAgent;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isAndroidStreamingUrl;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isIosStreamingUrl;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isTvHtml5StreamingUrl;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isWebEmbeddedPlayerStreamingUrl;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isWebStreamingUrl;

import com.hhst.youtubelite.util.StreamIOUtils;

import android.net.Uri;
import android.webkit.CookieManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DataSpec.HttpMethod;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.HttpUtil;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.HttpHeaders;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.NoRouteToHostException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * An {@link HttpDataSource} that uses Android's {@link HttpURLConnection}, based on
 * {@link DefaultHttpDataSource}, for YouTube streams.
 */
@UnstableApi
public final class YoutubeHttpDataSource extends BaseDataSource implements HttpDataSource {

	private static final String TAG = YoutubeHttpDataSource.class.getSimpleName();
	private static final int MAX_REDIRECTS = 20;
	private static final int HTTP_STATUS_TEMPORARY_REDIRECT = 307;
	private static final int HTTP_STATUS_PERMANENT_REDIRECT = 308;
	private static final String RN_PARAMETER = "&rn=";
	private static final String YOUTUBE_BASE_URL = "https://m.youtube.com";
	private static final byte[] POST_BODY = new byte[]{0x78, 0};
	private final boolean allowCrossProtocolRedirects;
	private final boolean rangeParameterEnabled;
	private final boolean rnParameterEnabled;
	private final int connectTimeoutMillis;
	private final int readTimeoutMillis;
	@Nullable
	private final RequestProperties defaultRequestProperties;
	private final RequestProperties requestProperties;
	private final boolean keepPostFor302Redirects;
	private final String userAgent;

	@Nullable
	private DataSpec dataSpec;
	@Nullable
	private HttpURLConnection connection;
	@Nullable
	private InputStream inputStream;
	private boolean opened;
	private int responseCode;
	private long bytesToRead;
	private long bytesRead;
	private long requestNumber;

	private YoutubeHttpDataSource(final int connectTimeoutMillis, final int readTimeoutMillis, final boolean allowCrossProtocolRedirects, final boolean rangeParameterEnabled, final boolean rnParameterEnabled, @Nullable final RequestProperties defaultRequestProperties, final boolean keepPostFor302Redirects, final String userAgent) {
		super(true);
		this.connectTimeoutMillis = connectTimeoutMillis;
		this.readTimeoutMillis = readTimeoutMillis;
		this.allowCrossProtocolRedirects = allowCrossProtocolRedirects;
		this.rangeParameterEnabled = rangeParameterEnabled;
		this.rnParameterEnabled = rnParameterEnabled;
		this.defaultRequestProperties = defaultRequestProperties;
		this.requestProperties = new RequestProperties();
		this.keepPostFor302Redirects = keepPostFor302Redirects;
		this.userAgent = userAgent;
		this.requestNumber = 0;
	}

	private static void maybeTerminateInputStream() {
	}

	private static boolean isCompressed(final HttpURLConnection connection) {
		final String contentEncoding = connection.getHeaderField("Content-Encoding");
		return "gzip".equalsIgnoreCase(contentEncoding);
	}

	@Nullable
	private static String buildRangeParameter(final long position, final long length) {
		if (position == 0 && length == C.LENGTH_UNSET) return null;
		final StringBuilder rangeValue = new StringBuilder();
		rangeValue.append("&range=");
		rangeValue.append(position);
		rangeValue.append("-");
		if (length != C.LENGTH_UNSET) rangeValue.append(position + length - 1);
		return rangeValue.toString();
	}

	private static boolean isRedirectResponse(int httpMethod, int responseCode) {
		if (httpMethod != DataSpec.HTTP_METHOD_GET && httpMethod != DataSpec.HTTP_METHOD_HEAD)
			return false;
		return responseCode == HttpURLConnection.HTTP_MULT_CHOICE || responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP || responseCode == HttpURLConnection.HTTP_SEE_OTHER || responseCode == HTTP_STATUS_TEMPORARY_REDIRECT || responseCode == HTTP_STATUS_PERMANENT_REDIRECT;
	}

	private static boolean isPostRedirectResponse(int responseCode) {
		return responseCode == HttpURLConnection.HTTP_MULT_CHOICE || responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP || responseCode == HttpURLConnection.HTTP_SEE_OTHER;
	}

	@Override
	@Nullable
	public Uri getUri() {
		return connection == null ? null : Uri.parse(connection.getURL().toString());
	}

	@Override
	public int getResponseCode() {
		return connection == null || responseCode <= 0 ? -1 : responseCode;
	}

	@NonNull
	@Override
	public Map<String, List<String>> getResponseHeaders() {
		if (connection == null) return ImmutableMap.of();
		return new NullFilteringHeadersMap(connection.getHeaderFields());
	}

	@Override
	public void setRequestProperty(@NonNull final String name, @NonNull final String value) {
		Preconditions.checkNotNull(name);
		Preconditions.checkNotNull(value);
		requestProperties.set(name, value);
	}

	@Override
	public void clearRequestProperty(@NonNull final String name) {
		Preconditions.checkNotNull(name);
		requestProperties.remove(name);
	}

	@Override
	public void clearAllRequestProperties() {
		requestProperties.clear();
	}

	@Override
	public long open(@NonNull final DataSpec dataSpecParameter) throws HttpDataSourceException {
		this.dataSpec = dataSpecParameter;
		bytesRead = 0;
		bytesToRead = 0;
		transferInitializing(dataSpecParameter);

		final HttpURLConnection httpURLConnection;
		final String responseMessage;
		try {
			this.connection = makeConnection(dataSpec);
			httpURLConnection = this.connection;
			responseCode = httpURLConnection.getResponseCode();
			responseMessage = httpURLConnection.getResponseMessage();
		} catch (final IOException e) {
			closeConnectionQuietly();
			throw HttpDataSourceException.createForIOException(e, dataSpec, HttpDataSourceException.TYPE_OPEN);
		}

		if (responseCode < 200 || responseCode > 299) {
			final Map<String, List<String>> headers = httpURLConnection.getHeaderFields();
			if (responseCode == 416) {
				final long documentSize = HttpUtil.getDocumentSize(httpURLConnection.getHeaderField(HttpHeaders.CONTENT_RANGE));
				if (dataSpecParameter.position == documentSize) {
					opened = true;
					transferStarted(dataSpecParameter);
					return dataSpecParameter.length != C.LENGTH_UNSET ? dataSpecParameter.length : 0;
				}
			}

			final InputStream errorStream = httpURLConnection.getErrorStream();
			final byte[] errorResponseBody = errorStream != null ? StreamIOUtils.readInputStreamToBytes(errorStream) : Util.EMPTY_BYTE_ARRAY;

			closeConnectionQuietly();
			final IOException cause = responseCode == 416 ? new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) : null;
			throw new InvalidResponseCodeException(responseCode, responseMessage, cause, headers, dataSpec, errorResponseBody);
		}


		final long bytesToSkip;
		if (!rangeParameterEnabled)
			bytesToSkip = responseCode == 200 && dataSpecParameter.position != 0 ? dataSpecParameter.position : 0;
		else bytesToSkip = 0;

		final boolean isCompressed = isCompressed(httpURLConnection);
		if (!isCompressed) {
			if (dataSpecParameter.length != C.LENGTH_UNSET) bytesToRead = dataSpecParameter.length;
			else {
				final long contentLength = HttpUtil.getContentLength(httpURLConnection.getHeaderField(HttpHeaders.CONTENT_LENGTH), httpURLConnection.getHeaderField(HttpHeaders.CONTENT_RANGE));
				bytesToRead = contentLength != C.LENGTH_UNSET ? (contentLength - bytesToSkip) : C.LENGTH_UNSET;
			}
		} else bytesToRead = dataSpecParameter.length;

		try {
			inputStream = httpURLConnection.getInputStream();
			if (isCompressed) inputStream = new GZIPInputStream(inputStream);
		} catch (final IOException e) {
			closeConnectionQuietly();
			throw new HttpDataSourceException(e, dataSpec, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, HttpDataSourceException.TYPE_OPEN);
		}

		opened = true;
		transferStarted(dataSpecParameter);

		try {
			skipFully(bytesToSkip, dataSpec);
		} catch (final IOException e) {
			closeConnectionQuietly();
			if (e instanceof HttpDataSourceException) throw (HttpDataSourceException) e;
			throw new HttpDataSourceException(e, dataSpec, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, HttpDataSourceException.TYPE_OPEN);
		}

		return bytesToRead;
	}

	@Override
	public int read(@NonNull final byte[] buffer, final int offset, final int length) throws HttpDataSourceException {
		try {
			return readInternal(buffer, offset, length);
		} catch (final IOException e) {
			throw HttpDataSourceException.createForIOException(e, Util.castNonNull(dataSpec), HttpDataSourceException.TYPE_READ);
		}
	}

	@Override
	public void close() throws HttpDataSourceException {
		try {
			final InputStream connectionInputStream = this.inputStream;
			if (connectionInputStream != null) {
				// bytesRemaining calculation removed as it's no longer needed
				maybeTerminateInputStream();

				try {
					connectionInputStream.close();
				} catch (final IOException e) {
					throw new HttpDataSourceException(e, Util.castNonNull(dataSpec), PlaybackException.ERROR_CODE_IO_UNSPECIFIED, HttpDataSourceException.TYPE_CLOSE);
				}
			}
		} finally {
			inputStream = null;
			closeConnectionQuietly();
			if (opened) {
				opened = false;
				transferEnded();
			}
		}
	}

	@NonNull
	private HttpURLConnection makeConnection(@NonNull final DataSpec dataSpecToUse) throws IOException {
		URL url = new URL(dataSpecToUse.uri.toString());
		@HttpMethod int httpMethod = dataSpecToUse.httpMethod;
		@Nullable byte[] httpBody = dataSpecToUse.httpBody;
		final long position = dataSpecToUse.position;
		final long length = dataSpecToUse.length;
		final boolean allowGzip = dataSpecToUse.isFlagSet(DataSpec.FLAG_ALLOW_GZIP);

		if (!allowCrossProtocolRedirects && !keepPostFor302Redirects)
			return makeConnection(url, httpMethod, httpBody, position, length, allowGzip, true, dataSpecToUse.httpRequestHeaders);

		int redirectCount = 0;
		while (redirectCount++ <= MAX_REDIRECTS) {
			final HttpURLConnection connection = makeConnection(url, httpMethod, httpBody, position, length, allowGzip, false, dataSpecToUse.httpRequestHeaders);
			final int code = connection.getResponseCode();
			final String location = connection.getHeaderField(HttpHeaders.LOCATION);

			if (isRedirectResponse(httpMethod, code)) {
				connection.disconnect();
				url = handleRedirect(url, location, dataSpecToUse);
			} else if (httpMethod == DataSpec.HTTP_METHOD_POST && isPostRedirectResponse(code)) {
				connection.disconnect();
				if (!(keepPostFor302Redirects && code == HttpURLConnection.HTTP_MOVED_TEMP)) {
					httpMethod = DataSpec.HTTP_METHOD_GET;
					httpBody = null;
				}
				url = handleRedirect(url, location, dataSpecToUse);
			} else return connection;
		}

		throw new HttpDataSourceException(new NoRouteToHostException("Too many redirects: " + redirectCount), dataSpecToUse, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSourceException.TYPE_OPEN);
	}

	@NonNull
	private HttpURLConnection makeConnection(@NonNull final URL url, @HttpMethod final int ignoredHttpMethod, @Nullable final byte[] ignoredHttpBody, final long position, final long length, final boolean allowGzip, final boolean followRedirects, final Map<String, String> requestParameters) throws IOException {

		String requestUrl = url.toString();

		final boolean isVideoPlaybackUrl = url.getPath().startsWith("/videoplayback");
		if (isVideoPlaybackUrl && rnParameterEnabled && !requestUrl.contains(RN_PARAMETER)) {
			requestUrl += RN_PARAMETER + requestNumber;
			++requestNumber;
		}

		if (rangeParameterEnabled && isVideoPlaybackUrl) {
			final String rangeParameterBuilt = buildRangeParameter(position, length);
			if (rangeParameterBuilt != null) requestUrl += rangeParameterBuilt;
		}

		final HttpURLConnection httpURLConnection = openConnection(new URL(requestUrl));
		httpURLConnection.setConnectTimeout(connectTimeoutMillis);
		httpURLConnection.setReadTimeout(readTimeoutMillis);

		final Map<String, String> requestHeaders = new HashMap<>();
		if (defaultRequestProperties != null)
			requestHeaders.putAll(defaultRequestProperties.getSnapshot());
		requestHeaders.putAll(requestProperties.getSnapshot());
		requestHeaders.putAll(requestParameters);

		final String cookies = CookieManager.getInstance().getCookie(requestUrl);
		if (cookies != null && !cookies.isEmpty())
			requestHeaders.put(HttpHeaders.COOKIE, cookies);

		for (final Map.Entry<String, String> property : requestHeaders.entrySet())
			httpURLConnection.setRequestProperty(property.getKey(), property.getValue());

		if (!rangeParameterEnabled) {
			final String rangeHeader = HttpUtil.buildRangeRequestHeader(position, length);
			if (rangeHeader != null) httpURLConnection.setRequestProperty(HttpHeaders.RANGE, rangeHeader);
		}

		final boolean isTvHtml5StreamingUrl = isTvHtml5StreamingUrl(requestUrl);

		if (isWebStreamingUrl(requestUrl) || isTvHtml5StreamingUrl || isWebEmbeddedPlayerStreamingUrl(requestUrl)) {
			httpURLConnection.setRequestProperty(HttpHeaders.ORIGIN, YOUTUBE_BASE_URL);
			httpURLConnection.setRequestProperty(HttpHeaders.REFERER, YOUTUBE_BASE_URL);
			httpURLConnection.setRequestProperty(HttpHeaders.SEC_FETCH_DEST, "empty");
			httpURLConnection.setRequestProperty(HttpHeaders.SEC_FETCH_MODE, "cors");
			httpURLConnection.setRequestProperty(HttpHeaders.SEC_FETCH_SITE, "cross-site");
		}

		httpURLConnection.setRequestProperty(HttpHeaders.TE, "trailers");
		httpURLConnection.setRequestProperty(HttpHeaders.ACCEPT, "*/*");

		final boolean isAndroidStreamingUrl = isAndroidStreamingUrl(requestUrl);
		final boolean isIosStreamingUrl = isIosStreamingUrl(requestUrl);
		if (isAndroidStreamingUrl)
			httpURLConnection.setRequestProperty(HttpHeaders.USER_AGENT, getAndroidUserAgent(null));
		else if (isIosStreamingUrl)
			httpURLConnection.setRequestProperty(HttpHeaders.USER_AGENT, getIosUserAgent(null));
		else if (isTvHtml5StreamingUrl)
			httpURLConnection.setRequestProperty(HttpHeaders.USER_AGENT, getTvHtml5UserAgent());
		else
			httpURLConnection.setRequestProperty(HttpHeaders.USER_AGENT, userAgent);

		httpURLConnection.setRequestProperty(HttpHeaders.ACCEPT_ENCODING, allowGzip ? "gzip" : "identity");
		httpURLConnection.setInstanceFollowRedirects(followRedirects);

		if (isVideoPlaybackUrl) {
			httpURLConnection.setRequestMethod("POST");
			httpURLConnection.setDoOutput(true);
			httpURLConnection.setFixedLengthStreamingMode(POST_BODY.length);

			try (final OutputStream os = httpURLConnection.getOutputStream()) {
				os.write(POST_BODY);
			}
		} else {
			httpURLConnection.setRequestMethod("GET");
			httpURLConnection.connect();
		}

		return httpURLConnection;
	}

	private HttpURLConnection openConnection(@NonNull final URL url) throws IOException {
		return (HttpURLConnection) url.openConnection();
	}

	@NonNull
	private URL handleRedirect(final URL originalUrl, @Nullable final String location, final DataSpec dataSpecToHandleRedirect) throws HttpDataSourceException {
		if (location == null)
			throw new HttpDataSourceException("Null location redirect", dataSpecToHandleRedirect, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSourceException.TYPE_OPEN);

		try {
			return new URL(originalUrl, location);
		} catch (final MalformedURLException e) {
			throw new HttpDataSourceException(e, dataSpecToHandleRedirect, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSourceException.TYPE_OPEN);
		}
	}

	private void skipFully(final long bytesToSkip, final DataSpec dataSpec) throws IOException {
		if (bytesToSkip == 0) return;
		final byte[] skipBuffer = new byte[4096];
		long bytesSkipped = 0;
		while (bytesSkipped < bytesToSkip) {
			final int readLength = (int) Math.min(bytesToSkip - bytesSkipped, skipBuffer.length);
			if (inputStream == null) throw new IOException("InputStream is null");
			final int read = inputStream.read(skipBuffer, 0, readLength);
			if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
			if (read == -1)
				throw new HttpDataSourceException(dataSpec, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, HttpDataSourceException.TYPE_OPEN);
			bytesSkipped += read;
			bytesRead += read;
		}
	}

	private int readInternal(final byte[] buffer, final int offset, final int readLength) throws IOException {
		if (readLength == 0) return 0;
		if (bytesToRead != C.LENGTH_UNSET) {
			final long bytesRemaining = bytesToRead - bytesRead;
			if (bytesRemaining == 0) return C.RESULT_END_OF_INPUT;
			final int bytesToReadInt = (int) Math.min(readLength, bytesRemaining);
			final int read = Util.castNonNull(inputStream).read(buffer, offset, bytesToReadInt);
			if (read == -1) return C.RESULT_END_OF_INPUT;
			bytesRead += read;
			bytesTransferred(read);
			return read;
		}
		final int read = Util.castNonNull(inputStream).read(buffer, offset, readLength);
		if (read == -1) return C.RESULT_END_OF_INPUT;
		bytesRead += read;
		bytesTransferred(read);
		return read;
	}

	private void closeConnectionQuietly() {
		if (connection != null) {
			try {
				connection.disconnect();
			} catch (final Exception e) {
				Log.e(TAG, "Unexpected error while disconnecting", e);
			}
			connection = null;
		}
	}

	public static final class Factory implements HttpDataSource.Factory {

		private final RequestProperties defaultRequestProperties;
		private final boolean allowCrossProtocolRedirects;
		private final boolean keepPostFor302Redirects;
		private final String userAgent;
		private boolean rangeParameterEnabled;
		private boolean rnParameterEnabled;

		private int connectTimeoutMs;
		private int readTimeoutMs;

		public Factory(final String userAgent) {
			this.userAgent = userAgent;
			defaultRequestProperties = new RequestProperties();
			connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MILLIS;
			readTimeoutMs = DEFAULT_READ_TIMEOUT_MILLIS;
			allowCrossProtocolRedirects = false;
			keepPostFor302Redirects = false;
			rangeParameterEnabled = false;
			rnParameterEnabled = false;
		}

		@NonNull
		@Override
		public Factory setDefaultRequestProperties(@NonNull final Map<String, String> defaultRequestPropertiesMap) {
			defaultRequestProperties.clearAndSet(defaultRequestPropertiesMap);
			return this;
		}

		public Factory setConnectTimeoutMs(final int connectTimeoutMsValue) {
			connectTimeoutMs = connectTimeoutMsValue;
			return this;
		}

		public Factory setReadTimeoutMs(final int readTimeoutMsValue) {
			readTimeoutMs = readTimeoutMsValue;
			return this;
		}

		public Factory setRangeParameterEnabled(final boolean rangeParameterEnabled) {
			this.rangeParameterEnabled = rangeParameterEnabled;
			return this;
		}

		public Factory setRnParameterEnabled(final boolean rnParameterEnabled) {
			this.rnParameterEnabled = rnParameterEnabled;
			return this;
		}

		@NonNull
		@Override
		public YoutubeHttpDataSource createDataSource() {
			return new YoutubeHttpDataSource(connectTimeoutMs, readTimeoutMs, allowCrossProtocolRedirects, rangeParameterEnabled, rnParameterEnabled, defaultRequestProperties, keepPostFor302Redirects, userAgent);
		}
	}
}
