package com.hhst.youtubelite.extractor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

public class YoutubeAuthTest {

	@Test
	public void headers_buildLoggedInWebApiHeaders() throws Exception {
		AuthContext auth = new AuthContext(
						"webview",
						"SAPISID=sid; __Secure-1PAPISID=one; __Secure-3PAPISID=three",
						"visitor",
						"page||user",
						"1.0",
						"2",
						true,
						false,
						1L);

		YoutubeAuth.Result result = YoutubeAuth.headers(
						"https://www.youtube.com/youtubei/v1/player",
						auth,
						1_700_000_000_000L);

		assertNull(result.note());
		Map<String, String> headers = result.headers();
		assertEquals(
						"SAPISIDHASH " + expected("user", 1_700_000_000L, "sid", "https://www.youtube.com")
										+ "_u"
										+ " SAPISID1PHASH " + expected("user", 1_700_000_000L, "one", "https://www.youtube.com") + "_u"
										+ " SAPISID3PHASH " + expected("user", 1_700_000_000L, "three", "https://www.youtube.com") + "_u",
						headers.get("Authorization"));
		assertEquals("https://www.youtube.com", headers.get("Origin"));
		assertEquals("https://www.youtube.com", headers.get("X-Origin"));
		assertEquals("visitor", headers.get("X-Goog-Visitor-Id"));
		assertEquals("2", headers.get("X-Goog-AuthUser"));
		assertEquals("page", headers.get("X-Goog-PageId"));
		assertEquals("true", headers.get("X-Youtube-Bootstrap-Logged-In"));
	}

	@Test
	public void headers_skipLoggedInWebApiRequestWithoutSidCookie() {
		AuthContext auth = new AuthContext(
						"webview",
						"VISITOR_INFO1_LIVE=visitor",
						"visitor",
						"page||user",
						"1.0",
						"0",
						true,
						false,
						1L);

		YoutubeAuth.Result result = YoutubeAuth.headers(
						"https://www.youtube.com/youtubei/v1/player",
						auth,
						1_700_000_000_000L);

		assertTrue(result.headers().isEmpty());
		assertEquals("missing sid cookie", result.note());
	}

	@Test
	public void headers_ignoreNonWebApiRequests() {
		AuthContext auth = new AuthContext(
						"webview",
						"SAPISID=sid",
						"visitor",
						"page||user",
						"1.0",
						"0",
						true,
						false,
						1L);

		YoutubeAuth.Result result = YoutubeAuth.headers(
						"https://youtubei.googleapis.com/youtubei/v1/player",
						auth,
						1_700_000_000_000L);

		assertTrue(result.headers().isEmpty());
		assertNull(result.note());
		assertFalse(YoutubeAuth.isWebApi("https://youtubei.googleapis.com/youtubei/v1/player"));
	}

	@Test
	public void pageId_extractsFirstDataSyncPart() {
		assertEquals("page", YoutubeAuth.pageId("page||user"));
		assertNull(YoutubeAuth.pageId("user"));
		assertEquals("user", YoutubeAuth.userId("page||user"));
		assertEquals("user", YoutubeAuth.userId("user"));
		assertNull(YoutubeAuth.pageId(null));
	}

	private String expected(String pageId,
	                        long nowSeconds,
	                        String sid,
	                        String origin) throws Exception {
		String input = pageId + " " + nowSeconds + " " + sid + " " + origin;
		byte[] digest = MessageDigest.getInstance("SHA-1")
						.digest(input.getBytes(StandardCharsets.UTF_8));
		StringBuilder out = new StringBuilder();
		for (byte b : digest) {
			out.append(Character.forDigit((b >>> 4) & 0xf, 16));
			out.append(Character.forDigit(b & 0xf, 16));
		}
		return nowSeconds + "_" + out;
	}
}
