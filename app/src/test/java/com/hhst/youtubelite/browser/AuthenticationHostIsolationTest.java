package com.hhst.youtubelite.browser;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.util.Map;

public class AuthenticationHostIsolationTest {

	@Test
	public void webViewInterceptor_rejectsAuthenticationHosts() {
		assertFalse(OkHttpWebViewInterceptor.shouldProxyRequest(
						"GET",
						Map.of(),
						"https://accounts.google.com/signin/v2/identifier"));
		assertFalse(OkHttpWebViewInterceptor.shouldProxyRequest(
						"GET",
						Map.of(),
						"https://accounts.youtube.com/accounts/CheckConnection"));
	}
}
