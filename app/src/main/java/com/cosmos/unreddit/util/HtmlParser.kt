package com.cosmos.unreddit.util

import android.view.Gravity
import androidx.core.text.HtmlCompat
import com.cosmos.unreddit.data.model.Block.TableBlock
import com.cosmos.unreddit.data.model.Block.TextBlock
import com.cosmos.unreddit.data.model.HtmlBlock
import com.cosmos.unreddit.data.model.RedditText
import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.data.remote.api.reddit.model.MediaMetadata
import com.cosmos.unreddit.util.LinkUtil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.util.LinkedList

class HtmlParser(private val defaultDispatcher: CoroutineDispatcher) {

    private val TABLE_REGEX = Regex("<table>.*?</table>", RegexOption.DOT_MATCHES_ALL)
    private val CODE_REGEX = Regex("<pre><code>(.*?)</code></pre>", RegexOption.DOT_MATCHES_ALL)
    private val PLACEHOLDER_REGEX = Regex("<(table|code)_placeholder/>")
    private val MARKDOWN_IMAGE_REGEX = Regex("!\\[(img|gif|[^\\]]*)\\]\\(([^)]+)\\)", RegexOption.IGNORE_CASE)
    private val PROCESSING_IMG_REGEX = Regex("^Processing img ([A-Za-z0-9_-]+)\\.\\.\\.$", RegexOption.IGNORE_CASE)
    private val REDDIT_MEDIA_ID_REGEX = Regex("^[A-Za-z0-9_-]+$")

    private val tagHandler = RedditTagHandler()

    suspend fun separateHtmlBlocks(
        html: String?,
        mediaMetadata: MediaMetadata? = null,
        includeExpandAll: Boolean = true
    ): RedditText = withContext(defaultDispatcher) {
            val redditText = RedditText()

            if (html == null) return@withContext redditText

            val tables = LinkedList<String>()
            val codes = LinkedList<String>()

            var newHtml = preprocessCommentHtml(html, mediaMetadata, includeExpandAll)

            newHtml = newHtml.replace(TABLE_REGEX) {
                tables.add(it.groupValues[0])
                TABLE_PLACEHOLDER
            }

            newHtml = newHtml.replace(CODE_REGEX) {
                codes.add(it.groupValues[1])
                CODE_PLACEHOLDER
            }

            if (PLACEHOLDER_REGEX.containsMatchIn(newHtml)) {
                var lastIndex = 0

                for (match in PLACEHOLDER_REGEX.findAll(newHtml)) {
                    if (match.range.first > 0) {
                        val previousBlock = newHtml.substring(lastIndex, match.range.first - 1)
                        redditText.addBlock(getTextBlock(previousBlock), HtmlBlock.BlockType.TEXT)
                    }

                    lastIndex = match.range.last + 1

                    if (match.value == TABLE_PLACEHOLDER) {
                        val tableBlock = getTableFromHtmlTable(tables.pop())
                        redditText.addBlock(tableBlock, HtmlBlock.BlockType.TABLE)
                    } else if (match.value == CODE_PLACEHOLDER) {
                        val codeBlock = Parser.unescapeEntities(codes.pop(), true)
                        redditText.addBlock(TextBlock(codeBlock), HtmlBlock.BlockType.CODE)
                    }
                }

                if (lastIndex < newHtml.length) {
                    val lastBlock = newHtml.substring(lastIndex)
                    redditText.addBlock(getTextBlock(lastBlock), HtmlBlock.BlockType.TEXT)
                }
            } else {
                redditText.addBlock(getTextBlock(newHtml), HtmlBlock.BlockType.TEXT)
            }

            return@withContext redditText
    }

    private fun getTableFromHtmlTable(html: String): TableBlock {
        val table = TableBlock()

        val doc = Jsoup.parse(html)

        val rows = doc.select("tr")

        for (row in rows) {
            var isHeader = false

            val cols = if (row.select("td").isNotEmpty()) {
                row.select("td")
            } else {
                isHeader = true
                row.select("th")
            }

            val tableRow = TableBlock.Row(isHeader)

            for (col in cols) {
                val alignment = col.attr("align")

                val spannedHtml = fromHtml(col.html())
                val gravity = getGravityFromAlign(alignment)

                tableRow.addColumn(spannedHtml, gravity)
            }

            table.addRow(tableRow)
        }

        return table
    }

