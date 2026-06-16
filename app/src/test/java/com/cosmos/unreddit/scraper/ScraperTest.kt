package com.cosmos.unreddit.scraper

import com.cosmos.unreddit.data.remote.api.reddit.scraper.PostScraper
import com.cosmos.unreddit.data.remote.api.reddit.scraper.PostSearchScraper
import com.cosmos.unreddit.data.remote.api.reddit.scraper.UserSearchScraper
import com.cosmos.unreddit.util.LinkUtil
import com.cosmos.unreddit.data.model.MediaType
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

    @Test
    fun testResolveMediaUrls() = runBlocking {
        val oldResolved = com.cosmos.unreddit.util.LinkUtil.resolveMediaUrls(
            "https://v.redd.it/mmpzzcrb15p91/DASHPlaylist.mpd",
            Dispatchers.IO
        )
        assertNotNull(oldResolved)
        println("Old resolved: $oldResolved")
        org.junit.Assert.assertTrue(
            "Old video URL should contain DASH_720.mp4",
            oldResolved.videoUrl.contains("DASH_720.mp4")
        )
        org.junit.Assert.assertNotNull("Old audio URL should not be null", oldResolved.audioUrl)
        org.junit.Assert.assertTrue(
            "Old audio URL should contain DASH_audio.mp4",
            oldResolved.audioUrl!!.contains("DASH_audio.mp4")
        )

        val newResolved = com.cosmos.unreddit.util.LinkUtil.resolveMediaUrls(
            "https://v.redd.it/pvok1vrtqb6h1/DASHPlaylist.mpd",
            Dispatchers.IO
        )
        assertNotNull(newResolved)
        println("New resolved: $newResolved")
        org.junit.Assert.assertTrue(
            "New video URL should contain CMAF_720.mp4",
            newResolved.videoUrl.contains("CMAF_720.mp4")
        )
        org.junit.Assert.assertNotNull("New audio URL should not be null", newResolved.audioUrl)
        org.junit.Assert.assertTrue(
            "New audio URL should contain CMAF_AUDIO_128.mp4",
            newResolved.audioUrl!!.contains("CMAF_AUDIO_128.mp4")
        )

        val rawShortResolved = com.cosmos.unreddit.util.LinkUtil.resolveMediaUrls(
            "https://v.redd.it/mmpzzcrb15p91",
            Dispatchers.IO
        )
        assertNotNull(rawShortResolved)
        println("Raw short resolved: $rawShortResolved")
        org.junit.Assert.assertTrue(
            "Raw short video URL should contain DASH_720.mp4",
            rawShortResolved.videoUrl.contains("DASH_720.mp4")
        )
        org.junit.Assert.assertNotNull("Raw short audio URL should not be null", rawShortResolved.audioUrl)
        org.junit.Assert.assertTrue(
            "Raw short audio URL should contain DASH_audio.mp4",
            rawShortResolved.audioUrl!!.contains("DASH_audio.mp4")
        )
    }

    @Test
    fun testOldPostScraping() = runBlocking {
        val html = fetchHtml("https://old.reddit.com/r/trashy/comments/xjuy16/trashy_woman_caught_in_4k_on_a_twitch_live_stream/")
        val scraper = PostScraper(Dispatchers.Unconfined)
        val listing = scraper.scrap(html)
        assertNotNull(listing)
        val children = listing.data.children
        assertFalse(children.isEmpty())
        val post = children[0] as com.cosmos.unreddit.data.remote.api.reddit.model.PostChild
        val postData = post.data
        println("Scraped old post url: ${postData.url}")
        println("Scraped old post mediaType: ${postData.mediaType}")
        println("Scraped old post mediaUrl: ${postData.mediaUrl}")
        assertNotNull(postData.media)
        assertNotNull(postData.media?.redditVideoPreview)
        val videoUrl = postData.media?.redditVideoPreview?.fallbackUrl
        println("Scraped videoUrl: $videoUrl")
        org.junit.Assert.assertTrue("videoUrl should contain DASHPlaylist.mpd", videoUrl!!.contains("DASHPlaylist.mpd"))

        val resolved = LinkUtil.resolveMediaUrls(videoUrl, Dispatchers.IO)
        println("Resolved urls from manifest: video=${resolved.videoUrl}, audio=${resolved.audioUrl}")
        org.junit.Assert.assertTrue("Resolved videoUrl should contain DASH_720.mp4 or similar", resolved.videoUrl.contains("DASH_"))
        org.junit.Assert.assertNotNull("Resolved audioUrl should not be null", resolved.audioUrl)
        org.junit.Assert.assertTrue("Resolved audioUrl should contain DASH_audio.mp4", resolved.audioUrl!!.contains("DASH_audio.mp4"))
    }

    @Test
    fun testSubredditScraper() = runBlocking {
        val html = fetchHtml("https://old.reddit.com/r/android/")
        val scraper = com.cosmos.unreddit.data.remote.api.reddit.scraper.SubredditScraper(Dispatchers.Unconfined)
        val child = scraper.scrap(html)
        assertNotNull(child)
        org.junit.Assert.assertTrue("Result should be AboutChild", child is com.cosmos.unreddit.data.remote.api.reddit.model.AboutChild)
        val aboutChild = child as com.cosmos.unreddit.data.remote.api.reddit.model.AboutChild
        println("SubredditScraper displayName: ${aboutChild.data.displayName}")
        println("SubredditScraper title: ${aboutChild.data.title}")
        println("SubredditScraper subscribers: ${aboutChild.data.subscribers}")
        println("SubredditScraper descriptionHtml: ${aboutChild.data.descriptionHtml}")
        org.junit.Assert.assertEquals("Android", aboutChild.data.displayName)
        org.junit.Assert.assertNotNull("Title should not be null", aboutChild.data.title)
    }

    @Test
    fun testGetRedditVideoMpdUrl() {
        val mpdUrl = LinkUtil.getRedditVideoMpdUrl("https://v.redd.it/mmpzzcrb15p91")
        org.junit.Assert.assertEquals("https://v.redd.it/mmpzzcrb15p91/DASHPlaylist.mpd", mpdUrl)
    }

    @Test
    fun testLinkClassificationAndPreprocessing() = runBlocking {
        // Test LinkUtil classification
        val redditsaveLink = "https://redditsave.com/info?url=https://i.redd.it/something.png"
        val directImageLink = "https://example.com/foo.png"
        val directGifLink = "https://example.com/foo.gif"
        
        org.junit.Assert.assertEquals(MediaType.LINK, LinkUtil.getLinkType(redditsaveLink))
        org.junit.Assert.assertEquals(MediaType.IMAGE, LinkUtil.getLinkType(directImageLink))
        org.junit.Assert.assertEquals(MediaType.IMAGE, LinkUtil.getLinkType(directGifLink))

        // Test HtmlParser preprocessing
        val htmlParser = com.cosmos.unreddit.util.HtmlParser(Dispatchers.Unconfined)
        
        // 1. Redditsave link
        val html1 = """<p>Here is a <a href="$redditsaveLink">Redditsave</a> link.</p>"""
        val preprocessed1 = htmlParser.preprocessCommentHtml(html1)
        org.junit.Assert.assertTrue("Redditsave link text should be preserved", preprocessed1.contains("Redditsave"))
        org.junit.Assert.assertFalse("Redditsave should not have an expand all link", preprocessed1.contains("expand_all:"))

        // 2. Image link
        val html2 = """<p>Here is a <a href="$directImageLink"><img src="$directImageLink" /></a> image.</p>"""
        val preprocessed2 = htmlParser.preprocessCommentHtml(html2)
        // Image link should be replaced by "<image> <expand all>"
        org.junit.Assert.assertTrue("Direct image link should show image placeholders", preprocessed2.contains("&lt;image&gt;"))
        org.junit.Assert.assertTrue("Direct image link should have an expand all link", preprocessed2.contains("expand_all:"))
    }

    @Test
    fun testTextDrawable() {
        val textDrawable = com.cosmos.unreddit.ui.common.TextDrawable("txt", 0, 0)
        assertNotNull(textDrawable)
    }
}
