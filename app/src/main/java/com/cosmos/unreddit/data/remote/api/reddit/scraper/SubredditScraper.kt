package com.cosmos.unreddit.data.remote.api.reddit.scraper

import com.cosmos.unreddit.data.remote.api.reddit.model.AboutChild
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutData
import com.cosmos.unreddit.data.remote.api.reddit.model.Child
import com.cosmos.unreddit.data.remote.scraper.Scraper
import kotlinx.coroutines.CoroutineDispatcher
import org.jsoup.nodes.Document

class SubredditScraper(
    ioDispatcher: CoroutineDispatcher
) : RedditScraper<Child>(ioDispatcher) {

    override suspend fun scrapDocument(document: Document): Child {
        val title = document.selectFirst(Scraper.Selector.Tag.TITLE)?.text().orEmpty()

        val shredditHeader = document.selectFirst("shreddit-subreddit-header")

        val name: String
        val link: String
        val communityIcon: String
        val subscribers: Int?
        val activeUsers: Int?
        val descriptionHtml: String?
        val cleanTitle: String

        if (shredditHeader != null) {
            val prefName = shredditHeader.attr("prefixed-name").removePrefix("r/").removePrefix("/")
            name = prefName
            link = "/r/$prefName/"
            cleanTitle = shredditHeader.attr("display-name")

            val iconImg = shredditHeader.selectFirst("img")
            communityIcon = iconImg?.attr("src")?.toValidLink().orEmpty()

            subscribers = shredditHeader.attr("subscribers").toIntOrNull()
            activeUsers = shredditHeader.attr("active").toIntOrNull()

            val desc = shredditHeader.attr("description")
            descriptionHtml = if (desc.isNotEmpty()) "<div class=\"md\"><p>$desc</p></div>" else null
        } else {
            cleanTitle = title
            val redditName = document.selectFirst("h1.redditname")
                ?.selectFirst(Scraper.Selector.Tag.A)

            name = redditName?.text().orEmpty()
            link = redditName?.attr(Scraper.Selector.Attr.HREF).orEmpty()

            communityIcon = document.selectFirst("img[id=header-img]")
                ?.attr(Scraper.Selector.Attr.SRC)
                ?.toValidLink()
                .orEmpty()

            subscribers = document.selectFirst("span.subscribers")
                ?.selectFirst(Selector.NUMBER)
                ?.toInt()

            activeUsers = document.selectFirst("p.users-online")
                ?.selectFirst(Selector.NUMBER)
                ?.toInt()

            descriptionHtml = document.selectFirst("div.titlebox")
                ?.selectFirst(Selector.MD)
                ?.outerHtml()
        }

        val data = AboutData(
            null,
            name,
            null,
            cleanTitle,
            null,
            activeUsers,
            null,
            subscribers,
            null,
            null,
            communityIcon,
            "",
            null,
            null,
            false,
            descriptionHtml,
            link,
            0L // TODO
        )

        return AboutChild(data)
    }
}
