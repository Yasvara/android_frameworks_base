/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.lelap;

/**
 * Sleep and telemetry rules, under "yasvara_lelap". Every call requires
 * yasvara.permission.MANAGE_LELAP.
 *
 * Kinds and their values:
 *   strict     package that may run only while open or in Recents
 *   push       package that gets no FCM broadcasts
 *   job        "package/substring" of a job class or trace tag, package "*" for any
 *   sensor     substring of a sensor listener class
 *   component  "package/class" kept disabled
 *   package    package kept disabled
 *   sync       sync authority kept off on every account
 *   setting    "global|secure|system:key=value" held at that value
 *
 * @hide
 */
interface IYasvaraLelap {
    String[] getRules(String kind);

    /** Replaces the rules of one kind; removed rules are undone. Throws on a refused value. */
    void setRules(String kind, in String[] values);

    /** Denials, oldest first: "wallTimeMs\tkind\tpackage\tdetail". */
    List<String> getEvents();

    void clearEvents();

    /** Strict packages that are asleep now. */
    String[] getAsleep();

    /** Sleeping packages kept out of the stopped state because a widget of theirs is placed. */
    String[] getWidgetKept();
}
