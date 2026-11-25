/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.webkit;

import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Message;
import android.view.InputEvent;
import android.view.KeyEvent;

import java.io.ByteArrayInputStream;

/**
 * Stands between a WebView and the app's client: refuses requests to filtered hosts and
 * navigations nobody tapped that lead to the app store, and hands everything else on.
 */
final class SaringWebViewClient extends WebViewClient {
    final WebViewClient mInner;
    private final SaringNames mNames;
    /** Host of the page being shown; read on the request thread. */
    private volatile String mPageHost;

    private SaringWebViewClient(WebViewClient inner, SaringNames names) {
        mInner = inner;
        mNames = names;
    }

    static WebViewClient wrap(WebViewClient client) {
        if (client == null || client instanceof SaringWebViewClient) return client;
        final SaringNames names = SaringFilter.names();
        return names == null ? client : new SaringWebViewClient(client, names);
    }

    static WebViewClient unwrap(WebViewClient client) {
        return client instanceof SaringWebViewClient ? ((SaringWebViewClient) client).mInner : client;
    }

    private static WebResourceResponse refused() {
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", null,
                new ByteArrayInputStream(new byte[0]));
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        mPageHost = SaringNames.host(url);
        mInner.onPageStarted(view, url, favicon);
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        final boolean top = request.isForMainFrame();
        if (mNames.requestBlocked(request.getUrl().toString(), top ? null : mPageHost, top)) {
            SaringFilter.count();
            return refused();
        }
        return mInner.shouldInterceptRequest(view, request);
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        if (request.isForMainFrame()) {
            final Uri uri = request.getUrl();
            final String url = uri.toString();
            if (!request.hasGesture()) {
                final String scheme = uri.getScheme();
                final boolean store = "market".equalsIgnoreCase(scheme)
                        || ("play.google.com".equalsIgnoreCase(uri.getHost())
                                && String.valueOf(uri.getPath()).startsWith("/store"));
                if (store || mNames.requestBlocked(url, null, true)) {
                    SaringFilter.count();
                    return true;
                }
            } else {
                final String cleaned = SaringNames.cleanUrl(url);
                if (cleaned != null) {
                    if (mNames.requestBlocked(cleaned, null, true)) {
                        SaringFilter.count();
                        return true;
                    }
                    view.loadUrl(cleaned);
                    return true;
                }
            }
        }
        return mInner.shouldOverrideUrlLoading(view, request);
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return mInner.shouldOverrideUrlLoading(view, url);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        mInner.onPageFinished(view, url);
    }

    @Override
    public void onLoadResource(WebView view, String url) {
        mInner.onLoadResource(view, url);
    }

    @Override
    public void onPageCommitVisible(WebView view, String url) {
        mInner.onPageCommitVisible(view, url);
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
        return mInner.shouldInterceptRequest(view, url);
    }

    @Override
    public void onTooManyRedirects(WebView view, Message cancelMsg, Message continueMsg) {
        mInner.onTooManyRedirects(view, cancelMsg, continueMsg);
    }

    @Override
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        mInner.onReceivedError(view, errorCode, description, failingUrl);
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        mInner.onReceivedError(view, request, error);
    }

    @Override
    public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
        mInner.onReceivedHttpError(view, request, errorResponse);
    }

    @Override
    public void onFormResubmission(WebView view, Message dontResend, Message resend) {
        mInner.onFormResubmission(view, dontResend, resend);
    }

    @Override
    public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
        mInner.doUpdateVisitedHistory(view, url, isReload);
    }

    @Override
    public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
        mInner.onReceivedSslError(view, handler, error);
    }

    @Override
    public void onReceivedClientCertRequest(WebView view, ClientCertRequest request) {
        mInner.onReceivedClientCertRequest(view, request);
    }

    @Override
    public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
        mInner.onReceivedHttpAuthRequest(view, handler, host, realm);
    }

    @Override
    public boolean shouldOverrideKeyEvent(WebView view, KeyEvent event) {
        return mInner.shouldOverrideKeyEvent(view, event);
    }

    @Override
    public void onUnhandledKeyEvent(WebView view, KeyEvent event) {
        mInner.onUnhandledKeyEvent(view, event);
    }

    @Override
    public void onUnhandledInputEvent(WebView view, InputEvent event) {
        mInner.onUnhandledInputEvent(view, event);
    }

    @Override
    public void onScaleChanged(WebView view, float oldScale, float newScale) {
        mInner.onScaleChanged(view, oldScale, newScale);
    }

    @Override
    public void onReceivedLoginRequest(WebView view, String realm, String account, String args) {
        mInner.onReceivedLoginRequest(view, realm, account, args);
    }

    @Override
    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        return mInner.onRenderProcessGone(view, detail);
    }

    @Override
    public void onSafeBrowsingHit(WebView view, WebResourceRequest request, int threatType, SafeBrowsingResponse callback) {
        mInner.onSafeBrowsingHit(view, request, threatType, callback);
    }
}
