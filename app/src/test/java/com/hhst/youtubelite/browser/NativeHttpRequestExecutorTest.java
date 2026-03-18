package com.hhst.youtubelite.browser;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import okio.Buffer;

public class NativeHttpRequestExecutorTest {

	@Test
	public void prepare_preservesBinaryPostBodyAndDisablesCaching() throws Exception {
		final byte[] gzippedBody = new byte[]{0x1f, (byte) 0x8b, 0x08, 0x00, 0x01, 0x02};
		final NativeHttpRequestExecutor executor = new NativeHttpRequestExecutor("TestAgent/1.0");
		final NativeHttpRequestExecutor.PreparedRequest prepared = executor.prepare("""
						{
						  "url": "https://m.youtube.com/youtubei/v1/next?prettyPrint=false",
						  "method": "POST",
						  "includeCookies": true,
						  "headers": {
						    "content-type": "application/json",
						    "content-encoding": "gzip",
						    "authorization": "SAPISIDHASH example"
						  },
						  "bodyBase64": "%s"
						}
						""".replace("%s", Base64.getEncoder().encodeToString(gzippedBody)),
						url -> "SID=session");

		assertTrue(prepared.intercepted());
		assertNotNull(prepared.request());
		assertEquals("POST", prepared.request().method());
		assertEquals("TestAgent/1.0", prepared.request().header("User-Agent"));
		assertEquals("SID=session", prepared.request().header("Cookie"));
		assertEquals("gzip", prepared.request().header("Content-Encoding"));
		assertEquals("application/json", prepared.request().header("Content-Type"));
		assertEquals("no-cache, no-store", prepared.request().header("Cache-Control"));
		assertEquals("no-cache", prepared.request().header("Pragma"));

		final Buffer buffer = new Buffer();
		prepared.request().body().writeTo(buffer);
		assertArrayEquals(gzippedBody, buffer.readByteArray());
	}

	@Test
	public void prepare_rejectsRequestsOutsideAllowedDomains() {
		final NativeHttpRequestExecutor executor = new NativeHttpRequestExecutor("TestAgent/1.0");
		final NativeHttpRequestExecutor.PreparedRequest prepared = executor.prepare("""
						{
						  "url": "https://example.com/api/player",
						  "method": "POST",
						  "headers": {},
						  "bodyBase64": ""
						}
						""",
						url -> null);

		assertFalse(prepared.intercepted());
		assertEquals("disallowed-url", prepared.reason());
	}

	@Test
	public void prepare_acceptsGetRequestsWithoutBody() {
		final NativeHttpRequestExecutor executor = new NativeHttpRequestExecutor("TestAgent/1.0");
		final NativeHttpRequestExecutor.PreparedRequest prepared = executor.prepare("""
						{
						  "url": "https://m.youtube.com/youtubei/v1/guide",
						  "method": "GET",
						  "headers": {
						    "accept": "application/json"
						  },
						  "bodyBase64": ""
						}
						""",
						url -> "SID=session");

		assertTrue(prepared.intercepted());
		assertNotNull(prepared.request());
		assertEquals("GET", prepared.request().method());
		assertEquals("SID=session", prepared.request().header("Cookie"));
		assertEquals("no-cache, no-store", prepared.request().header("Cache-Control"));
		assertEquals("no-cache", prepared.request().header("Pragma"));
	}

	@Test
	public void prepare_acceptsHeadRequestsWithoutBody() {
		final NativeHttpRequestExecutor executor = new NativeHttpRequestExecutor("TestAgent/1.0");
		final NativeHttpRequestExecutor.PreparedRequest prepared = executor.prepare("""
						{
						  "url": "https://m.youtube.com/youtubei/v1/guide",
						  "method": "HEAD",
						  "headers": {
						    "accept": "application/json"
						  },
						  "bodyBase64": ""
						}
						""",
						url -> "SID=session");

		assertTrue(prepared.intercepted());
		assertNotNull(prepared.request());
		assertEquals("HEAD", prepared.request().method());
	}

	@Test
	public void prepare_acceptsPutRequestsWithBody() throws Exception {
		final byte[] body = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
		final NativeHttpRequestExecutor executor = new NativeHttpRequestExecutor("TestAgent/1.0");
		final NativeHttpRequestExecutor.PreparedRequest prepared = executor.prepare("""
						{
						  "url": "https://m.youtube.com/youtubei/v1/log_event",
						  "method": "PUT",
						  "headers": {
						    "content-type": "application/json"
						  },
						  "bodyBase64": "%s"
						}
						""".replace("%s", Base64.getEncoder().encodeToString(body)),
						url -> null);

		assertTrue(prepared.intercepted());
		assertNotNull(prepared.request());
		assertEquals("PUT", prepared.request().method());

		final Buffer buffer = new Buffer();
		prepared.request().body().writeTo(buffer);
		assertArrayEquals(body, buffer.readByteArray());
	}

