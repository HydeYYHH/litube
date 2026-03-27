package com.hhst.youtubelite.browser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class JavascriptBridgeCompatibilityTest {
	@Test
	public void assetScripts_useLiteBridgeForPreferences() throws Exception {
		assertBridgeUpdated("display_dislikes.js");
		assertBridgeUpdated("hide_shorts.js");
	}

	private void assertBridgeUpdated(final String fileName) throws IOException {
		final String script = new String(Files.readAllBytes(resolveScriptPath(fileName)), StandardCharsets.UTF_8);
		assertFalse(script.contains("android.getPreferences("));
		assertTrue(script.contains("lite.getPreferences("));
	}

	private Path resolveScriptPath(final String fileName) {
		final Path moduleRelativePath = Paths.get("src", "main", "assets", "script", fileName);
		if (Files.exists(moduleRelativePath)) {
			return moduleRelativePath;
		}

		final Path rootRelativePath = Paths.get("app", "src", "main", "assets", "script", fileName);
		if (Files.exists(rootRelativePath)) {
			return rootRelativePath;
		}

		throw new AssertionError("Unable to locate script asset: " + fileName);
	}
}
