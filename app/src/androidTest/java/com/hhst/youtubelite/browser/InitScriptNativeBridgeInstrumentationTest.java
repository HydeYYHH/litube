package com.hhst.youtubelite.browser;

import static org.junit.Assert.assertEquals;
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
public class InitScriptNativeBridgeInstrumentationTest {

    private static final String TEST_ACTIVITY_CLASS = "com.hhst.youtubelite.browser.DomLiteEngineTestActivity";
    private static final long TIMEOUT_SECONDS = 10;

    @Test
    public void initScript_doesNotExposeLiteNativeHttpHook() throws Exception {
        try (ActivityScenario<Activity> scenario = launchScenario()) {
            final WebView webView = getWebView(scenario);

            loadFixture(webView, "https://m.youtube.com/");
            installBridgeCapableAndroidStub(webView);
            injectInitScript(webView);

            final JSONObject result = evaluateObject(webView, """
                    (() => ({
                        hasLiteNativeHttp: Boolean(window.__liteNativeHttp),
                        hasNativeResultHandler: Boolean(window.__liteNativeHttp?.onNativeResult)
                    }))()
                    """);

            assertTrue("native bridge surface should not be installed on window", !result.getBoolean("hasLiteNativeHttp"));
            assertTrue("native result callback should not be installed", !result.getBoolean("hasNativeResultHandler"));
        }
    }

    @Test
    public void fetchAndXhr_stayOnDelegateImplementationsWithoutNativeBridgeRouting() throws Exception {
        try (ActivityScenario<Activity> scenario = launchScenario()) {
            final WebView webView = getWebView(scenario);

            loadFixture(webView, "https://m.youtube.com/");
            installBridgeCapableAndroidStub(webView);
            injectInitScript(webView);

            evaluateScript(webView, """
                    window.__bridgeTestResult = null;
                    (async () => {
                        const fetchResponse = await fetch('https://m.youtube.com/youtubei/v1/next', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: '{"id":"1"}'
                        });
                        const fetchText = await fetchResponse.text();
                        const xhrSettled = await new Promise((resolve) => {
                            const xhr = new XMLHttpRequest();
                            xhr.open('GET', 'https://m.youtube.com/youtubei/v1/guide', true);
                            xhr.onloadend = () => {
                                resolve({
                                    status: xhr.status,
                                    responseText: xhr.responseText
                                });
                            };
                            xhr.send();
                        });
                        window.__bridgeTestResult = {
                            enqueueCalls: window.__nativeBridgeState.enqueueCalls,
                            cancelCalls: window.__nativeBridgeState.cancelCalls,
                            delegateFetchCalls: window.__nativeBridgeState.delegateFetchCalls,
                            delegateXhrSendCalls: window.__nativeBridgeState.delegateXhrSendCalls,
                            fetchText,
                            xhrStatus: xhrSettled.status,
                            xhrText: xhrSettled.responseText
                        };
                    })();
                    null;
                    """);

            waitForScriptCondition(webView, "window.__bridgeTestResult !== null", 1500,
                    "network assertions should settle");
            final JSONObject result = evaluateObject(webView, "window.__bridgeTestResult");

            assertEquals(0, result.getInt("enqueueCalls"));
            assertEquals(0, result.getInt("cancelCalls"));
            assertEquals(1, result.getInt("delegateFetchCalls"));
            assertEquals(1, result.getInt("delegateXhrSendCalls"));
            assertEquals("delegate-fetch", result.getString("fetchText"));
            assertEquals(200, result.getInt("xhrStatus"));
            assertEquals("delegate-xhr", result.getString("xhrText"));
        }
    }

    private ActivityScenario<Activity> launchScenario() {
        final Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Intent intent = new Intent().setClassName(appContext, TEST_ACTIVITY_CLASS);
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

    private void injectInitScript(WebView webView) throws Exception {
        evaluateScript(webView, readTargetAsset("script/init.js"));
    }

    private void installBridgeCapableAndroidStub(WebView webView) throws Exception {
        evaluateScript(webView, """
                (() => {
                    window.__nativeBridgeState = {
                        enqueueCalls: 0,
                        cancelCalls: 0,
                        delegateFetchCalls: 0,
                        delegateXhrSendCalls: 0
                    };
                    window.fetch = async function () {
                        window.__nativeBridgeState.delegateFetchCalls += 1;
                        return new Response('delegate-fetch', {
                            status: 200,
                            headers: { 'Content-Type': 'text/plain' }
                        });
                    };
                    const DelegateXHR = function () {
                        this.readyState = 0;
                        this.response = null;
                        this.responseText = '';
                        this.responseURL = '';
                        this.responseXML = null;
                        this.status = 0;
                        this.statusText = '';
                        this.onreadystatechange = null;
                        this.onloadend = null;
                    };
                    DelegateXHR.prototype.open = function (_method, url) {
                        this._url = url;
                        this.readyState = 1;
                        if (typeof this.onreadystatechange === 'function') {
                            this.onreadystatechange(new Event('readystatechange'));
                        }
                    };
                    DelegateXHR.prototype.setRequestHeader = function () {};
                    DelegateXHR.prototype.send = function () {
                        window.__nativeBridgeState.delegateXhrSendCalls += 1;
                        this.readyState = 4;
                        this.status = 200;
                        this.statusText = 'OK';
                        this.responseURL = this._url || '';
                        this.response = 'delegate-xhr';
                        this.responseText = 'delegate-xhr';
                        if (typeof this.onreadystatechange === 'function') {
                            this.onreadystatechange(new Event('readystatechange'));
                        }
                        if (typeof this.onloadend === 'function') {
                            this.onloadend(new Event('loadend'));
                        }
                    };
                    DelegateXHR.prototype.abort = function () {};
                    DelegateXHR.prototype.overrideMimeType = function () {};
                    DelegateXHR.prototype.getAllResponseHeaders = function () { return ''; };
                    DelegateXHR.prototype.getResponseHeader = function () { return null; };
                    DelegateXHR.prototype.addEventListener = function () {};
                    DelegateXHR.prototype.removeEventListener = function () {};
                    DelegateXHR.prototype.dispatchEvent = function () { return true; };
                    window.XMLHttpRequest = DelegateXHR;
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
                        getPreferences() { return '{}'; },
                        enqueueNativeHttpRequest() {
                            window.__nativeBridgeState.enqueueCalls += 1;
                        },
                        cancelNativeHttpRequest() {
                            window.__nativeBridgeState.cancelCalls += 1;
                        }
                    };
                    return null;
                })();
                """);
    }

    private JSONObject evaluateObject(WebView webView, String script) throws Exception {
        return new JSONObject(evaluateScript(webView, script));
    }

    private void waitForScriptCondition(WebView webView, String conditionScript, long timeoutMs, String timeoutMessage) throws Exception {
        final long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadlineNanos) {
            if (Boolean.parseBoolean(evaluateScript(webView, "Boolean(" + conditionScript + ")"))) {
                return;
            }
            Thread.sleep(25);
        }
        assertTrue(timeoutMessage, Boolean.parseBoolean(evaluateScript(webView, "Boolean(" + conditionScript + ")")));
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
        final Context context = InstrumentationRegistry.getInstrumentation().getContext();
        try (InputStream inputStream = context.getAssets().open(path);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[4096];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String readTargetAsset(String path) throws IOException {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (InputStream inputStream = context.getAssets().open(path);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[4096];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toString(StandardCharsets.UTF_8.name());
        }
    }
}
