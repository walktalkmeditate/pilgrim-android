// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.sounds

/**
 * Breath cadence presets driving [org.walktalkmeditate.pilgrim.ui.meditation.BreathingCircle]
 * during meditation. Direct port of iOS `BreathRhythm` (MeditationView.swift:832-853).
 *
 * Stored on disk as the integer [id] (matches iOS `breathRhythm` UserDefaults Int).
 *
 * `isNone` (`inhale == 0.0`) means "no guided cadence — just a still
 * focus point." MeditationView treats this as a no-op for the timing
 * driver and lets the meditation run open.
 */
data class BreathRhythm(
    val id: Int,
    val name: String,
    val label: String,
    val description: String,
    val inhaleSeconds: Double,
    val holdInSeconds: Double,
    val exhaleSeconds: Double,
    val holdOutSeconds: Double,
) {
    val isNone: Boolean get() = inhaleSeconds == 0.0

    companion object {
        val all: List<BreathRhythm> = listOf(
            BreathRhythm(0, "Calm",      "5 / 7",     "Long exhale for gentle relaxation",     5.0, 0.0, 7.0, 0.0),
            BreathRhythm(1, "Equal",     "4 / 4",     "Balanced and simple",                   4.0, 0.0, 4.0, 0.0),
            BreathRhythm(2, "Relaxing",  "4-7-8",     "Deep relaxation with held breath",      4.0, 7.0, 8.0, 0.0),
            BreathRhythm(3, "Box",       "4-4-4-4",   "Four equal phases for focus",           4.0, 4.0, 4.0, 4.0),
            BreathRhythm(4, "Coherent",  "5 / 5",     "Heart rate variability training",       5.0, 0.0, 5.0, 0.0),
            BreathRhythm(5, "Deep calm", "3 / 6",     "Short inhale, slow release",            3.0, 0.0, 6.0, 0.0),
            BreathRhythm(6, "None",      "—",    "Still focus point, open meditation",    0.0, 0.0, 0.0, 0.0),
        )

        const val DEFAULT_ID: Int = 0

        /**
         * Resolve an [id] to a [BreathRhythm]. Out-of-range ids fall
         * back to [DEFAULT_ID] (Calm) and emit a Log.w so a future
         * maintainer debugging "user says they picked Box but the
         * circle animates Calm" has a breadcrumb. Out-of-range ids
         * can come from data corruption, a future iOS build adding
         * presets we don't recognize, or a `.pilgrim` ZIP from a
         * newer iOS version.
         */
        fun byId(id: Int): BreathRhythm {
            val match = all.getOrNull(id)
            if (match != null) return match
            android.util.Log.w(
                "BreathRhythm",
                "unknown breathRhythm id=$id (range 0..${all.lastIndex}) — falling back to Calm",
            )
            return all[DEFAULT_ID]
        }
    }
}
