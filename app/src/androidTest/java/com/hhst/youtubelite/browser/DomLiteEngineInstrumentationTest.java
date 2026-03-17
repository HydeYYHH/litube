package com.hhst.youtubelite.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;
import android.view.ViewGroup;

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
    public void addedNodes_areQueuedBeforeFlushAndReportedByDebugSurface() throws Exception {
        try (ActivityScenario<Activity> scenario = launchScenario()) {
            final WebView webView = getWebView(scenario);

            loadFixture(webView, "https://m.youtube.com/");
            installAndroidStub(webView);
            injectInitScript(webView);
            evaluateScript(webView, "appendCards(3); null;");

            final JSONObject debug = evaluateObject(webView, "readDebug()");

            assertTrue("__liteDomDebug should be available", debug.getBoolean("available"));
            final JSONObject snapshot = debug.getJSONObject("snapshot");
            assertTrue("pendingAdds should be positive after a batch append", snapshot.getInt("pendingAdds") > 0);
            assertTrue("flush should be scheduled after a batch append", snapshot.getBoolean("flushScheduled"));
        }
    }

    @Test
    public void timerCoordinator_onlyRunsMatchingTasksForCurrentPageClass() throws Exception {
        try (ActivityScenario<Activity> scenario = launchScenario()) {
            final WebView webView = getWebView(scenario);

            loadFixture(webView, "https://m.youtube.com/");
            installAndroidStub(webView);
            injectInitScript(webView);

            final JSONObject debug = evaluateObject(
                    webView,
                    "setVirtualPath('/watch?v=abc123def45'); appendCards(1); readDebug();"
            );

            assertTrue("__liteDomDebug should be available", debug.getBoolean("available"));
            final JSONObject snapshot = debug.getJSONObject("snapshot");
            assertEquals("watch", snapshot.getString("currentPageClass"));
            assertTrue("watch task count should be positive", snapshot.getJSONObject("taskRuns").getInt("watch") > 0);
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

    private void injectInitScript(WebView webView) throws Exception {
        evaluateScript(webView, readTargetAsset("script/init.js"));
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
