package com.cosmos.unreddit.data.remote.api.reddit.scraper

import com.cosmos.unreddit.data.remote.api.reddit.model.Child
import com.cosmos.unreddit.data.remote.api.reddit.model.CommentChild
import com.cosmos.unreddit.data.remote.api.reddit.model.CommentData
import com.cosmos.unreddit.data.remote.api.reddit.model.Data
import com.cosmos.unreddit.data.remote.api.reddit.model.JsonMore
import com.cosmos.unreddit.data.remote.api.reddit.model.Listing
import com.cosmos.unreddit.data.remote.api.reddit.model.ListingData
import com.cosmos.unreddit.data.remote.api.reddit.model.MoreChild
import com.cosmos.unreddit.data.remote.api.reddit.model.MoreChildren
import com.cosmos.unreddit.data.remote.api.reddit.model.MoreData
import com.cosmos.unreddit.data.remote.scraper.Scraper
import com.cosmos.unreddit.util.extension.toSeconds
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ShredditCommentScraper(
    ioDispatcher: CoroutineDispatcher,
    private val linkId: String,
    private val subredditPrefixed: String,
    private val fallbackParentId: String? = null,
    private val fallbackDepth: Int = 0
) : Scraper<Listing>(ioDispatcher) {

    override suspend fun scrapDocument(document: Document): Listing {
        val children = parseElements(document)
        return Listing(
            KIND_LISTING,
            ListingData(
                null,
                null,
                children,
                null,
                null
            )
        )
    }

    suspend fun scrapMoreChildren(body: String): MoreChildren {
        val listing = scrap(body)
        return MoreChildren(JsonMore(Data(listing.data.children)))
    }

    private fun parseElements(document: Document): List<Child> {
        val elements = document.select("shreddit-comment, faceplate-partial[src*=/svc/shreddit/more-comments/]")
        val childrenByParentId = mutableMapOf<String, MutableList<Element>>()
        val rootElements = mutableListOf<Element>()

        for (element in elements) {
            if (element.tagName().equals("shreddit-comment", ignoreCase = true)) {
                val thingId = element.attr("thingId")
                if (thingId.isNotBlank()) {
                    childrenByParentId.putIfAbsent(thingId, mutableListOf())
                }
            }
        }

        for (element in elements) {
            val tagName = element.tagName().lowercase(Locale.US)
            val parentCommentEl = element.parent()?.closest("shreddit-comment")
            val parentId = when (tagName) {
                "shreddit-comment" -> {
                    element.attr("parentId").takeIf { it.isNotBlank() && it.startsWith("t1_") }
                        ?: parentCommentEl?.attr("thingId")?.takeIf { it.isNotBlank() }
                }
                "faceplate-partial" -> {
                    parentCommentEl?.attr("thingId")?.takeIf { it.isNotBlank() }
                        ?: element.selectFirst("input[name=cursor]")?.attr("value")
                            ?.let { extractParentIdFromCursor(it) }
                }
                else -> null
            }

            if (parentId != null && childrenByParentId.containsKey(parentId)) {
                childrenByParentId[parentId]?.add(element)
            } else {
                rootElements.add(element)
            }
        }

        return rootElements.mapNotNull { it.toChild(childrenByParentId) }
    }

    private fun Element.toChild(childrenByParentId: Map<String, List<Element>>): Child? {
        return when (tagName().lowercase(Locale.US)) {
            "shreddit-comment" -> toCommentChild(childrenByParentId)
            "faceplate-partial" -> toMoreChild()
            else -> null
        }
    }

    private fun Element.toCommentChild(childrenByParentId: Map<String, List<Element>>): CommentChild? {
        val name = attr("thingId").takeIf { it.isNotBlank() } ?: return null
        val id = name.removePrefix("t1_")
        val postFullName = attr("postId").ifBlank { linkId }
        val author = attr("author").ifBlank { "[deleted]" }
        val score = attr("score").toIntOrNull() ?: 0
        val scoreHidden = hasAttr("score-hidden")
        val isSubmitter = hasAttr("is-op")
        val stickied = hasAttr("pinned") || hasAttr("stickied")
        val distinguished = attr("distinguished-as").takeIf { it.isNotBlank() }
        val permalink = attr("permalink")
        val depth = attr("depth").toIntOrNull() ?: fallbackDepth
        val created = parseCreatedSeconds()

        val bodyElement = selectFirst("div[id=$name-comment-rtjson-content], div[slot=comment]")
        if (bodyElement != null) {
            bodyElement.select("template, .comment-image-lightbox-content, .lightboxed-content").remove()
            for (img in bodyElement.select("img")) {
                if (img.closest("a") == null) {
                    val src = img.attr("src").takeIf { it.isNotBlank() } ?: continue
                    img.wrap("<a href=\"$src\"></a>")
                }
            }
        }
        val bodyHtml = bodyElement?.outerHtml().orEmpty()

        val flair = selectFirst(
            "[slot=commentMeta] author-flair-event-handler, [slot=commentMeta] shreddit-comment-author-flair, [slot=commentMeta] .author-flair"
        )?.text()?.trim()?.takeIf { it.isNotEmpty() }

        val childElements = childrenByParentId[name].orEmpty()
        val childComments = childElements.mapNotNull { it.toChild(childrenByParentId) }
        val replies = childComments.takeIf { it.isNotEmpty() }?.let {
            Listing(KIND_LISTING, ListingData(null, null, it, null, null))
        }

        val data = CommentData(
            totalAwards = 0,
            authorFlairRichText = null,
            linkId = postFullName,
            replies = replies,
            author = author,
            score = score,
            awardings = emptyList(),
            bodyHtml = bodyHtml,
            edited = -1L,
            isSubmitter = isSubmitter,
            stickied = stickied,
            scoreHidden = scoreHidden,
            permalink = permalink,
            id = id,
            name = name,
            created = created,
            controversiality = 0,
            flair = flair,
            depth = depth,
            distinguished = distinguished,
            subreddit = subredditPrefixed,
            linkTitle = null,
            linkPermalink = null,
            linkAuthor = null
        )

        return CommentChild(data)
    }

    private fun Element.toMoreChild(): MoreChild? {
        val src = attr("src").replace("&amp;", "&").takeIf { it.isNotBlank() } ?: return null
        val cursor = selectFirst("input[name=cursor]")?.attr("value")?.takeIf { it.isNotBlank() }
            ?: return null

        val parentCommentEl = parent()?.closest("shreddit-comment")
        val httpUrl = "https://www.reddit.com$src".toHttpUrlOrNull()

        val parentId = parentCommentEl?.attr("thingId")?.takeIf { it.isNotBlank() }
            ?: extractParentIdFromCursor(cursor)
            ?: fallbackParentId
            ?: linkId

        val depth = httpUrl?.queryParameter("startingDepth")?.toIntOrNull()
            ?: parentCommentEl?.attr("depth")?.toIntOrNull()?.plus(1)
            ?: fallbackDepth

        val count = selectFirst("faceplate-number")?.attr("number")?.toIntOrNull()
            ?: httpUrl?.queryParameter("comments-remaining")?.toIntOrNull()
            ?: 1

        val moreId = "more_${(src + cursor).hashCode().toUInt().toString(16)}"
        val encodedToken = "$src|$cursor|$parentId|$depth"

        val data = MoreData(
            count = count,
            name = "t1_$moreId",
            id = moreId,
            depth = depth,
            parentId = parentId,
            children = mutableListOf(encodedToken)
        )

        return MoreChild(data)
    }

    private fun extractParentIdFromCursor(cursor: String): String? {
        return try {
            val decoded = String(java.util.Base64.getDecoder().decode(cursor), Charsets.UTF_8)
            val afterLastColon = decoded.substringAfterLast(":", "")
            afterLastColon.takeIf { it.startsWith("t1_") && it.length > 3 }
        } catch (e: Exception) {
            null
        }
    }

    private fun Element.parseCreatedSeconds(): Long {
        val raw = attr("created").ifBlank {
            selectFirst("faceplate-timeago")?.attr("ts").orEmpty()
        }.ifBlank {
            selectFirst("time")?.attr("datetime").orEmpty()
        }
        if (raw.length >= 19) {
            return try {
                val sdf = SimpleDateFormat(DATETIME_FORMAT, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val millis = sdf.parse(raw.substring(0, 19))?.time ?: System.currentTimeMillis()
                millis.toSeconds()
            } catch (e: Exception) {
                System.currentTimeMillis().toSeconds()
            }
        }
        return System.currentTimeMillis().toSeconds()
    }

    companion object {
        private const val KIND_LISTING = "Listing"
        private const val DATETIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss"
    }
}
