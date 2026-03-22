package com.hhst.youtubelite.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.google.gson.Gson;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public class AuthenticationHostIsolationTest {

	private static final Gson GSON = new Gson();

	@Test
	public void nativeHttpExecutor_rejectsAuthenticationHosts() {
		assertRejected(
						"https://accounts.google.com/_/lookup/accountlookup",
						"Email=user@example.com");
		assertRejected(
						"https://accounts.youtube.com/accounts/CheckConnection?pmpo=https%3A%2F%2Faccounts.google.com",
						"checkConnection=1");
	}

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

	private void assertRejected(final String url, final String body) {
		final NativeHttpRequestExecutor.PreparedRequest prepared = new NativeHttpRequestExecutor("TestAgent/1.0").prepare(
						payloadJson(
										url,
										"POST",
										true,
										Map.of("content-type", "application/x-www-form-urlencoded"),
										Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8))),
						ignored -> "SID=session");

		assertFalse(prepared.intercepted());
		assertEquals("disallowed-url", prepared.reason());
	}

	private String payloadJson(final String url,
	                           final String method,
	                           final Boolean includeCookies,
	                           final Map<String, String> headers,
	                           final String bodyBase64) {
		final Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("url", url);
		payload.put("method", method);
		payload.put("includeCookies", includeCookies);
		payload.put("headers", headers);
		payload.put("bodyBase64", bodyBase64);
		return GSON.toJson(payload);
	}
}
