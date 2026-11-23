package com.cosmos.unreddit.data.remote.api.reddit.source

import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.model.TimeSorting
import com.cosmos.unreddit.data.remote.api.reddit.RedditApi
import com.cosmos.unreddit.data.remote.api.reddit.RedditCookieJar
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutChildJsonAdapter
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutUserChild
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutUserChildJsonAdapter
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutUserData
import com.cosmos.unreddit.data.remote.api.reddit.model.Child
import com.cosmos.unreddit.data.remote.api.reddit.model.Listing
import com.cosmos.unreddit.data.remote.api.reddit.model.ListingData
import com.cosmos.unreddit.data.remote.api.reddit.model.ListingJsonAdapter
import com.cosmos.unreddit.data.remote.api.reddit.model.MoreChildren
import com.cosmos.unreddit.data.remote.api.reddit.model.MoreChildrenJsonAdapter
import com.cosmos.unreddit.data.remote.api.reddit.model.PostChild
import com.cosmos.unreddit.data.remote.api.reddit.scraper.Over18Scraper
import com.cosmos.unreddit.data.remote.api.reddit.scraper.ShredditCommentScraper
import com.cosmos.unreddit.data.remote.api.reddit.scraper.ShredditSearchScraper
import com.cosmos.unreddit.data.remote.api.reddit.scraper.UserScaper
import com.cosmos.unreddit.di.DispatchersModule.IoDispatcher
import com.cosmos.unreddit.di.NetworkModule.RedditMoshi
import com.cosmos.unreddit.di.NetworkModule.RedditOfficial
import com.cosmos.unreddit.di.NetworkModule.RedditScrap
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RedditSource @Inject constructor(
    @RedditOfficial private val redditApi: RedditApi,
    @RedditScrap private val shredditApi: RedditApi,
    private val cookieJar: RedditCookieJar,
    @RedditMoshi moshi: Moshi,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : BaseRedditSource {

    private val listingAdapter = ListingJsonAdapter(moshi)
    private val aboutChildAdapter = AboutChildJsonAdapter(moshi)
    private val aboutUserChildAdapter = AboutUserChildJsonAdapter(moshi)
    private val moreChildrenAdapter = MoreChildrenJsonAdapter(moshi)

    override suspend fun getSubreddit(
        subreddit: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withContext(ioDispatcher) {
        val normalized = subreddit.trim()
        val isAll = normalized.equals("all", ignoreCase = true)
        val isPopular = normalized.equals("popular", ignoreCase = true)

        val response = when {
            (isAll || isPopular) && (sort == Sort.NEW || sort == Sort.RISING) -> {
                redditApi.getFrontpage(sort, timeSorting, after)
            }
            isAll -> {
                redditApi.getSubreddit("all-none", sort, timeSorting, after)
            }
            else -> {
                val encodedSubreddit = normalized.replace("+", "%2B")
                redditApi.getSubreddit(encodedSubreddit, sort, timeSorting, after)
            }
        }
        listingAdapter.fromJson(response.source()) ?: throw IOException()
    }

    override suspend fun getSubredditInfo(subreddit: String): Child = withContext(ioDispatcher) {
        val response = redditApi.getSubredditInfo(subreddit)
        aboutChildAdapter.fromJson(response.source()) ?: throw IOException()
    }

    override suspend fun searchInSubreddit(
        subreddit: String,
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withContext(ioDispatcher) {
        val encodedSubreddit = subreddit.replace("+", "%2B")
        try {
            val response = redditApi.searchInSubreddit(
                subreddit = encodedSubreddit,
                query = query,
                sort = sort,
                timeSorting = timeSorting,
                after = after
            )
            listingAdapter.fromJson(response.source()) ?: throw IOException()
        } catch (_: Exception) {
            val response = shredditApi.searchInSubredditShreddit(
                subreddit = encodedSubreddit,
                query = query,
                sort = sort,
                timeSorting = timeSorting,
                cursor = after
            )
            resolvePostSearchListing(response.string())
        }
    }

    override suspend fun getPost(permalink: String, limit: Int?, sort: Sort): List<Listing> =
        withContext(ioDispatcher) {
            val cleanPermalink = permalink.substringBefore("?").trim('/')
            val segments = cleanPermalink.split("/").filter { it.isNotBlank() }
            val commentsIdx = segments.indexOf("comments")

            val postId: String
            val commentId: String?

            if (commentsIdx != -1 && commentsIdx + 1 < segments.size) {
                postId = segments[commentsIdx + 1].removePrefix("t3_")
                commentId = if (commentsIdx + 3 < segments.size) {
                    segments[commentsIdx + 3].removePrefix("t1_").takeIf { it.isNotBlank() }
                } else {
                    null
                }
            } else {
                postId = segments.lastOrNull()?.removePrefix("t3_")
                    ?: throw IOException("Invalid permalink")
                commentId = null
            }

            val fullPostId = "t3_$postId"
            val postResponse = redditApi.getPostById(fullPostId)
            val postListing = listingAdapter.fromJson(postResponse.source()) ?: throw IOException()
            val postData = (postListing.data.children.firstOrNull() as? PostChild)?.data
                ?: throw IOException("Post not found")

            val shredditUrl = buildString {
                append("https://www.reddit.com/svc/shreddit/comments/r/")
                append(postData.subreddit)
                append("/")
                append(fullPostId)
                if (commentId != null) {
                    append("/t1_")
                    append(commentId)
                }
            }

            val commentsListing = try {
                val commentsResponse = shredditApi.getShredditComments(shredditUrl, sort)
                val html = commentsResponse.string()
                val isBlockedOrChallenge = !html.contains("shreddit-comment", ignoreCase = true) &&
                    (html.contains("blocked by network security", ignoreCase = true) ||
                        html.contains("namedItem(\"solution\")"))
                if (isBlockedOrChallenge) {
                    Listing(
                        "Listing",
                        ListingData(
                            COMMENT_FETCH_ERROR_MARKER,
                            403,
                            emptyList(),
                            "Blocked by Reddit verification",
                            null
                        )
                    )
                } else {
                    ShredditCommentScraper(
                        ioDispatcher = ioDispatcher,
                        linkId = postData.name,
                        subredditPrefixed = postData.prefixedSubreddit
                    ).scrap(html)
                }
            } catch (e: retrofit2.HttpException) {
                Listing(
                    "Listing",
                    ListingData(
                        COMMENT_FETCH_ERROR_MARKER,
                        e.code(),
                        emptyList(),
                        e.message(),
                        null
                    )
                )
            } catch (e: Exception) {
                Listing(
                    "Listing",
                    ListingData(
                        COMMENT_FETCH_ERROR_MARKER,
                        null,
                        emptyList(),
                        e.message,
                        null
                    )
                )
            }

            listOf(postListing, commentsListing)
        }

    override suspend fun getMoreChildren(children: String, linkId: String): MoreChildren =
        withContext(ioDispatcher) {
            if (children.contains("|")) {
                val parts = children.split("|")
                if (parts.size < 4) throw IOException("Invalid more-comments token")
                val morePath = parts[0]
                val cursor = parts[1]
                val parentId = parts[2]
                val depth = parts[3].toIntOrNull() ?: 0

                val fullUrl = buildString {
                    if (morePath.startsWith("http")) {
                        append(morePath)
                    } else {
                        append("https://www.reddit.com")
                        if (!morePath.startsWith("/")) append("/")
                        append(morePath)
                    }
                    if (!morePath.contains("render-mode=")) {
                        append(if (morePath.contains("?")) "&" else "?")
                        append("render-mode=partial")
                    }
                }

                val subreddit = morePath.substringAfter("/more-comments/", "")
                    .removePrefix("r/")
                    .substringBefore("/")
                    .substringBefore("?")
                    .ifBlank { "all" }

                if (cookieJar.getCookieValue("csrf_token").isNullOrEmpty()) {
                    try {
                        shredditApi.getSubredditPage("https://www.reddit.com/r/$subreddit/")
                    } catch (_: Exception) {
                    }
                }

                val csrfToken = cookieJar.getCookieValue("csrf_token").orEmpty()
                val formBody = FormBody.Builder()
                    .add("cursor", cursor)
                    .add("csrf_token", csrfToken)
                    .build()

                val response = shredditApi.getShredditMoreComments(fullUrl, formBody)
                val fullLinkId = if (linkId.startsWith("t3_")) linkId else "t3_$linkId"
                ShredditCommentScraper(
                    ioDispatcher = ioDispatcher,
                    linkId = fullLinkId,
                    subredditPrefixed = "r/$subreddit",
                    fallbackParentId = parentId,
                    fallbackDepth = depth
                ).scrapMoreChildren(response.string())
            } else {
                val response = redditApi.getMoreChildren(children, linkId)
                moreChildrenAdapter.fromJson(response.source()) ?: throw IOException()
            }
        }

    override suspend fun getUserInfo(user: String): Child = withContext(ioDispatcher) {
        try {
            val response = redditApi.getUserInfo(user)
            val parsed = aboutUserChildAdapter.fromJson(response.source()) ?: throw IOException()
            if (!parsed.data.isSuspended) {
                return@withContext parsed
            }
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 404) throw e
        } catch (_: Exception) {
        }

        try {
            val scrapResponse = shredditApi.getUserPosts(user, Sort.HOT, null)
            val over18Scraper = Over18Scraper(ioDispatcher)
            val dest = over18Scraper.scrap(scrapResponse.string())
            val scrapedChild = if (dest == null) {
                val doc = over18Scraper.document ?: error("Document cannot be null")
                UserScaper(ioDispatcher).scrap(doc, null)
            } else {
                val requestBody = FormBody.Builder().add("over18", "yes").build()
                val consentResponse = shredditApi.consentOver18(requestBody, dest)
                UserScaper(ioDispatcher).scrap(null, consentResponse.string())
            } as? AboutUserChild

            if (scrapedChild != null && scrapedChild.data.name.isNotBlank()) {
                return@withContext scrapedChild
            }
        } catch (_: Exception) {
        }

        AboutUserChild(
            AboutUserData(
                isSuspended = false,
                isEmployee = false,
                subreddit = null,
                id = null,
                iconImg = null,
                linkKarma = 0,
                totalKarma = 0,
                name = user,
                created = 0L,
                snoovatarImg = null,
                commentKarma = 0
            )
        )
    }

    override suspend fun getUserPosts(
        user: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withContext(ioDispatcher) {
        val response = redditApi.getUserPosts(user, sort, timeSorting, after)
        listingAdapter.fromJson(response.source()) ?: throw IOException()
    }

    override suspend fun getUserComments(
        user: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withContext(ioDispatcher) {
        val response = redditApi.getUserComments(user, sort, timeSorting, after)
        listingAdapter.fromJson(response.source()) ?: throw IOException()
    }

    override suspend fun searchPost(
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withContext(ioDispatcher) {
        try {
            val response = redditApi.searchPost(query, sort, timeSorting, after)
            listingAdapter.fromJson(response.source()) ?: throw IOException()
        } catch (_: Exception) {
            val response = shredditApi.searchShreddit(
                query = query,
                type = "posts",
                sort = sort,
                timeSorting = timeSorting,
                cursor = after
            )
            resolvePostSearchListing(response.string())
        }
    }

    override suspend fun searchUser(
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withContext(ioDispatcher) {
        try {
            val response = redditApi.searchUser(query, sort, timeSorting, after)
            listingAdapter.fromJson(response.source()) ?: throw IOException()
        } catch (_: Exception) {
            val response = shredditApi.searchShreddit(
                query = query,
                type = "people",
                sort = sort,
                timeSorting = timeSorting,
                cursor = after
            )
            ShredditSearchScraper(ioDispatcher).scrapUserSearch(response.string())
        }
    }

    override suspend fun searchSubreddit(
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withContext(ioDispatcher) {
        try {
            val response = redditApi.searchSubreddit(query, sort, timeSorting, after)
            listingAdapter.fromJson(response.source()) ?: throw IOException()
        } catch (_: Exception) {
            val response = shredditApi.searchShreddit(
                query = query,
                type = "communities",
                sort = sort,
                timeSorting = timeSorting,
                cursor = after
            )
            ShredditSearchScraper(ioDispatcher).scrapSubredditSearch(response.string())
        }
    }

    private suspend fun resolvePostSearchListing(html: String): Listing = coroutineScope {
        val searchResult = ShredditSearchScraper(ioDispatcher).scrapPostSearch(html)
        val children = searchResult.postIds.map { postId ->
            async {
                val oauthPost = runCatching {
                    val postResponse = redditApi.getPostById(postId)
                    val postListing = listingAdapter.fromJson(postResponse.source())
                    postListing?.data?.children?.firstOrNull() as? PostChild
                }.getOrNull()
                oauthPost ?: searchResult.fallbackPosts[postId]
            }
        }.awaitAll().filterNotNull()

        Listing(
            "Listing",
            ListingData(
                modhash = null,
                dist = children.size,
                children = children,
                after = searchResult.after,
                before = null
            )
        )
    }

    companion object {
        const val COMMENT_FETCH_ERROR_MARKER = "COMMENT_FETCH_ERROR"
    }
}
