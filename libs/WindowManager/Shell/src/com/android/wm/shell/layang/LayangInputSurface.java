/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.wm.shell.layang;

import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Region;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.IWindowSession;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.WindowInputChannelParams;
import android.window.InputTransferToken;

import com.android.wm.shell.common.ShellExecutor;

import java.util.function.Consumer;

/**
 * A bufferless input surface of a Layang window: the corner grips, and the
 * shield that takes every touch of a mini window. It is a container layer, so it costs no
 * composition, and its touch region moves with the surface, so a gesture needs no binder call
 * per frame. Both channel grant and region updates run off the Shell main thread to avoid
 * blocking animations or gesture dispatch.
 */
final class LayangInputSurface {
    private static final String TAG = "LayangInputSurface";

    private final IWindowSession mSession;
    private final ShellExecutor mMainExecutor;
    private final ShellExecutor mBgExecutor;
    private final int mDisplayId;
    private final SurfaceControl mSurface;
    private final IBinder mClientToken = new Binder();
    private final Consumer<MotionEvent> mOnTouch;
    private final Region mLastRegion = new Region();

    @Nullable private Receiver mReceiver;
    @Nullable private Region mPendingRegion;
    private boolean mHasLastRegion;
    private volatile boolean mClosed;

    LayangInputSurface(@NonNull IWindowSession session, @NonNull ShellExecutor mainExecutor,
            @NonNull ShellExecutor bgExecutor, int displayId, @NonNull SurfaceControl parent,
            @NonNull String name, @NonNull Consumer<MotionEvent> onTouch) {
        mSession = session;
        mMainExecutor = mainExecutor;
        mBgExecutor = bgExecutor;
        mDisplayId = displayId;
        mOnTouch = onTouch;
        mSurface = new SurfaceControl.Builder()
                .setName(name)
                .setContainerLayer()
                .setParent(parent)
                .setCallsite("LayangInputSurface")
                .build();
        final SurfaceControl surfaceCopy = new SurfaceControl(mSurface, "LayangInputSurface.bg");
        bgExecutor.execute(() -> {
            InputChannel channel = null;
            try {
                if (!mClosed) {
                    final WindowInputChannelParams params = new WindowInputChannelParams();
                    params.displayId = mDisplayId;
                    params.surface = surfaceCopy;
                    params.clientToken = mClientToken;
                    params.inputTransferToken = new InputTransferToken();
                    params.type = TYPE_APPLICATION;
                    params.flags = FLAG_NOT_FOCUSABLE;
                    params.privateFlags = PRIVATE_FLAG_TRUSTED_OVERLAY;
                    params.inputHandleName = name;
                    params.region = new Region();
                    channel = mSession.grantInputChannel(params);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "grantInputChannel failed", e);
            } finally {
                // The copy would keep the layer alive after close().
                surfaceCopy.release();
            }
            final InputChannel granted = channel;
            mMainExecutor.execute(() -> {
                if (granted == null) {
                    return;
                }
                if (mClosed) {
                    granted.dispose();
                    return;
                }
                mReceiver = new Receiver(granted, Looper.myLooper());
                if (mPendingRegion != null) {
                    final Region pending = mPendingRegion;
                    mPendingRegion = null;
                    setRegion(pending);
                }
            });
        });
    }

    @NonNull
    SurfaceControl getSurface() {
        return mSurface;
    }

    /** Touchable region in this surface's coordinates. Deduplicated and dispatched off-main. */
    void setRegion(@NonNull Region region) {
        if (mClosed || !mSurface.isValid()) {
            return;
        }
        if (mReceiver == null) {
            mPendingRegion = new Region(region);
            return;
        }
        if (mHasLastRegion && mLastRegion.equals(region)) {
            return;
        }
        mLastRegion.set(region);
        mHasLastRegion = true;
        final IBinder token = mReceiver.getToken();
        final Region copy = new Region(region);
        final SurfaceControl surfaceCopy = new SurfaceControl(mSurface, "LayangInputSurface.upd");
        mBgExecutor.execute(() -> {
            try {
                if (mClosed) {
                    return;
                }
                final WindowInputChannelParams params = new WindowInputChannelParams();
                params.displayId = mDisplayId;
                params.channelToken = token;
                params.surface = surfaceCopy;
                params.flags = FLAG_NOT_FOCUSABLE;
                params.privateFlags = PRIVATE_FLAG_TRUSTED_OVERLAY;
                params.region = copy;
                mSession.updateInputChannel(params);
            } catch (RemoteException e) {
                Log.e(TAG, "updateInputChannel failed", e);
            } finally {
                surfaceCopy.release();
            }
        });
    }

    void close(@NonNull SurfaceControl.Transaction t) {
        if (mClosed) {
            return;
        }
        mClosed = true;
        if (mReceiver != null) {
            mReceiver.dispose();
            mReceiver = null;
        }
        if (mSurface.isValid()) {
            t.remove(mSurface);
            mSurface.release();
        }
        mBgExecutor.execute(() -> {
            try {
                mSession.remove(mClientToken);
            } catch (RemoteException e) {
                Log.e(TAG, "remove failed", e);
            }
        });
    }

    private final class Receiver extends InputEventReceiver {
        Receiver(InputChannel channel, Looper looper) {
            super(channel, looper);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            boolean handled = false;
            if (event instanceof MotionEvent && !mClosed) {
                mOnTouch.accept((MotionEvent) event);
                handled = true;
            }
            finishInputEvent(event, handled);
        }
    }
}
