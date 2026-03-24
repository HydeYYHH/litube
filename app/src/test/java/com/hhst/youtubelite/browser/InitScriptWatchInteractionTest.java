package com.hhst.youtubelite.browser;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class InitScriptWatchInteractionTest {

	@Test
	public void initScript_hidesWatchSearchSuggestionsThroughManagedTask() throws Exception {
		final String script = readInitScript();

		assertTrue(script.contains("const syncWatchSearchSuggestions = (pageClass) => {"));
		assertTrue(script.contains("node.dataset.liteManagedWatchSuggestionsHidden"));
		assertTrue(script.contains("syncWatchSearchSuggestions(pageClass);"));
	}

	@Test
	public void initScript_interceptsWatchTimestampLinksAndDelegatesNativeSeek() throws Exception {
		final String script = readInitScript();

		assertTrue(script.contains("const parseTimestampSeconds = (rawValue) => {"));
		assertTrue(script.contains("const handleWatchTimestampClick = (event) => {"));
		assertTrue(script.contains("android.seekLoadedVideo?.(targetUrl.toString(), timestampSeconds * 1000)"));
		assertTrue(script.contains("document.addEventListener('click', handleWatchTimestampClick, true);"));
	}

	@Test
	public void initScript_addsWatchQueueButtonAndDelegatesToNativeQueueSave() throws Exception {
		final String script = readInitScript();

		assertTrue(script.contains("!actionBar.querySelector('#queueButton')"));
		assertTrue(script.contains("queueButton.id = 'queueButton';"));
		assertTrue(script.contains("getLocalizedText('add_to_queue')"));
		assertTrue(script.contains("const videoId = getVideoId(location.href);"));
		assertTrue(script.contains("android.addToQueue("));
	}

	@Test
	public void initScript_cleansUpAndReinjectsWatchQueueButtonsAcrossNavigation() throws Exception {
		final String script = readInitScript();

		assertTrue(script.contains("queueButton.remove()"));
		assertTrue(script.contains("downloadButton.remove()"));
		assertTrue(script.contains("const existingQueueButton"));
		assertTrue(script.contains("const existingDownloadButton"));
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
