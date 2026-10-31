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

package com.verlintas.baic2

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * A provider whose only useful entry point is [call]. The CRUD surface stays
 * empty so Android's provider contract is satisfied without exposing data.
 */
abstract class CallOnlyProvider : ContentProvider() {

    final override fun query(
        contentUri: Uri,
        columns: Array<out String>?,
        where: String?,
        whereArgs: Array<out String>?,
        order: String?,
    ): Cursor = MatrixCursor(columns ?: emptyArray())

    final override fun getType(contentUri: Uri): String? = null

    final override fun insert(contentUri: Uri, row: ContentValues?): Uri? = null

    final override fun delete(contentUri: Uri, where: String?, whereArgs: Array<out String>?): Int = 0

    final override fun update(
        contentUri: Uri,
        row: ContentValues?,
        where: String?,
        whereArgs: Array<out String>?,
    ): Int = 0
}
