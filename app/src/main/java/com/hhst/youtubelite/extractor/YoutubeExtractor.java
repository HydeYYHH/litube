package com.hhst.youtubelite.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.hhst.youtubelite.extractor.potoken.LitePoTokenProvider;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Contract for fetching extracted playback data by video id.
 */
@FunctionalInterface
interface Fetch {
	ExtractedInfo fetch(@NonNull String videoId,
	                    @Nullable ExtractionSession session)
					throws org.schabi.newpipe.extractor.exceptions.ExtractionException, IOException;
}

/**
 * Coordinates YouTube extraction, caching, and playback-plan assembly.
 */
@Singleton
public final class YoutubeExtractor {
	@NonNull
	private final Fetch play;
	@NonNull
	private final Fetch info;
	@NonNull
	private final InfoCache cache;
	@NonNull
	private final Executor executor;
	@NonNull
	private final Gson gson;
	@NonNull
	private final AuthContextFactory auth;
	@NonNull
	private final ConcurrentMap<String, Task> tasks = new ConcurrentHashMap<>();

	@Inject
	public YoutubeExtractor(@NonNull DownloaderImpl downloader,
	                        @NonNull LitePoTokenProvider litePoTokenProvider,
	                        @NonNull AuthContextFactory auth,
	                        @NonNull SessionClientProfileProvider profiles,
	                        @NonNull InfoCache cache,
	                        @NonNull Executor executor,
	                        @NonNull Gson gson) {
		this(
						(videoId, session) -> downloader.withExtractionSession(
										() -> extract(
														"https://www.youtube.com/watch?v=" + videoId,
														true),
										session),
						(videoId, session) -> downloader.withExtractionSession(
										() -> extract(
														"https://www.youtube.com/watch?v=" + videoId,
														false),
										session),
						cache,
						executor,
						gson,
						auth);
		NewPipe.init(downloader);
		YoutubeStreamExtractor.setPoTokenProvider(litePoTokenProvider);
		YoutubeStreamExtractor.setClientProfileProvider(profiles);
	}

	YoutubeExtractor(@NonNull Fetch play,
	                 @NonNull Fetch info,
	                 @NonNull InfoCache cache,
	                 @NonNull Executor executor,
	                 @NonNull Gson gson,
	                 @NonNull AuthContextFactory auth) {
		this.play = play;
		this.info = info;
		this.cache = cache;
		this.executor = executor;
		this.gson = gson;
		this.auth = auth;
	}

	@Nullable
	public static String getVideoId(@Nullable String url) {
		if (url == null) return null;

		Pattern compiledPattern = Pattern.compile("(?:v=|=v/|/v/|/u/\\w/|embed/|watch\\?v=|shorts/|youtu.be/)([a-zA-Z0-9_-]{11})");
		Matcher matcher = compiledPattern.matcher(url);

		if (matcher.find()) {
			return matcher.group(1);
		}
		return null;
	}

	private static boolean same(@Nullable Object first,
	                            @Nullable Object second) {
		return Objects.equals(first, second);
	}

	private static boolean isLive(@NonNull org.schabi.newpipe.extractor.stream.StreamType streamType) {
		return streamType == org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM
						|| streamType == org.schabi.newpipe.extractor.stream.StreamType.AUDIO_LIVE_STREAM
						|| streamType == org.schabi.newpipe.extractor.stream.StreamType.POST_LIVE_STREAM;
	}

	@Nullable
	private static <T> List<T> copyList(@Nullable List<T> source) {
		return source == null ? null : new ArrayList<>(source);
	}

	@NonNull
	private static <T> List<T> orEmpty(@Nullable List<T> source) {
		return source == null ? Collections.emptyList() : source;
	}

	@NonNull
	private static <T> CompletableFuture<T> fail(@NonNull Throwable error) {
		CompletableFuture<T> future = new CompletableFuture<>();
		future.completeExceptionally(error);
		return future;
	}

	@NonNull
	private static ExtractedInfo extract(@NonNull String url,
	                                     boolean streams)
					throws org.schabi.newpipe.extractor.exceptions.ExtractionException, IOException {
		var extractor = ServiceList.YouTube.getStreamExtractor(url);
		YoutubeStreamExtractor youtube = extractor instanceof YoutubeStreamExtractor y ? y : null;
		StreamInfo info = streams
						? StreamInfo.getStream(extractor)
						: StreamInfo.getInfo(extractor);
		return new ExtractedInfo(info, youtube);
	}

