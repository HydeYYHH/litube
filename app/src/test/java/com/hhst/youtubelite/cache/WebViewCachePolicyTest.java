package com.hhst.youtubelite.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class WebViewCachePolicyTest {

	private final WebViewCachePolicy policy = new WebViewCachePolicy();

	private static Response createResponse(final Request request,
	                                       final String cacheControl,
	                                       final String contentType,
	                                       final long receivedAtMillis) {
		final Response.Builder builder = new Response.Builder()
						.request(request)
						.protocol(Protocol.HTTP_1_1)
						.code(200)
						.message("OK")
						.sentRequestAtMillis(receivedAtMillis)
						.receivedResponseAtMillis(receivedAtMillis)
						.body(ResponseBody.create("<body/>", MediaType.get(contentType)));
		if (cacheControl != null) {
			builder.header("Cache-Control", cacheControl);
		}
		return builder.build();
	}

	@Test
	public void classifyRequest_marksYoutubeMainFrameForForcedCacheLookup() {
		final WebViewCachePolicy.CacheRequestInfo requestInfo = policy.classifyRequest(
						true,
						"https://m.youtube.com/watch?v=abc123",
						"/watch");

		assertTrue(requestInfo.forceCacheMainFrame());
		assertFalse(requestInfo.forceCacheStaticResource());
		assertTrue(policy.shouldAttemptCacheLookup(requestInfo));
	}

	@Test
	public void maybeRewriteResponse_forcesStaticResourceCachingAndPreservesOriginalHeader() {
		final Request request = new Request.Builder()
						.url("https://i.ytimg.com/vi/abc/default.jpg")
						.build();
		final Response response = createResponse(
						request,
						"max-age=60",
						"image/jpeg",
						System.currentTimeMillis());

		final Response rewritten = policy.maybeRewriteResponse(null, request, response);

		assertEquals(
						"public, max-age=" + WebViewCachePolicy.WEBVIEW_CACHE_MAX_AGE_SECONDS + ", immutable",
						rewritten.header("Cache-Control"));
		assertEquals("max-age=60", rewritten.header(WebViewCachePolicy.ORIGINAL_CACHE_CONTROL_HEADER));
	}

	@Test
	public void maybeRewriteResponse_forcesYoutubeMainFrameHtmlCaching() {
		final Request request = new Request.Builder()
						.url("https://m.youtube.com/watch?v=abc123")
						.header("Accept", "text/html,application/xhtml+xml")
						.build();
		final Response response = createResponse(
						request,
						"no-cache",
						"text/html",
						System.currentTimeMillis());

		final Response rewritten = policy.maybeRewriteResponse(
						policy.classifyRequest(true, request.url().toString(), request.url().encodedPath()),
						request,
						response);

		assertEquals(
						"public, max-age=" + WebViewCachePolicy.WEBVIEW_CACHE_MAX_AGE_SECONDS + ", immutable",
						rewritten.header("Cache-Control"));
		assertEquals("no-cache", rewritten.header(WebViewCachePolicy.ORIGINAL_CACHE_CONTROL_HEADER));
	}

	@Test
	public void shouldRefreshCache_returnsFalseForFreshForcedCacheEntry() {
		final Request request = new Request.Builder()
						.url("https://i.ytimg.com/vi/abc/default.jpg")
						.build();
		final Response rewritten = policy.maybeRewriteResponse(
						null,
						request,
						createResponse(request, "max-age=3600", "image/jpeg", System.currentTimeMillis()));

		assertFalse(policy.shouldRefreshCache(rewritten));
	}

	@Test
	public void shouldRefreshCache_returnsTrueWhenOriginalCachePolicyIsNearExpiry() {
		final Request request = new Request.Builder()
						.url("https://i.ytimg.com/vi/abc/default.jpg")
						.build();
		final Response rewritten = policy.maybeRewriteResponse(
						null,
						request,
						createResponse(request, "max-age=60", "image/jpeg", System.currentTimeMillis() - 59_000L));

		assertTrue(policy.shouldRefreshCache(rewritten));
	}

	@Test
	public void maybeRewriteResponse_leavesNonCachedRequestUntouched() {
		final Request request = new Request.Builder()
						.url("https://m.youtube.com/watch?v=abc123")
						.build();
		final Response response = createResponse(
						request,
						"max-age=120",
						"application/json",
						System.currentTimeMillis());

		final Response rewritten = policy.maybeRewriteResponse(
						policy.classifyRequest(true, request.url().toString(), request.url().encodedPath()),
						request,
						response);

		assertEquals("max-age=120", rewritten.header("Cache-Control"));
		assertNull(rewritten.header(WebViewCachePolicy.ORIGINAL_CACHE_CONTROL_HEADER));
	}
}
