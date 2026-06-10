package com.cosmos.unreddit.scraper

import com.cosmos.unreddit.data.remote.api.reddit.scraper.PostSearchScraper
import com.cosmos.unreddit.data.remote.api.reddit.scraper.UserSearchScraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class ScraperTest {

    private fun fetchHtml(urlStr: String): String {
        val url = URL(urlStr)
        val connection = url.openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    @Test
    fun testPostSearchScraper() = runBlocking {
        val html = fetchHtml("https://old.reddit.com/search?q=spez&type=link")
        val scraper = PostSearchScraper(Dispatchers.Unconfined)
        val listing = scraper.scrap(html)
        assertNotNull(listing)
        val children = listing.data.children
        println("PostSearchScraper found ${children.size} posts:")
        for (child in children) {
            println("  Post: ${child}")
        }
        assertFalse("Post search children list should not be empty", children.isEmpty())
    }

    @Test
    fun testUserSearchScraper() = runBlocking {
        val html = fetchHtml("https://old.reddit.com/search?q=spez&type=sr")
        val scraper = UserSearchScraper(Dispatchers.Unconfined)
        val listing = scraper.scrap(html)
        assertNotNull(listing)
        val children = listing.data.children
        println("UserSearchScraper found ${children.size} users:")
        for (child in children) {
            println("  User: ${child}")
        }
        assertFalse("User search children list should not be empty", children.isEmpty())
    }
}
