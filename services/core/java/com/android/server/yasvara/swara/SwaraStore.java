/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.swara;

import android.annotation.NonNull;
import android.os.Environment;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/** Rules per user in /data/system/yasvara_swara/&lt;user&gt;.xml. */
final class SwaraStore {
    private static final String TAG = "SwaraStore";
    private static final String TAG_RULES = "swara";
    private static final String TAG_APP = "app";

    private final File mDir = new File(Environment.getDataSystemDirectory(), "yasvara_swara");

    @NonNull
    ArrayMap<String, SwaraRule> read(int userId) {
        final ArrayMap<String, SwaraRule> rules = new ArrayMap<>();
        final AtomicFile file = new AtomicFile(fileFor(userId));
        if (!file.exists()) return rules;
        try (FileInputStream in = file.openRead()) {
            final TypedXmlPullParser xml = Xml.resolvePullParser(in);
            int type;
            while ((type = xml.next()) != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG || !TAG_APP.equals(xml.getName())) continue;
                final String pkg = xml.getAttributeValue(null, "pkg");
                if (pkg == null) continue;
                final SwaraRule r = new SwaraRule();
                r.volume = xml.getAttributeFloat(null, "volume", 1f);
                r.muted = xml.getAttributeBoolean(null, "muted", false);
                r.micMuted = xml.getAttributeBoolean(null, "mic_muted", false);
                r.micGain = xml.getAttributeFloat(null, "mic_gain", 1f);
                r.shareMic = xml.getAttributeBoolean(null, "share_mic", false);
                r.outputType = xml.getAttributeInt(null, "output_type", 0);
                final String address = xml.getAttributeValue(null, "output_address");
                r.outputAddress = address != null ? address : "";
                r.resetOnClose = xml.getAttributeBoolean(null, "reset_on_close", false);
                r.normalize();
                if (!r.isDefault()) rules.put(pkg, r);
            }
        } catch (Exception e) {
            Slog.w(TAG, "Unreadable rules for user " + userId + "; starting empty", e);
            rules.clear();
        }
        return rules;
    }

    void write(int userId, @NonNull ArrayMap<String, SwaraRule> rules) {
        final AtomicFile file = new AtomicFile(fileFor(userId));
        if (rules.isEmpty()) {
            file.delete();
            return;
        }
        mDir.mkdirs();
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            final TypedXmlSerializer xml = Xml.resolveSerializer(out);
            xml.startDocument(null, true);
            xml.startTag(null, TAG_RULES);
            for (int i = 0; i < rules.size(); i++) {
                final SwaraRule r = rules.valueAt(i);
                xml.startTag(null, TAG_APP);
                xml.attribute(null, "pkg", rules.keyAt(i));
                xml.attributeFloat(null, "volume", r.volume);
                xml.attributeBoolean(null, "muted", r.muted);
                xml.attributeBoolean(null, "mic_muted", r.micMuted);
                xml.attributeFloat(null, "mic_gain", r.micGain);
                xml.attributeBoolean(null, "share_mic", r.shareMic);
                xml.attributeInt(null, "output_type", r.outputType);
                xml.attribute(null, "output_address", r.outputAddress);
                xml.attributeBoolean(null, "reset_on_close", r.resetOnClose);
                xml.endTag(null, TAG_APP);
            }
            xml.endTag(null, TAG_RULES);
            xml.endDocument();
            file.finishWrite(out);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to write rules for user " + userId, e);
            if (out != null) file.failWrite(out);
        }
    }

    void delete(int userId) {
        new AtomicFile(fileFor(userId)).delete();
    }

    private File fileFor(int userId) {
        return new File(mDir, userId + ".xml");
    }
}
