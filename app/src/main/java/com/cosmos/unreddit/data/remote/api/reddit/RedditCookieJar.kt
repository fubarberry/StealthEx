package com.cosmos.unreddit.data.remote.api.reddit

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RedditCookieJar @Inject constructor() : CookieJar {

    private val cookies = ConcurrentHashMap<String, Cookie>().apply {
        put(
            "over18",
            Cookie.Builder()
                .name("over18")
                .value("1")
                .domain("reddit.com")
                .path("/")
                .build()
        )
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        syncFromWebView(url)
        return cookies.values.toList()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            this.cookies[cookie.name] = cookie
            syncCookieToWebView(url, cookie)
        }
    }

    fun getCookieValue(name: String): String? {
        if (!cookies.containsKey(name)) {
            syncFromWebView()
        }
        return cookies[name]?.value
    }

    fun syncFromWebView(url: HttpUrl? = null) {
        runCatching {
            val cookieManager = CookieManager.getInstance()
            val targetUrl = url?.toString() ?: DEFAULT_REDDIT_URL
            val cookieHeader = cookieManager.getCookie(targetUrl)
                ?: cookieManager.getCookie(DEFAULT_REDDIT_URL)
                ?: return
            val hostUrl = url ?: DEFAULT_REDDIT_URL.toHttpUrlOrNull() ?: return
            for (part in cookieHeader.split(";")) {
                val trimmed = part.trim()
                if (trimmed.isEmpty()) continue
                val eqIdx = trimmed.indexOf('=')
                if (eqIdx <= 0) continue
                val name = trimmed.substring(0, eqIdx).trim()
                val value = trimmed.substring(eqIdx + 1).trim()
                if (name.isNotEmpty()) {
                    val parsed = Cookie.parse(hostUrl, "$name=$value; Domain=reddit.com; Path=/")
                    if (parsed != null) {
                        cookies[name] = parsed
                    }
                }
            }
        }
    }

    private fun syncCookieToWebView(url: HttpUrl, cookie: Cookie) {
        runCatching {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setCookie(url.toString(), cookie.toString())
        }
    }

    companion object {
        private const val DEFAULT_REDDIT_URL = "https://www.reddit.com/"
    }
}
