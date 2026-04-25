// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

/** Stage 8-A: successful share response from the Cloudflare Worker. */
data class ShareResult(val url: String, val id: String)
