// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.WalkFavicon
import org.walktalkmeditate.pilgrim.ui.design.seals.SealSpec
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * Composition smoke tests for [GoshuinScreen] via the internal
 * content composable. SealRenderer's Canvas draws are stubbed under
 * Robolectric (Stage 3-C lesson) — we assert on text/semantic nodes
 * + callback wiring, not visual output.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GoshuinScreenTest {

    @get:Rule val composeRule = createComposeRule()

    private fun seal(id: Long, date: LocalDate = LocalDate.of(2026, 4, 19)): GoshuinSeal =
        GoshuinSeal(
            walkId = id,
            sealSpec = SealSpec(
                uuid = "uuid-$id",
                startMillis = 1_700_000_000_000L + id,
                distanceMeters = 5_000.0,
                durationSeconds = 1_800.0,
                displayDistance = "5.00",
                unitLabel = "km",
                ink = Color.Transparent,
            ),
            walkDate = date,
            shortDateLabel = "Apr 19",
        )

    @Test fun `Empty state shows caption`() {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    GoshuinScreenContent(
                        uiState = GoshuinUiState.Empty,
                        onBack = {},
                        onSealTap = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Your goshuin will fill as you walk").assertIsDisplayed()
    }

    @Test fun `Loaded state renders without crashing`() {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    GoshuinScreenContent(
                        uiState = GoshuinUiState.Loaded(
                            seals = listOf(seal(1L), seal(2L)),
                            totalCount = 2,
                        ),
                        onBack = {},
                        onSealTap = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    @Test fun `Loading state composes without crashing`() {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    GoshuinScreenContent(
                        uiState = GoshuinUiState.Loading,
                        onBack = {},
                        onSealTap = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    @Test fun `title is shown in header`() {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    GoshuinScreenContent(
                        uiState = GoshuinUiState.Empty,
                        onBack = {},
                        onSealTap = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Goshuin").assertIsDisplayed()
    }

    @Test fun `back button click fires onBack`() {
        var backCalls = 0
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    GoshuinScreenContent(
                        uiState = GoshuinUiState.Empty,
                        onBack = { backCalls++ },
                        onSealTap = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backCalls)
    }

    @Test fun `seal cell click fires onSealTap with correct walkId`() {
        // Use a single cell with a unique date caption so the tap-target
        // node is unambiguous — two cells sharing "Apr 19" would return
        // multiple nodes and break `onNodeWithText`.
        val single = seal(id = 42L).copy(shortDateLabel = "Unique-tag")
        var tappedWalkId: Long? = null
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    GoshuinScreenContent(
                        uiState = GoshuinUiState.Loaded(
                            seals = listOf(single),
                            totalCount = 1,
                        ),
                        onBack = {},
                        onSealTap = { id -> tappedWalkId = id },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Unique-tag").performClick()
        assertEquals(42L, tappedWalkId)
    }

    @Test fun `Loaded - milestone cell shows milestone label not date`() {
        // The cell with a non-null milestone should show the milestone
        // label INSTEAD OF the date. shortDateLabel = "should-not-show"
        // proves the substitution.
        val sealWithMilestone = seal(id = 7L).copy(
            shortDateLabel = "should-not-show",
            milestone = GoshuinMilestone.FirstWalk,
        )
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    GoshuinScreenContent(
                        uiState = GoshuinUiState.Loaded(
                            seals = listOf(sealWithMilestone),
                            totalCount = 1,
                        ),
                        onBack = {},
                        onSealTap = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("First Walk").assertIsDisplayed()
    }

    @Test fun `Loaded shows filter bar chips and Share button`() {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    GoshuinScreenContent(
                        uiState = GoshuinUiState.Loaded(
                            seals = listOf(seal(1L), seal(2L)),
                            totalCount = 2,
                        ),
                        onBack = {},
                        onSealTap = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("All").assertIsDisplayed()
        composeRule.onNodeWithText("Transformative").assertIsDisplayed()
        composeRule.onNodeWithText("Peaceful").assertIsDisplayed()
        composeRule.onNodeWithText("Extraordinary").assertIsDisplayed()
        composeRule.onNodeWithText("Share Goshuin").assertIsDisplayed()
    }

    @Test fun `filter chip filters the grid by favicon`() {
        val flame = seal(1L).copy(
            shortDateLabel = "Flame-row",
            favicon = WalkFavicon.FLAME,
        )
        val leaf = seal(2L).copy(
            shortDateLabel = "Leaf-row",
            favicon = WalkFavicon.LEAF,
        )
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    GoshuinScreenContent(
                        uiState = GoshuinUiState.Loaded(
                            seals = listOf(flame, leaf),
                            totalCount = 2,
                        ),
                        onBack = {},
                        onSealTap = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Flame-row").assertIsDisplayed()
        composeRule.onNodeWithText("Leaf-row").assertIsDisplayed()

        composeRule.onNodeWithText("Peaceful").performClick()
        composeRule.waitForIdle()
        // Leaf == Peaceful stays; Flame row filtered out.
        composeRule.onNodeWithText("Leaf-row").assertIsDisplayed()
        composeRule.onNodeWithText("Flame-row").assertDoesNotExist()
    }

    @Test fun `Share button click fires onShareGoshuin with filtered seals`() {
        var shared: List<GoshuinSeal>? = null
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    GoshuinScreenContent(
                        uiState = GoshuinUiState.Loaded(
                            seals = listOf(seal(1L), seal(2L)),
                            totalCount = 2,
                        ),
                        onBack = {},
                        onSealTap = {},
                        onShareGoshuin = { shared = it },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Share Goshuin").performClick()
        assertEquals(2, shared?.size)
    }
}
