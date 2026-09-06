package flow.network.cloudflare

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the most recently solved Cloudflare [CloudflareIdentity] so it can be attached to every
 * outgoing request without re-solving the challenge each time.
 */
@Singleton
class CloudflareCookieStore @Inject constructor() {
    @Volatile
    var identity: CloudflareIdentity? = null
        private set

    fun update(newIdentity: CloudflareIdentity?) {
        identity = newIdentity
    }
}
