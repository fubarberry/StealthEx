package com.cosmos.unreddit.util

import android.view.Gravity
import androidx.core.text.HtmlCompat
import com.cosmos.unreddit.data.model.Block.TableBlock
import com.cosmos.unreddit.data.model.Block.TextBlock
import com.cosmos.unreddit.data.model.HtmlBlock
import com.cosmos.unreddit.data.model.RedditText
import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.util.LinkUtil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.util.LinkedList

class HtmlParser(private val defaultDispatcher: CoroutineDispatcher) {

    private val TABLE_REGEX = Regex("<table>.*?</table>", RegexOption.DOT_MATCHES_ALL)
    private val CODE_REGEX = Regex("<pre><code>(.*?)</code></pre>", RegexOption.DOT_MATCHES_ALL)
    private val PLACEHOLDER_REGEX = Regex("<(table|code)_placeholder/>")

    private val tagHandler = RedditTagHandler()

    suspend fun separateHtmlBlocks(html: String?): RedditText = withContext(defaultDispatcher) {
            val redditText = RedditText()

            if (html == null) return@withContext redditText

            val tables = LinkedList<String>()
            val codes = LinkedList<String>()

            var newHtml = preprocessCommentHtml(html)

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

    internal fun preprocessCommentHtml(html: String): String {
        val doc = Jsoup.parseBodyFragment(html)

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
        val imageOrGifLinks = mutableListOf<org.jsoup.nodes.Element>()
        for (a in allLinks) {
            val href = a.attr("href")
            val text = a.text().trim()
            val wrapsImage = a.select("img").isNotEmpty()

            val isImageLink = LinkUtil.getLinkType(href) in listOf(
                MediaType.IMAGE,
                MediaType.IMGUR_IMAGE,
                MediaType.IMGUR_GIF,
                MediaType.REDDIT_GIF
            ) || href.contains("giphy.com")

            val isGiphyOrRedditImage = (text == "<image>" || text == "<gif>" || wrapsImage) && isImageLink

            if (isGiphyOrRedditImage) {
                if (text != "<image>" && text != "<gif>") {
                    if (href.contains(".gif") || href.contains("giphy.com")) {
                        a.text("<gif>")
                    } else {
                        a.text("<image>")
                    }
                }
                imageOrGifLinks.add(a)
            }
        }

        // 3. For each image/gif link, append "<expand all>" link after it
        for (a in imageOrGifLinks) {
            a.after(" <a href=\"expand_all:\">&lt;expand all&gt;</a>")
        }

        return doc.body().html()
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
