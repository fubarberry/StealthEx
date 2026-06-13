package com.cosmos.unreddit.util

import android.webkit.MimeTypeMap
import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.data.remote.api.imgur.model.Image
import com.cosmos.unreddit.util.extension.extension
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File
import java.io.IOException

object LinkUtil {

    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val HTTP_REGEX = Regex("^\\bhttp\\b")

    private val GIF_REGEX = Regex("gif(v)?")
    private val REDDIT_VIDEO_REGEX = Regex("DASH_(\\d+)")

    private val SUBREDDIT_REGEX = Regex("/r/[A-Za-z0-9_-]{3,21}")
    private val USER_REGEX = Regex("/u/[A-Za-z0-9_-]{3,20}")

    private val REDDIT_LINK = Regex("(.+?)\\.reddit\\.com")
    private val IMGUR_LINK = Regex("([im]\\.)?(stack\\.)?imgur\\.(com|io)")
    private val GFYCAT_LINK = Regex("(.+?\\.)?gfycat\\.com")
    private val REDGIFS_LINK = Regex("(.+?\\.)?redgifs\\.com")
    private val STREAMABLE_LINK = Regex("(.+?)\\.streamable\\.com")

    private const val REDDIT_SOUNDTRACK_NAME: String = "DASH_audio"

    val String.https: String
        get() = this.replace(HTTP_REGEX, "https")

    fun getImageIdFromImgurLink(link: String): String {
        return link.toHttpUrlOrNull()?.pathSegments?.getOrNull(0) ?: ""
    }

    fun getAlbumIdFromImgurLink(link: String): String {
        return link.toHttpUrlOrNull()?.pathSegments?.getOrNull(1) ?: ""
    }

    fun getUrlFromImgurImage(image: Image, convertToMp4: Boolean = true): String {
        val ext = if (convertToMp4 && image.ext.contains(GIF_REGEX)) {
            ".mp4"
        } else {
            image.ext
        }
        return getUrlFromImgurId(image.hash, ext)
    }

    fun getUrlFromImgurId(hash: String, extension: String = ".jpeg"): String {
        return "https://i.imgur.com/$hash$extension"
    }

    fun getImgurVideo(link: String): String {
        return link.replace(GIF_REGEX, "mp4")
    }

    fun getRedditSoundTrack(link: String): String {
        return link.replace(REDDIT_VIDEO_REGEX, REDDIT_SOUNDTRACK_NAME)
    }

    fun isRedditSoundTrack(link: String): Boolean {
        return link.contains(REDDIT_SOUNDTRACK_NAME)
    }

    fun getGfycatId(link: String): String {
        return link.toHttpUrlOrNull()?.pathSegments?.lastOrNull() ?: return link
    }

    fun getStreamableShortcode(link: String): String {
        return link.toHttpUrlOrNull()?.pathSegments?.getOrNull(0) ?: ""
    }

    fun getLinkType(link: String): MediaType {
        when {
            link.matches(SUBREDDIT_REGEX) -> return MediaType.REDDIT_SUBREDDIT
            link.matches(USER_REGEX) -> return MediaType.REDDIT_USER
            link.startsWith("/r/") -> return MediaType.REDDIT_PERMALINK
        }

        val httpUrl = link.toHttpUrlOrNull() ?: return MediaType.NO_MEDIA
        val domain = httpUrl.host
        val extension by lazy { link.extension }
        val mime by lazy { MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "" }

        return when {
            domain.matches(REDDIT_LINK) -> {
                if (httpUrl.pathSegments.contains("wiki")) {
                    // TODO: Handle Wiki links
                    MediaType.REDDIT_WIKI
                } else if (httpUrl.pathSegments.contains("poll")) {
                    MediaType.REDDIT_POLL
                } else {
                    MediaType.REDDIT_LINK
                }
            }

            domain.matches(IMGUR_LINK) -> {
                when {
                    link.contains("/a/") -> MediaType.IMGUR_ALBUM
                    link.contains("/gallery/") -> MediaType.IMGUR_GALLERY
                    extension.contains("gif") -> MediaType.IMGUR_GIF
                    mime.startsWith("video") -> MediaType.IMGUR_VIDEO
                    mime.startsWith("image") -> MediaType.IMGUR_IMAGE
                    else -> MediaType.IMGUR_LINK
                }
            }

            domain.matches(GFYCAT_LINK) -> MediaType.GFYCAT

            domain.matches(REDGIFS_LINK) -> MediaType.REDGIFS

            domain.matches(STREAMABLE_LINK) -> MediaType.STREAMABLE

            mime.startsWith("image") -> MediaType.IMAGE

            mime.startsWith("video") -> MediaType.VIDEO

            else -> MediaType.LINK
        }
    }

    fun getPermalinkFromMediaUrl(link: String): String {
        return link.toHttpUrlOrNull()?.pathSegments?.lastOrNull() ?: link
    }

    data class ResolvedUrls(
        val videoUrl: String,
        val audioUrl: String?
    )

