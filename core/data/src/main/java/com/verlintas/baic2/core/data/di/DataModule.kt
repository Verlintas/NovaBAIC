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

package com.verlintas.baic2.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.verlintas.baic2.core.data.db.AgentDao
import com.verlintas.baic2.core.data.db.AutomationDao
import com.verlintas.baic2.core.data.db.McpServerDao
import com.verlintas.baic2.core.data.db.Baic2Database
import com.verlintas.baic2.core.data.db.ConversationDao
import com.verlintas.baic2.core.data.db.CoreMemoryDao
import com.verlintas.baic2.core.data.db.MessageDao
import com.verlintas.baic2.core.data.db.MemoryHoldDao
import com.verlintas.baic2.core.data.db.NoteDao
import com.verlintas.baic2.core.data.db.NoteLinkDao
import com.verlintas.baic2.core.data.db.NoteRevisionDao
import com.verlintas.baic2.core.data.db.PlanDao
import com.verlintas.baic2.core.data.db.RunDao
import com.verlintas.baic2.core.data.db.ScheduledTaskDao
import com.verlintas.baic2.core.data.db.SnapshotDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): Baic2Database =
        Room.databaseBuilder(context, Baic2Database::class.java, "baic2.db")
            .addMigrations(
                Baic2Database.MIGRATION_1_2,
                Baic2Database.MIGRATION_2_3,
                Baic2Database.MIGRATION_3_4,
                Baic2Database.MIGRATION_4_5,
                Baic2Database.MIGRATION_5_6,
                Baic2Database.MIGRATION_6_7,
                Baic2Database.MIGRATION_7_8,
                Baic2Database.MIGRATION_8_9,
                Baic2Database.MIGRATION_9_10,
                Baic2Database.MIGRATION_10_11,
                Baic2Database.MIGRATION_11_12,
                Baic2Database.MIGRATION_12_13,
                Baic2Database.MIGRATION_13_14,
                Baic2Database.MIGRATION_14_15,
                Baic2Database.MIGRATION_15_16,
                Baic2Database.MIGRATION_16_17,
                Baic2Database.MIGRATION_17_18,
                Baic2Database.MIGRATION_18_19,
                Baic2Database.MIGRATION_19_20,
            )
            .build()

    @Provides
    fun provideAgentDao(db: Baic2Database): AgentDao = db.agentDao()

    @Provides
    fun provideConversationDao(db: Baic2Database): ConversationDao = db.conversationDao()

    @Provides
    fun provideMessageDao(db: Baic2Database): MessageDao = db.messageDao()

    @Provides
    fun provideSnapshotDao(db: Baic2Database): SnapshotDao = db.snapshotDao()

    @Provides
    fun provideScheduledTaskDao(db: Baic2Database): ScheduledTaskDao = db.scheduledTaskDao()

    @Provides
    fun provideRunDao(db: Baic2Database): RunDao = db.runDao()

    @Provides
    fun provideNoteDao(db: Baic2Database): NoteDao = db.noteDao()

    @Provides
    fun provideNoteLinkDao(db: Baic2Database): NoteLinkDao = db.noteLinkDao()

    @Provides
    fun provideNoteRevisionDao(db: Baic2Database): NoteRevisionDao = db.noteRevisionDao()

    @Provides
    fun provideMemoryHoldDao(db: Baic2Database): MemoryHoldDao = db.memoryHoldDao()

    @Provides
    fun provideCoreMemoryDao(db: Baic2Database): CoreMemoryDao = db.coreMemoryDao()

    @Provides
    fun providePlanDao(db: Baic2Database): PlanDao = db.planDao()

    @Provides
    fun provideAutomationDao(db: Baic2Database): AutomationDao = db.automationDao()

    @Provides
    fun provideMcpServerDao(db: Baic2Database): McpServerDao = db.mcpServerDao()

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore
}