    private fun getGravityFromAlign(align: String): Int {
        return when (align) {
            "left" -> Gravity.START
            "right" -> Gravity.END
            "center" -> Gravity.CENTER
            else -> Gravity.START
        }
    }

    private fun getTextBlock(html: String): TextBlock {
        return TextBlock(fromHtml(html))
    }

    internal fun preprocessCommentHtml(
        html: String,
        mediaMetadata: MediaMetadata? = null,
        includeExpandAll: Boolean = true
    ): String {
        val doc = Jsoup.parseBodyFragment(html)
        doc.select("template, .comment-image-lightbox-content, .lightboxed-content").remove()

        // Wrap any standalone <img> tags not already inside an <a>
        for (img in doc.select("img")) {
            if (img.closest("a") == null) {
                val src = img.attr("src").takeIf { it.isNotBlank() } ?: continue
                img.wrap("<a href=\"$src\"></a>")
            }
        }

        // Convert "Processing img <id>..." placeholders into image links
        for (em in doc.select("em")) {
            val match = PROCESSING_IMG_REGEX.matchEntire(em.text().trim()) ?: continue
            val mediaId = match.groupValues[1]
            val resolvedUrl = resolveEmbeddedMediaUrl(mediaId, false, mediaMetadata) ?: continue
            val isGif = resolvedUrl.contains(".gif", ignoreCase = true)
            val placeholder = if (isGif) "&lt;gif&gt;" else "&lt;image&gt;"
            em.after("<a href=\"$resolvedUrl\">$placeholder</a>")
            em.remove()
        }

        // Convert markdown ![img](...) / ![gif](...) syntax in text nodes into <a> links
        val textNodes = mutableListOf<TextNode>()
        collectTextNodes(doc.body(), textNodes)
        for (textNode in textNodes) {
            val rawText = textNode.wholeText
            if (!rawText.contains("![")) continue
            val matches = MARKDOWN_IMAGE_REGEX.findAll(rawText).toList()
            if (matches.isEmpty()) continue

            val sb = StringBuilder()
            var lastIdx = 0
            var replacedAny = false
            for (match in matches) {
                sb.append(Entities.escape(rawText.substring(lastIdx, match.range.first)))
                val alt = match.groupValues[1].trim()
                val target = match.groupValues[2].trim()
                val isGifHint = alt.equals("gif", ignoreCase = true) || target.startsWith("giphy|", ignoreCase = true)
                val resolvedUrl = resolveEmbeddedMediaUrl(target, isGifHint, mediaMetadata)
                if (resolvedUrl != null) {
                    val isGif = isGifHint || resolvedUrl.contains(".gif", ignoreCase = true)
                    val label = if (isGif) "&lt;gif&gt;" else "&lt;image&gt;"
                    val escapedUrl = Entities.escape(resolvedUrl)
                    sb.append("<a href=\"$escapedUrl\">$label</a>")
                    replacedAny = true
                } else {
                    sb.append(Entities.escape(match.value))
                }
                lastIdx = match.range.last + 1
            }
            if (replacedAny) {
                sb.append(Entities.escape(rawText.substring(lastIdx)))
                textNode.after(sb.toString())
                textNode.remove()
            }
        }

        // 1. Process Giphy links first
        val giphyLinks = doc.select("a[href*=giphy.com]")
        for (a in giphyLinks) {
            val href = a.attr("href")
            val id = LinkUtil.getGiphyId(href)
            if (id != null) {
                a.text("<gif>")
                a.attr("href", LinkUtil.getGiphyGifUrl(id))
            }
        }

        // 2. Find all image/gif links
        val allLinks = doc.select("a")
        val imageOrGifLinks = mutableListOf<Element>()
        for (a in allLinks) {
            var href = a.attr("href")
            val text = a.text().trim()
            val wrapsImage = a.select("img").isNotEmpty()

            // Rewrite preview.redd.it gif-as-mp4 links to animated .gif URLs so inline ImageView can render them
            if (href.contains("preview.redd.it", ignoreCase = true) &&
                href.contains(".gif", ignoreCase = true) &&
                href.contains("format=mp4", ignoreCase = true)
            ) {
                val segment = href.substringAfter("preview.redd.it/").substringBefore("?")
                val mediaId = segment.substringBeforeLast(".")
                val metadataGif = mediaMetadata?.items?.find { it.id == mediaId }?.image?.gif
                href = metadataGif ?: "https://i.redd.it/$mediaId.gif"
                a.attr("href", href)
            }

            val isImageLink = LinkUtil.getLinkType(href) in listOf(
                MediaType.IMAGE,
                MediaType.IMGUR_IMAGE,
                MediaType.IMGUR_GIF,
                MediaType.REDDIT_GIF
            ) || href.contains("giphy.com")

            val isRedditHostedImage = href.contains("preview.redd.it", ignoreCase = true) ||
                href.contains("i.redd.it", ignoreCase = true)
            val isBareUrlText = text.equals(href, ignoreCase = true) ||
                text.startsWith("http://", ignoreCase = true) ||
                text.startsWith("https://", ignoreCase = true)

            val isGiphyOrRedditImage = (
                text == "<image>" ||
                    text == "<gif>" ||
                    wrapsImage ||
                    isRedditHostedImage ||
                    isBareUrlText
                ) && isImageLink

            if (isGiphyOrRedditImage) {
                if (text != "<image>" && text != "<gif>" && (wrapsImage || isBareUrlText || text.isEmpty())) {
                    if (href.contains(".gif", ignoreCase = true) || href.contains("giphy.com", ignoreCase = true)) {
                        a.text("<gif>")
                    } else {
                        a.text("<image>")
                    }
                }
                imageOrGifLinks.add(a)
            }
        }

        // 3. For each image/gif link, append "<expand all>" link after it when enabled
        if (includeExpandAll) {
            for (a in imageOrGifLinks) {
                a.after(" <a href=\"expand_all:\">&lt;expand all&gt;</a>")
            }
        }

        return doc.body().html()
    }