	@NonNull
	public CompletableFuture<PlaybackDetails> getInfo(@NonNull String videoUrl,
	                                                  @Nullable ExtractionSession session) {
		String videoId = getVideoId(videoUrl);
		if (videoId == null) {
			return fail(new org.schabi.newpipe.extractor.exceptions.ExtractionException(
							"Invalid URL: " + videoUrl));
		}
		if (session != null && session.isCancelled()) {
			return fail(new InterruptedException("Extraction canceled"));
		}
		Task task = tasks.compute(videoId, (key, active) -> {
			if (active != null && !active.base.isDone() && !active.root.isCancelled()) {
				return active;
			}
			return new Task(videoId);
		});
		return task.attach(session);
	}

	@NonNull
	private PlaybackDetails load(@NonNull String videoId,
	                             @NonNull ExtractionSession session)
					throws org.schabi.newpipe.extractor.exceptions.ExtractionException,
					IOException,
					InterruptedException {
		ensureNotCancelled(session);

		PlaybackDetails cached = cache.getPlaybackDetails(videoId);
		if (cached != null) {
			return copy(cached, PlaybackDetails.class);
		}

		VideoDetails longVideo = cache.getVideoDetails(videoId);
		if (longVideo != null) {
			try {
				ExtractedInfo extracted = play.fetch(videoId, session);
				StreamInfo streamInfo = extracted.info();
				ensureNotCancelled(session);
				StreamCatalog catalog = buildCatalog(streamInfo, extracted.youtube());
				DeliveryCatalog deliveries = buildDeliveries(catalog);
				PlaybackPlan plan = PlaybackPlanner.plan(deliveries);
				PlaybackDetails details = new PlaybackDetails(
								mergeVideo(longVideo, streamInfo),
								catalog,
								deliveries,
								plan,
								copyList(orEmpty(streamInfo.getStreamSegments())),
								copyList(orEmpty(streamInfo.getSubtitles())));
				ensurePlayableSources(videoId, details.deliveries(), details.plan());
				cache.putPlaybackDetails(videoId, details);
				cache.putVideoDetails(videoId, details.video());
				return copy(details, PlaybackDetails.class);
			} catch (IOException | org.schabi.newpipe.extractor.exceptions.ExtractionException e) {
				ensureNotCancelled(session);
			}
		}

		ExtractedInfo extracted = info.fetch(videoId, session);
		StreamInfo streamInfo = extracted.info();
		ensureNotCancelled(session);
		Description description = streamInfo.getDescription();
		Date uploadDate = streamInfo.getUploadDate() == null
						? null
						: Date.from(streamInfo.getUploadDate().getInstant());
		String thumbnailUrl = getBestImageUrl(streamInfo.getThumbnails());
		StreamCatalog catalog = buildCatalog(streamInfo, extracted.youtube());
		DeliveryCatalog deliveries = buildDeliveries(catalog);
		PlaybackPlan plan = PlaybackPlanner.plan(deliveries);
		PlaybackDetails details = new PlaybackDetails(
						new VideoDetails(
										streamInfo.getId(),
										streamInfo.getName(),
										streamInfo.getUploaderName(),
										description == null ? null : description.getContent(),
										Math.max(0L, streamInfo.getDuration()),
										thumbnailUrl != null ? thumbnailUrl : buildDefaultThumbnailUrl(streamInfo.getId()),
										streamInfo.getLikeCount(),
										streamInfo.getDislikeCount(),
										uploadDate,
										streamInfo.getUploaderUrl(),
										getBestImageUrl(streamInfo.getUploaderAvatars()),
										streamInfo.getViewCount()),
						catalog,
						deliveries,
						plan,
						copyList(orEmpty(streamInfo.getStreamSegments())),
						copyList(orEmpty(streamInfo.getSubtitles())));
		ensurePlayableSources(videoId, details.deliveries(), details.plan());
		cache.putPlaybackDetails(videoId, details);
		cache.putVideoDetails(videoId, details.video());
		return copy(details, PlaybackDetails.class);
	}

	@NonNull
	private VideoDetails mergeVideo(@NonNull VideoDetails cached,
	                                @NonNull StreamInfo streamInfo) {
		VideoDetails details = copy(cached, VideoDetails.class);
		if (isBlank(details.getId())) {
			details.setId(streamInfo.getId());
		}
		if (isBlank(details.getTitle())) {
			details.setTitle(streamInfo.getName());
		}
		if (isBlank(details.getThumbnailUrl())) {
			details.setThumbnailUrl(buildDefaultThumbnailUrl(streamInfo.getId()));
		}
		if (details.getDuration() == null || details.getDuration() < 0L) {
			details.setDuration(Math.max(0L, streamInfo.getDuration()));
		}
		return details;
	}

