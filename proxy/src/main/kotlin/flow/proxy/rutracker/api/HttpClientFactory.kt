package flow.proxy.rutracker.api

import flow.proxy.rutracker.flaresolverr.FlareSolverrClient
import flow.proxy.rutracker.flaresolverr.FlareSolverrEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.Logging

internal object HttpClientFactory {
    private const val DefaultUrl = "https://rutracker.org/forum/"

    /**
     * Rutracker.org now runs a Cloudflare managed challenge in front of every endpoint except its
     * static index page, and that challenge can't be solved or replayed by a plain HTTP client -
     * see [FlareSolverrEngine] for why. Every request this client makes is relayed through a
     * FlareSolverr sidecar (a real, remotely-driven Chromium browser) instead of hitting
     * rutracker.org directly; its address is read from FLARESOLVERR_URL so it matches whatever
     * the deployment's docker-compose service is actually named/reachable at.
     */
    fun create(): HttpClient {
        val flareSolverrUrl = System.getenv("FLARESOLVERR_URL") ?: "http://flaresolverr:8191/v1"
        val engine = FlareSolverrEngine(FlareSolverrClient(flareSolverrUrl))
        return HttpClient(engine) {
            defaultRequest { url(DefaultUrl) }
            install(Logging)
        }
    }
}
