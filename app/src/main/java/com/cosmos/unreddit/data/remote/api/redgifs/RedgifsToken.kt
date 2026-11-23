package com.cosmos.unreddit.data.remote.api.redgifs

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RedgifsToken @Inject constructor(private val redgifsApi: RedgifsApi) {

    @Volatile
    private var token: String? = null

    @Volatile
    private var expiresAtMillis: Long = 0L

    private suspend fun getToken(forceRefresh: Boolean = false): String? {
        val now = System.currentTimeMillis()
        val cached = token
        if (!forceRefresh && !cached.isNullOrBlank() && now < expiresAtMillis) {
            return cached
        }

        runCatching {
            redgifsApi.getTemporaryToken()
        }.onSuccess {
            token = it.token
            expiresAtMillis = now + TOKEN_TTL_MS
        }

        return token
    }

    fun invalidateToken() {
        token = null
        expiresAtMillis = 0L
    }

    suspend fun getAuthorization(forceRefresh: Boolean = false): String {
        val token = getToken(forceRefresh) ?: ""
        return "Bearer $token"
    }

    companion object {
        private const val TOKEN_TTL_MS = 12 * 60 * 60 * 1000L
    }
}
