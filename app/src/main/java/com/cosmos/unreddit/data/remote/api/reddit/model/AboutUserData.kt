package com.cosmos.unreddit.data.remote.api.reddit.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class AboutUserData(
    @Json(name = "is_suspended")
    val isSuspended: Boolean = false,

    @Json(name = "is_employee")
    val isEmployee: Boolean = false,

    @Json(name = "subreddit")
    val subreddit: Subreddit? = null,

    @Json(name = "id")
    val id: String? = null,

    @Json(name = "icon_img")
    val iconImg: String? = null,

    @Json(name = "link_karma")
    val linkKarma: Int = 0,

    @Json(name = "total_karma")
    val totalKarma: Int = 0,

    @Json(name = "name")
    val name: String = "",

    @Json(name = "created_utc")
    val created: Long = 0,

    @Json(name = "snoovatar_img")
    val snoovatarImg: String? = null,

    @Json(name = "comment_karma")
    val commentKarma: Int = 0
) {
    fun getTimeInMillis(): Long { // TODO: Remove from all *Data class and put in Util class
        return TimeUnit.SECONDS.toMillis(created)
    }
}
