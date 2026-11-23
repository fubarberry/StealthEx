package com.cosmos.unreddit.util

import com.cosmos.unreddit.data.model.GalleryMedia
import com.squareup.moshi.JsonReader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object TweetResolver {

    data class TweetVideo(
        val url: String,
        val thumbnailUrl: String?
    )

    data class ResolvedTweet(
        val id: String,
        val url: String,
        val authorName: String,
        val authorHandle: String,
        val authorAvatarUrl: String?,
        val text: String,
        val createdTimestamp: Long?,
        val likes: Int,
        val retweets: Int,
        val replies: Int,
        val photos: List<String>,
        val videos: List<TweetVideo>,
        val quoteTweet: ResolvedTweet? = null
    ) {
        val hasMedia: Boolean
            get() = photos.isNotEmpty() || videos.isNotEmpty()

        val hasVideo: Boolean
            get() = videos.isNotEmpty()

        val mediaCount: Int
            get() = photos.size + videos.size

        val previewImageUrl: String?
            get() = videos.firstOrNull()?.thumbnailUrl ?: photos.firstOrNull()

        fun toGalleryMedia(): List<GalleryMedia> {
            val items = mutableListOf<GalleryMedia>()
            for (video in videos) {
                if (video.url.isNotBlank()) {
                    items.add(GalleryMedia(GalleryMedia.Type.VIDEO, video.url))
                }
            }
            for (photo in photos) {
                if (photo.isNotBlank()) {
                    items.add(GalleryMedia(GalleryMedia.Type.IMAGE, photo))
                }
            }
            return items
        }
    }

    private val T_CO_END_REGEX = Regex("(?:\\s*https?://t\\.co/[A-Za-z0-9]+)+\\s*$")

    private val cache = ConcurrentHashMap<String, ResolvedTweet>()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun getCachedTweet(linkOrId: String): ResolvedTweet? {
        val id = LinkUtil.getTweetId(linkOrId) ?: linkOrId.takeIf { it.all(Char::isDigit) } ?: return null
        return cache[id]
    }

    suspend fun resolveTweet(
        linkOrId: String,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ): ResolvedTweet? = withContext(ioDispatcher) {
        val tweetId = LinkUtil.getTweetId(linkOrId)
            ?: linkOrId.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?: return@withContext null

        cache[tweetId]?.let { return@withContext it }

        val fxTweet = runCatching { fetchFromFxTwitter(tweetId) }.getOrNull()
        if (fxTweet != null) {
            cache[tweetId] = fxTweet
            return@withContext fxTweet
        }

        val syndicationTweet = runCatching { fetchFromSyndication(tweetId) }.getOrNull()
        if (syndicationTweet != null) {
            cache[tweetId] = syndicationTweet
            return@withContext syndicationTweet
        }

        null
    }

    private fun fetchFromFxTwitter(tweetId: String): ResolvedTweet? {
        val request = Request.Builder()
            .url("https://api.fxtwitter.com/status/$tweetId")
            .header("User-Agent", LinkUtil.USER_AGENT)
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val source = response.body?.source() ?: return null
            return parseFxTwitterResponse(JsonReader.of(source), tweetId)
        }
    }

    internal fun parseFxTwitterResponse(json: String, fallbackId: String = ""): ResolvedTweet? {
        val buffer = Buffer().writeUtf8(json)
        return parseFxTwitterResponse(JsonReader.of(buffer), fallbackId)
    }

    private fun parseFxTwitterResponse(reader: JsonReader, fallbackId: String): ResolvedTweet? {
        var tweet: ResolvedTweet? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "tweet" -> {
                    if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                        tweet = parseFxTweetObject(reader, fallbackId)
                    } else {
                        reader.skipValue()
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return tweet
    }

    private fun parseFxTweetObject(reader: JsonReader, fallbackId: String): ResolvedTweet {
        var id = fallbackId
        var url = ""
        var text = ""
        var authorName = ""
        var authorHandle = ""
        var authorAvatarUrl: String? = null
        var createdTimestamp: Long? = null
        var likes = 0
        var retweets = 0
        var replies = 0
        val photos = mutableListOf<String>()
        val videos = mutableListOf<TweetVideo>()
        var quoteTweet: ResolvedTweet? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.nextStringOrEmpty().ifBlank { fallbackId }
                "url" -> url = reader.nextStringOrEmpty()
                "text" -> text = reader.nextStringOrEmpty()
                "created_timestamp" -> createdTimestamp = reader.nextLongOrNull()
                "likes" -> likes = reader.nextIntOrZero()
                "retweets" -> retweets = reader.nextIntOrZero()
                "replies" -> replies = reader.nextIntOrZero()
                "author" -> {
                    if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "name" -> authorName = reader.nextStringOrEmpty()
                                "screen_name" -> authorHandle = reader.nextStringOrEmpty()
                                "avatar_url" -> authorAvatarUrl = reader.nextStringOrNull()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    } else {
                        reader.skipValue()
                    }
                }
                "media" -> {
                    if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "photos" -> {
                                    if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                                        reader.beginArray()
                                        while (reader.hasNext()) {
                                            if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                                                reader.beginObject()
                                                var photoUrl: String? = null
                                                while (reader.hasNext()) {
                                                    when (reader.nextName()) {
                                                        "url" -> photoUrl = reader.nextStringOrNull()
                                                        else -> reader.skipValue()
                                                    }
                                                }
                                                reader.endObject()
                                                if (!photoUrl.isNullOrBlank()) {
                                                    photos.add(photoUrl)
                                                }
                                            } else {
                                                reader.skipValue()
                                            }
                                        }
                                        reader.endArray()
                                    } else {
                                        reader.skipValue()
                                    }
                                }
                                "videos" -> {
                                    if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                                        reader.beginArray()
                                        while (reader.hasNext()) {
                                            if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                                                reader.beginObject()
                                                var videoUrl: String? = null
                                                var thumbUrl: String? = null
                                                while (reader.hasNext()) {
                                                    when (reader.nextName()) {
                                                        "url" -> videoUrl = reader.nextStringOrNull()
                                                        "thumbnail_url" -> thumbUrl = reader.nextStringOrNull()
                                                        else -> reader.skipValue()
                                                    }
                                                }
                                                reader.endObject()
                                                if (!videoUrl.isNullOrBlank()) {
                                                    videos.add(TweetVideo(videoUrl, thumbUrl))
                                                }
                                            } else {
                                                reader.skipValue()
                                            }
                                        }
                                        reader.endArray()
                                    } else {
                                        reader.skipValue()
                                    }
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    } else {
                        reader.skipValue()
                    }
                }
                "quote" -> {
                    if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                        quoteTweet = parseFxTweetObject(reader, "")
                    } else {
                        reader.skipValue()
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val cleanedText = text.replace(T_CO_END_REGEX, "").trim()
        val finalUrl = url.ifBlank {
            if (authorHandle.isNotBlank()) {
                "https://x.com/$authorHandle/status/$id"
            } else {
                "https://x.com/i/status/$id"
            }
        }

        return ResolvedTweet(
            id = id,
            url = finalUrl,
            authorName = authorName.ifBlank { authorHandle },
            authorHandle = authorHandle,
            authorAvatarUrl = authorAvatarUrl,
            text = cleanedText,
            createdTimestamp = createdTimestamp,
            likes = likes,
            retweets = retweets,
            replies = replies,
            photos = photos,
            videos = videos,
            quoteTweet = quoteTweet
        )
    }

    private fun fetchFromSyndication(tweetId: String): ResolvedTweet? {
        val request = Request.Builder()
            .url("https://cdn.syndication.twimg.com/tweet-result?id=$tweetId&token=a")
            .header("User-Agent", LinkUtil.USER_AGENT)
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val source = response.body?.source() ?: return null
            return parseSyndicationObject(JsonReader.of(source), tweetId)
        }
    }

    private fun parseSyndicationObject(reader: JsonReader, fallbackId: String): ResolvedTweet? {
        var id = fallbackId
        var text = ""
        var authorName = ""
        var authorHandle = ""
        var authorAvatarUrl: String? = null
        var likes = 0
        var replies = 0
        val photos = mutableListOf<String>()
        val videos = mutableListOf<TweetVideo>()

        if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) return null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id_str" -> id = reader.nextStringOrEmpty().ifBlank { fallbackId }
                "text" -> text = reader.nextStringOrEmpty()
                "favorite_count" -> likes = reader.nextIntOrZero()
                "conversation_count" -> replies = reader.nextIntOrZero()
                "user" -> {
                    if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "name" -> authorName = reader.nextStringOrEmpty()
                                "screen_name" -> authorHandle = reader.nextStringOrEmpty()
                                "profile_image_url_https" -> authorAvatarUrl = reader.nextStringOrNull()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    } else {
                        reader.skipValue()
                    }
                }
                "mediaDetails" -> {
                    if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                                reader.beginObject()
                                var mediaType = ""
                                var mediaUrlHttps: String? = null
                                var bestVideoUrl: String? = null
                                var bestBitrate = -1
                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "type" -> mediaType = reader.nextStringOrEmpty()
                                        "media_url_https" -> mediaUrlHttps = reader.nextStringOrNull()
                                        "video_info" -> {
                                            if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                                                reader.beginObject()
                                                while (reader.hasNext()) {
                                                    when (reader.nextName()) {
                                                        "variants" -> {
                                                            if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                                                                reader.beginArray()
                                                                while (reader.hasNext()) {
                                                                    if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                                                                        reader.beginObject()
                                                                        var contentType = ""
                                                                        var bitrate = 0
                                                                        var vUrl: String? = null
                                                                        while (reader.hasNext()) {
                                                                            when (reader.nextName()) {
                                                                                "content_type" -> contentType = reader.nextStringOrEmpty()
                                                                                "bitrate" -> bitrate = reader.nextIntOrZero()
                                                                                "url" -> vUrl = reader.nextStringOrNull()
                                                                                else -> reader.skipValue()
                                                                            }
                                                                        }
                                                                        reader.endObject()
                                                                        if (contentType.contains("mp4") && !vUrl.isNullOrBlank() && bitrate >= bestBitrate) {
                                                                            bestBitrate = bitrate
                                                                            bestVideoUrl = vUrl
                                                                        }
                                                                    } else {
                                                                        reader.skipValue()
                                                                    }
                                                                }
                                                                reader.endArray()
                                                            } else {
                                                                reader.skipValue()
                                                            }
                                                        }
                                                        else -> reader.skipValue()
                                                    }
                                                }
                                                reader.endObject()
                                            } else {
                                                reader.skipValue()
                                            }
                                        }
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                                if ((mediaType == "video" || mediaType == "animated_gif") && !bestVideoUrl.isNullOrBlank()) {
                                    videos.add(TweetVideo(bestVideoUrl, mediaUrlHttps))
                                } else if (!mediaUrlHttps.isNullOrBlank()) {
                                    photos.add(mediaUrlHttps)
                                }
                            } else {
                                reader.skipValue()
                            }
                        }
                        reader.endArray()
                    } else {
                        reader.skipValue()
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (authorHandle.isBlank() && text.isBlank()) return null
        val cleanedText = text.replace(T_CO_END_REGEX, "").trim()
        val finalUrl = if (authorHandle.isNotBlank()) {
            "https://x.com/$authorHandle/status/$id"
        } else {
            "https://x.com/i/status/$id"
        }

        return ResolvedTweet(
            id = id,
            url = finalUrl,
            authorName = authorName.ifBlank { authorHandle },
            authorHandle = authorHandle,
            authorAvatarUrl = authorAvatarUrl,
            text = cleanedText,
            createdTimestamp = null,
            likes = likes,
            retweets = 0,
            replies = replies,
            photos = photos,
            videos = videos,
            quoteTweet = null
        )
    }

    private fun JsonReader.nextStringOrNull(): String? {
        return if (peek() == JsonReader.Token.NULL) {
            nextNull<Unit>()
            null
        } else if (peek() == JsonReader.Token.STRING || peek() == JsonReader.Token.NUMBER) {
            nextString()
        } else {
            skipValue()
            null
        }
    }

    private fun JsonReader.nextStringOrEmpty(): String = nextStringOrNull().orEmpty()

    private fun JsonReader.nextIntOrZero(): Int {
        return try {
            when (peek()) {
                JsonReader.Token.NUMBER -> nextInt()
                JsonReader.Token.STRING -> nextString().toIntOrNull() ?: 0
                else -> {
                    skipValue()
                    0
                }
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun JsonReader.nextLongOrNull(): Long? {
        return try {
            when (peek()) {
                JsonReader.Token.NUMBER -> nextLong()
                JsonReader.Token.STRING -> nextString().toLongOrNull()
                else -> {
                    skipValue()
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
