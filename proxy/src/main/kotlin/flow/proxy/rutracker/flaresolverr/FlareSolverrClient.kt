package flow.proxy.rutracker.flaresolverr

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class FlareSolverrCookieInput(
    val name: String,
    val value: String,
    val domain: String,
)

@Serializable
data class FlareSolverrCookieOutput(
    val name: String,
    val value: String,
    val domain: String = "",
)

@Serializable
data class FlareSolverrSolution(
    val url: String,
    val status: Int,
    val response: String,
    val cookies: List<FlareSolverrCookieOutput> = emptyList(),
    val userAgent: String = "",
)

@Serializable
private data class FlareSolverrCommand(
    val cmd: String,
    val session: String? = null,
    val url: String? = null,
    val postData: String? = null,
    val cookies: List<FlareSolverrCookieInput>? = null,
    val maxTimeout: Long = 60_000,
)

@Serializable
private data class FlareSolverrResponse(
    val status: String,
    val message: String = "",
    val session: String? = null,
    val solution: FlareSolverrSolution? = null,
)

/**
 * Talks to a FlareSolverr instance (a remotely-driven real Chromium browser) instead of
 * rutracker.org directly. FlareSolverr keeps its own persistent, Cloudflare-cleared browser
 * session; the rutracker session cookie (`bb_session`) for whichever account is calling is passed
 * in per-request instead, so one shared browser session can serve requests for any account
 * without FlareSolverr itself needing to "be" that account.
 */
internal class FlareSolverrClient(private val baseUrl: String) {
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) { requestTimeoutMillis = 90_000 }
    }

    private val sessionMutex = Mutex()
    private var cachedSessionId: String? = null

    suspend fun get(url: String, cookies: List<FlareSolverrCookieInput>): FlareSolverrSolution {
        return solve { session -> FlareSolverrCommand(cmd = "request.get", session = session, url = url, cookies = cookies.ifEmpty { null }) }
    }

    suspend fun post(url: String, postData: String, cookies: List<FlareSolverrCookieInput>): FlareSolverrSolution {
        return solve { session ->
            FlareSolverrCommand(
                cmd = "request.post",
                session = session,
                url = url,
                postData = postData,
                cookies = cookies.ifEmpty { null },
            )
        }
    }

    /** Runs [buildCommand], retrying once with a freshly created session if FlareSolverr rejects the cached one. */
    private suspend fun solve(buildCommand: (session: String) -> FlareSolverrCommand): FlareSolverrSolution {
        val response = send(buildCommand(session()))
        if (response.status == "ok") {
            return requireNotNull(response.solution) { "FlareSolverr returned no solution" }
        }
        sessionMutex.withLock { cachedSessionId = null }
        val retried = send(buildCommand(session()))
        return requireNotNull(retried.solution) { "FlareSolverr error: ${retried.message}" }
    }

    private suspend fun session(): String = sessionMutex.withLock {
        cachedSessionId ?: createSession().also { cachedSessionId = it }
    }

    private suspend fun createSession(): String {
        val response = send(FlareSolverrCommand(cmd = "sessions.create"))
        return requireNotNull(response.session) { "FlareSolverr did not return a session id" }
    }

    private suspend fun send(command: FlareSolverrCommand): FlareSolverrResponse {
        return httpClient.post(baseUrl) {
            contentType(ContentType.Application.Json)
            setBody(command)
        }.body()
    }
}