	@NonNull
	private StreamCatalog buildCatalog(@NonNull StreamInfo streamInfo,
	                                   @Nullable YoutubeStreamExtractor youtube) {
		StreamCatalog catalog = new StreamCatalog();
		catalog.setStreamType(streamInfo.getStreamType());
		boolean live = isLive(streamInfo.getStreamType());

		if (youtube != null) {
			addManifestChoices(catalog, youtube.getDashManifestChoices(), true, live);
			addManifestChoices(catalog, youtube.getHlsManifestChoices(), false, live);
			addVideoChoices(catalog.getVideoCandidates(), youtube.getVideoOnlyStreamChoices(), false, live);
			addAudioChoices(catalog.getAudioCandidates(), youtube.getAudioStreamChoices(), live);
			addVideoChoices(catalog.getMuxedCandidates(), youtube.getMuxedStreamChoices(), true, live);
		}

		if (catalog.getManifestCandidates().isEmpty()) {
			addFallbackManifests(catalog, streamInfo, live);
		}
		if (catalog.getVideoCandidates().isEmpty()) {
			for (VideoStream stream : normalizeVideoStreams(filterPlayableStreams(streamInfo.getVideoOnlyStreams()))) {
				catalog.getVideoCandidates().add(StreamCandidate.videoOnly(stream, null, false, false, live));
			}
		}
		if (catalog.getAudioCandidates().isEmpty()) {
			for (AudioStream stream : normalizeAudioStreams(filterPlayableAudioStreams(streamInfo.getAudioStreams()))) {
				catalog.getAudioCandidates().add(StreamCandidate.audioOnly(stream, null, false, false, live));
			}
		}
		if (catalog.getMuxedCandidates().isEmpty()) {
			for (VideoStream stream : normalizeVideoStreams(filterPlayableStreams(streamInfo.getVideoStreams()))) {
				catalog.getMuxedCandidates().add(StreamCandidate.muxed(stream, null, false, false, live));
			}
		}
		for (SubtitlesStream stream : orEmpty(streamInfo.getSubtitles())) {
			if (isPlayableUrl(stream.getContent())) {
				catalog.getSubtitleCandidates().add(StreamCandidate.subtitle(stream));
			}
		}
		return catalog;
	}

	@NonNull
	private DeliveryCatalog buildDeliveries(@NonNull StreamCatalog catalog) {
		DeliveryCatalog deliveries = new DeliveryCatalog();
		deliveries.setStreamType(catalog.getStreamType());
		boolean live = isLive(catalog.getStreamType());
		if (live) {
			StreamCandidate dash = catalog.firstDashManifest();
			if (dash != null) {
				Delivery delivery = new Delivery();
				delivery.setMode(PlaybackMode.LIVE_DASH);
				delivery.setStreamType(catalog.getStreamType());
				delivery.setManifest(dash);
				delivery.setVideo(copyList(catalog.getVideoCandidates()));
				delivery.setAudio(copyList(catalog.getAudioCandidates()));
				delivery.setAbr(true);
				delivery.setTrackLock(false);
				delivery.setCache(false);
				deliveries.getItems().add(delivery);
			}
			StreamCandidate hls = catalog.firstHlsManifest();
			if (hls != null) {
				Delivery delivery = new Delivery();
				delivery.setMode(PlaybackMode.LIVE_HLS);
				delivery.setStreamType(catalog.getStreamType());
				delivery.setManifest(hls);
				delivery.setAbr(false);
				delivery.setTrackLock(false);
				delivery.setCache(false);
				deliveries.getItems().add(delivery);
			}
			return deliveries;
		}
		if (!catalog.getVideoCandidates().isEmpty() && !catalog.getAudioCandidates().isEmpty()) {
			Delivery delivery = new Delivery();
			delivery.setMode(PlaybackMode.ADAPTIVE);
			delivery.setStreamType(catalog.getStreamType());
			delivery.setVideo(copyList(catalog.getVideoCandidates()));
			delivery.setAudio(copyList(catalog.getAudioCandidates()));
			delivery.setAbr(false);
			delivery.setTrackLock(false);
			delivery.setCache(true);
			deliveries.getItems().add(delivery);
		}
		if (!catalog.getMuxedCandidates().isEmpty()) {
			Delivery delivery = new Delivery();
			delivery.setMode(PlaybackMode.MUXED);
			delivery.setStreamType(catalog.getStreamType());
			delivery.setMuxed(copyList(catalog.getMuxedCandidates()));
			delivery.setAbr(false);
			delivery.setTrackLock(false);
			delivery.setCache(true);
			deliveries.getItems().add(delivery);
		}
		if (deliveries.getItems().isEmpty() && !catalog.getAudioCandidates().isEmpty()) {
			Delivery delivery = new Delivery();
			delivery.setMode(PlaybackMode.AUDIO_ONLY);
			delivery.setStreamType(catalog.getStreamType());
			delivery.setAudio(copyList(catalog.getAudioCandidates()));
			delivery.setAbr(false);
			delivery.setTrackLock(false);
			delivery.setCache(true);
			deliveries.getItems().add(delivery);
		}
		return deliveries;
	}

