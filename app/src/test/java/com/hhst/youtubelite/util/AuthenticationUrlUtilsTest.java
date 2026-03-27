package com.hhst.youtubelite.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AuthenticationUrlUtilsTest {

	@Test
	public void isGoogleAccountsUrl_matchesAuthenticationHosts() {
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
}
