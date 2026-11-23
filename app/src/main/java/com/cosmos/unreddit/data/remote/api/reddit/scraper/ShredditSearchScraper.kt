package com.cosmos.unreddit.data.remote.api.reddit.scraper

import com.cosmos.unreddit.data.remote.api.reddit.model.AboutChild
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutData
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutUserChild
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutUserData
import com.cosmos.unreddit.data.remote.api.reddit.model.Listing
import com.cosmos.unreddit.data.remote.api.reddit.model.ListingData
import com.cosmos.unreddit.data.remote.api.reddit.model.PostChild
import com.cosmos.unreddit.data.remote.api.reddit.model.PostData
import com.cosmos.unreddit.data.remote.api.reddit.model.Subreddit
import com.cosmos.unreddit.util.extension.toSeconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ShredditSearchScraper(
    private val ioDispatcher: CoroutineDispatcher
) {

    data class PostSearchResult(
        val postIds: List<String>,
        val fallbackPosts: Map<String, PostChild>,
        val after: String?
    )

    suspend fun scrapPostSearch(html: String): PostSearchResult = withContext(ioDispatcher) {
        val document = Jsoup.parse(html)
        val postUnits = document.select("div[data-testid=sdui-post-unit]")
        val postIds = mutableListOf<String>()
        val fallbackPosts = mutableMapOf<String, PostChild>()

        for (unit in postUnits) {
            val fallback = unit.toFallbackPostChild()
            if (fallback != null) {
                val id = fallback.data.name
                if (id !in fallbackPosts) {
                    postIds.add(id)
                    fallbackPosts[id] = fallback
                }
            }
        }

        if (postIds.isEmpty()) {
            POST_ID_REGEX.findAll(html)
                .mapNotNull { it.groupValues.getOrNull(1)?.takeIf { id -> id.isNotBlank() } }
                .distinct()
                .forEach { postIds.add(it) }
        }

        val after = extractNextCursor(document)
        PostSearchResult(postIds, fallbackPosts, after)
    }

    suspend fun scrapSubredditSearch(html: String): Listing = withContext(ioDispatcher) {
        val document = Jsoup.parse(html)
        val elements = document.select("div[data-testid=search-community]")
        val children = elements.mapNotNull { it.toSubredditChild() }
        val after = extractNextCursor(document)

        Listing(
            KIND_LISTING,
            ListingData(
                modhash = null,
                dist = children.size,
                children = children,
                after = after,
                before = null
            )
        )
    }

    suspend fun scrapUserSearch(html: String): Listing = withContext(ioDispatcher) {
        val document = Jsoup.parse(html)
        val elements = document.select("div[data-testid=search-author]")
        val children = elements.mapNotNull { it.toUserChild() }
        val after = extractNextCursor(document)

        Listing(
            KIND_LISTING,
            ListingData(
                modhash = null,
                dist = children.size,
                children = children,
                after = after,
                before = null
            )
        )
    }

    private fun extractNextCursor(document: Document): String? {
        val partial = document.selectFirst("faceplate-partial[src*=/search]") ?: return null
        val src = partial.attr("src").trim().takeIf { it.isNotBlank() } ?: return null
        val fullUrl = if (src.startsWith("http")) {
            src
        } else {
            "https://www.reddit.com" + if (src.startsWith("/")) src else "/$src"
        }
        return fullUrl.toHttpUrlOrNull()?.queryParameter("cursor")
    }

    private fun Element.toSubredditChild(): AboutChild? {
        val trackingContext = selectFirst("search-telemetry-tracker")
            ?.attr("data-faceplate-tracking-context")
            .orEmpty()
        val joinButton = selectFirst("shreddit-join-button")
        val titleSpan = selectFirst("[data-testid=search-community-title]")
        val linkElement = selectFirst("a[href]")

        val displayName = joinButton?.attr("name")?.trim()?.takeIf { it.isNotBlank() }
            ?: titleSpan?.text()?.trim()?.removePrefix("r/")?.takeIf { it.isNotBlank() }
            ?: linkElement?.attr("href")
                ?.substringAfter("/r/", "")
                ?.substringBefore("/")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: SUBREDDIT_NAME_REGEX.find(trackingContext)?.groupValues?.getOrNull(1)
            ?: return null

        val url = linkElement?.attr("href")?.takeIf { it.isNotBlank() } ?: "/r/$displayName/"
        val icon = selectFirst("img")?.attr("src").orEmpty()
        val description = selectFirst("[data-testid=search-subreddit-desc-text]")
            ?.text()
            ?.trim()
            .orEmpty()
        val subscribers = selectFirst("faceplate-number[number]")
            ?.attr("number")
            ?.toIntOrNull()
        val over18 = trackingContext.contains("\"nsfw\":true")
        val quarantine = trackingContext.contains("\"quarantined\":true")

        val data = AboutData(
            wikiEnabled = null,
            displayName = displayName,
            headerImg = null,
            title = description,
            primaryColor = null,
            activeUserCount = null,
            iconImg = icon.ifEmpty { null },
            subscribers = subscribers,
            quarantine = quarantine,
            publicDescriptionHtml = if (description.isNotBlank()) "<p>$description</p>" else null,
            communityIcon = icon,
            bannerBackgroundImage = "",
            keyColor = null,
            bannerBackgroundColor = null,
            over18 = over18,
            descriptionHtml = null,
            url = url,
            created = 0L
        )

        return AboutChild(data)
    }

    private fun Element.toUserChild(): AboutUserChild? {
        val trackingContext = selectFirst("search-telemetry-tracker")
            ?.attr("data-faceplate-tracking-context")
            .orEmpty()
        val linkElement = selectFirst("a[href]")
        val nameplate = selectFirst("[data-testid=nameplate]")

        val username = nameplate?.text()?.trim()?.removePrefix("u/")?.takeIf { it.isNotBlank() }
            ?: linkElement?.attr("href")
                ?.removePrefix("/user/")
                ?.removePrefix("/u/")
                ?.substringBefore("/")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: PROFILE_NAME_REGEX.find(trackingContext)?.groupValues?.getOrNull(1)
            ?: return null

        val userId = PROFILE_ID_REGEX.find(trackingContext)?.groupValues?.getOrNull(1)
        val iconImg = selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") }
        val karma = selectFirst("faceplate-number[number]")
            ?.attr("number")
            ?.toIntOrNull()
            ?: 0
        val bio = selectFirst("[data-testid=search-subreddit-desc-text]")
            ?.text()
            ?.trim()
            .orEmpty()
        val over18 = trackingContext.contains("\"nsfw\":true")

        val sub = Subreddit(
            bannerImg = null,
            communityIcon = iconImg,
            iconColor = "",
            headerImg = null,
            title = bio,
            over18 = over18,
            primaryColor = "",
            iconImg = iconImg.orEmpty(),
            description = bio,
            subscribers = 0,
            displayNamePrefixed = "u/$username",
            keyColor = "",
            name = "u_$username",
            isDefaultBanner = true,
            url = "/user/$username/",
            publicDescription = bio
        )

        val data = AboutUserData(
            isSuspended = false,
            isEmployee = false,
            subreddit = sub,
            id = userId,
            iconImg = iconImg,
            linkKarma = karma,
            totalKarma = karma,
            name = username,
            created = 0L,
            snoovatarImg = null,
            commentKarma = 0
        )

        return AboutUserChild(data)
    }

    private fun Element.toFallbackPostChild(): PostChild? {
        val trackingContext = selectFirst("search-telemetry-tracker")
            ?.attr("data-faceplate-tracking-context")
            .orEmpty()
        val titleLink = selectFirst("a[data-testid=post-title-text]")
        val postId = POST_ID_REGEX.find(trackingContext)?.groupValues?.getOrNull(1)
            ?: titleLink?.attr("id")?.removePrefix("search-post-title-")?.takeIf { it.startsWith("t3_") }
            ?: return null

        val title = titleLink?.text()?.trim().orEmpty()
        val permalink = titleLink?.attr("href")?.trim().orEmpty()
        val subreddit = SUBREDDIT_NAME_REGEX.find(trackingContext)?.groupValues?.getOrNull(1)
            ?: permalink.substringAfter("/r/", "").substringBefore("/").ifBlank { "all" }
        val prefixedSubreddit = "r/$subreddit"
        val author = PROFILE_NAME_REGEX.find(trackingContext)?.groupValues?.getOrNull(1)
            ?: "[deleted]"
        val isOver18 = trackingContext.contains("\"nsfw\":true")
        val isSpoiler = trackingContext.contains("\"spoiler\":true")

        val createdAttr = selectFirst("faceplate-timeago")?.attr("ts").orEmpty()
        val created = parseIsoTimestampSeconds(createdAttr)

        val counterNumbers = select("[data-testid=search-counter-row] faceplate-number[number]")
        val score = counterNumbers.getOrNull(0)?.attr("number")?.toIntOrNull() ?: 0
        val commentsNumber = counterNumbers.getOrNull(1)?.attr("number")?.toIntOrNull() ?: 0

        val thumbnailImg = selectFirst("[data-testid=search_post_thumbnail]")?.attr("src")

        val postData = PostData(
            subreddit = subreddit,
            linkFlairRichText = emptyList(),
            authorFlairRichText = null,
            title = title,
            prefixedSubreddit = prefixedSubreddit,
            name = postId,
            ratio = null,
            totalAwards = 0,
            isOC = false,
            flair = null,
            authorFlair = null,
            galleryData = null,
            score = score,
            hint = if (thumbnailImg != null) "image" else null,
            isSelf = thumbnailImg == null,
            crossposts = null,
            domain = "self.$subreddit",
            selfTextHtml = null,
            suggestedSort = null,
            isArchived = false,
            isOver18 = isOver18,
            mediaPreview = null,
            awardings = emptyList(),
            isSpoiler = isSpoiler,
            isLocked = false,
            distinguished = null,
            author = author,
            commentsNumber = commentsNumber,
            permalink = permalink,
            isStickied = false,
            url = permalink,
            created = created,
            media = null,
            mediaMetadata = null,
            isRedditGallery = null,
            isVideo = false
        ).apply {
            this.thumbnail = thumbnailImg
        }

        return PostChild(postData)
    }

    private fun parseIsoTimestampSeconds(iso: String): Long {
        if (iso.isBlank()) return 0L
        for (pattern in ISO_PATTERNS) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val date = sdf.parse(iso)
                if (date != null) {
                    return date.time.toSeconds()
                }
            } catch (_: Exception) {
            }
        }
        return 0L
    }

    companion object {
        private const val KIND_LISTING = "Listing"

        private val POST_ID_REGEX = Regex(
            """(?:&quot;|")post(?:&quot;|")\s*:\s*\{[^}]*?(?:&quot;|")id(?:&quot;|")\s*:\s*(?:&quot;|")(t3_[a-z0-9]+)(?:&quot;|")"""
        )
        private val SUBREDDIT_NAME_REGEX = Regex(
            """(?:&quot;|")subreddit(?:&quot;|")\s*:\s*\{[^}]*?(?:&quot;|")name(?:&quot;|")\s*:\s*(?:&quot;|")([^"&]+)(?:&quot;|")"""
        )
        private val PROFILE_NAME_REGEX = Regex(
            """(?:&quot;|")profile(?:&quot;|")\s*:\s*\{[^}]*?(?:&quot;|")name(?:&quot;|")\s*:\s*(?:&quot;|")([^"&]+)(?:&quot;|")"""
        )
        private val PROFILE_ID_REGEX = Regex(
            """(?:&quot;|")profile(?:&quot;|")\s*:\s*\{[^}]*?(?:&quot;|")id(?:&quot;|")\s*:\s*(?:&quot;|")(t2_[a-z0-9]+)(?:&quot;|")"""
        )

        private val ISO_PATTERNS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSZ",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
    }
}
