/*
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of BetterAIChat2.
 *
 * BetterAIChat2 is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.device.api;

import android.view.Surface;

/**
 * Binder surface exposed by the resident shell helper.
 *
 * The helper owns the virtual display; the app owns the ImageReader surface
 * passed to [createDisplay]. Only display lifecycle lives here: launching and
 * input stay on the existing shell transport so there is one audited path for
 * actions.
 */
interface IVirtualDisplayService {
    /** Creates a display backed by [surface]; returns its id, or -1. */
    int createDisplay(in Surface surface, int width, int height, int dpi, String name);

    void releaseDisplay(int displayId);

    /** Launches a component on the helper-owned display. */
    boolean startApp(int displayId, String component);

    boolean tap(int displayId, int x, int y);

    boolean swipe(int displayId, int x1, int y1, int x2, int y2, int durationMs);

    boolean keyEvent(int displayId, int keyCode);

    boolean typeText(int displayId, String text);

    boolean ping();
}