    private fun collectTextNodes(element: Element, out: MutableList<TextNode>) {
        for (node in element.childNodes()) {
            when (node) {
                is TextNode -> out.add(node)
                is Element -> {
                    val tag = node.tagName().lowercase()
                    if (tag != "pre" && tag != "code" && tag != "a") {
                        collectTextNodes(node, out)
                    }
                }
            }
        }
    }

    private fun resolveEmbeddedMediaUrl(
        idOrUrl: String,
        isGifHint: Boolean,
        mediaMetadata: MediaMetadata?
    ): String? {
        val metadataItem = mediaMetadata?.items?.find { it.id == idOrUrl }
        if (metadataItem != null) {
            val resolved = metadataItem.image?.gif
                ?: metadataItem.image?.url
                ?: metadataItem.image?.mp4
                ?: metadataItem.previews?.lastOrNull()?.url
            if (!resolved.isNullOrBlank()) {
                return resolved.replace("&amp;", "&")
            }
        }

        if (idOrUrl.startsWith("http://", ignoreCase = true) || idOrUrl.startsWith("https://", ignoreCase = true)) {
            return idOrUrl
        }

        if (idOrUrl.startsWith("giphy|", ignoreCase = true)) {
            val giphyId = idOrUrl.substringAfter("giphy|").substringBefore("|").trim()
            if (giphyId.isNotEmpty()) {
                return LinkUtil.getGiphyGifUrl(giphyId)
            }
        }

        if (idOrUrl.startsWith("emote|", ignoreCase = true)) {
            val parts = idOrUrl.split("|")
            if (parts.size >= 3) {
                return "https://reddit-econ-prod-assets-permanent.s3.amazonaws.com/asset-manager/${parts[1]}/${parts[2]}.png"
            }
        }

        if (REDDIT_MEDIA_ID_REGEX.matches(idOrUrl)) {
            val ext = if (isGifHint) "gif" else "jpeg"
            return "https://i.redd.it/$idOrUrl.$ext"
        }

        return null
    }

    private fun fromHtml(html: String): CharSequence {
        val overriddenHtml = tagHandler.overrideTags(html)
        return HtmlCompat.fromHtml(
            overriddenHtml,
            HtmlCompat.FROM_HTML_MODE_LEGACY,
            null,
            tagHandler
        ).removeSuffix("\n\n")
    }

    companion object {
        private const val TABLE_PLACEHOLDER = "<table_placeholder/>"
        private const val CODE_PLACEHOLDER = "<code_placeholder/>"
    }
}
