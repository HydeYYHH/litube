package com.hhst.youtubelite.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Streamlined tests for StringUtils utility methods.
 */
public class StringUtilsTest {

	@Test
	public void parseHeight_extractsNumberFromResolution() {
		assertEquals(1080, StringUtils.parseHeight("1080p"));
		assertEquals(720, StringUtils.parseHeight("720p"));
		assertEquals(480, StringUtils.parseHeight("480p"));
		assertEquals(2160, StringUtils.parseHeight("2160p"));
	}

	@Test
	public void parseHeight_handlesNullAndInvalidInputs() {
		assertEquals(0, StringUtils.parseHeight(null));
		assertEquals(0, StringUtils.parseHeight(""));
		assertEquals(0, StringUtils.parseHeight("unknown"));
		assertEquals(0, StringUtils.parseHeight("p"));
	}

	@Test
	public void parseHeight_extractsFirstNumber() {
		assertEquals(1080, StringUtils.parseHeight("1080p60"));
		assertEquals(720, StringUtils.parseHeight("hd720"));
	}
}
