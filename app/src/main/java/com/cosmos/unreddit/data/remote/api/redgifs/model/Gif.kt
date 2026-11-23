package com.cosmos.unreddit.data.remote.api.redgifs.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Gif(
    @Json(name = "id")
    val id: String = "",

    @Json(name = "createDate")
    val createDate: Int? = null,

    @Json(name = "hasAudio")
    val hasAudio: Boolean? = null,

    @Json(name = "width")
    val width: Int? = null,

    @Json(name = "height")
    val height: Int? = null,

    @Json(name = "duration")
    val duration: Double? = null,

    @Json(name = "urls")
    val urls: Urls
)
