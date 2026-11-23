package com.cosmos.unreddit.scraper

import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.remote.RawJsonInterceptor
import com.cosmos.unreddit.data.remote.api.reddit.RedditApi
import com.cosmos.unreddit.data.remote.api.reddit.RedditAuthInterceptor
import com.cosmos.unreddit.data.remote.api.reddit.RedditCookieJar
import com.cosmos.unreddit.data.remote.api.reddit.RedditTokenManager
import com.cosmos.unreddit.data.remote.api.reddit.SortingConverterFactory
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutChild
import com.cosmos.unreddit.data.remote.api.reddit.model.CommentChild
import com.cosmos.unreddit.data.remote.api.reddit.model.MoreChild
import com.cosmos.unreddit.data.remote.api.reddit.model.PostChild
import com.cosmos.unreddit.data.remote.api.reddit.scraper.ShredditCommentScraper
import com.cosmos.unreddit.data.remote.api.reddit.source.RedditSource
import com.cosmos.unreddit.di.NetworkModule
import com.cosmos.unreddit.util.LinkUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class ScraperTest {

    private fun createRedditSource(): RedditSource {
        val moshi = NetworkModule.provideRedditMoshi()
        val genericClient = NetworkModule.provideGenericOkHttpClient()
        val tokenManager = RedditTokenManager(genericClient)
        val oauthClient = OkHttpClient.Builder()
            .addInterceptor(RawJsonInterceptor())
            .addInterceptor(RedditAuthInterceptor(tokenManager))
            .build()
        val cookieJar = RedditCookieJar()
        val scrapClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", LinkUtil.USER_AGENT)
                    .build()
                chain.proceed(req)
            }
            .cookieJar(cookieJar)
            .build()
        val redditApi = Retrofit.Builder()
            .baseUrl(RedditApi.BASE_URL_OAUTH)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .addConverterFactory(SortingConverterFactory())
            .client(oauthClient)
            .build()
            .create(RedditApi::class.java)
        val shredditApi = Retrofit.Builder()
            .baseUrl(RedditApi.BASE_URL)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .addConverterFactory(SortingConverterFactory())
            .client(scrapClient)
            .build()
            .create(RedditApi::class.java)
        return RedditSource(redditApi, shredditApi, cookieJar, moshi, Dispatchers.Unconfined)
    }

    @Test
    fun testShredditCommentScraperUnit() = runBlocking {
        val sampleHtml = """
            <shreddit-comment thingId="t1_abc1234" author="alice" score="42" depth="0" postId="t3_xyz987" permalink="/r/Android/comments/xyz987/test/abc1234/" created="2026-10-25T12:00:00.000Z">
                <div slot="commentMeta">
                    <shreddit-comment-author-flair>Pixel User</shreddit-comment-author-flair>
                </div>
                <div id="t1_abc1234-comment-rtjson-content" slot="comment">
                    <p>Top level comment</p>
                </div>
                <shreddit-comment thingId="t1_def5678" parentId="t1_abc1234" author="bob" score="10" depth="1" is-op postId="t3_xyz987" permalink="/r/Android/comments/xyz987/test/def5678/" created="2026-10-25T12:05:00.000Z">
                    <div id="t1_def5678-comment-rtjson-content" slot="comment">
                        <p>Nested reply</p>
                    </div>
                </shreddit-comment>
                <faceplate-partial src="/svc/shreddit/more-comments/Android/t3_xyz987?startingDepth=1&amp;comments-remaining=5">
                    <input name="cursor" value="cursor123" />
                    <faceplate-number number="5"></faceplate-number>
                </faceplate-partial>
            </shreddit-comment>
        """.trimIndent()

        val scraper = ShredditCommentScraper(Dispatchers.Unconfined, "t3_xyz987", "r/Android")
        val listing = scraper.scrap(sampleHtml)
        val topChildren = listing.data.children
        assertEquals(1, topChildren.size)

        val topComment = (topChildren[0] as CommentChild).data
        assertEquals("t1_abc1234", topComment.name)
        assertEquals("alice", topComment.author)
        assertEquals(42, topComment.score)
        assertEquals("Pixel User", topComment.flair)
        assertNotNull(topComment.replies)

        val replies = topComment.replies!!.data.children
        assertEquals(2, replies.size)
        val nestedComment = (replies[0] as CommentChild).data
        assertEquals("t1_def5678", nestedComment.name)
        assertTrue(nestedComment.isSubmitter)
        assertEquals(1, nestedComment.depth)

        val moreChild = (replies[1] as MoreChild).data
        assertEquals(5, moreChild.count)
        assertEquals("t1_abc1234", moreChild.parentId)
        assertEquals(1, moreChild.depth)
        assertTrue(moreChild.children.first().contains("|cursor123|t1_abc1234|1"))
    }

    @Test
    fun testPostSearchViaOAuth() = runBlocking {
        val source = createRedditSource()
        val listing = source.searchPost("spez", Sort.RELEVANCE, com.cosmos.unreddit.data.model.TimeSorting.ALL, null)
        assertNotNull(listing)
        val children = listing.data.children
        println("RedditSource.searchPost found ${children.size} posts, after=${listing.data.after}")
        assertFalse("Post search children list should not be empty", children.isEmpty())
        assertNotNull("Post search after cursor should not be null", listing.data.after)

        val inSrListing = source.searchInSubreddit("android", "pixel", Sort.RELEVANCE, com.cosmos.unreddit.data.model.TimeSorting.ALL, null)
        assertNotNull(inSrListing)
        println("RedditSource.searchInSubreddit found ${inSrListing.data.children.size} posts, after=${inSrListing.data.after}")
        assertFalse("In-subreddit search children list should not be empty", inSrListing.data.children.isEmpty())
        assertNotNull("In-subreddit search after cursor should not be null", inSrListing.data.after)
    }

    @Test
    fun testSubredditSearchViaOAuth() = runBlocking {
        val source = createRedditSource()
        val listing = source.searchSubreddit("android", Sort.RELEVANCE, com.cosmos.unreddit.data.model.TimeSorting.ALL, null)
        assertNotNull(listing)
        val children = listing.data.children
        println("RedditSource.searchSubreddit found ${children.size} subreddits, after=${listing.data.after}")
        assertFalse("Subreddit search children list should not be empty", children.isEmpty())
        assertNotNull("Subreddit search after cursor should not be null", listing.data.after)

        val userListing = source.searchUser("spez", Sort.RELEVANCE, com.cosmos.unreddit.data.model.TimeSorting.ALL, null)
        assertNotNull(userListing)
        println("RedditSource.searchUser found ${userListing.data.children.size} users, after=${userListing.data.after}")
        assertFalse("User search children list should not be empty", userListing.data.children.isEmpty())
        assertNotNull("User search after cursor should not be null", userListing.data.after)
    }

    @Test
    fun testSubredditAndPostAndCommentsViaOAuth() = runBlocking {
        val source = createRedditSource()
        val aboutChild = source.getSubredditInfo("android") as AboutChild
        assertEquals("Android", aboutChild.data.displayName)
        assertNotNull(aboutChild.data.title)

        val listing = source.getSubreddit("android", Sort.HOT, null, null)
        assertFalse(listing.data.children.isEmpty())
        val firstPost = (listing.data.children.first { it is PostChild } as PostChild).data

        val postListings = source.getPost(firstPost.permalink, null, Sort.BEST)
        assertEquals(2, postListings.size)
        val fetchedPost = (postListings[0].data.children.first() as PostChild).data
        assertEquals(firstPost.name, fetchedPost.name)
        println("Fetched post '${fetchedPost.title}' with ${postListings[1].data.children.size} top-level comment items")
    }

    @Test
    fun testMultiSubredditHomeFeedViaOAuth() = runBlocking {
        val source = createRedditSource()
        val listing = source.getSubreddit("android+kotlin+linux", Sort.HOT, null, null)
        assertNotNull(listing)
        val posts = listing.data.children.filterIsInstance<PostChild>()
        assertFalse("Multi-subreddit home feed should not be empty", posts.isEmpty())
        val subreddits = posts.map { it.data.subreddit.lowercase() }.toSet()
        println("Multi-subreddit feed returned ${posts.size} posts from subreddits: $subreddits")
        assertTrue("Expected posts from multiple subreddits, got $subreddits", subreddits.size >= 2)
    }

    @Test
    fun testAllFeedViaOAuth() = runBlocking {
        val source = createRedditSource()

        val hotListing = source.getSubreddit("all", Sort.HOT, null, null)
        val hotPosts = hotListing.data.children.filterIsInstance<PostChild>()
        assertFalse("r/all hot should not be empty", hotPosts.isEmpty())
        assertNotNull("r/all hot after cursor should not be null", hotListing.data.after)

        val page2Listing = source.getSubreddit("all", Sort.HOT, null, hotListing.data.after)
        assertFalse("r/all hot page 2 should not be empty", page2Listing.data.children.isEmpty())

        val topListing = source.getSubreddit("all", Sort.TOP, com.cosmos.unreddit.data.model.TimeSorting.DAY, null)
        assertFalse("r/all top should not be empty", topListing.data.children.isEmpty())

        val newListing = source.getSubreddit("all", Sort.NEW, null, null)
        assertFalse("r/all new should not be empty", newListing.data.children.isEmpty())

        val risingListing = source.getSubreddit("all", Sort.RISING, null, null)
        assertFalse("r/all rising should not be empty", risingListing.data.children.isEmpty())
    }

    @Test
    fun testRedgifsApi() = runBlocking {
        assertEquals("requireddarkbluenightheron", LinkUtil.getGfycatId("https://www.redgifs.com/watch/RequiredDarkblueNightheron/"))
        assertEquals("alarmedwornbandicoot", LinkUtil.getGfycatId("https://v3.redgifs.com/watch/alarmedwornbandicoot-tag"))

        val source = createRedditSource()
        val listing = source.getSubreddit("TrashyBonersEWW", Sort.HOT, null, null)
        val redgifsPosts = listing.data.children
            .filterIsInstance<PostChild>()
            .map { it.data }
            .filter { it.domain.contains("redgifs") }

        assertFalse("Expected Redgifs posts in listing", redgifsPosts.isEmpty())
        for (post in redgifsPosts) {
            println("POST ${post.name}: domain=${post.domain} url=${post.url} mediaType=${post.mediaType} mediaUrl=${post.mediaUrl}")
            assertTrue(
                "Expected VIDEO or REDDIT_VIDEO mediaType for ${post.url}, got ${post.mediaType}",
                post.mediaType == com.cosmos.unreddit.data.model.MediaType.VIDEO ||
                    post.mediaType == com.cosmos.unreddit.data.model.MediaType.REDDIT_VIDEO
            )
            assertTrue(
                "Expected direct .mp4 mediaUrl for ${post.url}, got ${post.mediaUrl}",
                post.mediaUrl.endsWith(".mp4", ignoreCase = true)
            )
        }
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
            "New video URL should contain CMAF_720.mp4 or fallback DASH_720.mp4",
            newResolved.videoUrl.contains("CMAF_720.mp4") || newResolved.videoUrl.contains("DASH_720.mp4")
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

        // 3. Shreddit comment image with lightbox template should only produce a single image link
        val shredditCommentHtml = """
            <shreddit-comment thingId="t1_imgtest" author="alice" score="10" depth="0" postId="t3_xyz987" permalink="/r/test/comments/xyz987/test/imgtest/" created="2026-10-25T12:00:00.000Z">
                <div id="t1_imgtest-comment-rtjson-content" slot="comment">
                    <p>Check out this image</p>
                    <figure class="rte-media">
                        <div class="w-fit">
                            <a href="$directImageLink" data-comment-image-lightbox aria-haspopup="dialog">
                                <img src="$directImageLink" alt="Comment Image" class="non-lightboxed-content" />
                            </a>
                            <template class="comment-image-lightbox-content">
                                <div class="lightboxed-content media-lightbox-img">
                                    <img alt="Comment Image" class="i18n-post-media-img media-lightbox-img" src="$directImageLink" />
                                </div>
                            </template>
                        </div>
                    </figure>
                </div>
            </shreddit-comment>
        """.trimIndent()
        val scrapedListing = ShredditCommentScraper(Dispatchers.Unconfined, "t3_xyz987", "r/test").scrap(shredditCommentHtml)
        val scrapedBodyHtml = (scrapedListing.data.children.first() as CommentChild).data.bodyHtml
        val preprocessed3 = htmlParser.preprocessCommentHtml(scrapedBodyHtml)
        org.junit.Assert.assertEquals(1, Regex("&lt;image&gt;").findAll(preprocessed3).count())
        org.junit.Assert.assertEquals(1, Regex("expand_all:").findAll(preprocessed3).count())

        // 4. Self post with markdown ![img](<media_id>) syntax and no media_metadata (e.g., t3_1xqobpe)
        val selfPostMarkdownHtml = """
            <!-- SC_OFF --><div class="md"><p>My mom found this plugged into the outdoor outlet behind a Bush, what is it?</p>

            <p>![img](rs5gbhx13b0i1)</p>

            <p>![img](i2hrxhx13b0i1)</p>
            </div><!-- SC_ON -->
        """.trimIndent()
        val preprocessedSelfPost = htmlParser.preprocessCommentHtml(
            selfPostMarkdownHtml,
            mediaMetadata = null,
            includeExpandAll = false
        )
        org.junit.Assert.assertTrue(
            "Markdown ![img](rs5gbhx13b0i1) should resolve to i.redd.it link",
            preprocessedSelfPost.contains("https://i.redd.it/rs5gbhx13b0i1.jpeg")
        )
        org.junit.Assert.assertTrue(
            "Markdown ![img](i2hrxhx13b0i1) should resolve to i.redd.it link",
            preprocessedSelfPost.contains("https://i.redd.it/i2hrxhx13b0i1.jpeg")
        )
        org.junit.Assert.assertEquals(2, Regex("&lt;image&gt;").findAll(preprocessedSelfPost).count())
        org.junit.Assert.assertFalse(
            "Self post preprocessing with includeExpandAll=false should not include expand_all",
            preprocessedSelfPost.contains("expand_all:")
        )

        // 5. Self post with standard preview.redd.it <a> links and media_metadata
        val previewLink = "https://preview.redd.it/mzu9epzu0f0i1.png?width=1119&amp;format=png&amp;auto=webp&amp;s=8c3a5002226386bc8a10ae95a5019db5246f7634"
        val selfPostPreviewHtml = """<div class="md"><p>Text before</p><p><a href="$previewLink">$previewLink</a></p></div>"""
        val preprocessedPreviewPost = htmlParser.preprocessCommentHtml(
            selfPostPreviewHtml,
            mediaMetadata = null,
            includeExpandAll = false
        )
        org.junit.Assert.assertEquals(1, Regex("&lt;image&gt;").findAll(preprocessedPreviewPost).count())
        org.junit.Assert.assertEquals(MediaType.IMAGE, LinkUtil.getLinkType(previewLink.replace("&amp;", "&")))
        org.junit.Assert.assertEquals(
            MediaType.IMAGE,
            LinkUtil.getLinkType("https://preview.redd.it/idWithoutExt?width=1080&format=pjpg&auto=webp&s=123")
        )

        // 6. MediaMetadataAdapter captures map key as GalleryItem.id and resolves markdown ![img](id)
        val moshi = NetworkModule.provideRedditMoshi()
        val metadataJson = """
            {
              "abc123xyz": {
                "status": "valid",
                "e": "Image",
                "m": "image/png",
                "s": {
                  "y": 800,
                  "x": 600,
                  "u": "https://preview.redd.it/abc123xyz.png?width=600&amp;format=png&amp;auto=webp&amp;s=deadbeef"
                }
              }
            }
        """.trimIndent()
        val parsedMetadata = moshi.adapter(com.cosmos.unreddit.data.remote.api.reddit.model.MediaMetadata::class.java)
            .fromJson(metadataJson)
        assertNotNull(parsedMetadata)
        assertEquals("abc123xyz", parsedMetadata!!.items.first().id)
        val preprocessedWithMeta = htmlParser.preprocessCommentHtml(
            "<p>![img](abc123xyz)</p>",
            mediaMetadata = parsedMetadata,
            includeExpandAll = false
        )
        org.junit.Assert.assertTrue(
            "Should use URL from media_metadata when available",
            preprocessedWithMeta.contains("https://preview.redd.it/abc123xyz.png?width=600&amp;format=png&amp;auto=webp&amp;s=deadbeef")
        )

        // 7. Fallback extensions for i.redd.it
        val altUrls = LinkUtil.getAlternativeRedditImageUrls("https://i.redd.it/rs5gbhx13b0i1.jpeg")
        assertTrue(altUrls.contains("https://i.redd.it/rs5gbhx13b0i1.png"))
        assertTrue(altUrls.contains("https://i.redd.it/rs5gbhx13b0i1.webp"))
        assertFalse(altUrls.contains("https://i.redd.it/rs5gbhx13b0i1.jpeg"))
    }

    @Test
    fun testTextDrawable() {
        val textDrawable = com.cosmos.unreddit.ui.common.TextDrawable("txt", 0, 0)
        assertNotNull(textDrawable)
    }

    @Test
    fun testTwitterLinkDetectionAndResolver() = runBlocking {
        val twitterUrl = "https://twitter.com/NASA/status/1880000000000000000"
        val xUrl = "https://x.com/OpenAI/status/123456789"
        val fxUrl = "https://fxtwitter.com/jack/status/20?s=20"
        val nonTwitterUrl = "https://twitter.com/NASA"

        assertTrue(LinkUtil.isTwitterStatusLink(twitterUrl))
        assertTrue(LinkUtil.isTwitterStatusLink(xUrl))
        assertTrue(LinkUtil.isTwitterStatusLink(fxUrl))
        assertFalse(LinkUtil.isTwitterStatusLink(nonTwitterUrl))
        assertEquals("1880000000000000000", LinkUtil.getTweetId(twitterUrl))
        assertEquals("123456789", LinkUtil.getTweetId(xUrl))
        assertEquals("20", LinkUtil.getTweetId(fxUrl))

        val sampleFxJson = """
            {
              "code": 200,
              "message": "OK",
              "tweet": {
                "id": "20",
                "url": "https://twitter.com/jack/status/20",
                "text": "just setting up my twttr",
                "created_at": "Tue Mar 21 20:50:14 +0000 2006",
                "author": {
                  "name": "Jack",
                  "screen_name": "jack",
                  "avatar_url": "https://pbs.twimg.com/profile_images/jack.jpg"
                },
                "replies": 100,
                "retweets": 200,
                "likes": 500,
                "media": {
                  "photos": [
                    { "url": "https://pbs.twimg.com/media/sample.jpg" }
                  ]
                }
              }
            }
        """.trimIndent()

        val parsed = com.cosmos.unreddit.util.TweetResolver.parseFxTwitterResponse(sampleFxJson)
        assertNotNull(parsed)
        assertEquals("20", parsed!!.id)
        assertEquals("Jack", parsed.authorName)
        assertEquals("jack", parsed.authorHandle)
        assertEquals("just setting up my twttr", parsed.text)
        assertEquals(500, parsed.likes)
        assertEquals(200, parsed.retweets)
        assertEquals(100, parsed.replies)
        assertTrue(parsed.hasMedia)
        assertEquals("https://pbs.twimg.com/media/sample.jpg", parsed.previewImageUrl)
        val gallery = parsed.toGalleryMedia()
        assertEquals(1, gallery.size)
        assertEquals("https://pbs.twimg.com/media/sample.jpg", gallery[0].url)
        assertEquals(com.cosmos.unreddit.data.model.GalleryMedia.Type.IMAGE, gallery[0].type)
    }
}
