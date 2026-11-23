package com.cosmos.unreddit.data.model.backup

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Subscription(
    @Json(name = "name")
    val name: String,

    @Json(name = "time")
    val time: Long,

    @Json(name = "icon")
    val icon: String?,

    @Json(name = "is_subscribed")
    val isSubscribed: Boolean = true,

    @Json(name = "is_pinned")
    val isPinned: Boolean = false
)
