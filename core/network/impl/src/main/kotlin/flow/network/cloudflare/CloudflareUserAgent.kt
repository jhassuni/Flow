package flow.network.cloudflare

/**
 * Cloudflare ties a solved challenge (the `cf_clearance` cookie) to the User-Agent that solved
 * it. The WebView that solves the challenge and the OkHttpClient that reuses its cookies must
 * send the exact same value or Cloudflare will re-challenge every request.
 */
object CloudflareUserAgent {
    const val VALUE = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
}