	@Test
	public void prepare_buildsStableDedupeKeyForEquivalentRequests() {
		final NativeHttpRequestExecutor executor = new NativeHttpRequestExecutor("TestAgent/1.0");
		final NativeHttpRequestExecutor.PreparedRequest first = executor.prepare("""
						{
						  "url": "https://m.youtube.com/youtubei/v1/guide",
						  "method": "GET",
						  "headers": {
						    "accept": "application/json",
						    "x-youtube-client-name": "1"
						  }
						}
						""",
						url -> "SID=session");
		final NativeHttpRequestExecutor.PreparedRequest second = executor.prepare("""
						{
						  "url": "https://m.youtube.com/youtubei/v1/guide",
						  "method": "GET",
						  "headers": {
						    "x-youtube-client-name": "1",
						    "accept": "application/json"
						  }
						}
						""",
						url -> "SID=session");

		assertNotNull(first.dedupeKey());
		assertEquals(first.dedupeKey(), second.dedupeKey());
	}

	@Test
	public void prepare_dedupeKeyChangesWhenCookieSnapshotChanges() {
		final NativeHttpRequestExecutor executor = new NativeHttpRequestExecutor("TestAgent/1.0");
		final String payload = """
						{
						  "url": "https://m.youtube.com/youtubei/v1/next",
						  "method": "POST",
						  "headers": {
						    "content-type": "application/json"
						  },
						  "bodyBase64": "e30="
						}
						""";
		final NativeHttpRequestExecutor.PreparedRequest first = executor.prepare(payload, url -> "SID=first");
		final NativeHttpRequestExecutor.PreparedRequest second = executor.prepare(payload, url -> "SID=second");

		assertNotNull(first.dedupeKey());
		assertNotNull(second.dedupeKey());
		assertNotEquals(first.dedupeKey(), second.dedupeKey());
	}

	@Test
	public void buildResponsePayload_keepsUtf8TextResponsesAsText() throws Exception {
		final byte[] responseBody = "ok".getBytes(StandardCharsets.UTF_8);
		final NativeHttpRequestExecutor executor = new NativeHttpRequestExecutor("TestAgent/1.0");
		final okhttp3.Response response = new okhttp3.Response.Builder()
						.request(new okhttp3.Request.Builder().url("https://m.youtube.com/youtubei/v1/next").build())
						.protocol(okhttp3.Protocol.HTTP_1_1)
						.code(200)
						.message("OK")
						.header("Content-Type", "application/json")
						.body(okhttp3.ResponseBody.create(responseBody, null))
						.priorResponse(new okhttp3.Response.Builder()
										.request(new okhttp3.Request.Builder().url("https://m.youtube.com/redirect").build())
										.protocol(okhttp3.Protocol.HTTP_1_1)
										.code(302)
										.message("Found")
										.body(okhttp3.ResponseBody.create(new byte[0], null))
										.build())
						.build();

		final NativeHttpRequestExecutor.ResponsePayload payload = executor.buildResponsePayload("req-1", response);

		assertTrue(payload.intercepted());
		assertEquals("req-1", payload.requestId());
		assertEquals("https://m.youtube.com/youtubei/v1/next", payload.url());
		assertTrue(payload.redirected());
		assertEquals(200, payload.status());
		assertEquals("OK", payload.statusText());
		assertFalse(payload.binaryBody());
		assertEquals("ok", payload.bodyText());
		assertEquals("", payload.bodyBase64());
		assertEquals(Map.of("Content-Type", "application/json"), payload.headers());
	}

	@Test
	public void buildResponsePayload_keepsBinaryResponsesAsBase64() throws Exception {
		final byte[] responseBody = new byte[]{0x1f, (byte) 0x8b, 0x08, 0x00};
		final NativeHttpRequestExecutor executor = new NativeHttpRequestExecutor("TestAgent/1.0");
		final okhttp3.Response response = new okhttp3.Response.Builder()
						.request(new okhttp3.Request.Builder().url("https://m.youtube.com/youtubei/v1/player").build())
						.protocol(okhttp3.Protocol.HTTP_1_1)
						.code(200)
						.message("OK")
						.header("Content-Type", "application/octet-stream")
						.body(okhttp3.ResponseBody.create(responseBody, null))
						.build();

		final NativeHttpRequestExecutor.ResponsePayload payload = executor.buildResponsePayload("req-2", response);

		assertTrue(payload.binaryBody());
		assertEquals("", payload.bodyText());
		assertEquals(Base64.getEncoder().encodeToString(responseBody), payload.bodyBase64());
	}
}