	private void addFallbackManifests(@NonNull StreamCatalog catalog,
	                                  @NonNull StreamInfo streamInfo,
	                                  boolean live) {
		String dash = sanitizePlaybackUrl(streamInfo.getDashMpdUrl());
		String hls = sanitizePlaybackUrl(streamInfo.getHlsUrl());
		if (dash != null) {
			catalog.getManifestCandidates().add(StreamCandidate.dashManifest(
							dash,
							null,
							false,
							false,
							live));
		}
		if (hls != null) {
			catalog.getManifestCandidates().add(StreamCandidate.hlsManifest(
							hls,
							null,
							false,
							false,
							live));
		}
	}

	private void addManifestChoices(@NonNull StreamCatalog catalog,
	                                @NonNull List<YoutubeStreamExtractor.ManifestChoice> choices,
	                                boolean dash,
	                                boolean live) {
		for (final YoutubeStreamExtractor.ManifestChoice choice : choices) {
			String url = sanitizePlaybackUrl(choice.getUrl());
			if (url == null) {
				continue;
			}
			StreamCandidate candidate = dash
							? StreamCandidate.dashManifest(
							url,
							choice.getClient(),
							choice.hasPlayerPoToken(),
							choice.hasStreamPoToken(),
							live)
							: StreamCandidate.hlsManifest(
							url,
							choice.getClient(),
							choice.hasPlayerPoToken(),
							choice.hasStreamPoToken(),
							live);
			addUnique(catalog.getManifestCandidates(), candidate);
		}
	}

	private void addVideoChoices(@NonNull List<StreamCandidate> out,
	                             @NonNull List<YoutubeStreamExtractor.ItagChoice<VideoStream>> choices,
	                             boolean muxed,
	                             boolean live) {
		for (final YoutubeStreamExtractor.ItagChoice<VideoStream> choice : choices) {
			for (VideoStream stream : normalizeVideoStreams(choice.getStreams())) {
				StreamCandidate candidate = muxed
								? StreamCandidate.muxed(
								stream,
								choice.getClient(),
								choice.hasPlayerPoToken(),
								choice.hasStreamPoToken(),
								live)
								: StreamCandidate.videoOnly(
								stream,
								choice.getClient(),
								choice.hasPlayerPoToken(),
								choice.hasStreamPoToken(),
								live);
				addUnique(out, candidate);
			}
		}
	}

	private void addAudioChoices(@NonNull List<StreamCandidate> out,
	                             @NonNull List<YoutubeStreamExtractor.ItagChoice<AudioStream>> choices,
	                             boolean live) {
		for (final YoutubeStreamExtractor.ItagChoice<AudioStream> choice : choices) {
			for (AudioStream stream : normalizeAudioStreams(choice.getStreams())) {
				StreamCandidate candidate = StreamCandidate.audioOnly(
								stream,
								choice.getClient(),
								choice.hasPlayerPoToken(),
								choice.hasStreamPoToken(),
								live);
				addUnique(out, candidate);
			}
		}
	}

	private void addUnique(@NonNull List<StreamCandidate> out,
	                       @NonNull StreamCandidate candidate) {
		String url = candidate.getUrl();
		for (StreamCandidate item : out) {
			if (same(item.getKind(), candidate.getKind())
							&& same(item.getSourceClient(), candidate.getSourceClient())
							&& same(item.getUrl(), url)) {
				return;
			}
		}
		out.add(candidate);
	}

