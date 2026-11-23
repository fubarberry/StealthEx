package com.cosmos.unreddit.data.remote.api.reddit

import com.cosmos.unreddit.di.NetworkModule.GenericOkHttp
import com.squareup.moshi.JsonReader
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RedditTokenManager @Inject constructor(
    @GenericOkHttp private val okHttpClient: OkHttpClient
) {

    private val lock = Any()

    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var expirationTimeMillis: Long = 0L

    @Throws(IOException::class)
    fun getToken(): String {
        val now = System.currentTimeMillis()
        val current = accessToken
        if (current != null && now < expirationTimeMillis) {
            return current
        }
        synchronized(lock) {
            val recheck = accessToken
            if (recheck != null && System.currentTimeMillis() < expirationTimeMillis) {
                return recheck
            }
            return fetchNewTokenLocked()
        }
    }

    @Throws(IOException::class)
    fun refreshToken(failedToken: String?): String {
        synchronized(lock) {
            val current = accessToken
            if (current != null && current != failedToken && System.currentTimeMillis() < expirationTimeMillis) {
                return current
            }
            return fetchNewTokenLocked()
        }
    }

    @Throws(IOException::class)
    private fun fetchNewTokenLocked(): String {
        val formBody = FormBody.Builder()
            .add("grant_type", GRANT_TYPE_INSTALLED_CLIENT)
            .add("device_id", DEVICE_ID)
            .build()

        val request = Request.Builder()
            .url(ACCESS_TOKEN_URL)
            .header("User-Agent", USER_AGENT)
            .header("Authorization", Credentials.basic(CLIENT_ID, ""))
            .post(formBody)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to fetch anonymous Reddit OAuth token: HTTP ${response.code}")
            }
            val bodySource = response.body?.source()
                ?: throw IOException("Empty response body from Reddit OAuth token endpoint")

            var parsedToken: String? = null
            var expiresInSeconds: Long = DEFAULT_EXPIRES_IN_SECONDS

            JsonReader.of(bodySource).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "access_token" -> parsedToken = reader.nextString()
                        "expires_in" -> expiresInSeconds = reader.nextLong()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }

            val token = parsedToken?.takeIf { it.isNotBlank() }
                ?: throw IOException("Missing access_token in Reddit OAuth response")

            accessToken = token
            expirationTimeMillis = System.currentTimeMillis() +
                ((expiresInSeconds - EXPIRY_BUFFER_SECONDS).coerceAtLeast(60L) * 1000L)

            return token
        }
    }

    companion object {
        private const val ACCESS_TOKEN_URL = "https://www.reddit.com/api/v1/access_token"
        private const val CLIENT_ID = "ohXpoqrZYub1kg"
        private const val GRANT_TYPE_INSTALLED_CLIENT = "https://oauth.reddit.com/grants/installed_client"
        private const val DEVICE_ID = "DO_NOT_TRACK_THIS_DEVICE"
        private const val DEFAULT_EXPIRES_IN_SECONDS = 86400L
        private const val EXPIRY_BUFFER_SECONDS = 300L

        const val USER_AGENT = "Reddit/Version 2024.17.0/Build 1567890/Android 14"
    }
}
