package com.hhst.youtubelite.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class DomLiteEngineInstrumentationTest {

    private static final String TEST_ACTIVITY_CLASS = "com.hhst.youtubelite.browser.DomLiteEngineTestActivity";
    private static final long TIMEOUT_SECONDS = 10;

    @Test
    public void debugSurface_isDisabledByDefault() throws Exception {
        try (ActivityScenario<Activity> scenario = launchScenario()) {
            final WebView webView = getWebView(scenario);

            loadFixture(webView, "https://m.youtube.com/");
            installAndroidStub(webView);
            injectInitScript(webView);

            final JSONObject debug = evaluateObject(webView, "readDebug()");

            assertFalse("__liteDomDebug should be hidden unless tests opt in", debug.getBoolean("available"));
            assertTrue("snapshot should be absent when debug surface is disabled", debug.isNull("snapshot"));
        }
    }

    @Test
    public void observer_targetsFixtureRootInsteadOfWholeDocument() throws Exception {
        try (ActivityScenario<Activity> scenario = launchScenario()) {
            final WebView webView = getWebView(scenario);

            loadFixture(webView, "https://m.youtube.com/");
            installAndroidStub(webView);
            enableInitDebugHooks(webView);
            injectInitScript(webView);

            final JSONObject snapshot = evaluateObject(webView, "readDebug()").getJSONObject("snapshot");

            assertEquals("#card-list", snapshot.getString("observerRootName"));
            assertTrue("observer should remain connected for the fixture root", snapshot.getBoolean("observerConnected"));
        }
    }

    @Test
    public void watchPage_doesNotPreferStalePageManagerTrapAsObserverRoot() throws Exception {
        try (ActivityScenario<Activity> scenario = launchScenario()) {
            final WebView webView = getWebView(scenario);

            loadFixture(webView, "https://m.youtube.com/");
            installAndroidStub(webView);
            enableInitDebugHooks(webView);
            injectInitScript(webView);

            final JSONObject snapshot = evaluateObject(
                    webView,
                    "injectObserverRootTrap('page-manager'); setVirtualPath('/watch?v=abc123def45'); readDebug();"
            ).getJSONObject("snapshot");

            assertTrue("observer should stay connected after watch-page navigation", snapshot.getBoolean("observerConnected"));
            assertFalse("watch page should not lock onto a stale #page-manager trap", "#page-manager".equals(snapshot.getString("observerRootName")));
        }
    }

    @Test
    public void mutationBatch_schedulesSingleFlushAndClearsDirtyRoots() throws Exception {
        try (ActivityScenario<Activity> scenario = launchScenario()) {
            final WebView webView = getWebView(scenario);

            loadFixture(webView, "https://m.youtube.com/");
            installAndroidStub(webView);
            enableInitDebugHooks(webView);
            installSetTimeoutQueue(webView);
            injectInitScript(webView);

            evaluateScript(webView, "appendCards(3); appendCards(2); null;");
            final JSONObject beforeFlush = evaluateObject(webView, "readDebug()").getJSONObject("snapshot");

            assertTrue("flush should be pending after mutation batch", beforeFlush.getBoolean("flushScheduled"));
            assertTrue("dirty roots should be collected before flush", beforeFlush.getInt("dirtyRootCount") > 0);
            assertEquals("only one flush callback should be scheduled while dirty", 1,
                    Integer.parseInt(evaluateScript(webView, "window.__liteTimeoutQueue.scheduledCount")));

            assertEquals("exactly one pending timeout callback should run", 1,
                    Integer.parseInt(evaluateScript(webView, "window.__flushLiteTimeoutQueue()")));

            final JSONObject afterFlush = evaluateObject(webView, "readDebug()").getJSONObject("snapshot");
            assertFalse("flush should not remain scheduled after draining queue", afterFlush.getBoolean("flushScheduled"));
            assertEquals("dirty roots should clear after flush", 0, afterFlush.getInt("dirtyRootCount"));
            assertEquals("flush counter should increase after the scheduled run", 1, afterFlush.getInt("flushCount"));
        }
    }

    @Test
    public void watchPage_doesNotKeepIdleTimerArmedAfterNavigation() throws Exception {
        try (ActivityScenario<Activity> scenario = launchScenario()) {
            final WebView webView = getWebView(scenario);

            loadFixture(webView, "https://m.youtube.com/");
            installAndroidStub(webView);
            enableInitDebugHooks(webView);
            injectInitScript(webView);
            installWatchActionBarHost(webView);

            final JSONObject homeSnapshot = evaluateObject(webView, "readDebug()").getJSONObject("snapshot");
            assertFalse("home page should not keep the fallback timer armed", homeSnapshot.getBoolean("timerActive"));
            assertTrue("home page should not expose a follow-up timer delay", homeSnapshot.isNull("nextTimerDelayMs"));

            final JSONObject watchSnapshot = evaluateObject(
                    webView,
                    "setVirtualPath('/watch?v=abc123def45'); readDebug();"
            ).getJSONObject("snapshot");

            final JSONObject watchDomState = evaluateObject(webView, """
                    (() => ({
                        hasDownloadButton: Boolean(document.getElementById('downloadButton'))
                    }))()
                    """);
            assertTrue("watch page should insert a download button when the action host exists", watchDomState.getBoolean("hasDownloadButton"));
            assertFalse("watch page should not keep an idle timer armed without ads or retries", watchSnapshot.getBoolean("timerActive"));
            assertTrue("watch page should not expose a follow-up timer delay without ads or retries", watchSnapshot.isNull("nextTimerDelayMs"));
        }
    }

    private ActivityScenario<Activity> launchScenario() {
        final Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Intent intent = new Intent().setClassName(targetContext, TEST_ACTIVITY_CLASS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return ActivityScenario.launch(intent);
    }

    private WebView getWebView(ActivityScenario<Activity> scenario) {
        final AtomicReference<WebView> webViewRef = new AtomicReference<>();
        scenario.onActivity(activity -> {
            final View content = activity.findViewById(android.R.id.content);
            assertTrue("content root should be a ViewGroup", content instanceof ViewGroup);
            final ViewGroup contentGroup = (ViewGroup) content;
            assertTrue("test activity should host a WebView child", contentGroup.getChildCount() > 0);
            final View child = contentGroup.getChildAt(0);
            assertTrue("root child should be a WebView", child instanceof WebView);
            webViewRef.set((WebView) child);
        });
        final WebView webView = webViewRef.get();
        assertNotNull("WebView should be available", webView);
        return webView;
    }

    private void loadFixture(WebView webView, String baseUrl) throws Exception {
        final String html = readInstrumentationAsset("dom_lite_engine_fixture.html");
        final CountDownLatch latch = new CountDownLatch(1);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            webView.getSettings().setJavaScriptEnabled(true);
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    latch.countDown();
                }
            });
            webView.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null);
        });

        assertTrue("fixture page should finish loading", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    private void installAndroidStub(WebView webView) throws Exception {
        evaluateScript(webView, """
                window.android = {
                    finishRefresh() {},
                    setRefreshLayoutEnabled() {},
                    play() {},
                    hidePlayer() {},
                    setPlayerHeight() {},
                    download() {},
                    about() {},
                    extension() {},
                    openTab() {},
                    onPosterLongPress() {},
                    getPreferences() { return '{}'; }
                };
                null;
                """);
    }

    private void installWatchActionBarHost(WebView webView) throws Exception {
        evaluateScript(webView, """
                (() => {
                    if (document.querySelector('.ytSpecButtonViewModelHost.slim_video_action_bar_renderer_button')) {
                        return null;
                    }
                    const bar = document.createElement('div');
                    bar.id = 'watch-action-bar';
                    const host = document.createElement('button');
                    host.className = 'ytSpecButtonViewModelHost slim_video_action_bar_renderer_button';
                    const label = document.createElement('span');
                    label.className = 'yt-spec-button-shape-next__button-text-content';
                    label.textContent = 'Save';
                    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
                    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
                    svg.appendChild(path);
                    host.appendChild(label);
                    host.appendChild(svg);
                    bar.appendChild(host);
                    document.body.appendChild(bar);
                    return null;
                })();
                """);
    }

    private void installSetTimeoutQueue(WebView webView) throws Exception {
        evaluateScript(webView, """
                (() => {
                    const queue = [];
                    window.__liteTimeoutQueue = {
                        scheduledCount: 0,
                        queue
                    };
                    window.setTimeout = (callback) => {
                        window.__liteTimeoutQueue.scheduledCount += 1;
                        queue.push(callback);
                        return window.__liteTimeoutQueue.scheduledCount;
                    };
                    window.__flushLiteTimeoutQueue = () => {
                        const callbacks = queue.splice(0, queue.length);
                        callbacks.forEach((callback) => callback(performance.now()));
                        return callbacks.length;
                    };
                    return null;
                })();
                """);
    }

    private void injectInitScript(WebView webView) throws Exception {
        evaluateScript(webView, readTargetAsset("script/init.js"));
    }

    private void enableInitDebugHooks(WebView webView) throws Exception {
        evaluateScript(webView, "window.__liteDomEnableDebug = true; null;");
    }

    private JSONObject evaluateObject(WebView webView, String script) throws Exception {
        return new JSONObject(evaluateScript(webView, script));
    }

    private String evaluateScript(WebView webView, String script) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> valueRef = new AtomicReference<>();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                webView.evaluateJavascript(script, value -> {
                    valueRef.set(value);
                    latch.countDown();
                })
        );

        assertTrue("javascript evaluation should complete", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNotNull("javascript evaluation should return a value", valueRef.get());
        return valueRef.get();
    }

    private String readInstrumentationAsset(String path) throws IOException {
        return readAsset(InstrumentationRegistry.getInstrumentation().getContext(), path);
    }

    private String readTargetAsset(String path) throws IOException {
        return readAsset(InstrumentationRegistry.getInstrumentation().getTargetContext(), path);
    }

    private String readAsset(Context context, String path) throws IOException {
        try (InputStream inputStream = context.getAssets().open(path);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[4096];
            int count;
            while ((count = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, count);
            }
            return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
