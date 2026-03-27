package com.hhst.youtubelite.browser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class InitScriptWatchInteractionTest {

	@Test
	public void initScript_interceptsWatchTimestampLinksAndDelegatesNativeSeek() throws Exception {
		final String script = readInitScript();

		assertTrue(script.contains("const parseTimestampSeconds = (rawValue) => {"));
		assertTrue(script.contains("const handleWatchTimestampClick = (event) => {"));
		assertTrue(script.contains("lite.seekLoadedVideo?.(targetUrl.toString(), timestampSeconds * 1000)"));
		assertTrue(script.contains("bindListener(document, 'click', handleWatchTimestampClick, true);"));
	}

	@Test
	public void initScript_addsWatchQueueButtonAndDelegatesToNativeQueueSave() throws Exception {
		final String script = readInitScript();

		assertTrue(script.contains("const resizeIcon = (root, size = 24) => {"));
		assertTrue(script.contains("svg.setAttribute('width', `${size}`);"));
		assertTrue(script.contains("svg.setAttribute('height', `${size}`);"));
		assertTrue(script.contains("!actionBar.querySelector('#queueButton')"));
		assertTrue(script.contains("queueButton.id = 'queueButton';"));
		assertTrue(script.contains("getLocalizedText('add_to_queue')"));
		assertTrue(script.contains("const videoData = document.querySelector('#movie_player')?.getVideoData?.();"));
		assertTrue(script.contains("videoData?.video_id"));
		assertTrue(script.contains("videoData?.title"));
		assertTrue(script.contains("videoData?.author"));
		assertTrue(script.contains("if (!videoData?.video_id || !videoData?.title || !videoData?.author)"));
		assertTrue(script.contains("lite.showQueueItemUnavailable?.();"));
		assertTrue(script.contains("const thumbnailUrl = `https://img.youtube.com/vi/${videoData.video_id}/default.jpg`;"));
		assertFalse(script.contains("const thumbnails = globalThis.ytInitialPlayerResponse?.videoDetails?.thumbnail?.thumbnails;"));
		assertFalse(script.contains("document.querySelector('meta[property=\"og:image\"]')?.content ?? null"));
		assertTrue(script.contains("neutralizeActionButtonBehavior(queueButton);"));
		assertTrue(script.contains("neutralizeActionButtonBehavior(downloadButton);"));
		assertTrue(script.contains("resizeIcon(downloadButton);"));
		assertTrue(script.contains("resizeIcon(queueButton);"));
		assertTrue(script.contains("lite.addToQueue("));
	}

	@Test
	public void initScript_cleansUpAndReinjectsWatchQueueButtonsAcrossNavigation() throws Exception {
		final String script = readInitScript();

		assertTrue(script.contains("queueButton.remove()"));
		assertTrue(script.contains("downloadButton.remove()"));
	}

	@Test
	public void initScript_noLongerMutatesPlaylistPanelForQueue() throws Exception {
		final String script = readInitScript();

		assertFalse(script.contains("ytm-playlist-panel-entry-point"));
		assertFalse(script.contains("engagement-panel-playlist"));
	}

	@Test
	public void initScript_interceptsWatchPlaylistUrlsWhenQueueIsEnabled() throws Exception {
		final String script = readInitScript();

		assertTrue(script.contains("const stripWatchList = (url) => {"));
		assertTrue(script.contains("if (!url || !lite.isQueueEnabled?.()) return url;"));
		assertTrue(script.contains("u.searchParams.delete('list');"));
		assertTrue(script.contains("originalPushState.call(this, data, title, typeof url === 'string' ? stripWatchList(url) : url);"));
		assertTrue(script.contains("originalReplaceState.call(this, data, title, typeof url === 'string' ? stripWatchList(url) : url);"));
		assertTrue(script.contains("const nextUrl = stripWatchList(url);"));
		assertTrue(script.contains("if (nextUrl !== url && c === pageClass && c === 'watch') {"));
		assertTrue(script.contains("location.href = nextUrl;"));
		assertTrue(script.contains("lite.openTab(nextUrl, c);"));
	}

	@Test
	public void initScript_usesBalancedDownloadIconForInjectedButtons() throws Exception {
		final String script = readInitScript();

		assertTrue(script.contains("M480-336 288-528l51-51 105 105v-246h72v246l105-105 51 51-192 192ZM264-192q-30 0-51-21t-21-51v-72h72v72h432v-72h72v72q0 30-21 51t-51 21H264Z"));
		assertFalse(script.contains("M480-328.46 309.23-499.23l42.16-43.38"));
		assertFalse(script.contains("M480-320 280-520l56-58 104 104v-266h80v266l104-104 56 58-200 200Z"));
	}

	@Test
	public void initScript_tracksMediaItemMenuContextAndInjectsQueueActionIntoBottomSheetMenu() throws Exception {
		final String script = readInitScript();

		assertTrue(script.contains("const backoff = (stopOnTruthy = false) => {"));
		assertTrue(script.contains("let menuQueueItem = null;"));
		assertTrue(script.contains("event.target.closest('.media-item-menu')"));
		assertTrue(script.contains("'.media-item-metadata a[href]'"));
		assertTrue(script.contains("menuQueueItem = getMediaMenuQueueItem(info);"));
		assertTrue(script.contains("backoff(true)(() => {"));
		assertTrue(script.contains("thumbnailUrl: `https://img.youtube.com/vi/${videoId}/default.jpg`"));
		assertTrue(script.contains("const bottomSheetItem = document.querySelector('.bottom-sheet-media-menu-item');"));
		assertTrue(script.contains("const resolveBottomSheetMenuContainer = (origin) => {"));
		assertTrue(script.contains("const items = Array.from(menuContainer.children)"));
		assertTrue(script.contains("items.forEach((node, index) => {"));
		assertTrue(script.contains("let queueMenuItem = items[0];"));
		assertTrue(script.contains("queueMenuItem = menuContainer.firstElementChild?.cloneNode(true);"));
		assertTrue(script.contains("menuText.innerText = getLocalizedText('add_to_queue');"));
		assertTrue(script.contains("menuSvg.setAttribute(\"viewBox\", \"0 -960 960 960\");"));
		assertTrue(script.contains("resizeIcon(queueMenuItem);"));
		assertTrue(script.contains("const menuPath = menuSvg?.querySelector('path');"));
		assertTrue(script.contains("bindListener(menuButton, 'click', () => {"));
		assertFalse(script.contains("bindListener(menuButton, 'click', (event) => {"));
		assertTrue(script.contains("lite.addToQueue(JSON.stringify(menuQueueItem));"));
		assertTrue(script.contains("menuContainer.insertBefore(queueMenuItem, menuContainer.firstElementChild);"));
	}

	private String readInitScript() throws IOException {
		return new String(Files.readAllBytes(resolveInitScriptPath()), StandardCharsets.UTF_8);
	}

	private Path resolveInitScriptPath() {
		final Path moduleRelativePath = Paths.get("src", "main", "assets", "script", "init.js");
		if (Files.exists(moduleRelativePath)) {
			return moduleRelativePath;
		}

		final Path rootRelativePath = Paths.get("app", "src", "main", "assets", "script", "init.js");
		if (Files.exists(rootRelativePath)) {
			return rootRelativePath;
		}

		throw new AssertionError("Unable to locate init.js for watch interaction assertions");
	}
}
