// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.onboarding

import android.app.Application
import org.walktalkmeditate.pilgrim.data.sounds.FakeSoundsPreferencesRepository
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.BellPlaying
import org.walktalkmeditate.pilgrim.permissions.PermissionsRepository
import org.walktalkmeditate.pilgrim.permissions.PermissionsViewModel
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

private object NoopBellPlayer : BellPlaying {
    override fun play() {}
}

/**
 * B1 regression: the [BatteryExemptionCard] was orphaned (zero call
 * sites) so the user was never asked to battery-exempt the app — THE
 * determinant for long backgrounded-walk survival on OnePlus / Xiaomi /
 * Samsung per CLAUDE.md. It is now rendered inside [PermissionsScreen]'s
 * onboarding flow.
 *
 * These tests assert the card is reachable + shown when the user is
 * neither exempt nor has answered the prompt (Robolectric's PowerManager
 * reports not-exempt by default; a fresh DataStore reports not-asked),
 * and that answering the prompt makes it self-hide.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h1200dp")
class BatteryExemptionCardOnboardingTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var tempFile: File
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var viewModel: PermissionsViewModel

    @Before
    fun setUp() {
        tempFile = File(
            System.getProperty("java.io.tmpdir"),
            "pilgrim-${UUID.randomUUID()}.preferences_pb",
        )
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { tempFile },
        )
        viewModel = PermissionsRepository(dataStore).let {
            PermissionsViewModel(it, it, NoopBellPlayer, FakeSoundsPreferencesRepository())
        }
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        tempFile.delete()
    }

    @Test
    fun `card is shown when not exempt and not asked`() {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 1200.dp)) {
                    BatteryExemptionCard(viewModel = viewModel)
                }
            }
        }

        composeRule.onNodeWithText("A word about sleep").assertIsDisplayed()
        composeRule.onNodeWithText("Allow").assertIsDisplayed()
        composeRule.onNodeWithText("Later").assertIsDisplayed()
    }

    @Test
    fun `action button does NOT permanently hide the card (grant-then-backout stays visible)`() {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 1200.dp)) {
                    BatteryExemptionCard(viewModel = viewModel)
                }
            }
        }

        composeRule.onNodeWithText("Allow").performClick()
        composeRule.waitForIdle()

        // Robolectric PowerManager stays not-exempt and the system
        // dialog is a no-op, so a user who backs out without granting
        // must still see the card — the action path must NOT latch
        // `asked`. Permanent suppression here is the OxygenOS
        // dying-walk regression.
        composeRule.onNodeWithText("A word about sleep").assertIsDisplayed()
        composeRule.onNodeWithText("Allow").assertIsDisplayed()
    }

    @Test
    fun `Later button latches and hides the card`() {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 1200.dp)) {
                    BatteryExemptionCard(viewModel = viewModel)
                }
            }
        }

        composeRule.onNodeWithText("Later").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("A word about sleep")
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("A word about sleep").assertDoesNotExist()
    }

    @Test
    fun `card self-hides once the prompt has been answered`() {
        runBlocking { PermissionsRepository(dataStore).markBatteryExemptionAsked() }
        viewModel = PermissionsRepository(dataStore).let {
            PermissionsViewModel(it, it, NoopBellPlayer, FakeSoundsPreferencesRepository())
        }

        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 1200.dp)) {
                    BatteryExemptionCard(viewModel = viewModel)
                }
            }
        }

        // batteryExemptionAsked is a WhileSubscribed StateFlow seeded
        // false; the persisted-true emission lands after the subscriber
        // attaches, so poll until the card collapses.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("A word about sleep")
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("A word about sleep").assertDoesNotExist()
    }
}