	@NonNull
	private List<VideoStream> normalizeVideoStreams(@Nullable List<VideoStream> streams) {
		if (streams == null) return new ArrayList<>();
		Map<String, VideoStream> best = new LinkedHashMap<>();
		for (VideoStream stream : streams) {
			if (stream == null || !isPlayableUrl(stream.getContent())) continue;
			String key = videoKey(stream);
			VideoStream prev = best.get(key);
			if (prev == null || isBetterVideo(stream, prev)) {
				best.put(key, stream);
			}
		}
		List<VideoStream> result = new ArrayList<>(best.values());
		result.sort((first, second) -> {
			int height = Integer.compare(videoHeight(second), videoHeight(first));
			if (height != 0) return height;
			int fps = Integer.compare(videoFps(second), videoFps(first));
			if (fps != 0) return fps;
			return Integer.compare(videoBitrate(second), videoBitrate(first));
		});
		return result;
	}

	@NonNull
	private List<AudioStream> normalizeAudioStreams(@Nullable List<AudioStream> streams) {
		if (streams == null) return new ArrayList<>();
		Map<String, AudioStream> best = new LinkedHashMap<>();
		for (AudioStream stream : streams) {
			if (stream == null || stream.getFormat() != MediaFormat.M4A) continue;
			String key = audioKey(stream);
			AudioStream prev = best.get(key);
			if (prev == null || isBetterAudio(stream, prev)) {
				best.put(key, stream);
			}
		}
		return new ArrayList<>(best.values());
	}

	@NonNull
	private <T extends Stream> List<T> filterPlayableStreams(@Nullable List<T> streams) {
		if (streams == null) return new ArrayList<>();
		List<T> result = new ArrayList<>();
		for (T stream : streams) {
			if (stream != null && isPlayableUrl(stream.getContent())) {
				result.add(stream);
			}
		}
		return result;
	}

	@NonNull
	private List<AudioStream> filterPlayableAudioStreams(@Nullable List<AudioStream> streams) {
		if (streams == null) return new ArrayList<>();
		List<AudioStream> result = new ArrayList<>();
		for (AudioStream stream : streams) {
			if (stream != null
							&& stream.getFormat() == MediaFormat.M4A
							&& isPlayableUrl(stream.getContent())) {
				result.add(stream);
			}
		}
		return result;
	}

	@Nullable
	private String getBestImageUrl(@NonNull List<Image> images) {
		if (images.isEmpty()) return null;
		Map<Image.ResolutionLevel, Integer> priority = Map.of(
						Image.ResolutionLevel.HIGH, 3,
						Image.ResolutionLevel.MEDIUM, 2,
						Image.ResolutionLevel.LOW, 1,
						Image.ResolutionLevel.UNKNOWN, 0);
		return images.stream()
						.max(Comparator.comparingInt(img ->
										priority.getOrDefault(img.getEstimatedResolutionLevel(), 0)))
						.map(Image::getUrl)
						.orElse(null);
	}

	@Nullable
	private String buildDefaultThumbnailUrl(@Nullable String videoId) {
		if (videoId == null || videoId.isBlank()) {
			return null;
		}
		return "https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg";
	}

	private void ensurePlayableSources(@NonNull String videoId,
	                                   @NonNull DeliveryCatalog deliveries,
	                                   @NonNull PlaybackPlan plan)
					throws org.schabi.newpipe.extractor.exceptions.ExtractionException {
		if (isPlayableUrl(plan.getManifestUrl())
						|| plan.getDelivery() != null
						|| plan.getVideoCandidate() != null
						|| plan.getAudioCandidate() != null
						|| plan.getMuxedCandidate() != null
						|| !deliveries.getItems().isEmpty()) {
			return;
		}
		throw new org.schabi.newpipe.extractor.exceptions.ExtractionException(
						"No supported playable streams found for videoId=" + videoId);
	}

	private void ensureNotCancelled(@Nullable ExtractionSession session)
					throws InterruptedException {
		if (session != null && session.isCancelled()) {
			throw new InterruptedException("Extraction canceled");
		}
		if (Thread.currentThread().isInterrupted()) {
			throw new InterruptedException("Extraction interrupted");
		}
	}

	@NonNull
	private String videoKey(@NonNull VideoStream stream) {
		return stream.getResolution() + "#" + stream.getFps();
	}

	@NonNull
	private String audioKey(@NonNull AudioStream stream) {
		String name = stream.getAudioTrackName();
		return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
	}

