package com.cosmos.unreddit.data.remote.api.reddit.scraper

import com.cosmos.unreddit.data.remote.api.reddit.model.Listing
import com.cosmos.unreddit.data.remote.api.reddit.model.ListingData
import com.cosmos.unreddit.data.remote.api.reddit.model.PostChild
import com.cosmos.unreddit.data.remote.api.reddit.model.PostData
import com.cosmos.unreddit.data.remote.api.reddit.model.Media
import com.cosmos.unreddit.data.remote.api.reddit.model.RedditVideoPreview
import com.cosmos.unreddit.data.remote.scraper.Scraper
import kotlinx.coroutines.CoroutineDispatcher
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class PostSearchScraper(
    ioDispatcher: CoroutineDispatcher
) : RedditScraper<Listing>(ioDispatcher) {

    override suspend fun scrapDocument(document: Document): Listing {
        val searchResults = document.select("div.search-result-link")
        val children = searchResults.map { it.toSearchPost() }
        val after = getSearchNextKey()

        return Listing(
            KIND,
            ListingData(
                null,
                null,
                children,
                after,
                null
            )
        )
    }

    private fun getSearchNextKey(): String? {
        return document?.selectFirst("a[rel~=next]")
            ?.attr(Scraper.Selector.Attr.HREF)
            ?.toHttpUrlOrNull()
            ?.queryParameter("after")
    }

    private fun Element.toSearchPost(): PostChild {
        val name = attr("data-fullname")
        
        val titleLink = selectFirst("a.search-title")
        val title = titleLink?.text().orEmpty()
        val permalink = titleLink?.attr("href")?.toRelativeUrl().orEmpty()

        val subredditLink = selectFirst("a.search-subreddit-link")
        val prefixedSubreddit = subredditLink?.text().orEmpty()
        val subreddit = prefixedSubreddit.removePrefix("r/").removePrefix("u/")

        val scoreText = selectFirst("span.search-score")?.text().orEmpty()
        val score = SCORE_REGEX.find(scoreText)?.value?.replace(",", "")?.toIntOrNull() ?: 0

        val commentsText = selectFirst("a.search-comments")?.text().orEmpty()
        val commentsNumber = COMMENTS_REGEX.find(commentsText)?.value?.replace(",", "")?.toIntOrNull() ?: 0

        val author = selectFirst("span.search-author a.author")?.text()
            ?: selectFirst("a.author")?.text()
            ?: "[deleted]"

        val timeElement = selectFirst("time")
        val created = timeElement?.toTimeInSeconds() ?: 0L

        val externalLink = selectFirst("a.search-link")
        val isSelf = externalLink == null
        val url = externalLink?.attr("href") ?: titleLink?.attr("href")?.toRelativeUrl() ?: ""
        
        val domain = externalLink?.attr("href")?.toHttpUrlOrNull()?.host
            ?: if (isSelf) "self.$subreddit" else ""

        val thumbnail = selectFirst("a.thumbnail img")?.attr("src")?.toValidLink()

        val isOver18 = selectFirst("span.nsfw-stamp") != null
        val isSpoiler = selectFirst("span.spoiler-stamp") != null

        val isVideo = domain == "v.redd.it" || selectFirst("a.thumbnail div.duration-overlay") != null
        val media = if (isVideo) {
            Media(
                null,
                null,
                RedditVideoPreview(
                    url,
                    0,
                    0,
                    0,
                    false
                )
            )
        } else null

        val postData = PostData(
            subreddit = subreddit,
            linkFlairRichText = emptyList(),
            authorFlairRichText = null,
            title = title,
            prefixedSubreddit = prefixedSubreddit,
            name = name,
            ratio = null,
            totalAwards = 0,
            isOC = false,
            flair = selectFirst("span.linkflairlabel")?.text(),
            authorFlair = null,
            galleryData = null,
            score = score,
            hint = if (isVideo) "video" else if (thumbnail != null) "image" else null,
            isSelf = isSelf,
            crossposts = null,
            domain = domain,
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
            url = url,
            created = created,
            media = media,
            mediaMetadata = null,
            isRedditGallery = null,
            isVideo = isVideo
        ).apply {
            this.thumbnail = thumbnail
        }

        return PostChild(postData)
    }

    private fun String.toRelativeUrl(): String {
        return this.replace("https://old.reddit.com", "")
            .replace("https://www.reddit.com", "")
    }

    companion object {
        private const val KIND = "t3"
        private val SCORE_REGEX = Regex("[0-9,]+")
        private val COMMENTS_REGEX = Regex("[0-9,]+")
    }
}
