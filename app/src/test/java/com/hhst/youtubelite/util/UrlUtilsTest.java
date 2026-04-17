package com.hhst.youtubelite.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.hhst.youtubelite.Constant;

import org.junit.Test;

import java.util.Locale;

/**
 * Streamlined tests for URL utility methods.
 */
public class UrlUtilsTest {

	@Test
	public void turkishLocaleHandling_preservesCaseInsensitiveMatching() {
		Locale original = Locale.getDefault();
		Locale.setDefault(Locale.forLanguageTag("tr-TR"));
		try {
			assertEquals("history",
							UrlUtils.getPageClassFromHost("m.youtube.com", java.util.List.of("feed", "history")));
			assertEquals("history",
							UrlUtils.getPageClassFromHost("M.YOUTUBE.COM", java.util.List.of("FEED", "HISTORY")));
			assertTrue(UrlUtils.isAllowedUrl("https://GSTaTIC.COM/resources"));
		} finally {
			Locale.setDefault(original);
		}
	}

	@Test
	public void isGoogleAccountsUrl_recognizesAuthHosts() {
		assertTrue(UrlUtils.isGoogleAccountsUrl("https://accounts.google.com/signin/v2/identifier"));
		assertTrue(UrlUtils.isGoogleAccountsUrl("https://accounts.google.co.jp/o/oauth2/auth"));
		assertTrue(UrlUtils.isGoogleAccountsUrl("https://accounts.youtube.com/accounts/CheckConnection"));
		assertFalse(UrlUtils.isGoogleAccountsUrl("https://m.youtube.com/signin"));
	}

	@Test
	public void isGoogleAccountsUrl_rejectsInvalidUrls() {
		assertFalse(UrlUtils.isGoogleAccountsUrl("file:///android_asset/page/error.html"));
		assertFalse(UrlUtils.isGoogleAccountsUrl("about:blank"));
		assertFalse(UrlUtils.isGoogleAccountsUrl(null));
		assertFalse(UrlUtils.isGoogleAccountsUrl(""));
	}

	@Test
	public void youtuBeShortLinks_areHandledAsWatch() {
		String shortUrl = "https://youtu.be/mAdodMaERp0";
		assertTrue(UrlUtils.isAllowedUrl(shortUrl));
		assertEquals(Constant.PAGE_WATCH, UrlUtils.getPageClass(shortUrl));
	}

	@Test
	public void isAllowedUrl_handlesEdgeCases() {
		assertFalse(UrlUtils.isAllowedUrl(null));
		assertFalse(UrlUtils.isAllowedUrl(""));
		assertFalse(UrlUtils.isAllowedUrl("invalid-url"));
		assertTrue(UrlUtils.isAllowedUrl("https://youtube.com/watch?v=abc"));
		assertTrue(UrlUtils.isAllowedUrl("https://m.youtube.com/feed/trending"));
		assertTrue(UrlUtils.isAllowedUrl("https://googlevideo.com/videoplayback"));
		assertTrue(UrlUtils.isAllowedUrl("https://ytimg.com/img/placeholder.png"));
		assertFalse(UrlUtils.isAllowedUrl("https://malicious.com/phishing"));
	}

	@Test
	public void externalUri_extractsYoutubeRedirectTarget() {
		var uri = UrlUtils.externalUri(
						"https://www.youtube.com/redirect?event=video_description&q=https%3A%2F%2Fexample.com%2Fwatch%3Fa%3D1");
		assertNotNull(uri);
		assertEquals("https://example.com/watch?a=1", uri.toString());
	}

	@Test
	public void externalUri_ignoresRedirectsBackIntoAllowedHosts() {
		assertNull(UrlUtils.externalUri(
						"https://www.youtube.com/redirect?q=https%3A%2F%2Fm.youtube.com%2Fwatch%3Fv%3Dabc"));
	}

	@Test
	public void externalUri_rejectsMissingOrInvalidTargets() {
		assertNull(UrlUtils.externalUri("https://www.youtube.com/redirect?event=video_description"));
		assertNull(UrlUtils.externalUri("https://www.youtube.com/redirect?q=ftp%3A%2F%2Fexample.com%2Fdocs"));
		assertNull(UrlUtils.externalUri("https://example.com/redirect?q=https%3A%2F%2Fmalicious.com"));
	}

	@Test
	public void getPageClass_handlesInvalidInputs() {
		assertEquals("unknown", UrlUtils.getPageClass(null));
		assertEquals("unknown", UrlUtils.getPageClass(""));
		assertEquals("unknown", UrlUtils.getPageClass("not-a-url"));
	}

	@Test
	public void getPageClass_recognizesAllPageTypes() {
		assertEquals(Constant.PAGE_HOME, UrlUtils.getPageClass("https://m.youtube.com"));
		assertEquals(Constant.PAGE_SHORTS, UrlUtils.getPageClass("https://m.youtube.com/shorts/abc"));
		assertEquals(Constant.PAGE_WATCH, UrlUtils.getPageClass("https://m.youtube.com/watch?v=abc"));
		assertEquals("channel", UrlUtils.getPageClass("https://m.youtube.com/channel/UC123"));
		assertEquals("gaming", UrlUtils.getPageClass("https://m.youtube.com/gaming"));
		assertEquals("searching", UrlUtils.getPageClass("https://m.youtube.com/results?q=test"));
		assertEquals("@", UrlUtils.getPageClass("https://m.youtube.com/@username"));
	}

	@Test
	public void getPageClass_handlesFeedSubPages() {
		assertEquals(Constant.PAGE_SUBSCRIPTIONS,
						UrlUtils.getPageClass("https://m.youtube.com/feed/subscriptions"));
		assertEquals(Constant.PAGE_LIBRARY,
						UrlUtils.getPageClass("https://m.youtube.com/feed/library"));
		assertEquals("history",
						UrlUtils.getPageClass("https://m.youtube.com/feed/history"));
		assertEquals("channels",
						UrlUtils.getPageClass("https://m.youtube.com/feed/channels"));
		assertEquals("playlists",
						UrlUtils.getPageClass("https://m.youtube.com/feed/playlists"));
	}

	@Test
	public void isPlaylistFirstItemUrl_matchesPlaylistWatchHead() {
		assertTrue(UrlUtils.isPlaylistFirstItemUrl(
						"https://www.youtube.com/watch?v=abc123&list=PL1234567890&index=1"));
		assertFalse(UrlUtils.isPlaylistFirstItemUrl(
						"https://www.youtube.com/watch?v=abc123&list=PL1234567890&index=2"));
		assertFalse(UrlUtils.isPlaylistFirstItemUrl(
						"https://www.youtube.com/watch?v=abc123&list=PL1234567890&index=3"));
		assertTrue(UrlUtils.isPlaylistFirstItemUrl(
						"https://www.youtube.com/watch?v=abc123&list=PL1234567890"));
	}

	@Test
	public void isPlaylistFirstItemUrl_rejectsInvalidInputs() {
		assertFalse(UrlUtils.isPlaylistFirstItemUrl(null));
		assertFalse(UrlUtils.isPlaylistFirstItemUrl(""));
		assertFalse(UrlUtils.isPlaylistFirstItemUrl("not-a-url"));
	}
}
