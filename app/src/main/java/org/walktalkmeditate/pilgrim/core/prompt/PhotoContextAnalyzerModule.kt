// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the three ML Kit client interfaces consumed by
 * [PhotoContextAnalyzer]. Lives in `core/prompt/` for the same reason
 * as [CustomPromptStyleScopeModule]: keeps the analyzer testable +
 * injectable in isolation while Stage 13-XZ Task 10 (`PromptModule`)
 * is still pending. Task 10 may absorb these bindings or leave them
 * here — both are correct.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PhotoContextAnalyzerModule {

    @Binds
    @Singleton
    abstract fun bindImageLabeler(impl: MlKitImageLabelerClient): ImageLabelerClient

    @Binds
    @Singleton
    abstract fun bindTextRecognizer(impl: MlKitTextRecognizerClient): TextRecognizerClient

    @Binds
    @Singleton
    abstract fun bindFaceDetector(impl: MlKitFaceDetectorClient): FaceDetectorClient
}
