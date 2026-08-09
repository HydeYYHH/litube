package com.hhst.youtubelite.downloader.core.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

/**
 * Verifies that the downloader sends the per-client request headers googlevideo expects.
 * Streaming URLs issued to the ANDROID_VR / iOS / WEB clients are answered with HTTP 403
 * when requested without the matching User-Agent (and, for web-client URLs, the browser
 * headers) — this is why >360p downloads failed after YouTube enforced SABR on the
 * regular clients: the surviving non-SABR streams all require client-specific handling.
 */
public class StreamDownloaderImplClientHeadersTest {

	@Test
	public void webClientUrl_getsBrowserHeadersAndChromeUserAgent() {
		String url = "https://rr2---sn-xxx.googlevideo.com/videoplayback?expire=1&c=WEB&range=0-1";
		Map<String, String> headers = StreamDownloaderImpl.clientHeaders(url);

		assertEquals("https://www.youtube.com", headers.get("Origin"));
		assertEquals("https://www.youtube.com", headers.get("Referer"));
		assertEquals("empty", headers.get("Sec-Fetch-Dest"));
		assertNotNull(headers.get("User-Agent"));
		assertTrue(headers.get("User-Agent").contains("Chrome"));
	}

	@Test
	public void androidVrUrl_getsVrUserAgent() {
		String url = "https://rr2---sn-xxx.googlevideo.com/videoplayback?expire=1&c=ANDROID_VR";
		Map<String, String> headers = StreamDownloaderImpl.clientHeaders(url);

		assertNotNull(headers.get("User-Agent"));
		assertTrue(headers.get("User-Agent").contains("oculus"));
		assertFalse(headers.get("User-Agent").contains("Chrome"));
		assertFalse(headers.containsKey("Origin"));
	}

	@Test
	public void androidVrUrl_winsOverAndroidClientMatch() {
		// &c=ANDROID_VR contains the substring &c=ANDROID, so the VR check must run first.
		String url = "https://rr2---sn-xxx.googlevideo.com/videoplayback?c=ANDROID_VR&itag=137";
		Map<String, String> headers = StreamDownloaderImpl.clientHeaders(url);

		assertTrue(headers.get("User-Agent").contains("oculus"));
	}

	@Test
	public void unknownUrl_getsDefaultChromeUserAgentOnly() {
		Map<String, String> headers = StreamDownloaderImpl.clientHeaders("https://example.com/video.mp4");

		assertEquals(1, headers.size());
		assertTrue(headers.get("User-Agent").contains("Chrome"));
	}
}
