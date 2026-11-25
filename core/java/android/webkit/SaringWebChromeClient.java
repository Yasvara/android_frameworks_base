/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.webkit;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Message;
import android.os.SystemClock;
import android.view.View;

/**
 * Stands between a WebView and the app's chrome client: no window opens without a tap, a page
 * cannot hold the owner with a chain of dialogs or a leave-page prompt.
 */
class SaringWebChromeClient extends WebChromeClient {
    private static final long DIALOG_GAP_MS = 2000;

    final WebChromeClient mInner;
    private long mLastDialog;

    private SaringWebChromeClient(WebChromeClient inner) {
        mInner = inner;
    }

    static WebChromeClient wrap(WebChromeClient client) {
        if (client == null || client instanceof SaringWebChromeClient) return client;
        if (SaringFilter.names() == null) return client;
        return fullscreen(client) ? new Fullscreen(client) : new SaringWebChromeClient(client);
    }

    static WebChromeClient unwrap(WebChromeClient client) {
        return client instanceof SaringWebChromeClient
                ? ((SaringWebChromeClient) client).mInner : client;
    }

    /** WebView offers fullscreen video only to a client that declares both callbacks. */
    private static boolean fullscreen(WebChromeClient client) {
        boolean show = false;
        boolean hide = false;
        for (Class<?> c = client.getClass(); c != null && c != WebChromeClient.class;
                c = c.getSuperclass()) {
            try {
                c.getDeclaredMethod("onShowCustomView", View.class, CustomViewCallback.class);
                show = true;
            } catch (NoSuchMethodException ignored) {
            }
            try {
                c.getDeclaredMethod("onHideCustomView");
                hide = true;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return show && hide;
    }

    private static final class Fullscreen extends SaringWebChromeClient {
        Fullscreen(WebChromeClient inner) {
            super(inner);
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            mInner.onShowCustomView(view, callback);
        }

        @Override
        public void onHideCustomView() {
            mInner.onHideCustomView();
        }
    }

    /** True for a dialog that follows the previous one too closely to be the owner's doing. */
    private boolean flood() {
        final long now = SystemClock.uptimeMillis();
        final boolean flood = mLastDialog != 0 && now - mLastDialog < DIALOG_GAP_MS;
        mLastDialog = now;
        if (flood) SaringFilter.count();
        return flood;
    }

    @Override
    public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
            Message resultMsg) {
        if (!isUserGesture) {
            SaringFilter.count();
            return false;
        }
        return mInner.onCreateWindow(view, isDialog, isUserGesture, resultMsg);
    }

    @Override
    public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
        if (flood()) {
            result.cancel();
            return true;
        }
        return mInner.onJsAlert(view, url, message, result);
    }

    @Override
    public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
        if (flood()) {
            result.cancel();
            return true;
        }
        return mInner.onJsConfirm(view, url, message, result);
    }

    @Override
    public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
            JsPromptResult result) {
        if (flood()) {
            result.cancel();
            return true;
        }
        return mInner.onJsPrompt(view, url, message, defaultValue, result);
    }

    @Override
    public boolean onJsBeforeUnload(WebView view, String url, String message, JsResult result) {
        result.confirm();
        return true;
    }

    @Override
    public void onProgressChanged(WebView view, int newProgress) {
        mInner.onProgressChanged(view, newProgress);
    }

    @Override
    public void onReceivedTitle(WebView view, String title) {
        mInner.onReceivedTitle(view, title);
    }

    @Override
    public void onReceivedIcon(WebView view, Bitmap icon) {
        mInner.onReceivedIcon(view, icon);
    }

    @Override
    public void onReceivedTouchIconUrl(WebView view, String url, boolean precomposed) {
        mInner.onReceivedTouchIconUrl(view, url, precomposed);
    }

    @Override
    public void onShowCustomView(View view, int requestedOrientation, CustomViewCallback callback) {
        mInner.onShowCustomView(view, requestedOrientation, callback);
    }

    @Override
    public void onRequestFocus(WebView view) {
        mInner.onRequestFocus(view);
    }

    @Override
    public void onCloseWindow(WebView window) {
        mInner.onCloseWindow(window);
    }

    @Override
    public void onExceededDatabaseQuota(String url, String databaseIdentifier, long quota, long estimatedDatabaseSize, long totalQuota, WebStorage.QuotaUpdater quotaUpdater) {
        mInner.onExceededDatabaseQuota(url, databaseIdentifier, quota, estimatedDatabaseSize, totalQuota, quotaUpdater);
    }

    @Override
    public void onReachedMaxAppCacheSize(long requiredStorage, long quota, WebStorage.QuotaUpdater quotaUpdater) {
        mInner.onReachedMaxAppCacheSize(requiredStorage, quota, quotaUpdater);
    }

    @Override
    public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
        mInner.onGeolocationPermissionsShowPrompt(origin, callback);
    }

    @Override
    public void onGeolocationPermissionsHidePrompt() {
        mInner.onGeolocationPermissionsHidePrompt();
    }

    @Override
    public void onPermissionRequest(PermissionRequest request) {
        mInner.onPermissionRequest(request);
    }

    @Override
    public void onPermissionRequestCanceled(PermissionRequest request) {
        mInner.onPermissionRequestCanceled(request);
    }

    @Override
    public boolean onJsTimeout() {
        return mInner.onJsTimeout();
    }

    @Override
    public void onConsoleMessage(String message, int lineNumber, String sourceID) {
        mInner.onConsoleMessage(message, lineNumber, sourceID);
    }

    @Override
    public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
        return mInner.onConsoleMessage(consoleMessage);
    }

    @Override
    public Bitmap getDefaultVideoPoster() {
        return mInner.getDefaultVideoPoster();
    }

    @Override
    public View getVideoLoadingProgressView() {
        return mInner.getVideoLoadingProgressView();
    }

    @Override
    public void getVisitedHistory(ValueCallback<String[]> callback) {
        mInner.getVisitedHistory(callback);
    }

    @Override
    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
        return mInner.onShowFileChooser(webView, filePathCallback, fileChooserParams);
    }

    @Override
    public void openFileChooser(ValueCallback<Uri> uploadFile, String acceptType, String capture) {
        mInner.openFileChooser(uploadFile, acceptType, capture);
    }
}
