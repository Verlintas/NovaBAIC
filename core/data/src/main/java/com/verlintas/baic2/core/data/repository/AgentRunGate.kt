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

package com.verlintas.baic2.core.data.repository

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One agentic run at a time, process-wide.
 *
 * ACT/MAX runs share one foreground service, one stop button and one audit
 * trail, so two of them cannot safely overlap. Chat and research turns do not
 * take this gate and may run alongside an agentic task.
 */
@Singleton
class AgentRunGate @Inject constructor() {

    private val owner = AtomicReference<Long?>(null)

    /** Returns false when another conversation already owns the agent slot. */
    fun tryAcquire(conversationId: Long): Boolean {
        if (owner.compareAndSet(null, conversationId)) return true
        return false
    }

    fun release(conversationId: Long) {
        owner.compareAndSet(conversationId, null)
    }
}
