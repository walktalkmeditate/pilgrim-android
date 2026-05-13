// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.proximity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.walktalkmeditate.pilgrim.data.cairn.CachedCairn
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenSource
import org.walktalkmeditate.pilgrim.data.whisper.CachedWhisper
import org.walktalkmeditate.pilgrim.domain.Clock

/**
 * Test fake for [GeoCacheService]. Overrides every IO-touching
 * method to short-circuit. Test can seed the cached whisper / cairn
 * lists via [setWhispers] / [setCairns].
 */
open class FakeGeoCacheService(
    context: Context = ApplicationProvider.getApplicationContext(),
    clock: Clock = Clock { 0L },
) : GeoCacheService(
    context = context,
    httpClient = OkHttpClient(),
    deviceTokenStore = object : DeviceTokenSource {
        override suspend fun getToken(): String = "fake-token"
    },
    json = Json,
    clock = clock,
) {
    private val _whispers = MutableStateFlow<List<CachedWhisper>>(emptyList())
    private val _cairns = MutableStateFlow<List<CachedCairn>>(emptyList())

    override val whispers: StateFlow<List<CachedWhisper>> = _whispers.asStateFlow()
    override val cairns: StateFlow<List<CachedCairn>> = _cairns.asStateFlow()

    var fetchCalls = 0
    var enqueueCalls = 0
    var invalidateCalls = 0

    override fun invalidateLastFetch() {
        invalidateCalls += 1
    }

    override suspend fun fetchIfNeeded(latitude: Double, longitude: Double) {
        fetchCalls += 1
    }

    override suspend fun enqueuePending(placement: PendingPlacement) {
        enqueueCalls += 1
    }

    fun setWhispers(list: List<CachedWhisper>) {
        _whispers.value = list
    }

    fun setCairns(list: List<CachedCairn>) {
        _cairns.value = list
    }
}
