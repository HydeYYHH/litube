package com.hhst.youtubelite.browser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class InitScriptAnimationTuningTest {

    @Test
    public void initScript_usesUnifiedRepairPipeline_withoutNativeBridgeOrAnimationHooks() throws Exception {
        final String script = readInitScript();

        assertTrue(script.contains("const DomRepairPipeline = (() => {"));
        assertTrue(script.contains("const resolveObserverRoot = (pageClass = state.currentPageClass) => {"));
        assertTrue(script.contains("const scheduleFlush = (reason = 'mutation') => {"));
        assertTrue(script.contains("runTimedTasks(state.currentPageClass);"));
        assertTrue(script.contains("function runTimedTasks(pageClass) {"));
        assertTrue(script.contains("const repairPlayerSurface = (pageClass) => {"));

        assertFalse(script.contains("enqueueNativeHttpRequest"));
        assertFalse(script.contains("cancelNativeHttpRequest"));
        assertFalse(script.contains("__liteNativeHttp"));
        assertFalse(script.contains("lite-dom-ghost"));
        assertFalse(script.contains("pendingAdds"));
        assertFalse(script.contains("ghostCount"));
        assertFalse(script.contains("animationMode"));
        assertFalse(script.contains("window.__liteDomEnableDebug"));
    }

    @Test
    public void initScript_retriesWatchLayoutSyncFromHookOnly() throws Exception {
        final String script = readInitScript();

        assertTrue(script.contains("const WATCH_LAYOUT_SYNC_DELAYS_MS = [16, 32, 64"));
        assertTrue(script.contains("document.addEventListener('animationstart', (event) => {"));
        assertTrue(script.contains("WATCH_LAYOUT_SYNC_DELAYS_MS.forEach((delayMs) => {"));
        assertTrue(script.contains("window.setTimeout(() => {"));
        assertTrue(script.contains("window.changePlayerHeight();"));

        assertFalse(script.contains("new ResizeObserver(window.changePlayerHeight)"));
        assertFalse(script.contains("window.addEventListener('resize', window.changePlayerHeight"));
        assertFalse(script.contains("window.visualViewport?.addEventListener('resize', window.changePlayerHeight"));
        assertFalse(script.contains("window.changePlayerHeight?.();"));
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

        throw new AssertionError("Unable to locate init.js for pipeline assertions");
    }
}
