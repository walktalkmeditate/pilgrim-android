// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

/**
 * Pure function: would compute a narrative arc summary across a walk's
 * photo sequence. **Android no-op stub.** iOS feeds attentionArc /
 * solitude / recurringTheme / dominantColors derived from
 * [PhotoContext]'s `salientRegion` (Vision saliency) into PromptAssembler.
 * ML Kit has no saliency equivalent; a constant "center" salientRegion
 * would drive `computeAttentionArc` to always emit "consistently_close"
 * → the rendered prompt would always say
 * *"Consistently focused on close-up detail throughout"*, feeding
 * misleading information to the LLM. Better to omit. PromptAssembler
 * (Task 8) drops the "Visual narrative" + "Color progression" arc block
 * accordingly. Type kept so the assembler's `build`/`null` gate stays
 * symmetric with iOS.
 */
object PhotoNarrativeArcBuilder {
    @Suppress("UNUSED_PARAMETER")
    fun build(entries: List<PhotoContextEntry>): NarrativeArc = NarrativeArc.EMPTY
}
