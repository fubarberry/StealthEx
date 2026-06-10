package com.cosmos.unreddit.ui.mediaviewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cosmos.unreddit.data.local.mapper.PostMapper2
import com.cosmos.unreddit.data.model.GalleryMedia
import com.cosmos.unreddit.data.model.GalleryMedia.Type
import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.data.model.Resource
import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.model.Sorting
import com.cosmos.unreddit.data.repository.GfycatRepository
import com.cosmos.unreddit.data.repository.ImgurRepository
import com.cosmos.unreddit.data.repository.PostListRepository
import com.cosmos.unreddit.data.repository.PreferencesRepository
import com.cosmos.unreddit.data.repository.RedgifsRepository
import com.cosmos.unreddit.data.repository.StreamableRepository
import com.cosmos.unreddit.di.DispatchersModule.DefaultDispatcher
import com.cosmos.unreddit.util.LinkUtil
import com.cosmos.unreddit.util.LinkUtil.https
import com.cosmos.unreddit.util.PostUtil
import com.cosmos.unreddit.util.extension.updateValue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class MediaViewerViewModel
@Inject constructor(
    private val imgurRepository: ImgurRepository,
    private val streamableRepository: StreamableRepository,
    private val gfycatRepository: GfycatRepository,
    private val redgifsRepository: RedgifsRepository,
    private val postListRepository: PostListRepository,
    private val postMapper: PostMapper2,
    private val preferencesRepository: PreferencesRepository,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _media: MutableStateFlow<Resource<List<GalleryMedia>>> =
        MutableStateFlow(Resource.Loading())
    val media: StateFlow<Resource<List<GalleryMedia>>> = _media

    private val _selectedPage: MutableStateFlow<Int> = MutableStateFlow(0)
    val selectedPage: StateFlow<Int> = _selectedPage

    val isMultiMedia: StateFlow<Boolean> = _media
        .filter { it is Resource.Success }
        .map { (it as Resource.Success).data.size > 1 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isVideoMuted: Flow<Boolean>
        get() = preferencesRepository.getMuteVideo(false)

    var hideControls: Boolean = false

    init {
        viewModelScope.launch { preferencesRepository.getMuteVideo(false).first() }
    }

    fun loadMedia(link: String, mediaType: MediaType, forceUpdate: Boolean = false) {
        android.util.Log.d("StealthEx", "loadMedia: link=$link, mediaType=$mediaType, forceUpdate=$forceUpdate")
        if (_media.value !is Resource.Success || forceUpdate) {
            viewModelScope.launch {
                val httpsLink = withContext(defaultDispatcher) { link.https }
                val normalizedLink = if (mediaType == MediaType.REDDIT_VIDEO || mediaType == MediaType.REDDIT_GIF) {
                    LinkUtil.getRedditVideoMpdUrl(httpsLink) ?: httpsLink
                } else {
                    httpsLink
                }
                retrieveMedia(normalizedLink, mediaType)
            }
        }
    }

    private suspend fun retrieveMedia(link: String, mediaType: MediaType) {
        when (mediaType) {
            MediaType.IMGUR_IMAGE, MediaType.IMAGE -> {
                setMedia(GalleryMedia.singleton(Type.IMAGE, link))
            }
            MediaType.IMGUR_LINK -> {
                val id = LinkUtil.getImageIdFromImgurLink(link)
                setMedia(GalleryMedia.singleton(Type.IMAGE, LinkUtil.getUrlFromImgurId(id)))
            }
            MediaType.IMGUR_GIF -> {
                setMedia(GalleryMedia.singleton(Type.VIDEO, LinkUtil.getImgurVideo(link)))
            }
            MediaType.REDDIT_GIF -> {
                android.util.Log.d("StealthEx", "retrieveMedia REDDIT_GIF: link=$link")
                val targetLink = if (link.contains("v.redd.it") && !link.contains(".mpd") && !link.contains(".m3u8")) {
                    LinkUtil.getRedditVideoMpdUrl(link) ?: link
                } else {
                    link
                }
                android.util.Log.d("StealthEx", "retrieveMedia REDDIT_GIF targeted: $targetLink")
                if (targetLink.contains(".mpd") || targetLink.contains(".m3u8")) {
                    setMedia(GalleryMedia.singleton(Type.VIDEO, targetLink, null))
                } else {
                    val resolved = runCatching {
                        LinkUtil.resolveMediaUrls(targetLink, defaultDispatcher)
                    }.getOrNull()
                    android.util.Log.d("StealthEx", "REDDIT_GIF resolved urls: video=${resolved?.videoUrl}, audio=${resolved?.audioUrl}")
                    if (resolved != null) {
                        setMedia(GalleryMedia.singleton(Type.VIDEO, resolved.videoUrl, null))
                    } else {
                        setMedia(GalleryMedia.singleton(Type.VIDEO, targetLink, null))
                    }
                }
            }
            MediaType.IMGUR_VIDEO, MediaType.VIDEO -> {
                android.util.Log.d("StealthEx", "retrieveMedia IMGUR_VIDEO/VIDEO: link=$link")
                setMedia(GalleryMedia.singleton(Type.VIDEO, link))
            }
            MediaType.REDDIT_VIDEO -> {
                android.util.Log.d("StealthEx", "retrieveMedia REDDIT_VIDEO: link=$link")
                val targetLink = if (link.contains("v.redd.it") && !link.contains(".mpd") && !link.contains(".m3u8")) {
                    LinkUtil.getRedditVideoMpdUrl(link) ?: link
                } else {
                    link
                }
                android.util.Log.d("StealthEx", "retrieveMedia REDDIT_VIDEO targeted: $targetLink")
                if (targetLink.contains(".mpd") || targetLink.contains(".m3u8")) {
                    setMedia(GalleryMedia.singleton(Type.VIDEO, targetLink, null))
                } else {
                    val resolved = runCatching {
                        LinkUtil.resolveMediaUrls(targetLink, defaultDispatcher)
                    }.getOrNull()
                    android.util.Log.d("StealthEx", "REDDIT_VIDEO resolved urls: video=${resolved?.videoUrl}, audio=${resolved?.audioUrl}")
                    if (resolved != null) {
                        setMedia(GalleryMedia.singleton(Type.VIDEO, resolved.videoUrl, resolved.audioUrl))
                    } else {
                        val sound = LinkUtil.getRedditSoundTrack(targetLink).takeIf { it != targetLink }
                        android.util.Log.d("StealthEx", "REDDIT_VIDEO fallback sound check: $sound")
                        setMedia(
                            GalleryMedia.singleton(Type.VIDEO, targetLink, sound)
                        )
                    }
                }
            }
            MediaType.GFYCAT -> {
                val id = LinkUtil.getGfycatId(link)

                gfycatRepository.getGfycatGif(id)
                    .onStart {
                        _media.value = Resource.Loading()
                    }
                    .catch {
                        catchError(it)
                    }
                    .map {
                        GalleryMedia.singleton(Type.VIDEO, it.gfyItem.contentUrls.mp4.url)
                    }
                    .collect {
                        setMedia(it)
                    }
            }
            MediaType.REDGIFS -> {
                val id = LinkUtil.getGfycatId(link)
                android.util.Log.d("StealthEx", "retrieveMedia REDGIFS: link=$link, id=$id")

                redgifsRepository.getRedgifsGif(id)
                    .onStart {
                        _media.value = Resource.Loading()
                    }
                    .catch {
                        android.util.Log.e("StealthEx", "REDGIFS API request failed for id $id", it)
                        catchError(it)
                    }
                    .map {
                        val videoUrl = it.gif.urls.hd ?: it.gif.urls.sd ?: ""
                        android.util.Log.d("StealthEx", "REDGIFS API resolved: hd=${it.gif.urls.hd}, sd=${it.gif.urls.sd}, final=$videoUrl")
                        GalleryMedia.singleton(Type.VIDEO, videoUrl)
                    }
                    .collect {
                        setMedia(it)
                    }
            }
            MediaType.STREAMABLE -> {
                val shortcode = LinkUtil.getStreamableShortcode(link)
                streamableRepository.getVideo(shortcode).onStart {
                    _media.value = Resource.Loading()
                }.catch {
                    catchError(it)
                }.map { video ->
                    GalleryMedia.singleton(Type.VIDEO, video.files.mp4.url)
                }.collect {
                    setMedia(it)
                }
            }
            MediaType.IMGUR_ALBUM, MediaType.IMGUR_GALLERY -> {
                val albumId = LinkUtil.getAlbumIdFromImgurLink(link)
                imgurRepository.getAlbum(albumId)
                    .map { album ->
                        album.data.images.map { image ->
                            GalleryMedia(
                                if (image.preferVideo) Type.VIDEO else Type.IMAGE,
                                LinkUtil.getUrlFromImgurImage(image),
                                description = image.description
                            )
                        }
                    }
                    .map {
                        // Some Imgur galleries are empty and actually point to a single image
                        it.ifEmpty {
                            GalleryMedia.singleton(Type.IMAGE, LinkUtil.getUrlFromImgurId(albumId))
                        }
                    }
                    .flowOn(defaultDispatcher)
                    .onStart {
                        _media.value = Resource.Loading()
                    }
                    .catch {
                        catchError(it)
                    }
                    .collect {
                        setMedia(it)
                    }
            }
            MediaType.REDDIT_GALLERY -> {
                val permalink = LinkUtil.getPermalinkFromMediaUrl(link)
                postListRepository.getPost(permalink, Sorting(Sort.BEST)).onStart {
                    _media.value = Resource.Loading()
                }.catch {
                    catchError(it)
                }.map { listings ->
                    postMapper.dataToEntity(PostUtil.getPostData(listings)).gallery
                }.collect {
                    setMedia(it)
                }
            }
            else -> {
                _media.value = Resource.Error()
            }
        }
    }

    private fun catchError(throwable: Throwable) {
        android.util.Log.e("StealthEx", "catchError caught exception", throwable)
        when (throwable) {
            is IOException -> _media.value = Resource.Error(message = throwable.message)
            is HttpException -> _media.value = Resource.Error(throwable.code(), throwable.message())
            else -> _media.value = Resource.Error()
        }
    }

    fun setMedia(media: List<GalleryMedia>) {
        _media.updateValue(Resource.Success(media))
    }

    fun setMuted(mutedVideo: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setMuteVideo(mutedVideo)
        }
    }

    fun setSelectedPage(position: Int) {
        _selectedPage.updateValue(position)
    }
}
