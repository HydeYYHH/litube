package com.hhst.youtubelite;

import com.hhst.youtubelite.common.YoutubeExtractor;

import org.junit.Test;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;

import java.io.IOException;

public class YoutubeExtractorTest {

  @Test
  public void test_info() throws ExtractionException, IOException {
    var info = YoutubeExtractor.info("https://www.youtube.com/watch?v=4CuF4PYzEfU");
    System.out.println(info);
  }
}