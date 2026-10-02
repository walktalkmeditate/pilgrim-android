// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.honor.WayStore

/**
 * A records reader over [db]'s Honor tables and [store], by default an
 * empty Ways store of its own, for the view models and the coordinator
 * whose tests never honor a Way. Unconfined, so a read stays on the
 * test's own dispatcher and Room executors.
 */
internal fun honorWalkRecordsForTests(
    db: PilgrimDatabase,
    context: Context,
    store: WayStore = WayStore({ File(context.cacheDir, "ways-${UUID.randomUUID()}") }),
    dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
): HonorWalkRecords = HonorWalkRecords(db.honorDao(), store, dispatcher)
