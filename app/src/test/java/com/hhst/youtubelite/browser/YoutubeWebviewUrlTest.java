package com.hhst.youtubelite.browser;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class YoutubeWebviewUrlTest {

	@Test
	public void sanitizeLoadUrl_stripsListParameterFromWatchUrlWhenQueueIsEnabled() {
		assertEquals(
				"https://m.youtube.com/watch?v=new&start_radio=1&pp=oAcB",
				YoutubeWebview.sanitizeLoadUrl(
						"https://m.youtube.com/watch?v=new&list=RDnew&start_radio=1&pp=oAcB",
						true));
	}

	@Test
	public void sanitizeLoadUrl_keepsUrlUntouchedWhenQueueIsDisabled() {
		assertEquals(
				"https://m.youtube.com/watch?v=new&list=RDnew&start_radio=1",
				YoutubeWebview.sanitizeLoadUrl(
						"https://m.youtube.com/watch?v=new&list=RDnew&start_radio=1",
						false));
	}

	@Test
	public void sanitizeLoadUrl_keepsNonWatchUrlsUntouched() {
		assertEquals(
				"https://m.youtube.com/feed/library?list=RDnew",
				YoutubeWebview.sanitizeLoadUrl(
						"https://m.youtube.com/feed/library?list=RDnew",
						true));
	}
}
