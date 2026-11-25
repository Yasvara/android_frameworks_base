/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.webkit;

import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.ServiceManager;
import android.os.UserHandle;

import java.io.FileInputStream;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The name table for this process, mapped once on the first WebView client. A process that
 * gets none (filtering off, app exempt, system uid) never wraps a client and pays nothing.
 */
final class SaringFilter {
    private static final String SERVICE = "yasvara_saring";
    private static final String DESCRIPTOR = "com.android.server.yasvara.saring.IYasvaraSaring";
    /** IYasvaraSaring.openTable, the tenth method. */
    private static final int OPEN_TABLE = IBinder.FIRST_CALL_TRANSACTION + 9;

    private static boolean sOpened;
    private static SaringNames sNames;
    private static final AtomicInteger sRefused = new AtomicInteger();

    private SaringFilter() {}

    static synchronized SaringNames names() {
        if (!sOpened) {
            sOpened = true;
            sNames = open();
        }
        return sNames;
    }

    static void count() {
        sRefused.incrementAndGet();
    }

    private static SaringNames open() {
        if (UserHandle.getAppId(Process.myUid()) < Process.FIRST_APPLICATION_UID) return null;
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            final IBinder service = ServiceManager.getService(SERVICE);
            if (service == null) return null;
            data.writeInterfaceToken(DESCRIPTOR);
            service.transact(OPEN_TABLE, data, reply, 0);
            reply.readException();
            if (reply.readInt() == 0) return null;
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.CREATOR.createFromParcel(reply);
                    FileInputStream in = new FileInputStream(fd.getFileDescriptor());
                    FileChannel channel = in.getChannel()) {
                return SaringNames.open(
                        channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()));
            }
        } catch (Throwable t) {
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
