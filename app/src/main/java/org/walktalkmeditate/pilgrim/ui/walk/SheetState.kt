// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

/**
 * Two-detent state for the Active Walk bottom sheet.
 *
 * Kotlin enums implement [java.io.Serializable] by default, so plain
 * `rememberSaveable { mutableStateOf(SheetState.Expanded) }` survives
 * config changes via the bundle's Serializable saver. No custom Saver
 * required.
 */
enum class SheetState { Minimized, Expanded }
