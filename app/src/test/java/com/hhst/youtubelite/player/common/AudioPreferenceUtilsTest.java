package com.hhst.youtubelite.player.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.schabi.newpipe.extractor.stream.AudioStream;

import java.util.List;
import java.util.Locale;

public class AudioPreferenceUtilsTest {

	@Test
	public void reorder_returnsMutableListWhenInputIsImmutable() {
		final AudioStream dubbed = mock(AudioStream.class);
		final AudioStream original = mock(AudioStream.class);
		when(dubbed.getAudioTrackName()).thenReturn("French");
		when(original.getAudioTrackName()).thenReturn("English original");
		when(dubbed.getAudioLocale()).thenReturn(Locale.FRENCH);
		when(original.getAudioLocale()).thenReturn(Locale.ENGLISH);
		when(dubbed.getAverageBitrate()).thenReturn(128);
		when(original.getAverageBitrate()).thenReturn(128);

		final List<AudioStream> reordered = AudioPreferenceUtils.reorder(List.of(dubbed, original), "en");

		assertEquals(2, reordered.size());
		assertSame(original, reordered.get(0));
		reordered.clear();
		assertEquals(0, reordered.size());
	}
}
