package com.hhst.youtubelite;

import com.hhst.youtubelite.extractor.DownloaderImpl;

import org.junit.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.io.IOException;

public class YoutubeExtractorTest {

	@Test
	public void test_info() throws ExtractionException, IOException {
		DownloaderImpl.init(null);
		NewPipe.init(DownloaderImpl.getInstance());
		var info = StreamInfo.getInfo(ServiceList.YouTube, "https://m.youtube.com/watch?v=mAdodMaERp0");
		System.out.println(info.getAudioStreams().size()); // 72
		System.out.println(info.getAudioStreams().stream().map(AudioStream::getAudioTrackName).toList());//[German (DE), Spanish (US), French (FR), Hindi, Indonesian, Italian, Japanese, Malayalam, Polish, Portuguese (BR), Ukrainian, English (US) original, German (DE), Spanish (US), French (FR), Hindi, Indonesian, Italian, Japanese, Malayalam, Polish, Portuguese (BR), Ukrainian, English (US) original, German (DE), Spanish (US), French (FR), Hindi, Indonesian, Italian, Japanese, Malayalam, Polish, Portuguese (BR), Ukrainian, English (US) original, German (DE), Spanish (US), French (FR), Hindi, Indonesian, Italian, Japanese, Malayalam, Polish, Portuguese (BR), Ukrainian, English (US) original, German (DE), Spanish (US), French (FR), Hindi, Indonesian, Italian, Japanese, Malayalam, Polish, Portuguese (BR), Ukrainian, English (US) original, German (DE), Spanish (US), French (FR), Hindi, Indonesian, Italian, Japanese, Malayalam, Polish, Portuguese (BR), Ukrainian, English (US) original]
		System.out.println(info.getAudioStreams().stream().map(AudioStream::getAudioTrackType).toList());//[DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, ORIGINAL, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, ORIGINAL, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, ORIGINAL, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, ORIGINAL, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, ORIGINAL, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, DUBBED, ORIGINAL]
		System.out.println(info.getAudioStreams().stream().map(AudioStream::getAudioTrackId).toList());//[de-DE.10, es-US.10, fr-FR.10, hi.10, id.10, it.10, ja.10, ml.10, pl.10, pt-BR.10, uk.10, en-US.4, de-DE.10, es-US.10, fr-FR.10, hi.10, id.10, it.10, ja.10, ml.10, pl.10, pt-BR.10, uk.10, en-US.4, de-DE.10, es-US.10, fr-FR.10, hi.10, id.10, it.10, ja.10, ml.10, pl.10, pt-BR.10, uk.10, en-US.4, de-DE.10, es-US.10, fr-FR.10, hi.10, id.10, it.10, ja.10, ml.10, pl.10, pt-BR.10, uk.10, en-US.4, de-DE.10, es-US.10, fr-FR.10, hi.10, id.10, it.10, ja.10, ml.10, pl.10, pt-BR.10, uk.10, en-US.4, de-DE.10, es-US.10, fr-FR.10, hi.10, id.10, it.10, ja.10, ml.10, pl.10, pt-BR.10, uk.10, en-US.4]
		System.out.println(info.getAudioStreams().stream().map(AudioStream::getFormat).toList());//[M4A, M4A, M4A, M4A, M4A, M4A, M4A, M4A, M4A, M4A, M4A, M4A, M4A, M4A, M4A, M4A, M4A, M4A, M4A, M4A, M4A, M4A, M4A, M4A, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, M4A, M4A, M4A, M4A, M4A, M4A, M4A, M4A, M4A, M4A, M4A, M4A, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS, WEBMA_OPUS]
		System.out.println(info.getAudioStreams().stream().map(AudioStream::getAverageBitrate).toList());//[48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 128, 50, 50, 50, 50, 50, 50, 50, 50, 50, 50, 50, 50, 160, 160, 160, 160, 160, 160, 160, 160, 160, 160, 160, 160, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32, 35, 35, 35, 35, 35, 35, 35, 35, 35, 35, 35, 35]
	}
}