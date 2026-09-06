package me.rutrackersearch.app.cloudflare

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import flow.logger.api.LoggerFactory
import flow.network.cloudflare.CloudflareChallengeCoordinator
import flow.network.cloudflare.CloudflareIdentity
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/**
 * Transient, invisible-to-the-user host for the WebView that solves rutracker.org's Cloudflare
 * challenge. It is started by [CloudflareChallengeCoordinator] (via an implicit intent, so
 * `core:network:impl` doesn't need to depend on this module) whenever a plain API request comes
 * back as a Cloudflare challenge instead of the expected page.
 *
 * The WebView keeps its own, real, unmodified User-Agent - Cloudflare ties the resulting
 * `cf_clearance` cookie to the exact browser fingerprint that solved the challenge (User-Agent +
 * Client Hints), so a fabricated identity would just get re-challenged when replayed. Once the
 * challenge page navigates away from "Just a moment...", this activity reads back the real
 * User-Agent, asks the page for its own Client Hints via `navigator.userAgentData`, and hands all
 * of it - together with the cookies - to the coordinator before finishing itself.
 */
@AndroidEntryPoint
class CloudflareChallengeActivity : ComponentActivity() {

    @Inject
    lateinit var coordinator: CloudflareChallengeCoordinator

    @Inject
    lateinit var loggerFactory: LoggerFactory

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { finishChallenge(identity = null) }
    @Volatile
    private var webView: WebView? = null
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(CloudflareChallengeCoordinator.EXTRA_URL)
        if (url.isNullOrBlank()) {
            finishChallenge(identity = null)
            return
        }
        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(ClientHintsBridge(), ClientHintsBridgeName)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, loadedUrl: String) {
                    checkChallengeStatus(view, loadedUrl)
                }
            }
        }
        this.webView = webView
        setContentView(webView)
        webView.loadUrl(url)
        mainHandler.postDelayed(timeoutRunnable, TimeoutMs)
    }

    private fun checkChallengeStatus(view: WebView, loadedUrl: String) {
        view.evaluateJavascript("document.title") { rawTitle ->
            val title = rawTitle?.trim('"').orEmpty()
            val stillChallenging = ChallengeTitleMarkers.any { title.contains(it, ignoreCase = true) }
            if (!stillChallenging) {
                view.evaluateJavascript(ClientHintsScript, null)
            }
        }
    }

    private inner class ClientHintsBridge {
        @JavascriptInterface
        fun onResult(json: String?) {
            val webView = webView ?: return
            mainHandler.post {
                val url = webView.url ?: return@post
                val cookies = extractCloudflareCookies(CookieManager.getInstance().getCookie(url))
                if (cookies == null) {
                    finishChallenge(identity = null)
                    return@post
                }
                finishChallenge(
                    CloudflareIdentity(
                        cookies = cookies,
                        userAgent = webView.settings.userAgentString,
                        clientHints = parseClientHints(json),
                    ),
                )
            }
        }
    }

    /**
     * The WebView's cookie jar also carries rutracker.org's own cookies (e.g. a fresh guest
     * `bb_session`). Only Cloudflare's own cookies should be merged into API requests - otherwise
     * they'd silently overwrite the caller's authenticated session cookie of the same name.
     */
    private fun extractCloudflareCookies(rawCookies: String?): String? {
        val cfCookies = rawCookies
            ?.split(";")
            ?.map { it.trim() }
            ?.filter { entry ->
                val name = entry.substringBefore("=").trim()
                name.startsWith("cf_", ignoreCase = true) || name.startsWith("__cf", ignoreCase = true)
            }
            .orEmpty()
        return cfCookies.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun parseClientHints(json: String?): List<Pair<String, String>> {
        if (json.isNullOrBlank() || json == "null") return emptyList()
        return runCatching {
            val data = JSONObject(json)
            buildList {
                data.optJSONArray("brands")?.let { add("Sec-CH-UA" to it.toBrandList()) }
                add("Sec-CH-UA-Mobile" to data.optBoolean("mobile").toSecChUaBoolean())
                data.optString("platform").takeIf { it.isNotEmpty() }?.let {
                    add("Sec-CH-UA-Platform" to "\"$it\"")
                }
                data.optString("platformVersion").takeIf { it.isNotEmpty() }?.let {
                    add("Sec-CH-UA-Platform-Version" to "\"$it\"")
                }
                data.optString("model").let { add("Sec-CH-UA-Model" to "\"$it\"") }
                data.optString("uaFullVersion").takeIf { it.isNotEmpty() }?.let {
                    add("Sec-CH-UA-Full-Version" to "\"$it\"")
                }
                data.optJSONArray("fullVersionList")?.let {
                    add("Sec-CH-UA-Full-Version-List" to it.toBrandList())
                }
                data.optString("architecture").let { add("Sec-CH-UA-Arch" to "\"$it\"") }
                data.optString("bitness").let { add("Sec-CH-UA-Bitness" to "\"$it\"") }
                add("Sec-CH-UA-WoW64" to data.optBoolean("wow64").toSecChUaBoolean())
            }
        }.getOrElse { emptyList() }
    }

    private fun JSONArray.toBrandList(): String {
        return (0 until length()).joinToString(", ") { i ->
            val entry = getJSONObject(i)
            "\"${entry.getString("brand")}\";v=\"${entry.getString("version")}\""
        }
    }

    private fun Boolean.toSecChUaBoolean() = if (this) "?1" else "?0"

    private fun finishChallenge(identity: CloudflareIdentity?) {
        if (finished) return
        finished = true
        mainHandler.removeCallbacks(timeoutRunnable)
        loggerFactory.get("CloudflareChallengeActivity").d { "Challenge solved=${identity != null}" }
        coordinator.completeSolution(identity)
        webView?.destroy()
        webView = null
        finish()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(timeoutRunnable)
        webView?.destroy()
        super.onDestroy()
    }

    private companion object {
        const val TimeoutMs = 30_000L
        const val ClientHintsBridgeName = "AndroidClientHints"
        val ChallengeTitleMarkers = listOf("Just a moment", "Проверка браузера", "Attention Required")

        @Suppress("MaxLineLength")
        val ClientHintsScript = """
            (function() {
                function send(hints) {
                    try { $ClientHintsBridgeName.onResult(JSON.stringify(hints)); } catch (e) {
                        try { $ClientHintsBridgeName.onResult(null); } catch (e2) {}
                    }
                }
                if (!navigator.userAgentData) { send(null); return; }
                var lowEntropy = {
                    brands: navigator.userAgentData.brands,
                    mobile: navigator.userAgentData.mobile,
                    platform: navigator.userAgentData.platform
                };
                navigator.userAgentData.getHighEntropyValues(
                    ["architecture", "bitness", "model", "platformVersion", "uaFullVersion", "fullVersionList", "wow64"]
                ).then(function(highEntropy) {
                    send(Object.assign({}, lowEntropy, highEntropy));
                }).catch(function() {
                    send(lowEntropy);
                });
            })();
        """.trimIndent()
    }
}
