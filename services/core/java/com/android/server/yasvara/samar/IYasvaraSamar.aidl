/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.samar;

/**
 * Location override and location privacy layers. A target is "" for the global profile or a
 * package name for that app's profile.
 */
interface IYasvaraSamar {
    boolean isGlobalOn();
    void setGlobalOn(boolean on);

    /** lat, lng, altitude, speed m/s, heading, speed preset, has place (1/0); null if unknown. */
    double[] getPosition(String target);
    /** Pins a target. Returns the jump speed in m/s it implies, or NaN when refused. */
    double setPlace(String target, double lat, double lng);
    void setAltitude(String target, double altitude);
    /** x east, y north, each in [-1, 1]; (0, 0) stops. */
    void setJoystick(String target, float x, float y);
    /** 0 walk, 1 run, 2 bike, 3 car. */
    void setSpeedPreset(String target, int preset);

    /** 0 follow global, 1 real, 2 own profile. */
    int getAppMode(String pkg);
    void setAppMode(String pkg, int mode);
    /** "package\tmode\tmask\tvalue" for each app with its own settings. */
    String[] getConfiguredApps();

    /** Layer bits: 1 Wi-Fi and cell, 2 Bluetooth LE, 4 photo location, 8 local network. */
    long getLayers(String target);
    void setLayers(String target, int mask, int value);

    /** [lat, lng] from coordinates, a map link or a plus code; null when not understood. */
    double[] parseLocation(String text, double refLat, double refLng);

    String[] getPlaceNames();
    /** lat/lng interleaved, in the order of getPlaceNames. */
    double[] getPlaceCoords();
    boolean savePlace(String name, double lat, double lng);
    boolean removePlace(int index);

    /** Whether radio identifiers are hidden from this uid. For the phone process. */
    boolean hidesRadio(int uid);
}