    suspend fun resolveMediaUrls(
        url: String,
        ioDispatcher: CoroutineDispatcher
    ): ResolvedUrls {
        android.util.Log.d("StealthEx", "resolveMediaUrls: url=$url")
        var targetUrl = url
        if (url.contains("v.redd.it") && !url.contains(".mpd") && !url.contains(".m3u8")) {
            targetUrl = getRedditVideoMpdUrl(url) ?: url
        }

        if (targetUrl.contains("v.redd.it") && (targetUrl.contains(".mpd") || targetUrl.contains(".m3u8"))) {
            val dashUrl = targetUrl.replace("HLSPlaylist.m3u8", "DASHPlaylist.mpd")
            val client = OkHttpClient()
            val request = Request.Builder()
                .url(dashUrl)
                .header("User-Agent", LinkUtil.USER_AGENT)
                .build()

            val xml = withContext(ioDispatcher) {
                runCatching {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) response.body?.string() else null
                    }
                }.onFailure {
                    android.util.Log.e("StealthEx", "resolveMediaUrls failed to fetch MPD manifest for $dashUrl", it)
                }.getOrNull()
            }

            if (!xml.isNullOrBlank()) {
                val playlistName = if (targetUrl.contains(".mpd")) "DASHPlaylist.mpd" else "HLSPlaylist.m3u8"

                val videoRegex = Regex("<Representation[^>]*?height=\"(\\d+)\"[^>]*?>[\\s\\S]*?<BaseURL>(.*?)</BaseURL>")
                val videoMatches = videoRegex.findAll(xml).mapNotNull { match ->
                    val resolution = match.groupValues[1].toIntOrNull()
                    val filename = match.groupValues[2].trim()
                    if (resolution != null && filename.isNotEmpty()) filename to resolution else null
                }.toList()
                val bestVideo = videoMatches.maxByOrNull { it.second }?.first

                val audioSetRegex = Regex("<AdaptationSet[^>]*?contentType=\"audio\"[^>]*?>([\\s\\S]*?)</AdaptationSet>")
                val audioSetMatch = audioSetRegex.find(xml)
                var bestAudio = if (audioSetMatch != null) {
                    val audioSetContent = audioSetMatch.groupValues[1]
                    val baseUrlRegex = Regex("<BaseURL>(.*?)</BaseURL>")
                    baseUrlRegex.findAll(audioSetContent).map { it.groupValues[1].trim() }.lastOrNull()
                } else {
                    null
                }

                if (bestAudio == null) {
                    val audioRegex = Regex("<Representation[^>]*?mimeType=\"audio/mp4\"[^>]*?>[\\s\\S]*?<BaseURL>(.*?)</BaseURL>")
                    val audioMatches = audioRegex.findAll(xml).mapNotNull { match ->
                        val filename = match.groupValues[1].trim()
                        if (filename.isNotEmpty()) filename else null
                    }.toList()
                    bestAudio = audioMatches.lastOrNull()
                }

                val resolvedVideoUrl = if (bestVideo != null) targetUrl.replace(playlistName, bestVideo) else targetUrl.replace(playlistName, "DASH_720.mp4")
                val resolvedAudioUrl = if (bestAudio != null) targetUrl.replace(playlistName, bestAudio) else null

                android.util.Log.d("StealthEx", "resolveMediaUrls parsed: videoUrl=$resolvedVideoUrl, audioUrl=$resolvedAudioUrl")
                return ResolvedUrls(resolvedVideoUrl, resolvedAudioUrl)
            }

            val playlistName = if (targetUrl.contains(".mpd")) "DASHPlaylist.mpd" else "HLSPlaylist.m3u8"
            val fallbackVideoUrl = targetUrl.replace(playlistName, "DASH_720.mp4")
            android.util.Log.d("StealthEx", "resolveMediaUrls manifest blank fallback: videoUrl=$fallbackVideoUrl")
            return ResolvedUrls(fallbackVideoUrl, null)
        }

        android.util.Log.d("StealthEx", "resolveMediaUrls no-op for url: $url")
        return ResolvedUrls(url, null)
    }

    fun muxAudioVideo(videoFile: File, audioFile: File, outputFile: File) {
        val videoExtractor = MediaExtractor()
        videoExtractor.setDataSource(videoFile.absolutePath)

        val audioExtractor = MediaExtractor()
        audioExtractor.setDataSource(audioFile.absolutePath)

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        var videoTrackIndex = -1
        for (i in 0 until videoExtractor.trackCount) {
            val format = videoExtractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/")) {
                videoExtractor.selectTrack(i)
                videoTrackIndex = muxer.addTrack(format)
                break
            }
        }

        var audioTrackIndex = -1
        for (i in 0 until audioExtractor.trackCount) {
            val format = audioExtractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioExtractor.selectTrack(i)
                audioTrackIndex = muxer.addTrack(format)
                break
            }
        }

        muxer.start()

        val maxBufferSize = 1024 * 1024
        val buffer = ByteBuffer.allocate(maxBufferSize)
        val bufferInfo = MediaCodec.BufferInfo()

        if (videoTrackIndex != -1) {
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) {
                    break
                }
                bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                bufferInfo.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                videoExtractor.advance()
            }
        }

        if (audioTrackIndex != -1) {
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = audioExtractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) {
                    break
                }
                bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                bufferInfo.flags = audioExtractor.sampleFlags
                muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo)
                audioExtractor.advance()
            }
        }

        muxer.stop()
        muxer.release()

        videoExtractor.release()
        audioExtractor.release()
    }

    fun getRedditVideoMpdUrl(link: String): String? {
        val httpUrl = link.toHttpUrlOrNull() ?: return null
        if (!httpUrl.host.contains("v.redd.it")) return null
        val videoId = httpUrl.pathSegments.firstOrNull() ?: return null
        if (videoId.isBlank()) return null
        return httpUrl.newBuilder()
            .encodedPath("/$videoId/DASHPlaylist.mpd")
            .build()
            .toString()
    }
}
