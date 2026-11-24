/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.ketik;

import android.os.Bundle;

/**
 * Gboard flag overrides for the calling user, under "yasvara_ketik". Every call requires
 * yasvara.permission.MANAGE_KETIK.
 *
 * @hide
 */
interface IYasvaraKetik {
    /** The stored overrides: Boolean, Integer and String flags. */
    Bundle getOverrides();

    /** Replaces the overrides; empty removes them. Throws on a denied key or unsupported type. */
    void setOverrides(in Bundle values);

    /**
     * What Gboard itself reports, one "name\ttype\tfinalValue\tsources" line per flag, sources
     * comma separated. Null names returns every flag. Empty when Gboard's service is not running.
     * Slow: reads a service dump, so call it off the main thread.
     */
    List<String> readFlagStates(in String[] names);

    /** Flags the crash guard last removed, newest revert only; empty when none. */
    List<String> getLastRevert();

    /** Forgets the last revert once the owner has seen it. */
    void clearLastRevert();

    /**
     * Safe runtime health snapshot of Gboard (version, active GenAI handlers, whitelisted
     * component readiness tags, loaded feature modules, total registered flag count). Never
     * returns keyboard text or account identifiers.
     */
    Bundle readRuntimeHealth();
}
