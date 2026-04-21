// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

/**
 * The Light Reading koan corpus — 40 short contemplative sayings.
 *
 * Constraints enforced by [KoanPickerTest]:
 *  - Each text ≤ 120 characters.
 *  - No exclamation marks (wabi-sabi voice; iOS constraint).
 *  - ASCII plus en-dash (–), em-dash (—), smart quotes, apostrophes,
 *    and ellipsis (…).
 *
 * Attributions are optional. Use a bare name — no leading dash. The
 * UI layer (6-B) decides how to format attribution (e.g., prefix
 * with "— ", set in italics).
 *
 * English-only for MVP. Localization is a Phase 10 concern.
 */
internal object Koans {

    val all: List<Koan> = listOf(
        // Moon & silence
        Koan("The moon reflects in ten thousand pools, yet it is still one moon.", "Zen"),
        Koan("What is not said often speaks loudest.", null),
        Koan("In the dark, all paths lead inward.", "Thich Nhat Hanh"),
        Koan("The moon does not fight the darkness. It offers light.", null),
        Koan("To see one thing clearly is to see all things.", "Zen"),
        Koan("The night sky is full of holes, each one a story.", null),
        Koan("Silence is not empty. It is full of listening.", "Rumi"),
        Koan("The moon was there long before you looked up at it.", null),

        // Path & pilgrimage
        Koan("The destination is never the point. The walking is.", null),
        Koan("Every step away is a step toward.", null),
        Koan("The road teaches what the map cannot.", null),
        Koan("To wander is not to be lost.", "Rumi"),
        Koan("A walk is a conversation with the ground.", null),
        Koan("The path that winds is the one worth taking.", null),
        Koan("You do not always arrive where you intend. You arrive where you need.", null),
        Koan("The world does not ask where you are going, only that you are present.", null),

        // Impermanence & season
        Koan("This hour will not come again. That is its gift.", null),
        Koan("The season changes whether we notice or not.", null),
        Koan("What grows must also fade. This is the law.", null),
        Koan("Everything returns, but never quite the same.", null),
        Koan("The light changes every moment. You are never walking in the same world twice.", null),
        Koan("Spring does not ask permission to arrive.", null),
        Koan("All things come and go like clouds.", "Zen"),
        Koan("The tree does not mourn the fallen leaf. It grows another.", null),
        Koan("Time is the only constant. Change is the only certainty.", null),

        // Sun & inner light
        Koan("The sun rose whether you noticed or not.", null),
        Koan("To see clearly is to see that you are already awake.", "Zen"),
        Koan("Light needs no permission to dispel darkness.", null),
        Koan("The sunrise asks nothing of you but to be there.", null),
        Koan("Within you is a sun that does not set.", "Rumi"),
        Koan("Golden light teaches what words cannot: that beauty is fleeting, and that is its power.", null),
        Koan("Every dawn is a reminder that you survived the dark.", null),

        // Presence & sensory
        Koan("The ground beneath you is older than thought.", null),
        Koan("To be here is to be nowhere else.", null),
        Koan("Your breath connects you to every living thing.", "Thich Nhat Hanh"),
        Koan("The air you breathe has crossed oceans and mountains.", null),
        Koan("Attention is the rarest gift you can give.", null),
        Koan("What you notice is what becomes real for you.", null),
        Koan("The moment does not need your approval to be complete.", null),
        Koan("You are walking through your own story, whether you know it or not.", null),
    )
}
