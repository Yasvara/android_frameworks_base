/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.visibility;

/**
 * Unified Framework Tabir control plane under "yasvara_app_visibility".
 * Every call requires yasvara.permission.MANAGE_APP_VISIBILITY.
 *
 * @hide
 */
interface IYasvaraAppVisibility {
    const int SCOPE_SELECTED = 0;
    const int SCOPE_ALL_USER_APPS = 1;

    const int LIST_HIDDEN = 0;
    const int LIST_OBSERVERS = 1;
    const int LIST_EXCEPTIONS = 2;

    const int MODE_GLOBAL = 0;
    const int MODE_BLACKLIST = 1;
    const int MODE_WHITELIST = 2;

    const int DEV_HIDE_OFF = 0;
    const int DEV_HIDE_OBSERVERS = 1;
    const int DEV_HIDE_ALL_NON_SYSTEM = 2;

    int getScope();

    void setScope(int scope);

    /** Whether every installed Xposed module counts as hidden. */
    boolean getHideXposed();

    void setHideXposed(boolean hide);

    /** Whether Custom ROM / Yasvara signature packages are hidden from filtered observers. */
    boolean getHideRomPackages();

    void setHideRomPackages(boolean hide);

    /** Whether known root / hook management apps are automatically included in hidden targets. */
    boolean getHideRootTooling();

    void setHideRootTooling(boolean hide);

    /** Concealment mode for Developer Options & ADB Settings / sticky USB broadcast. */
    int getDevOptionsHideMode();

    void setDevOptionsHideMode(int mode);

    /** The packages on a LIST_* list, installed or not. */
    String[] getList(int list);

    /** Adds to or removes from a LIST_* list; rebuilds the rules once. */
    void setListed(int list, in String[] packages, boolean listed);

    /** Installed user apps that are Xposed modules. */
    String[] getXposedModules();

    /** Installed Custom ROM / Yasvara signature packages eligible for safe hiding. */
    String[] getDetectedRomPackages();

    /** Installed user apps recognized as root/hook tooling. */
    String[] getDetectedRootPackages();

    /** Reusable HMA-OSS-style Templates (Blacklist or Whitelist). */
    String[] getTemplateNames();

    int getTemplateMode(String name);

    String[] getTemplatePackages(String name);

    void putTemplate(String name, int mode, in String[] packages);

    void deleteTemplate(String name);

    /** Per-Observer App Overrides (Blacklist / Whitelist + assigned Templates). */
    String[] getConfiguredObservers();

    int getObserverMode(String observer);

    String[] getObserverPackages(String observer);

    String[] getObserverTemplates(String observer);

    void setObserverConfig(String observer, int mode, in String[] packages, in String[] templates);

    void clearObserverConfig(String observer);

    /** Whether the rules now in force hide target from observer. */
    boolean isHiddenFrom(String target, String observer);

    /** Whether Developer Options & ADB runtime settings are concealed from observer. */
    boolean isDevOptionsHiddenFrom(String observer);

    /** Force-stops running hidden user apps; returns number of packages stopped. */
    int forceStopHiddenTargets();

    /** Exports full Tabir configuration (lists, switches, templates, per-app rules) as JSON. */
    String exportConfigJson();

    /** Imports full Tabir configuration from JSON, replacing current rules and persisting. */
    boolean importConfigJson(String json);

    /**
     * Single-IPC UI snapshot JSON (all settings, lists, detected modules/ROM/root packages,
     * templates, and observer rules) to eliminate N+1 Binder round-trips in Laras.
     */
    String getUiSnapshotJson();

    /**
     * Single-IPC batch View-As simulation for [observer]: returns JSON with effective mode,
     * devHidden flag, and per-target hidden booleans without N+1 Binder/PM lookups.
     */
    String evaluateObserverJson(String observer);

    /**
     * Atomically creates/renames/updates a template AND updates which observer apps use it
     * (HMA-OSS bi-directional template workflow) in a single rebuild + single disk write.
     */
    void setTemplateWithAppliedApps(String oldName, String newName, int mode,
            in String[] targetPackages, in String[] appliedObservers);

    /**
     * Atomically applies a Blacklist/Whitelist/Global rule to multiple observer apps at once
     * (HMA-OSS Bulk Config Wizard) in a single rebuild + single disk write.
     */
    void setBulkObserverConfig(in String[] observers, int mode,
            in String[] packages, in String[] templates);

    /** Force-stops a single package (e.g. observer app before re-testing in Laras). */
    boolean forceStopPackage(String packageName);

    /**
     * Schedules a login-preserving risk/DFP cache reset (e.g. Shopee/SeaBank key_dfp and generic
     * anti-fraud caches) on the observer's next launch, clears its app cache, and force-stops it.
     */
    boolean resetObserverRiskCache(String packageName);
}
