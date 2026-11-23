package com.cosmos.unreddit.util

import android.content.Context
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor
import com.google.android.exoplayer2.upstream.cache.SimpleCache

class ExoPlayerHelper(context: Context) {

    private val cacheDir = context.cacheDir

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setUserAgent(LinkUtil.USER_AGENT)

    private val exoDatabaseProvider = getDatabaseProvider(context.applicationContext)

    private val simpleCache = getSimpleCache(cacheDir, exoDatabaseProvider)

    private val cacheDataSourceFactory = CacheDataSource.Factory()
        .setCache(simpleCache)
        .setUpstreamDataSourceFactory(httpDataSourceFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    val defaultMediaSourceFactory by lazy { DefaultMediaSourceFactory(cacheDataSourceFactory) }

    fun setRequestProperties(requestProperties: Map<String, String>) {
        httpDataSourceFactory.setDefaultRequestProperties(requestProperties)
    }

    fun getMediaItem(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        if (url.contains(".mpd") || url.contains("DASHPlaylist.mpd")) {
            builder.setMimeType("application/dash+xml")
        } else if (url.contains(".m3u8") || url.contains("HLSPlaylist.m3u8")) {
            builder.setMimeType("application/x-mpegURL")
        }
        return builder.build()
    }

    fun getMediaSource(url: String): MediaSource {
        return getMediaSource(getMediaItem(url))
    }

    fun getMediaSource(mediaItem: MediaItem): MediaSource {
        return defaultMediaSourceFactory.createMediaSource(mediaItem)
    }

    fun clearCache() {
        // Shared SimpleCache is kept alive across adapters to prevent lock conflicts
    }

    companion object {
        @Volatile
        private var sharedDatabaseProvider: StandaloneDatabaseProvider? = null

        @Volatile
        private var sharedSimpleCache: SimpleCache? = null

        @Synchronized
        private fun getDatabaseProvider(context: Context): StandaloneDatabaseProvider {
            return sharedDatabaseProvider ?: StandaloneDatabaseProvider(context).also {
                sharedDatabaseProvider = it
            }
        }

        @Synchronized
        private fun getSimpleCache(
            cacheDir: java.io.File,
            databaseProvider: StandaloneDatabaseProvider
        ): SimpleCache {
            return sharedSimpleCache ?: SimpleCache(
                 java.io.File(cacheDir, "exo_cache"),
                LeastRecentlyUsedCacheEvictor(100 * 1024 * 1024),
                databaseProvider
            ).also {
                sharedSimpleCache = it
            }
        }
    }
}
