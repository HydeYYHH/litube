package com.hhst.youtubelite.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hhst.youtubelite.Constant;

import org.junit.Test;

import java.util.List;
import java.util.Locale;

public class UrlUtilsTest {

	@Test
	public void getPageClass_keepsHistoryRouteStableUnderTurkishLocale() {
		final Locale originalLocale = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));

			assertEquals(
							UrlUtils.PAGE_HISTORY,
							UrlUtils.resolvePageClass("m.youtube.com", List.of("feed", "history")));
		} finally {
			Locale.setDefault(originalLocale);
		}
	}

	@Test
	public void isAllowedUrl_acceptsUppercaseDomainsUnderTurkishLocale() {
		final Locale originalLocale = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));

			assertTrue(UrlUtils.isAllowedUrl("https://GSTaTIC.COM/resources"));
		} finally {
			Locale.setDefault(originalLocale);
		}
	}

	@Test
	public void resolvePageClass_handlesUppercaseSegmentsUnderTurkishLocale() {
		final Locale originalLocale = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));

			assertEquals(
							UrlUtils.PAGE_HISTORY,
							UrlUtils.resolvePageClass("M.YOUTUBE.COM", List.of("FEED", "HISTORY")));
		} finally {
			Locale.setDefault(originalLocale);
		}
	}

	@Test
	public void isGoogleAccountsUrl_matchesGoogleAuthHosts() {
		assertTrue(UrlUtils.isGoogleAccountsUrl("https://accounts.google.com/signin/v2/identifier"));
		assertTrue(UrlUtils.isGoogleAccountsUrl("https://accounts.google.co.jp/o/oauth2/auth"));
		assertTrue(UrlUtils.isGoogleAccountsUrl("https://accounts.youtube.com/accounts/CheckConnection"));
		assertFalse(UrlUtils.isGoogleAccountsUrl("https://m.youtube.com/signin"));
	}

	@Test
	public void isGoogleAccountsUrl_rejectsHostlessUrls() {
		assertFalse(UrlUtils.isGoogleAccountsUrl("file:///android_asset/page/error.html"));
		assertFalse(UrlUtils.isGoogleAccountsUrl("about:blank"));
	}

	@Test
	public void isAllowedUrl_acceptsYoutuBeShortLinks() {
		assertTrue(UrlUtils.isAllowedUrl("https://youtu.be/mAdodMaERp0"));
	}

	@Test
	public void getPageClass_treatsYoutuBeShortLinksAsWatch() {
		assertEquals(Constant.PAGE_WATCH, UrlUtils.getPageClass("https://youtu.be/mAdodMaERp0"));
	}
}
