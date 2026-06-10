package com.cosmos.unreddit.data.remote.api.reddit.scraper

import com.cosmos.unreddit.data.remote.api.reddit.model.AboutUserChild
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutUserData
import com.cosmos.unreddit.data.remote.api.reddit.model.Listing
import com.cosmos.unreddit.data.remote.api.reddit.model.ListingData
import com.cosmos.unreddit.data.remote.api.reddit.model.Subreddit
import com.cosmos.unreddit.data.remote.scraper.Scraper
import kotlinx.coroutines.CoroutineDispatcher
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class UserSearchScraper(
    ioDispatcher: CoroutineDispatcher
) : RedditScraper<Listing>(ioDispatcher) {

    override suspend fun scrapDocument(document: Document): Listing {
        val subreddits = document.select("div.search-result-subreddit")
        
        val userElements = subreddits.toList().filter {
            val link = it.selectFirst("a.search-subreddit-link")?.attr("href").orEmpty()
            link.contains("/user/")
        }

        val children = userElements.map { it.toSearchUser() }
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

    private fun Element.toSearchUser(): AboutUserChild {
        val linkElement = selectFirst("a.search-subreddit-link")
        val link = linkElement?.attr("href").orEmpty()
        val username = link.substringAfter("/user/").substringBefore("/").trim()
        val title = selectFirst("a.search-title")?.text().orEmpty()
        val over18 = selectFirst("span.nsfw-stamp") != null

        val sub = Subreddit(
            bannerImg = null,
            communityIcon = null,
            iconColor = "",
            headerImg = null,
            title = title,
            over18 = over18,
            primaryColor = "",
            iconImg = "",
            description = "",
            subscribers = 0,
            displayNamePrefixed = "u/$username",
            keyColor = "",
            name = "u_$username",
            isDefaultBanner = true,
            url = "/user/$username/",
            publicDescription = ""
        )

        val data = AboutUserData(
            isSuspended = false,
            isEmployee = false,
            subreddit = sub,
            id = null,
            iconImg = null,
            linkKarma = -1,
            totalKarma = -1,
            name = username,
            created = -1,
            snoovatarImg = null,
            commentKarma = -1
        )

        return AboutUserChild(data)
    }

    companion object {
        private const val KIND = "t2"
    }
}
