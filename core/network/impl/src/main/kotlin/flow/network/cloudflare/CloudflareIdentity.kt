package flow.network.cloudflare

/**
 * The browser identity that solved a Cloudflare challenge: the clearance cookie, plus the exact
 * User-Agent and Client Hints headers that earned it. All three must be replayed together -
 * Cloudflare ties `cf_clearance` to the fingerprint that solved the challenge, so mixing a real
 * solved cookie with a fabricated or mismatched identity is itself a bot signal.
 */
data class CloudflareIdentity(
    val cookies: String,
    val userAgent: String,
    val clientHints: List<Pair<String, String>>,
)
