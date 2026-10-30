// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import android.net.Uri
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.util.Locale
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageWayImporter
import org.walktalkmeditate.pilgrim.domain.honor.trimmingWhitespacesAndNewlines

/**
 * Where a link leads: iOS `HonorLinkDestination` (`HonorLink.swift:3-10@3b78728`).
 * [Unrecognised] is an honor-host address this build can't read, most
 * likely a shape a newer Pilgrim knows, so the walker is told instead of
 * met with silence.
 */
sealed interface HonorLinkDestination {
    data class Share(val id: String) : HonorLinkDestination

    /** [index] counts from 0, as the data does; the link counted from 1. */
    data class Stage(val routeId: String, val index: Int) : HonorLinkDestination

    data object Unrecognised : HonorLinkDestination
}

/**
 * The one place a link becomes a destination, for both a link the OS hands
 * the app and text the walker pasted: iOS `HonorLink`
 * (`HonorLink.swift:12-57@3b78728`; the share rules are shared-walk spec
 * S2 §1's, unchanged).
 *
 * - The host is case-folded and checked against both hosts; the scheme
 *   never is, and a port or user info is ignored.
 * - Empty path segments are dropped, and an empty path is no link. Each
 *   segment is percent-decoded before any check, as Foundation's
 *   `pathComponents` decodes, so an encoded newline fails and an escaped
 *   id letter passes. Segments are never case-folded.
 * - One segment that is a whole share id is a share, on either host. Any
 *   other shape on the walk host is no link.
 * - On [STAGE_HOST], a route slug then a stage number from 1 to
 *   [PilgrimageWayImporter.MAX_STAGE_COUNT] is a stage; anything else is
 *   [HonorLinkDestination.Unrecognised].
 * - The query and the fragment are ignored.
 *
 * Text is parsed with `java.net.URI` up to its first `?` or `#`. iOS 18's
 * `URL(string:)` percent-encodes what RFC 3986 doesn't allow rather than
 * refusing it, so a query or a fragment holding a space, a lone `%`, or
 * a brace still parses there, and both platforms ignore what follows the
 * path. In the host or the path such a character still fails: `URI`
 * refuses it, and iOS's decoded segment fails the id. `Uri.parse` refuses
 * nothing, so pasted text never goes through it (S2 §11.1).
 */
object HonorLink {

    val HOSTS: Set<String> = setOf("honor.pilgrimapp.org", "walk.pilgrimapp.org")

    /** Stage links live only where their fallback page does (`HonorLink.swift:17-18@3b78728`). */
    const val STAGE_HOST = "honor.pilgrimapp.org"

    /** `\A[A-Za-z0-9_-]{10}\z`: [Regex.matches] anchors the whole input, so no trailing newline slips past. */
    private val ID = Regex("[A-Za-z0-9_-]{10}")

    /** `\A[1-9][0-9]{0,2}\z` with literal ASCII digits: no leading zero, no sign, no other script's digits. */
    private val STAGE_NUMBER = Regex("[1-9][0-9]{0,2}")

    fun parse(url: URI): HonorLinkDestination? {
        val host = url.host?.lowercase(Locale.ROOT) ?: return null
        val parts = url.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }.map(::percentDecoded)
        return destination(host, parts)
    }

    /**
     * A link the OS hands the app, iOS's `parse(_ url:)` for an intent's
     * data. The platform already parsed it: [Uri.getPathSegments] decodes
     * each segment and drops the empty ones, as Foundation's
     * `pathComponents` does, and [Uri.getHost] leaves out the port and the
     * user info.
     */
    fun parse(uri: Uri): HonorLinkDestination? {
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        return destination(host, uri.pathSegments)
    }

    /** [candidate] itself when it is a whole share id, untrimmed: the install referrer's value (S2 §10.3). */
    fun shareId(candidate: String): String? = candidate.takeIf(::isId)

    /**
     * A bare id, or a link on either host with or without a scheme, trimmed
     * of whitespace first. Text Pilgrim can't open is simply no link, never
     * [HonorLinkDestination.Unrecognised]: the paste field says so itself
     * (`HonorLink.swift:34-43@3b78728`).
     */
    fun parse(text: String): HonorLinkDestination? {
        val trimmed = text.trimmingWhitespacesAndNewlines()
        if (trimmed.isEmpty()) return null
        if (isId(trimmed)) return HonorLinkDestination.Share(trimmed)
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val url = try {
            URI(withScheme.substringBefore('?').substringBefore('#'))
        } catch (_: URISyntaxException) {
            return null
        }
        return parse(url)?.takeUnless { it == HonorLinkDestination.Unrecognised }
    }

    /** iOS `parse(_ url:)` over a folded host and decoded segments (`HonorLink.swift:22-32@3b78728`). */
    private fun destination(host: String, parts: List<String>): HonorLinkDestination? {
        if (host !in HOSTS || parts.isEmpty()) return null
        if (parts.size == 1 && isId(parts[0])) return HonorLinkDestination.Share(parts[0])
        if (host != STAGE_HOST) return null
        if (parts.size == 2) {
            stageIndex(routeId = parts[0], number = parts[1])?.let { return HonorLinkDestination.Stage(parts[0], it) }
        }
        return HonorLinkDestination.Unrecognised
    }

    /** The link counts stages from 1, as the route page shows them; the data counts from 0 (`HonorLink.swift:49-56@3b78728`). */
    private fun stageIndex(routeId: String, number: String): Int? {
        if (!WayStore.isValidRouteId(routeId) || !STAGE_NUMBER.matches(number)) return null
        val n = number.toInt()
        return if (n <= PilgrimageWayImporter.MAX_STAGE_COUNT) n - 1 else null
    }

    private fun isId(s: String): Boolean = ID.matches(s)

    /**
     * `URLDecoder` alone would read `+` as a space, which a path never
     * means. The charset-name overload: the `Charset` one is API 33.
     */
    private fun percentDecoded(segment: String): String =
        URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
}
