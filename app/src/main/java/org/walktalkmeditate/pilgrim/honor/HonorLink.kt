// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.util.Locale
import org.walktalkmeditate.pilgrim.domain.honor.trimmingWhitespacesAndNewlines

/**
 * The one place a share link becomes a share id, for both a link the OS
 * hands the app and text the walker pasted: iOS `HonorLink`
 * (`HonorLink.swift:3-33@7c200bf`, shared-walk spec S2 §1).
 *
 * - Only the host is checked, against both hosts, case-folded; the scheme
 *   never is, and a port or user info is ignored.
 * - The path must be exactly one segment once empty ones are dropped, and
 *   that whole segment must be the id. Each segment is percent-decoded
 *   before the check, as Foundation's `pathComponents` decodes, so an
 *   encoded newline fails and an escaped id letter passes.
 * - The query and the fragment are ignored.
 * - The id is case-sensitive and never folded.
 *
 * Text is parsed with `java.net.URI` up to its first `?` or `#`. iOS 18's
 * `URL(string:)` percent-encodes what RFC 3986 doesn't allow rather than
 * refusing it, so a query or a fragment holding a space, a lone `%`, or
 * a brace still parses there, and both platforms ignore what follows the
 * path. In the host or the path such a character still fails: `URI`
 * refuses it, and iOS's decoded segment fails the id. `Uri.parse`
 * refuses nothing, so it isn't used (S2 §11.1).
 */
object HonorLink {

    val HOSTS: Set<String> = setOf("honor.pilgrimapp.org", "walk.pilgrimapp.org")

    /** `\A[A-Za-z0-9_-]{10}\z`: [Regex.matches] anchors the whole input, so no trailing newline slips past. */
    private val ID = Regex("[A-Za-z0-9_-]{10}")

    fun parse(url: URI): String? {
        val host = url.host?.lowercase(Locale.ROOT) ?: return null
        if (host !in HOSTS) return null
        val parts = url.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }.map(::percentDecoded)
        val id = parts.singleOrNull() ?: return null
        return id.takeIf(::isId)
    }

    /** A bare id, or a link on either host with or without a scheme, trimmed of whitespace first. */
    fun parse(text: String): String? {
        val trimmed = text.trimmingWhitespacesAndNewlines()
        if (trimmed.isEmpty()) return null
        if (isId(trimmed)) return trimmed
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val url = try {
            URI(withScheme.substringBefore('?').substringBefore('#'))
        } catch (_: URISyntaxException) {
            return null
        }
        return parse(url)
    }

    private fun isId(s: String): Boolean = ID.matches(s)

    /**
     * `URLDecoder` alone would read `+` as a space, which a path never
     * means. The charset-name overload: the `Charset` one is API 33.
     */
    private fun percentDecoded(segment: String): String =
        URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
}
