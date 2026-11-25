/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.android.server.yasvara.kait;

import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Slog;

import com.android.internal.util.XmlUtils;

import io.github.libxposed.service.IXposedService;

import org.yasvara.kait.ipc.IModuleService;
import org.yasvara.kait.ipc.IRemotePreferenceCallback;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.regex.Pattern;

/**
 * Per-(module, user) service handed to scoped processes.
 *
 * <p>Provides authenticated read-only access to the module's {@code shared_prefs} and {@code files},
 * and registers {@link IRemotePreferenceCallback} listeners with {@link KaitManagerService} so
 * preference edits in the module UI push live into hooked processes without restarts.
 */
final class InjectedModuleService extends IModuleService.Stub {
    private static final String TAG = "YasvaraKait";

    private static final Pattern GROUP_NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final KaitManagerService mOwner;
    private final String mModule;
    private final int mUserId;

    InjectedModuleService(KaitManagerService owner, String module, int userId) {
        mOwner = owner;
        mModule = module;
        mUserId = userId;
    }

    private void enforceServed() {
        if (!mOwner.isServedTo(mModule, Binder.getCallingUid())) {
            throw new SecurityException("module " + mModule + " was not served to this process");
        }
    }

    private File moduleDir() {
        for (String root : new String[] {"/data/user/", "/data/user_de/"}) {
            final File dir = new File(root + mUserId + "/" + mModule);
            if (dir.isDirectory()) return dir;
        }
        return new File("/data/user/" + mUserId + "/" + mModule);
    }

    private File prefsFile(String group) {
        if (group == null || !GROUP_NAME.matcher(group).matches()) return null;
        return new File(moduleDir(), "shared_prefs/" + group + ".xml");
    }

    private File remoteFile(String name) {
        if (name == null || !FILE_NAME.matcher(name).matches()
                || name.equals(".") || name.equals("..")
                || name.startsWith(".")) {
            return null;
        }
        return new File(moduleDir(), "files/" + name);
    }

    @Override
    public long getFrameworkProperties() {
        return IXposedService.PROP_CAP_SYSTEM | IXposedService.PROP_CAP_REMOTE;
    }

    @Override
    public Bundle requestRemotePreferences(String group, IRemotePreferenceCallback callback) {
        enforceServed();
        if (callback != null && group != null && GROUP_NAME.matcher(group).matches()) {
            mOwner.registerRemotePrefCallback(mModule, mUserId, group, callback);
        }
        final Bundle bundle = new Bundle();
        final HashMap<String, Object> map = new HashMap<>();
        final File file = prefsFile(group);
        if (file != null && file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                final Object read = XmlUtils.readMapXml(in);
                if (read instanceof java.util.Map) {
                    for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) read).entrySet()) {
                        if (e.getKey() instanceof String) map.put((String) e.getKey(), e.getValue());
                    }
                }
            } catch (Throwable t) {
                Slog.w(TAG, "cannot read remote prefs " + file + " for " + mModule, t);
            }
        }
        bundle.putSerializable("map", map);
        return bundle;
    }

    @Override
    public ParcelFileDescriptor openRemoteFile(String path) {
        enforceServed();
        final File file = remoteFile(path);
        if (file == null || !file.isFile()) return null;
        try {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public String[] getRemoteFileNames() {
        enforceServed();
        final File dir = new File(moduleDir(), "files");
        final String[] names = dir.list((d, name) ->
                FILE_NAME.matcher(name).matches() && !name.startsWith(".")
                        && new File(d, name).isFile());
        return names != null ? names : new String[0];
    }
}
