/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.layang;

import android.annotation.NonNull;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Slog;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Memory-pressure triggers for auto-park: PSI {@code some} 150 ms in 1 s
 * warns, {@code full} 100 ms in 1 s acts. One thread blocks in poll() only while a Layang
 * session exists, so there are no wakeups otherwise; a pipe ends it without a timeout.
 * system_server may already read and write /proc/pressure/memory (F17).
 */
final class LayangPressureWatcher {
    private static final String TAG = "LayangPSI";
    private static final String PSI = "/proc/pressure/memory";
    private static final String SOME = "some 150000 1000000";
    private static final String FULL = "full 100000 1000000";

    interface Listener {
        /** Called on the watcher thread for each "full" trigger. */
        void onFullPressure();
    }

    private final Listener mListener;
    private Thread mThread;
    private FileDescriptor mStopWrite;
    private int mGeneration;

    LayangPressureWatcher(@NonNull Listener listener) {
        mListener = listener;
    }

    synchronized void start() {
        if (mThread != null) {
            return;
        }
        FileDescriptor some = null;
        FileDescriptor full = null;
        FileDescriptor[] stop = null;
        try {
            some = openTrigger(SOME);
            full = openTrigger(FULL);
            stop = Os.pipe2(OsConstants.O_CLOEXEC);
        } catch (ErrnoException | IOException e) {
            closeQuietly(some);
            closeQuietly(full);
            if (stop != null) {
                closeQuietly(stop[0]);
                closeQuietly(stop[1]);
            }
            Slog.w(TAG, "PSI triggers unavailable; auto-park is off", e);
            return;
        }
        final int gen = ++mGeneration;
        final FileDescriptor someFd = some;
        final FileDescriptor fullFd = full;
        final FileDescriptor stopRead = stop[0];
        mStopWrite = stop[1];
        mThread = new Thread(() -> loop(gen, someFd, fullFd, stopRead), "layang-psi");
        mThread.setDaemon(true);
        mThread.start();
    }

    synchronized void stop() {
        if (mThread == null) {
            return;
        }
        mGeneration++;
        final FileDescriptor writeFd = mStopWrite;
        mStopWrite = null;
        mThread = null;
        if (writeFd != null) {
            try {
                Os.write(writeFd, new byte[] {1}, 0, 1);
            } catch (ErrnoException | IOException e) {
                Slog.w(TAG, "stop failed", e);
            } finally {
                // Close write end to avoid leaking a pipe FD in system_server on every session.
                closeQuietly(writeFd);
            }
        }
    }

    private synchronized boolean isCurrent(int gen) {
        return mGeneration == gen;
    }

    private static FileDescriptor openTrigger(String trigger)
            throws ErrnoException, IOException {
        final FileDescriptor fd = Os.open(PSI, OsConstants.O_RDWR | OsConstants.O_NONBLOCK
                | OsConstants.O_CLOEXEC, 0);
        try {
            final byte[] b = (trigger + "\0").getBytes(StandardCharsets.US_ASCII);
            Os.write(fd, b, 0, b.length);
            return fd;
        } catch (ErrnoException | IOException e) {
            closeQuietly(fd);
            throw e;
        }
    }

    private void loop(int gen, FileDescriptor some, FileDescriptor full, FileDescriptor stopRead) {
        final StructPollfd[] fds = new StructPollfd[3];
        for (int i = 0; i < fds.length; i++) {
            fds[i] = new StructPollfd();
        }
        fds[0].fd = some;
        fds[0].events = (short) OsConstants.POLLPRI;
        fds[1].fd = full;
        fds[1].events = (short) OsConstants.POLLPRI;
        fds[2].fd = stopRead;
        fds[2].events = (short) OsConstants.POLLIN;
        try {
            while (isCurrent(gen)) {
                fds[0].revents = fds[1].revents = fds[2].revents = 0;
                try {
                    Os.poll(fds, -1);
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.EINTR) continue;
                    throw e;
                }
                if (fds[2].revents != 0 || !isCurrent(gen)) {
                    break;
                }
                if ((fds[0].revents & OsConstants.POLLPRI) != 0) {
                    Slog.i(TAG, "memory pressure: some");
                }
                if ((fds[1].revents & OsConstants.POLLPRI) != 0) {
                    Slog.i(TAG, "memory pressure: full");
                    mListener.onFullPressure();
                }
                if (((fds[0].revents | fds[1].revents) & OsConstants.POLLERR) != 0) {
                    Slog.w(TAG, "PSI trigger error; stopping");
                    break;
                }
            }
        } catch (ErrnoException e) {
            Slog.w(TAG, "PSI poll failed", e);
        } finally {
            closeQuietly(some);
            closeQuietly(full);
            closeQuietly(stopRead);
        }
    }

    private static void closeQuietly(FileDescriptor fd) {
        if (fd == null) return;
        try {
            Os.close(fd);
        } catch (ErrnoException ignored) {
        }
    }
}
