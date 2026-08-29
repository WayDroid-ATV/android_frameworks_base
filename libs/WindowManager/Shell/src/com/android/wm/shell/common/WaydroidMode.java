/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wm.shell.common;

import android.annotation.NonNull;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * How the Waydroid host is presenting Android: either the whole UI inside one host window
 * ("full UI"), or every task in its own host window.
 *
 * <p>A per-app host window is composed from one task's layers, so layers that belong to no task -
 * the system bars among them - never reach it. Anything sized to the status bar is dead space
 * there, and the host draws its own panel and window decorations instead.
 *
 * <p>The navigation bar goes the same way, but its back gesture does not: EdgeBackGestureHandler
 * watches the display rather than the bar window, so an edge swipe still goes back inside a
 * per-app window - the host frames the window and Android keeps back, as ChromeOS does it. That
 * needs gesture navigation to be on; in three-button mode a per-app window has no way back at all
 * until the host sends one.
 */
public final class WaydroidMode {
    /**
     * Whether every task is shown in its own host window, published by the Waydroid platform
     * service - the only component that knows how the host composes. Unset on a stock device,
     * which is the same thing as full UI.
     */
    private static final String SETTING_PER_APP_WINDOWS = "per_app_host_windows";

    private static final Object sLock = new Object();
    private static final List<Runnable> sCallbacks = new ArrayList<>();
    private static ContentResolver sResolver;
    private static volatile boolean sPerAppWindows;

    private WaydroidMode() {}

    /** Whether the host is showing the whole Android UI inside a single window. */
    public static boolean isFullUi() {
        return !sPerAppWindows;
    }

    /**
     * Registers {@code callback} for mode changes. It runs on the main thread, and only when the
     * mode really flips. The host switches modes while apps are running, so callers must re-read
     * {@link #isFullUi()} rather than caching it.
     */
    public static void addChangeCallback(@NonNull Context context, @NonNull Runnable callback) {
        synchronized (sLock) {
            if (sResolver == null) {
                // The application context outlives any display context we may be called with.
                final Context appContext = context.getApplicationContext();
                sResolver = (appContext != null ? appContext : context).getContentResolver();
                sResolver.registerContentObserver(
                        Settings.Global.getUriFor(SETTING_PER_APP_WINDOWS), false,
                        new ContentObserver(new Handler(Looper.getMainLooper())) {
                            @Override
                            public void onChange(boolean selfChange) {
                                onModeChanged();
                            }
                        });
                sPerAppWindows = readPerAppWindows();
            }
            sCallbacks.add(callback);
        }
    }

    /** Unregisters a callback added by {@link #addChangeCallback}. */
    public static void removeChangeCallback(@NonNull Runnable callback) {
        synchronized (sLock) {
            sCallbacks.remove(callback);
        }
    }

    private static void onModeChanged() {
        final List<Runnable> callbacks;
        synchronized (sLock) {
            final boolean perAppWindows = readPerAppWindows();
            if (perAppWindows == sPerAppWindows) {
                return;
            }
            sPerAppWindows = perAppWindows;
            callbacks = new ArrayList<>(sCallbacks);
        }
        for (int i = 0; i < callbacks.size(); i++) {
            callbacks.get(i).run();
        }
    }

    private static boolean readPerAppWindows() {
        return Settings.Global.getInt(sResolver, SETTING_PER_APP_WINDOWS, 0) != 0;
    }
}
