package flow.models.settings

sealed interface Endpoint {
    val host: String

    data object Proxy : Endpoint {
        // flow-app.tech (the original maintainer's server) is dead. Point this at your own
        // self-hosted proxy/ deployment's domain - see docker-compose.yml and PROXY_DOMAIN in
        // .env. Must match the DNS name Caddy requests its certificate for.
        override val host: String = "proxy.example.com"
    }

    sealed interface RutrackerEndpoint : Endpoint

    data object Rutracker : RutrackerEndpoint {
        override val host: String = "rutracker.org"
    }

    data class Mirror(override val host: String) : RutrackerEndpoint

    companion object
}
