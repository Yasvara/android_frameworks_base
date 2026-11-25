/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.nada;

import android.os.ParcelFileDescriptor;

/**
 * Nada processor effects, implemented by NadaService in system_server under "yasvara_nada".
 * Every call requires yasvara.permission.MANAGE_SWARA.
 *
 * A profile is text, "key:value|key:value", over the keys of {@link #getSchema}. It belongs to a
 * scope: a device class ("spk", "wired", "usb", "bt", "other") or an app ("app:<package>").
 * An app's profile replaces the device profile while that app is in front.
 *
 * @hide
 */
interface INadaManager {
    /** The settings schema as served by the engine; see {@link Nada.Schema}. */
    String getSchema();

    /** The stored profile of a scope, or an empty string. */
    String getProfile(String scope);

    /** Stores a profile and applies it at once. Unknown keys are refused. */
    void setProfile(String scope, String spec);

    void clearProfile(String scope);

    /** Device class media plays on now. */
    String getRoute();

    /** Engine state and timing, one record per line. */
    String getState();

    /** Stores an impulse response: float32 little-endian samples, channel after channel. */
    void putImpulse(String name, int rate, int channels, in ParcelFileDescriptor data);

    String[] listImpulses();

    void removeImpulse(String name);
}
