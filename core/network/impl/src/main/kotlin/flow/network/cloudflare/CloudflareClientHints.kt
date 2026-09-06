package flow.network.cloudflare

/**
 * Rutracker.org's Cloudflare tier advertises `Accept-CH`/`Critical-CH` for the full set of
 * User-Agent Client Hints. A real Chrome/WebView automatically resends these on every request to
 * a domain that asked for them; a plain OkHttp client never sends them at all. Without them,
 * Cloudflare can tell the request replaying [CloudflareCookieStore]'s `cf_clearance` cookie isn't
 * really the browser that solved the challenge, and re-issues the challenge anyway.
 *
 * These values must describe the exact same fake browser identity as [CloudflareUserAgent.VALUE]
 * (Chrome 126 on Android 13, Pixel 7) - a mismatch between the two is itself a bot signal.
 */
object CloudflareClientHints {
    val HEADERS: List<Pair<String, String>> = listOf(
        "Sec-CH-UA" to "\"Not)A;Brand\";v=\"99\", \"Google Chrome\";v=\"126\", \"Chromium\";v=\"126\"",
        "Sec-CH-UA-Mobile" to "?1",
        "Sec-CH-UA-Platform" to "\"Android\"",
        "Sec-CH-UA-Platform-Version" to "\"13.0.0\"",
        "Sec-CH-UA-Model" to "\"Pixel 7\"",
        "Sec-CH-UA-Full-Version" to "\"126.0.6478.122\"",
        "Sec-CH-UA-Full-Version-List" to (
            "\"Not)A;Brand\";v=\"99.0.0.0\", \"Google Chrome\";v=\"126.0.6478.122\", " +
                "\"Chromium\";v=\"126.0.6478.122\""
            ),
        "Sec-CH-UA-Arch" to "\"\"",
        "Sec-CH-UA-Bitness" to "\"64\"",
        "Sec-CH-UA-WoW64" to "?0",
        "UA" to "\"Not)A;Brand\";v=\"99\", \"Google Chrome\";v=\"126\", \"Chromium\";v=\"126\"",
        "UA-Mobile" to "?1",
        "UA-Platform" to "\"Android\"",
        "UA-Platform-Version" to "\"13.0.0\"",
        "UA-Model" to "\"Pixel 7\"",
        "UA-Full-Version" to "\"126.0.6478.122\"",
        "UA-Arch" to "\"\"",
        "UA-Bitness" to "\"64\"",
    )
}
