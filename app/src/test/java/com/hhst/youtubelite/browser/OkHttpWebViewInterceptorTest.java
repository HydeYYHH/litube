package com.hhst.youtubelite.browser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

public class OkHttpWebViewInterceptorTest {

	@Test
	public void shouldProxyRequest_allowsYoutubeWatchUrl() {
		assertTrue(OkHttpWebViewInterceptor.shouldProxyRequest(
						"GET",
						Map.of(),
						"https://m.youtube.com/watch?v=test"));
	}

	@Test
	public void shouldProxyRequest_skipsYoutubeRedirectShellToExternalUrl() {
		assertFalse(OkHttpWebViewInterceptor.shouldProxyRequest(
						"GET",
						Map.of(),
						"https://www.youtube.com/redirect?event=video_description&q=https%3A%2F%2Fexample.com%2Fdocs"));
	}

	@Test
	public void shouldProxyRequest_rejectsRangeRequests() {
		assertFalse(OkHttpWebViewInterceptor.shouldProxyRequest(
						"GET",
						Map.of("Range", "bytes=0-1024"),
						"https://m.youtube.com/watch?v=test"));
	}
}
