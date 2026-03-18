package com.hhst.youtubelite.browser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class InitScriptNativeRouteFastPathTest {

	@Test
	public void initScript_usesBooleanBridgePredicateForHotPathRouting() throws Exception {
		final String initScript = new String(Files.readAllBytes(resolveInitScriptPath()), StandardCharsets.UTF_8);

		assertFalse(initScript.contains("androidBridge.resolveNativeHttpRoute("));
		assertFalse(initScript.contains("const resolveNativeRoute ="));
		assertFalse(initScript.contains("NATIVE_ROUTE_BRIDGE"));
		assertFalse(initScript.contains("NATIVE_ROUTE_NONE"));
		assertTrue(initScript.contains("const shouldBridgeRequest = (metadata) => {"));
		assertTrue(initScript.contains("const buildNativePayload = async (request, metadata) => {"));
		assertTrue(initScript.contains("const metadata = buildBridgeRequestMetadata(request, ...headerSources);"));
		assertTrue(initScript.contains("if (!shouldBridgeRequest(metadata)) return null;"));
		assertTrue(initScript.contains("const payload = await buildNativePayload(request, metadata);"));
		assertTrue(initScript.contains("return !BODYLESS_METHODS.has(metadata.method) || hasHeader(metadata.headers, 'range');"));
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
