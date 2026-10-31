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

package com.verlintas.baic2.device.impl.di

import com.verlintas.baic2.device.api.AccessibilityBridge
import com.verlintas.baic2.device.api.ReminderScheduler
import com.verlintas.baic2.device.api.OcrProvider
import com.verlintas.baic2.device.api.PdfTextExtractor
import com.verlintas.baic2.device.api.RunNotifier
import com.verlintas.baic2.device.api.ScreenRecorderBridge
import com.verlintas.baic2.device.api.ScreenshotProvider
import com.verlintas.baic2.device.api.ShellBridge
import com.verlintas.baic2.device.api.SpeechInputBridge
import com.verlintas.baic2.device.api.SpeechOutput
import com.verlintas.baic2.device.impl.AndroidPdfTextExtractor
import com.verlintas.baic2.device.impl.AndroidSpeechOutput
import com.verlintas.baic2.device.impl.a11y.AndroidAccessibilityBridge
import com.verlintas.baic2.device.impl.ocr.MlKitOcrProvider
import com.verlintas.baic2.device.impl.projection.AndroidScreenshotProvider
import com.verlintas.baic2.device.impl.recorder.AndroidScreenRecorder
import com.verlintas.baic2.device.impl.speech.AndroidSpeechInput
import com.verlintas.baic2.device.impl.reminder.AndroidReminderScheduler
import com.verlintas.baic2.device.impl.run.AndroidRunNotifier
import com.verlintas.baic2.device.impl.display.AndroidVirtualDisplayBridge
import com.verlintas.baic2.device.impl.wireless.AndroidWirelessAdbBridge
import com.verlintas.baic2.device.impl.shell.ShizukuShellBridge
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DeviceImplModule {

    @Binds
    @Singleton
    abstract fun bindSpeechOutput(impl: AndroidSpeechOutput): SpeechOutput

    @Binds
    @Singleton
    abstract fun bindScreenshotProvider(impl: AndroidScreenshotProvider): ScreenshotProvider

    @Binds
    @Singleton
    abstract fun bindOcrProvider(impl: MlKitOcrProvider): OcrProvider

    @Binds
    @Singleton
    abstract fun bindPdfTextExtractor(impl: AndroidPdfTextExtractor): PdfTextExtractor

    @Binds
    @Singleton
    abstract fun bindShellBridge(impl: ShizukuShellBridge): ShellBridge

    @Binds
    @Singleton
    abstract fun bindScreenRecorder(impl: AndroidScreenRecorder): ScreenRecorderBridge

    @Binds
    @Singleton
    abstract fun bindSpeechInput(impl: AndroidSpeechInput): SpeechInputBridge

    @Binds
    @Singleton
    abstract fun bindAccessibilityBridge(impl: AndroidAccessibilityBridge): AccessibilityBridge

    @Binds
    @Singleton
    abstract fun bindRunNotifier(impl: AndroidRunNotifier): RunNotifier

    @Binds
    @Singleton
    abstract fun bindReminderScheduler(impl: AndroidReminderScheduler): ReminderScheduler

    @Binds
    @Singleton
    abstract fun bindVirtualDisplayBridge(
        impl: AndroidVirtualDisplayBridge,
    ): com.verlintas.baic2.device.api.VirtualDisplayBridge

    @Binds
    @Singleton
    abstract fun bindWirelessAdbBridge(
        impl: AndroidWirelessAdbBridge,
    ): com.verlintas.baic2.device.api.WirelessAdbBridge
}
