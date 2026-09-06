package flow.network.cloudflare

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import javax.inject.Inject

/**
 * Rutracker.org now puts a Cloudflare managed challenge in front of `login.php`, `tracker.php`
 * and `viewforum.php` (index.php stays open). A plain HTTP client can't execute the challenge's
 * JavaScript, so a request comes back as HTTP 403 with a `cf-mitigated: challenge` header instead
 * of the real page.
 *
 * When that happens, this interceptor hands off to [CloudflareChallengeCoordinator], which opens
 * a real WebView to solve the challenge, then retries the request as the exact same browser
 * identity (User-Agent + Client Hints) that solved it, with the resulting `cf_clearance` cookie
 * attached - Cloudflare ties the cookie to that fingerprint, so replaying it under a different or
 * fabricated identity gets re-challenged just the same. Once solved, the identity is cached in
 * [CloudflareCookieStore] and pre-attached to every subsequent request until it expires.
 */
internal class CloudflareInterceptor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cookieStore: CloudflareCookieStore,
    private val coordinator: CloudflareChallengeCoordinator,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        var response = chain.proceed(decorate(original))

        if (response.isCloudflareChallenge()) {
            response.close()
            val identity = coordinator.solve(context, original.url.toString())
            cookieStore.update(identity)
            response = chain.proceed(decorate(original))
        }

        return response
    }

    private fun decorate(request: Request): Request {
        val identity = cookieStore.identity
        val userAgent = identity?.userAgent ?: CloudflareUserAgent.VALUE
        val clientHints = identity?.clientHints ?: CloudflareClientHints.HEADERS

        val builder = request.newBuilder().header(UserAgentHeader, userAgent)
        clientHints.forEach { (name, value) -> builder.header(name, value) }
        identity?.cookies?.let { cfCookies ->
            val merged = listOfNotNull(request.header(CookieHeader), cfCookies)
                .joinToString(CookieSeparator)
            builder.header(CookieHeader, merged)
        }
        return builder.build()
    }

    private fun Response.isCloudflareChallenge(): Boolean {
        return code == 403 && header(CfMitigatedHeader)?.contains("challenge", ignoreCase = true) == true
    }

    private companion object {
        const val UserAgentHeader = "User-Agent"
        const val CookieHeader = "Cookie"
        const val CfMitigatedHeader = "cf-mitigated"
        const val CookieSeparator = "; "
    }
}
