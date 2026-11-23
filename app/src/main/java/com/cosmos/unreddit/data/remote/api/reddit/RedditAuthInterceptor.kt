package com.cosmos.unreddit.data.remote.api.reddit

import okhttp3.Interceptor
import okhttp3.Response

class RedditAuthInterceptor(
    private val tokenManager: RedditTokenManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val token = tokenManager.getToken()

        val authenticatedRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer $token")
            .header("User-Agent", RedditTokenManager.USER_AGENT)
            .build()

        val response = chain.proceed(authenticatedRequest)

        if (response.code == 401) {
            response.close()
            val refreshedToken = tokenManager.refreshToken(token)
            val retryRequest = originalRequest.newBuilder()
                .header("Authorization", "Bearer $refreshedToken")
                .header("User-Agent", RedditTokenManager.USER_AGENT)
                .build()
            return chain.proceed(retryRequest)
        }

        return response
    }
}