	private boolean isBetterVideo(@NonNull VideoStream first, @NonNull VideoStream second) {
		int codec = Integer.compare(codecPriority(first.getCodec()), codecPriority(second.getCodec()));
		if (codec != 0) return codec > 0;
		int fps = Integer.compare(videoFps(first), videoFps(second));
		if (fps != 0) return fps > 0;
		return videoBitrate(first) > videoBitrate(second);
	}

	private boolean isBetterAudio(@NonNull AudioStream first, @NonNull AudioStream second) {
		return audioBitrate(first) > audioBitrate(second);
	}

	private int codecPriority(@Nullable String codec) {
		if (codec == null) return 0;
		String lower = codec.toLowerCase(Locale.ROOT);
		if (lower.startsWith("avc") || lower.startsWith("h264")) return 4;
		if (lower.contains("vp9") || lower.contains("vp8")) return 3;
		if (lower.contains("h265")) return 2;
		if (lower.contains("av01")) return 1;
		return 0;
	}

	private int videoHeight(@NonNull VideoStream stream) {
		return stream.getHeight();
	}

	private int videoFps(@NonNull VideoStream stream) {
		return stream.getFps();
	}

	private int videoBitrate(@NonNull VideoStream stream) {
		return stream.getBitrate();
	}

	private int audioBitrate(@NonNull AudioStream stream) {
		return stream.getAverageBitrate() > 0 ? stream.getAverageBitrate() : stream.getBitrate();
	}

	@Nullable
	private String sanitizePlaybackUrl(@Nullable String url) {
		if (url == null) {
			return null;
		}
		String trimmedUrl = url.trim();
		return isPlayableUrl(trimmedUrl) ? trimmedUrl : null;
	}

	private boolean isPlayableUrl(@Nullable String url) {
		if (url == null || url.isBlank()) {
			return false;
		}
		try {
			URI uri = URI.create(url.trim());
			String scheme = uri.getScheme();
			String host = uri.getHost();
			return host != null
							&& !host.isEmpty()
							&& ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
		} catch (IllegalArgumentException ignored) {
			return false;
		}
	}

	private boolean isBlank(@Nullable String value) {
		return value == null || value.isBlank();
	}

	@NonNull
	private <T> T copy(@NonNull T value,
	                   @NonNull Class<T> type) {
		T copy = gson.fromJson(gson.toJson(value), type);
		return copy != null ? copy : value;
	}

/**
 * Download task description used by the download engine.
 */
	private final class Task {
		@NonNull
		private final ExtractionSession root;
		@NonNull
		private final CompletableFuture<PlaybackDetails> base;
		@NonNull
		private final AtomicInteger refs = new AtomicInteger();

		private Task(@NonNull String videoId) {
			this.root = new ExtractionSession(auth.create("https://www.youtube.com/watch?v=" + videoId));
			this.base = CompletableFuture.supplyAsync(() -> {
				try {
					return load(videoId, root);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new CompletionException(e);
				} catch (final IOException
				               | org.schabi.newpipe.extractor.exceptions.ExtractionException e) {
					throw new CompletionException(e);
				}
			}, executor);
			base.whenComplete((ignored, error) -> tasks.remove(videoId, this));
		}

		@NonNull
		private CompletableFuture<PlaybackDetails> attach(@Nullable ExtractionSession session) {
			refs.incrementAndGet();
			AtomicBoolean done = new AtomicBoolean();
			CompletableFuture<PlaybackDetails> future = new CompletableFuture<>();
			future.whenComplete((ignored, error) -> release(done));
			if (session != null) {
				session.register(() ->
								future.completeExceptionally(new InterruptedException("Extraction canceled")));
				if (session.isCancelled()) {
					future.completeExceptionally(new InterruptedException("Extraction canceled"));
					return future;
				}
			}
			base.whenComplete((value, error) -> {
				if (error == null) {
					future.complete(copy(value, PlaybackDetails.class));
					return;
				}
				Throwable cause = error;
				while (cause instanceof CompletionException && cause.getCause() != null) {
					cause = cause.getCause();
				}
				future.completeExceptionally(cause);
			});
			return future;
		}

		private void release(@NonNull AtomicBoolean done) {
			if (!done.compareAndSet(false, true)) {
				return;
			}
			if (refs.decrementAndGet() == 0 && !base.isDone()) {
				root.cancel();
			}
		}
	}
}

/**
 * Value object that pairs StreamInfo with the optional YouTube extractor.
 */
record ExtractedInfo(@NonNull StreamInfo info,
                     @Nullable YoutubeStreamExtractor youtube) {
}
