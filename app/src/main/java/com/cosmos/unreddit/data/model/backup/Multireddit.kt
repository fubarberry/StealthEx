package com.cosmos.unreddit.data.model.backup

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Multireddit(
    @Json(name = "name")
    val name: String,

    @Json(name = "time")
    val time: Long = System.currentTimeMillis(),

    @Json(name = "subreddits")
    val subreddits: List<String> = emptyList(),

    @Json(name = "subreddit_weights")
    val subredditWeights: Map<String, Int>? = null
)
