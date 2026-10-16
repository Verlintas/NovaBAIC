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

import com.verlintas.baic2.core.engine.AgentLoop
import com.verlintas.baic2.core.engine.AuxiliaryTasks
import com.verlintas.baic2.core.engine.ConfirmationGate
import com.verlintas.baic2.core.engine.ConfirmationQueue
import com.verlintas.baic2.core.engine.ToolCatalog
import com.verlintas.baic2.core.engine.ToolRunner
import com.verlintas.baic2.core.network.provider.ProviderFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Runtime wiring. Tool catalog/runner bindings live in :tools; the
 * confirmation gate is backed by a queue the chat UI answers.
 */
@Module
@InstallIn(SingletonComponent::class)
object AgentRuntimeModule {

    @Provides
    @Singleton
    fun provideConfirmationQueue(): ConfirmationQueue = ConfirmationQueue()

    @Provides
    @Singleton
    fun provideQuestionQueue(): com.verlintas.baic2.core.engine.QuestionQueue =
        com.verlintas.baic2.core.engine.QuestionQueue()

    @Provides
    @Singleton
    fun provideConfirmationGate(queue: ConfirmationQueue): ConfirmationGate =
        ConfirmationGate { call -> queue.confirm(call) }

    @Provides
    @Singleton
    fun provideAgentLoop(
        providerFactory: ProviderFactory,
        toolCatalog: ToolCatalog,
        toolRunner: ToolRunner,
        confirmationGate: ConfirmationGate,
    ): AgentLoop = AgentLoop(
        providerFactory = { providerFactory.create(it) },
        toolCatalog = toolCatalog,
        toolRunner = toolRunner,
        confirmationGate = confirmationGate,
    )

    @Provides
    @Singleton
    fun provideAuxiliaryTasks(providerFactory: ProviderFactory): AuxiliaryTasks =
        AuxiliaryTasks(providerFactory = { providerFactory.create(it) })
}
