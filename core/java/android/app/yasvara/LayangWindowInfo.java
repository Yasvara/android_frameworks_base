/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.app.yasvara;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * One Layang window as reported by {@code ILayangManager#getWindows} and
 * {@code ILayangListener#onWindowsChanged}.
 *
 * @hide
 */
public final class LayangWindowInfo implements Parcelable {
    public final int taskId;
    public final int userId;
    @NonNull public final String packageName;
    @Nullable public final String label;
    @NonNull public final LayangTaskState state;
    /** The on-screen rect R in display px. */
    @NonNull public final Rect bounds;
    public final boolean focused;
    public final boolean visible;
    /** Uptime of the last focus or open, used to pick what to auto-park. */
    public final long lastInteraction;

    public LayangWindowInfo(int taskId, int userId, @NonNull String packageName,
            @Nullable String label, @NonNull LayangTaskState state, @NonNull Rect bounds,
            boolean focused, boolean visible, long lastInteraction) {
        this.taskId = taskId;
        this.userId = userId;
        this.packageName = packageName;
        this.label = label;
        this.state = state;
        this.bounds = new Rect(bounds);
        this.focused = focused;
        this.visible = visible;
        this.lastInteraction = lastInteraction;
    }

    private LayangWindowInfo(Parcel in) {
        taskId = in.readInt();
        userId = in.readInt();
        packageName = in.readString8();
        label = in.readString8();
        state = in.readTypedObject(LayangTaskState.CREATOR);
        bounds = in.readTypedObject(Rect.CREATOR);
        focused = in.readBoolean();
        visible = in.readBoolean();
        lastInteraction = in.readLong();
    }

    @Override
    public String toString() {
        return "LayangWindow{t" + taskId + " u" + userId + " " + packageName + " " + state
                + " " + bounds.toShortString() + (focused ? " focused" : "")
                + (visible ? "" : " invisible") + " last=" + lastInteraction + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(taskId);
        dest.writeInt(userId);
        dest.writeString8(packageName);
        dest.writeString8(label);
        dest.writeTypedObject(state, flags);
        dest.writeTypedObject(bounds, flags);
        dest.writeBoolean(focused);
        dest.writeBoolean(visible);
        dest.writeLong(lastInteraction);
    }

    public static final @NonNull Creator<LayangWindowInfo> CREATOR = new Creator<>() {
        @Override
        public LayangWindowInfo createFromParcel(Parcel in) {
            return new LayangWindowInfo(in);
        }

        @Override
        public LayangWindowInfo[] newArray(int size) {
            return new LayangWindowInfo[size];
        }
    };
}
