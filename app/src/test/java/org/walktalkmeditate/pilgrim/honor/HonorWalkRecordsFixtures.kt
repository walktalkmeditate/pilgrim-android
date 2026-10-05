// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageLedgerStore
import org.walktalkmeditate.pilgrim.walk.honor.HonorMediaFiles

/**
 * A records reader over [db]'s Honor tables and [store], by default an
 * empty Ways store of its own, for the view models and the coordinator
 * whose tests never honor a Way, with the ledgers kept in that store.
 * Unconfined, so a read stays on the test's own dispatcher and Room
 * executors.
 */
internal fun honorWalkRecordsForTests(
    db: PilgrimDatabase,
    context: Context,
    store: WayStore = WayStore({ File(context.cacheDir, "ways-${UUID.randomUUID()}") }),
    dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
): HonorWalkRecords = HonorWalkRecords(db.honorDao(), store, PilgrimageLedgerStore(store), dispatcher)

/** The summary's reply resolver over the app's own files, as production builds it. */
internal fun honorMediaFilesForTests(
    context: Context,
    store: WayStore = WayStore({ File(context.cacheDir, "ways-${UUID.randomUUID()}") }),
): HonorMediaFiles = HonorMediaFiles({ context.filesDir }, store)
