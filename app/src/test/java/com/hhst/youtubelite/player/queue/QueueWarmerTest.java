package com.hhst.youtubelite.player.queue;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hhst.youtubelite.extractor.ExtractionSession;
import com.hhst.youtubelite.extractor.PlaybackDetails;
import com.hhst.youtubelite.extractor.YoutubeExtractor;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

public class QueueWarmerTest {
	private static final String FIRST_VIDEO_ID = "abc123def45";
	private static final String SECOND_VIDEO_ID = "zyx987uvw65";

	private YoutubeExtractor extractor;
	private RecordingExecutor executor;
	private QueueWarmer warmer;

	@Before
	public void setUp() {
		extractor = mock(YoutubeExtractor.class);
		executor = new RecordingExecutor();
		warmer = new QueueWarmer(extractor, executor);
	}

	@Test
	public void warmItems_schedulesDistinctWarmableVideosAndRunsOnlyOnBackgroundExecutor() throws Exception {
		warmer.warmItems(List.of(
				item(FIRST_VIDEO_ID, url(FIRST_VIDEO_ID)),
				item(FIRST_VIDEO_ID, url(FIRST_VIDEO_ID)),
				item(null, url(SECOND_VIDEO_ID)),
				item("missing-url", null),
				item(null, "https://example.com/not-youtube")));

		assertEquals(1, executor.pendingCount());
		verifyNoInteractions(extractor);

		executor.runAll();

		final InOrder inOrder = inOrder(extractor);
		inOrder.verify(extractor).getPlaybackDetails(eq(url(FIRST_VIDEO_ID)), any(ExtractionSession.class));
		inOrder.verify(extractor).getPlaybackDetails(eq(url(SECOND_VIDEO_ID)), any(ExtractionSession.class));
		verifyNoMoreInteractions(extractor);
	}

	@Test
	public void warmItem_deduplicatesWhileVideoIsAlreadyQueuedOrInFlight() {
		final QueueItem item = item(FIRST_VIDEO_ID, url(FIRST_VIDEO_ID));

		warmer.warmItem(item);
		warmer.warmItem(item);

		assertEquals(1, executor.pendingCount());
	}

	@Test
	public void warmItem_allowsRetryAfterPreviousExtractionFailure() throws Exception {
		final QueueItem item = item(FIRST_VIDEO_ID, url(FIRST_VIDEO_ID));
		when(extractor.getPlaybackDetails(eq(url(FIRST_VIDEO_ID)), any(ExtractionSession.class)))
				.thenThrow(new IOException("boom"))
				.thenReturn(mock(PlaybackDetails.class));

		warmer.warmItem(item);
		executor.runAll();
		warmer.warmItem(item);
		executor.runAll();

		verify(extractor, times(2)).getPlaybackDetails(eq(url(FIRST_VIDEO_ID)), any(ExtractionSession.class));
	}

	@Test
	public void prioritizeItem_movesQueuedVideoToTheFront() throws Exception {
		warmer.warmItem(item(FIRST_VIDEO_ID, url(FIRST_VIDEO_ID)));
		warmer.warmItem(item(SECOND_VIDEO_ID, url(SECOND_VIDEO_ID)));
		warmer.prioritizeItem(item(SECOND_VIDEO_ID, url(SECOND_VIDEO_ID)));

		executor.runAll();

		final InOrder inOrder = inOrder(extractor);
		inOrder.verify(extractor).getPlaybackDetails(eq(url(SECOND_VIDEO_ID)), any(ExtractionSession.class));
		inOrder.verify(extractor).getPlaybackDetails(eq(url(FIRST_VIDEO_ID)), any(ExtractionSession.class));
	}

	private static QueueItem item(final String videoId, final String url) {
		return new QueueItem(videoId, url, "title-" + videoId, "author-" + videoId, null);
	}

	private static String url(final String videoId) {
		return "https://m.youtube.com/watch?v=" + videoId;
	}

	private static final class RecordingExecutor implements Executor {
		private final Queue<Runnable> tasks = new ArrayDeque<>();

		@Override
		public void execute(final Runnable command) {
			tasks.add(command);
		}

		int pendingCount() {
			return tasks.size();
		}

		void runAll() {
			while (!tasks.isEmpty()) {
				tasks.remove().run();
			}
		}
	}
}
