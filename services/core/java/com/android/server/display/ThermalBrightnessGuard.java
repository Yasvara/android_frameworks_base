/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.display;

import android.os.FileObserver;
import android.os.Handler;
import android.util.Slog;
import android.util.Xml;

import com.android.modules.utils.TypedXmlPullParser;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Caps brightness by skin temperature and ambient lux with the panel vendor's table. Opt-in: when
 * disabled nothing is watched or read.
 */
final class ThermalBrightnessGuard {
    private static final String TAG = "ThermalBrightnessGuard";

    static final String SETTING = "yasvara_brightness_thermal_guard";

    private static final File CONFIG =
            new File("/vendor/etc/displayconfig/thermal_brightness_control.xml");
    private static final File NODE_SKIN =
            new File("/sys/class/thermal/thermal_message/board_sensor_temp");
    private static final File NODE_SAFETY =
            new File("/sys/class/thermal/thermal_message/thermal_max_brightness");
    // Only the vendor's default condition; scene conditions need its scene ids.
    private static final int DEFAULT_CONDITION = 0;
    private static final float TEMPERATURE_GAP = 1f;

    private static final class Band {
        final float minLux;
        final float maxLux;
        final float[] minTemp;
        final float[] maxTemp;
        final float[] nits;

        Band(float minLux, float maxLux, List<float[]> pairs) {
            this.minLux = minLux;
            this.maxLux = maxLux;
            minTemp = new float[pairs.size()];
            maxTemp = new float[pairs.size()];
            nits = new float[pairs.size()];
            for (int i = 0; i < pairs.size(); i++) {
                minTemp[i] = pairs.get(i)[0];
                maxTemp[i] = pairs.get(i)[1];
                nits[i] = pairs.get(i)[2];
            }
        }

        float lookup(float temperature) {
            for (int i = 0; i < nits.length; i++) {
                if (temperature >= minTemp[i] && temperature < maxTemp[i]) {
                    return nits[i];
                }
            }
            return Float.NaN;
        }
    }

    private final Handler mHandler;
    private final Runnable mOnChange;

    private Band[] mBands;
    private FileObserver mObserver;
    private boolean mEnabled;
    // Written on the handler only.
    private float mTemperature = Float.NaN;
    private float mSafetyNits = Float.NaN;

    ThermalBrightnessGuard(Handler handler, Runnable onChange) {
        mHandler = handler;
        mOnChange = onChange;
    }

    void setEnabled(boolean enabled) {
        if (enabled == mEnabled) {
            return;
        }
        if (enabled && mBands == null) {
            mBands = loadBands();
            if (mBands.length == 0) {
                return;
            }
        }
        mEnabled = enabled;
        if (enabled) {
            mObserver = new FileObserver(List.of(NODE_SKIN, NODE_SAFETY), FileObserver.MODIFY) {
                @Override
                public void onEvent(int event, String path) {
                    readNodes();
                }
            };
            mObserver.startWatching();
            readNodes();
        } else {
            mObserver.stopWatching();
            mObserver = null;
            mTemperature = Float.NaN;
            mSafetyNits = Float.NaN;
        }
        mOnChange.run();
    }

    /** Returns the brightness ceiling in nits for this lux, or NaN when nothing is capped. */
    float getMaxNits(float lux) {
        if (!mEnabled || Float.isNaN(mTemperature)) {
            return mSafetyNits;
        }
        float nits = Float.NaN;
        for (Band band : mBands) {
            if (lux >= band.minLux && lux < band.maxLux) {
                nits = band.lookup(mTemperature);
                break;
            }
        }
        if (Float.isNaN(mSafetyNits)) {
            return nits;
        }
        return Float.isNaN(nits) ? mSafetyNits : Math.min(nits, mSafetyNits);
    }

    // Runs on the observer thread; the values are applied on the handler.
    private void readNodes() {
        final float skin = readFloat(NODE_SKIN) / 1000f;
        final float safety = readFloat(NODE_SAFETY);
        mHandler.post(() -> {
            if (!mEnabled) {
                return;
            }
            boolean changed = false;
            // Whole degrees with a gap, as the vendor applies it, so a 0.1 °C wobble is ignored.
            if (!Float.isNaN(skin) && (Float.isNaN(mTemperature)
                    || ((int) skin != (int) mTemperature
                            && Math.abs(skin - mTemperature) >= TEMPERATURE_GAP))) {
                mTemperature = skin;
                changed = true;
            }
            final float safetyNits = safety > 0f ? safety : Float.NaN;
            if (Float.compare(safetyNits, mSafetyNits) != 0) {
                mSafetyNits = safetyNits;
                changed = true;
            }
            if (changed) {
                mOnChange.run();
            }
        });
    }

    private static float readFloat(File node) {
        try {
            final String value = new String(Files.readAllBytes(node.toPath()),
                    StandardCharsets.US_ASCII).trim();
            return value.isEmpty() ? Float.NaN : Float.parseFloat(value);
        } catch (IOException | NumberFormatException e) {
            return Float.NaN;
        }
    }

    private static Band[] loadBands() {
        final List<Band> bands = new ArrayList<>();
        try (InputStream in = new FileInputStream(CONFIG)) {
            final TypedXmlPullParser parser = Xml.newFastPullParser();
            parser.setInput(in, StandardCharsets.UTF_8.name());
            int condition = -1;
            float bandMin = 0f;
            float bandMax = 0f;
            List<float[]> pairs = null;
            float[] pair = null;
            // min-inclusive and max-exclusive name both a band's lux and a pair's temperature.
            for (int type = parser.next(); type != XmlPullParser.END_DOCUMENT;
                    type = parser.next()) {
                if (type == XmlPullParser.START_TAG) {
                    switch (parser.getName()) {
                        case "identifier" -> condition = Integer.parseInt(parser.nextText().trim());
                        case "lux-temperature-pair" -> pairs = new ArrayList<>();
                        case "temperature-brightness-pair" -> pair = new float[3];
                        case "min-inclusive", "max-exclusive" -> {
                            final boolean min = parser.getName().equals("min-inclusive");
                            final float value = Float.parseFloat(parser.nextText().trim());
                            if (pair != null) {
                                pair[min ? 0 : 1] = value;
                            } else if (min) {
                                bandMin = value;
                            } else {
                                bandMax = value;
                            }
                        }
                        case "nit" -> {
                            if (pair != null) {
                                pair[2] = Float.parseFloat(parser.nextText().trim());
                            }
                        }
                        default -> { }
                    }
                } else if (type == XmlPullParser.END_TAG) {
                    switch (parser.getName()) {
                        case "temperature-brightness-pair" -> {
                            if (pairs != null) {
                                pairs.add(pair);
                            }
                            pair = null;
                        }
                        case "lux-temperature-pair" -> {
                            if (condition == DEFAULT_CONDITION && pairs != null) {
                                bands.add(new Band(bandMin, bandMax, pairs));
                            }
                            pairs = null;
                        }
                        default -> { }
                    }
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "No usable table in " + CONFIG + ": " + e);
            return new Band[0];
        }
        return bands.toArray(new Band[0]);
    }

    void dump(PrintWriter pw) {
        pw.println("ThermalBrightnessGuard:");
        pw.println("  mEnabled=" + mEnabled);
        pw.println("  mTemperature=" + mTemperature);
        pw.println("  mSafetyNits=" + mSafetyNits);
        pw.println("  bands=" + (mBands == null ? "not loaded" : mBands.length));
    }
}
