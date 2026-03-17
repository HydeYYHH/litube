package com.hhst.youtubelite.util;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

import android.content.Context;

import org.junit.Test;

public class DownloadStorageUtilsTest {

	@Test
	public void getMimeType_prefersOutputReferenceExtensionWhenHistoryNameHasNoExtension() {
		final Context context = mock(Context.class);

		final String mimeType = DownloadStorageUtils.getMimeType(
						context,
						"/storage/emulated/0/Download/LiteTube/Sample Video.mp4",
						"Sample Video");

		assertEquals("video/mp4", mimeType);
	}
}
