package com.hhst.youtubelite.browser;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class InitScriptAnimationConfigTest {

	@Test
	public void initScript_usesPronouncedDomLiteAnimations() throws Exception {
		final String initScript = new String(Files.readAllBytes(resolveInitScriptPath()), StandardCharsets.UTF_8);

		assertTrue(initScript.contains("const ENTER_ANIMATION_DURATION_MS = 320;"));
		assertTrue(initScript.contains("const ENTER_ANIMATION_TRANSLATE_Y_PX = 24;"));
		assertTrue(initScript.contains("const ENTER_ANIMATION_START_SCALE = 0.94;"));
		assertTrue(initScript.contains("const EXIT_ANIMATION_DURATION_MS = 320;"));
		assertTrue(initScript.contains("const EXIT_ANIMATION_TRANSLATE_Y_PX = 18;"));
		assertTrue(initScript.contains("const EXIT_ANIMATION_END_SCALE = 0.9;"));
		assertTrue(initScript.contains("node.style.transition = `opacity ${ENTER_ANIMATION_DURATION_MS}ms cubic-bezier(0.16, 1, 0.3, 1), transform ${ENTER_ANIMATION_DURATION_MS}ms cubic-bezier(0.16, 1, 0.3, 1)`;"));
		assertTrue(initScript.contains("node.style.transform = `translateY(${ENTER_ANIMATION_TRANSLATE_Y_PX}px) scale(${ENTER_ANIMATION_START_SCALE})`;"));
		assertTrue(initScript.contains("ghost.style.transition = `opacity ${EXIT_ANIMATION_DURATION_MS}ms cubic-bezier(0.4, 0, 0.2, 1), transform ${EXIT_ANIMATION_DURATION_MS}ms cubic-bezier(0.4, 0, 0.2, 1)`;"));
		assertTrue(initScript.contains("ghost.style.transform = `translateY(-${EXIT_ANIMATION_TRANSLATE_Y_PX}px) scale(${EXIT_ANIMATION_END_SCALE})`;"));
	}

	private Path resolveInitScriptPath() throws IOException {
		final Path moduleRelative = Path.of("src", "main", "assets", "script", "init.js");
		if (Files.exists(moduleRelative)) {
			return moduleRelative;
		}

		final Path projectRelative = Path.of("app", "src", "main", "assets", "script", "init.js");
		if (Files.exists(projectRelative)) {
			return projectRelative;
		}

		throw new IOException("Unable to locate init.js");
	}
}
