package flow.network.cloudflare

import android.content.Context
import android.content.Intent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges OkHttp's background dispatcher threads (where a Cloudflare challenge is detected) with
 * the main-thread WebView that can actually solve it.
 *
 * [solve] blocks the calling (background) thread while a `CloudflareChallengeActivity` loads the
 * challenge page in a real WebView and executes its JavaScript. The activity is started via an
 * implicit intent rather than a direct class reference so this module (`core:network:impl`) does
 * not need to depend on the `app` module that hosts the activity.
 */
@Singleton
class CloudflareChallengeCoordinator @Inject constructor() {
    private val lock = Any()
    private var pendingLatch: CountDownLatch? = null
    private var pendingIdentity: CloudflareIdentity? = null

    fun solve(context: Context, url: String): CloudflareIdentity? = synchronized(lock) {
        val latch = CountDownLatch(1)
        pendingLatch = latch
        pendingIdentity = null
        launchChallengeActivity(context, url)
        val completed = latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        pendingLatch = null
        if (completed) pendingIdentity else null
    }

    fun completeSolution(identity: CloudflareIdentity?) {
        pendingIdentity = identity
        pendingLatch?.countDown()
    }

    private fun launchChallengeActivity(context: Context, url: String) {
        val intent = Intent(ACTION_SOLVE_CHALLENGE).apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_URL, url)
        }
        context.startActivity(intent)
    }

    companion object {
        const val ACTION_SOLVE_CHALLENGE = "flow.network.cloudflare.action.SOLVE_CHALLENGE"
        const val EXTRA_URL = "flow.network.cloudflare.extra.URL"
        private const val TIMEOUT_MS = 30_000L
    }
}
