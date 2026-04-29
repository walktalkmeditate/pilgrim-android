// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import okhttp3.OkHttpClient
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenSource
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenStore
import org.walktalkmeditate.pilgrim.ui.settings.connect.BuildDeviceInfoProvider
import org.walktalkmeditate.pilgrim.ui.settings.connect.DeviceInfoProvider
import org.walktalkmeditate.pilgrim.ui.settings.connect.FeedbackSubmitter
import org.walktalkmeditate.pilgrim.ui.settings.connect.FeedbackSubmitterImpl

/**
 * Stage 10-F: feedback POST configuration. Decorates the project-wide
 * OkHttpClient (NetworkModule) with a 15-second call timeout to match
 * iOS `request.timeoutInterval = 15`. Same qualifier-decoration
 * pattern as CollectiveModule / ShareModule.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FeedbackHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FeedbackBaseUrl

@Module
@InstallIn(SingletonComponent::class)
object FeedbackModule {

    @Provides
    @Singleton
    @FeedbackHttpClient
    fun provideFeedbackHttpClient(default: OkHttpClient): OkHttpClient =
        default.newBuilder()
            .callTimeout(FEEDBACK_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    @FeedbackBaseUrl
    fun provideFeedbackBaseUrl(): String = FEEDBACK_BASE_URL

    private const val FEEDBACK_CALL_TIMEOUT_SEC = 15L
    // Matches iOS FeedbackService.baseURL.
    private const val FEEDBACK_BASE_URL = "https://walk.pilgrimapp.org"
}

@Module
@InstallIn(SingletonComponent::class)
abstract class FeedbackBindingsModule {
    @Binds
    @Singleton
    abstract fun bindDeviceTokenSource(impl: DeviceTokenStore): DeviceTokenSource

    @Binds
    @Singleton
    abstract fun bindFeedbackSubmitter(impl: FeedbackSubmitterImpl): FeedbackSubmitter

    @Binds
    @Singleton
    abstract fun bindDeviceInfoProvider(impl: BuildDeviceInfoProvider): DeviceInfoProvider
}
