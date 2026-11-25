/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.app.yasvara;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Layang state of a floating task. It lives on the root task in WM core, which
 * derives always-on-top, focusability, visibility and the size-compat scale from it. Shell
 * changes it with {@code WindowContainerTransaction#setLayangState} and reads it back from
 * {@code TaskInfo#layangState}.
 *
 * <p>Immutable; use the {@code with*} methods to derive a changed copy.
 *
 * @hide
 */
public final class LayangTaskState implements Parcelable {

    /** Interactive floating window. */
    public static final int MODE_FLOATING = 1;
    /** Small live window; never focusable, Shell's shield takes every touch. */
    public static final int MODE_MINI = 2;
    /** Invisible and stopped, listed only in the status-bar chip. */
    public static final int MODE_PARKED = 3;

    @IntDef(prefix = "MODE_", value = {MODE_FLOATING, MODE_MINI, MODE_PARKED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Mode {}

    /**
     * The app is a game. WM lays it out for the virtual size and scales it into the task
     * bounds through the size-compat path, so resizing never relayouts it (§4.1).
     */
    public static final int FLAG_GAME = 1;
    /** Ghost: touches pass through to what is below; not focusable (§4.5). */
    public static final int FLAG_GHOST = 1 << 1;
    /** Move and scale gestures are ignored. Shell-only. */
    public static final int FLAG_LOCKED = 1 << 2;
    /** A game whose aspect may change with edge drags (virtual size follows). */
    public static final int FLAG_ASPECT_UNLOCKED = 1 << 3;
    /**
     * Per-app opt-in: the app is laid out for the real window at a lowered density instead of
     * being scaled from the virtual size. Resizing relayouts it.
     */
    public static final int FLAG_REFLOW = 1 << 4;

    @IntDef(flag = true, prefix = "FLAG_",
            value = {FLAG_GAME, FLAG_GHOST, FLAG_LOCKED, FLAG_ASPECT_UNLOCKED, FLAG_REFLOW})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Flags {}

    private final @Mode int mMode;
    private final @Flags int mFlags;
    /** Long and short side of the virtual screen in px; 0 = the display's. */
    private final int mVirtualLong;
    private final int mVirtualShort;
    /** Density of a {@link #FLAG_REFLOW} window; 0 = the display density. */
    private final int mDensityDpi;

    public LayangTaskState(@Mode int mode, @Flags int flags, int virtualLong, int virtualShort,
            int densityDpi) {
        mMode = isValidMode(mode) ? mode : MODE_FLOATING;
        mFlags = flags;
        mVirtualLong = Math.max(0, Math.max(virtualLong, virtualShort));
        mVirtualShort = Math.max(0, Math.min(virtualLong, virtualShort));
        mDensityDpi = Math.max(0, densityDpi);
    }

    public LayangTaskState(@Mode int mode, @Flags int flags) {
        this(mode, flags, 0, 0, 0);
    }

    private LayangTaskState(Parcel in) {
        this(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
    }

    public static boolean isValidMode(int mode) {
        return mode >= MODE_FLOATING && mode <= MODE_PARKED;
    }

    public @Mode int getMode() {
        return mMode;
    }

    public @Flags int getFlags() {
        return mFlags;
    }

    public boolean hasFlag(@Flags int flag) {
        return (mFlags & flag) != 0;
    }

    public boolean isGame() {
        return hasFlag(FLAG_GAME);
    }

    public boolean isGhost() {
        return hasFlag(FLAG_GHOST);
    }

    public int getVirtualLong() {
        return mVirtualLong;
    }

    public int getVirtualShort() {
        return mVirtualShort;
    }

    public int getDensityDpi() {
        return mDensityDpi;
    }

    /** Whether the task takes focus and touches: floating and not a ghost. */
    public boolean isInteractive() {
        return mMode == MODE_FLOATING && !isGhost();
    }

    @NonNull
    public LayangTaskState withMode(@Mode int mode) {
        return new LayangTaskState(mode, mFlags, mVirtualLong, mVirtualShort, mDensityDpi);
    }

    /** Copy with {@code flags} set and every other bit of {@code mask} cleared. */
    @NonNull
    public LayangTaskState withFlags(@Flags int flags, @Flags int mask) {
        return new LayangTaskState(mMode, (mFlags & ~mask) | (flags & mask), mVirtualLong,
                mVirtualShort, mDensityDpi);
    }

    @NonNull
    public LayangTaskState withVirtualSize(int virtualLong, int virtualShort) {
        return new LayangTaskState(mMode, mFlags, virtualLong, virtualShort, mDensityDpi);
    }

    @NonNull
    public LayangTaskState withDensity(int densityDpi) {
        return new LayangTaskState(mMode, mFlags, mVirtualLong, mVirtualShort, densityDpi);
    }

    @NonNull
    public static String modeToString(int mode) {
        switch (mode) {
            case MODE_FLOATING: return "floating";
            case MODE_MINI: return "mini";
            case MODE_PARKED: return "parked";
            default: return "mode" + mode;
        }
    }

    /** Parses "floating", "mini" or "parked"; -1 when unknown. */
    public static int modeFromString(String s) {
        if ("floating".equals(s)) return MODE_FLOATING;
        if ("mini".equals(s)) return MODE_MINI;
        if ("parked".equals(s)) return MODE_PARKED;
        return -1;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LayangTaskState)) return false;
        final LayangTaskState that = (LayangTaskState) o;
        return mMode == that.mMode && mFlags == that.mFlags
                && mVirtualLong == that.mVirtualLong && mVirtualShort == that.mVirtualShort
                && mDensityDpi == that.mDensityDpi;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMode, mFlags, mVirtualLong, mVirtualShort, mDensityDpi);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Layang{").append(modeToString(mMode));
        if (isGame()) sb.append(" game");
        if (isGhost()) sb.append(" ghost");
        if (hasFlag(FLAG_LOCKED)) sb.append(" locked");
        if (hasFlag(FLAG_ASPECT_UNLOCKED)) sb.append(" aspect-unlocked");
        if (hasFlag(FLAG_REFLOW)) sb.append(" reflow");
        if (mVirtualLong > 0) sb.append(" v=").append(mVirtualLong).append('x').append(mVirtualShort);
        if (mDensityDpi > 0) sb.append(" dpi=").append(mDensityDpi);
        return sb.append('}').toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mMode);
        dest.writeInt(mFlags);
        dest.writeInt(mVirtualLong);
        dest.writeInt(mVirtualShort);
        dest.writeInt(mDensityDpi);
    }

    public static final @NonNull Creator<LayangTaskState> CREATOR = new Creator<>() {
        @Override
        public LayangTaskState createFromParcel(Parcel in) {
            return new LayangTaskState(in);
        }

        @Override
        public LayangTaskState[] newArray(int size) {
            return new LayangTaskState[size];
        }
    };
}
