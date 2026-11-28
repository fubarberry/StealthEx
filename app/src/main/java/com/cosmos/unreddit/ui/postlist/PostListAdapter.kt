package com.cosmos.unreddit.ui.postlist

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.cosmos.unreddit.data.model.GalleryMedia
import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.data.model.PostType
import com.cosmos.unreddit.data.model.db.PostEntity
import com.cosmos.unreddit.data.model.preferences.ContentPreferences
import com.cosmos.unreddit.data.repository.PostListRepository
import com.cosmos.unreddit.databinding.ItemPostCompactBinding
import com.cosmos.unreddit.databinding.ItemPostFullscreenBinding
import com.cosmos.unreddit.databinding.ItemPostImageBinding
import com.cosmos.unreddit.databinding.ItemPostLinkBinding
import com.cosmos.unreddit.databinding.ItemPostTextBinding
import com.cosmos.unreddit.ui.common.widget.RedditView
import com.cosmos.unreddit.util.ClickableMovementMethod
import com.cosmos.unreddit.util.ExoPlayerHelper
import com.cosmos.unreddit.util.LinkUtil
import com.cosmos.unreddit.util.LinkUtil.https
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.source.MergingMediaSource
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.video.VideoSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class PostListAdapter(
    private val repository: PostListRepository,
    private val postClickListener: PostClickListener,
    private val onLinkClickListener: RedditView.OnLinkClickListener? = null
) : PagingDataAdapter<PostEntity, RecyclerView.ViewHolder>(POST_COMPARATOR) {

    interface PostClickListener {
        fun onClick(post: PostEntity)

        fun onLongClick(post: PostEntity)

        fun onMenuClick(post: PostEntity)

        fun onImageClick(post: PostEntity)

        fun onVideoClick(post: PostEntity)

        fun onLinkClick(post: PostEntity)

        fun onSaveClick(post: PostEntity)

        fun onHideClick(post: PostEntity) {}

        fun onMediaLongClick(post: PostEntity) {}
    }

    interface Listener {
        fun onClick(position: Int, isLong: Boolean = false)

        fun onMediaClick(position: Int)

        fun onMediaLongClick(position: Int) {}

        fun onFullscreenVideoClick(position: Int) {}

        fun onMenuClick(position: Int)

        fun onSaveClick(position: Int)

        fun onHideClick(position: Int) {}
    }

    private val clickableMovementMethod = ClickableMovementMethod(
        object : ClickableMovementMethod.OnClickListener {
            override fun onLinkClick(link: String) {
                onLinkClickListener?.onLinkClick(link)
            }

            override fun onLinkLongClick(link: String) {
                onLinkClickListener?.onLinkLongClick(link)
            }

            override fun onClick() {
                // ignore
            }

            override fun onLongClick() {
                // ignore
            }
        }
    )

    var contentPreferences: ContentPreferences = ContentPreferences(
        showNsfw = false,
        showNsfwPreview = false,
        showSpoilerPreview = false
    )
        set(value) {
            if (field.showNsfwPreview != value.showNsfwPreview ||
                field.showSpoilerPreview != value.showSpoilerPreview
            ) {
                field = value
                notifyDataSetChanged()
                if (layoutType == 2) {
                    scheduleFullscreenVideoCheck()
                }
            }
        }

    var layoutType: Int = 0
        set(value) {
            if (field != value) {
                field = value
                useCompactLayout = (value == 1)
                if (value != 2) {
                    stopActiveVideo()
                }
                notifyDataSetChanged()
            }
        }

    var useCompactLayout: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                val targetType = if (value) 1 else 0
                if (layoutType != 2 && layoutType != targetType) {
                    layoutType = targetType
                } else {
                    notifyDataSetChanged()
                }
            }
        }

    var leftHandedMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    var isVideoMuted: Boolean = false
        set(value) {
            field = value
            exoPlayer?.volume = if (value) 0f else 1f
        }

    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var videoResolveJob: Job? = null
    private val activePlayers = mutableListOf<ExoPlayer>()
    private var exoPlayer: ExoPlayer? = null
    private var exoPlayerHelper: ExoPlayerHelper? = null
    private var activeVideoHolder: PostViewHolder.FullscreenPostViewHolder? = null
    private var attachedRecyclerView: RecyclerView? = null
    private var isHostPaused: Boolean = false
    private val revealedPostIds = mutableSetOf<String>()

    private fun shouldShowPreviewForPost(post: PostEntity): Boolean {
        return post.shouldShowPreview(contentPreferences) || revealedPostIds.contains(post.id)
    }

    private val checkVideoRunnable: Runnable = object : Runnable {
        override fun run() {
            val recyclerView = attachedRecyclerView ?: return
            if (layoutType == 2 && !isHostPaused && recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE) {
                if (recyclerView.itemAnimator?.isRunning == true) {
                    recyclerView.itemAnimator?.isRunning {
                        attachedRecyclerView?.let { rv ->
                            rv.removeCallbacks(this)
                            rv.post(this)
                        }
                    }
                }
                checkAndPlayCurrentFullscreenVideo()
            }
        }
    }

    private val dataObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() {
            if (layoutType == 2) scheduleFullscreenVideoCheck()
        }

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            if (layoutType == 2) scheduleFullscreenVideoCheck()
        }

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            if (layoutType == 2) scheduleFullscreenVideoCheck()
        }

        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
            if (layoutType == 2) scheduleFullscreenVideoCheck()
        }
    }

    private fun scheduleFullscreenVideoCheck() {
        val recyclerView = attachedRecyclerView ?: return
        recyclerView.removeCallbacks(checkVideoRunnable)
        recyclerView.post(checkVideoRunnable)
        if (recyclerView.itemAnimator?.isRunning == true) {
            recyclerView.itemAnimator?.isRunning {
                attachedRecyclerView?.let { rv ->
                    rv.removeCallbacks(checkVideoRunnable)
                    rv.post(checkVideoRunnable)
                }
            }
        }
    }

    private val layoutChangeListener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        val recyclerView = view as? RecyclerView ?: return@OnLayoutChangeListener
        if (layoutType == 2 && !isHostPaused && recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE) {
            if (activeVideoHolder == null || activeVideoHolder?.itemView?.parent == null) {
                scheduleFullscreenVideoCheck()
            }
        }
    }

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (layoutType == 2) {
                if (recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE) {
                    scheduleFullscreenVideoCheck()
                } else {
                    stopIfActiveHolderNotPrimary(recyclerView)
                }
            }
        }

        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (layoutType == 2) {
                if (recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE) {
                    scheduleFullscreenVideoCheck()
                } else {
                    stopIfActiveHolderNotPrimary(recyclerView)
                }
            }
        }
    }

    private val listener = object : Listener {
        override fun onClick(position: Int, isLong: Boolean) {
            getItem(position)?.let {
                if (isLong) {
                    postClickListener.onLongClick(it)
                } else {
                    setPostSeen(position, it)
                    postClickListener.onClick(it)
                }
            }
        }

        override fun onMediaClick(position: Int) {
            getItem(position)?.let {
                setPostSeen(position, it)
                if (
                    layoutType == 2 &&
                    !shouldShowPreviewForPost(it) &&
                    (it.type == PostType.IMAGE || it.type == PostType.VIDEO)
                ) {
                    revealedPostIds.add(it.id)
                    val holder = findFullscreenHolderForPost(position, it)
                    if (holder != null) {
                        holder.bind(it, contentPreferences, isVideoMuted, isRevealed = true)
                        if (canPlayInline(it)) {
                            isHostPaused = false
                            startInlineVideo(holder, it)
                        }
                    } else {
                        notifyItemChanged(position)
                    }
                    return
                }
                when (it.type) {
                    PostType.IMAGE -> postClickListener.onImageClick(it)
                    PostType.LINK -> {
                        if (LinkUtil.isTwitterStatusLink(it.url)) {
                            val cached = com.cosmos.unreddit.util.TweetResolver.getCachedTweet(it.url)
                            if (cached != null && cached.hasMedia) {
                                onLinkClickListener?.onLinkClick("tweet_media:${it.url}")
                            } else {
                                postClickListener.onLinkClick(it)
                            }
                        } else {
                            postClickListener.onLinkClick(it)
                        }
                    }
                    PostType.VIDEO -> {
                        if (layoutType == 2 && canPlayInline(it) && shouldShowPreviewForPost(it)) {
                            val holder = findFullscreenHolderForPost(position, it)
                            if (holder != null && !holder.inlinePlaybackFailed) {
                                isHostPaused = false
                                if (activeVideoHolder === holder && videoResolveJob?.isActive == true) {
                                    return
                                }
                                val currentPlayer = exoPlayer
                                if (
                                    activeVideoHolder === holder &&
                                    currentPlayer != null &&
                                    holder.binding.videoPostPlayer.player === currentPlayer
                                ) {
                                    holder.binding.videoPostPlayer.isVisible = true
                                    holder.binding.videoPostPlayer.showController()
                                } else {
                                    startInlineVideo(holder, it)
                                }
                                return
                            }
                        }
                        postClickListener.onVideoClick(it)
                    }
                    else -> {
                        // ignore
                    }
                }
            }
        }

        override fun onFullscreenVideoClick(position: Int) {
            getItem(position)?.let {
                setPostSeen(position, it)
                if (it.gallery.isNotEmpty()) {
                    postClickListener.onImageClick(it)
                } else {
                    postClickListener.onVideoClick(it)
                }
            }
        }

        override fun onMediaLongClick(position: Int) {
            getItem(position)?.let { post ->
                if (post.type == PostType.IMAGE || post.type == PostType.VIDEO) {
                    postClickListener.onMediaLongClick(post)
                }
            }
        }

        override fun onMenuClick(position: Int) {
            getItem(position)?.let {
                postClickListener.onMenuClick(it)
            }
        }

        override fun onSaveClick(position: Int) {
            getItem(position)?.let {
                postClickListener.onSaveClick(it)
                it.saved = !it.saved
                notifyItemChanged(position, it)
            }
        }

        override fun onHideClick(position: Int) {
            getItem(position)?.let {
                postClickListener.onHideClick(it)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return when (viewType) {
            // Text post
            PostType.TEXT.value -> PostViewHolder.TextPostViewHolder(
                ItemPostTextBinding.inflate(inflater, parent, false),
                listener,
                clickableMovementMethod
            )
            // Image post
            PostType.IMAGE.value -> PostViewHolder.ImagePostViewHolder(
                ItemPostImageBinding.inflate(inflater, parent, false),
                listener
            )
            // Video post
            PostType.VIDEO.value -> PostViewHolder.VideoPostViewHolder(
                ItemPostImageBinding.inflate(inflater, parent, false),
                listener
            )
            // Link post
            PostType.LINK.value -> PostViewHolder.LinkPostViewHolder(
                ItemPostLinkBinding.inflate(inflater, parent, false),
                listener
            )
            // Compact Image post
            VIEW_TYPE_COMPACT_IMAGE -> PostViewHolder.CompactImagePostViewHolder(
                ItemPostCompactBinding.inflate(inflater, parent, false),
                listener
            )
            // Compact Video post
            VIEW_TYPE_COMPACT_VIDEO -> PostViewHolder.CompactVideoPostViewHolder(
                ItemPostCompactBinding.inflate(inflater, parent, false),
                listener
            )
            // Compact Link post
            VIEW_TYPE_COMPACT_LINK -> PostViewHolder.CompactLinkPostViewHolder(
                ItemPostCompactBinding.inflate(inflater, parent, false),
                listener
            )
            // Compact Text post
            VIEW_TYPE_COMPACT_TEXT -> PostViewHolder.CompactTextPostViewHolder(
                ItemPostCompactBinding.inflate(inflater, parent, false),
                listener
            )
            // Fullscreen post
            VIEW_TYPE_FULLSCREEN -> PostViewHolder.FullscreenPostViewHolder(
                ItemPostFullscreenBinding.inflate(inflater, parent, false),
                listener,
                onLinkClickListener
            ) { muted ->
                isVideoMuted = muted
            }
            else -> throw IllegalArgumentException("Unknown type $viewType")
        }
    }

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position) ?: return -1
        return when (layoutType) {
            2 -> VIEW_TYPE_FULLSCREEN
            1 -> when (item.type) {
                PostType.IMAGE -> VIEW_TYPE_COMPACT_IMAGE
                PostType.VIDEO -> VIEW_TYPE_COMPACT_VIDEO
                PostType.LINK -> VIEW_TYPE_COMPACT_LINK
                PostType.TEXT -> VIEW_TYPE_COMPACT_TEXT
            }
            else -> item.type.value
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position) ?: return

        when (getItemViewType(position)) {
            // Text post
            PostType.TEXT.value -> (holder as PostViewHolder.TextPostViewHolder).bind(
                item,
                contentPreferences
            )
            // Image post
            PostType.IMAGE.value -> (holder as PostViewHolder.ImagePostViewHolder).bind(
                item,
                contentPreferences
            )
            // Video post
            PostType.VIDEO.value -> (holder as PostViewHolder.VideoPostViewHolder).bind(
                item,
                contentPreferences
            )
            // Link post
            PostType.LINK.value -> (holder as PostViewHolder.LinkPostViewHolder).bind(
                item,
                contentPreferences
            )
            // Compact Image post
            VIEW_TYPE_COMPACT_IMAGE -> (holder as PostViewHolder.CompactImagePostViewHolder).bind(
                item,
                contentPreferences,
                leftHandedMode
            )
            // Compact Video post
            VIEW_TYPE_COMPACT_VIDEO -> (holder as PostViewHolder.CompactVideoPostViewHolder).bind(
                item,
                contentPreferences,
                leftHandedMode
            )
            // Compact Link post
            VIEW_TYPE_COMPACT_LINK -> (holder as PostViewHolder.CompactLinkPostViewHolder).bind(
                item,
                contentPreferences,
                leftHandedMode
            )
            // Compact Text post
            VIEW_TYPE_COMPACT_TEXT -> (holder as PostViewHolder.CompactTextPostViewHolder).bind(
                item,
                contentPreferences,
                leftHandedMode
            )
            // Fullscreen post
            VIEW_TYPE_FULLSCREEN -> {
                val fullscreenHolder = holder as PostViewHolder.FullscreenPostViewHolder
                if (activeVideoHolder === fullscreenHolder) {
                    stopActiveVideo()
                }
                fullscreenHolder.bind(
                    item,
                    contentPreferences,
                    isVideoMuted,
                    isRevealed = revealedPostIds.contains(item.id)
                )
                scheduleFullscreenVideoCheck()
            }
            else -> throw IllegalArgumentException("Unknown type")
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            val item = getItem(position) ?: return
            if (holder is PostViewHolder.CompactImagePostViewHolder) {
                holder.bind(item, contentPreferences, leftHandedMode)
            } else if (holder is PostViewHolder.CompactVideoPostViewHolder) {
                holder.bind(item, contentPreferences, leftHandedMode)
            } else if (holder is PostViewHolder.CompactLinkPostViewHolder) {
                holder.bind(item, contentPreferences, leftHandedMode)
            } else if (holder is PostViewHolder.CompactTextPostViewHolder) {
                holder.bind(item, contentPreferences, leftHandedMode)
            } else {
                (holder as? PostViewHolder)?.update(item)
            }
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
        registerAdapterDataObserver(dataObserver)
        recyclerView.addOnScrollListener(scrollListener)
        recyclerView.addOnLayoutChangeListener(layoutChangeListener)
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is PostViewHolder.FullscreenPostViewHolder) {
            scheduleFullscreenVideoCheck()
        }
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        if (holder === activeVideoHolder) {
            stopActiveVideo()
        }
        if (layoutType == 2 && holder is PostViewHolder.FullscreenPostViewHolder) {
            scheduleFullscreenVideoCheck()
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        runCatching { unregisterAdapterDataObserver(dataObserver) }
        recyclerView.removeCallbacks(checkVideoRunnable)
        recyclerView.removeOnScrollListener(scrollListener)
        recyclerView.removeOnLayoutChangeListener(layoutChangeListener)
        if (attachedRecyclerView === recyclerView) {
            attachedRecyclerView = null
        }
        releasePlayer()
    }

    private fun findFullscreenHolderForPost(
        position: Int,
        post: PostEntity
    ): PostViewHolder.FullscreenPostViewHolder? {
        val recyclerView = attachedRecyclerView ?: return null
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i) ?: continue
            val holder = recyclerView.getChildViewHolder(child) as? PostViewHolder.FullscreenPostViewHolder ?: continue
            if (holder.boundPost?.id == post.id || holder.bindingAdapterPosition == position) {
                return holder
            }
        }
        return null
    }

    private fun findBestFullscreenHolder(recyclerView: RecyclerView): Pair<PostViewHolder.FullscreenPostViewHolder?, Int> {
        var bestHolder: PostViewHolder.FullscreenPostViewHolder? = null
        var maxVisibleHeight = 0

        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i) ?: continue
            val holder = recyclerView.getChildViewHolder(child) as? PostViewHolder.FullscreenPostViewHolder ?: continue
            if (holder.bindingAdapterPosition == RecyclerView.NO_POSITION) continue
            val visibleTop = maxOf(0, child.top)
            val visibleBottom = minOf(recyclerView.height, child.bottom)
            val visibleHeight = (visibleBottom - visibleTop).coerceAtLeast(0)
            if (visibleHeight > maxVisibleHeight) {
                maxVisibleHeight = visibleHeight
                bestHolder = holder
            }
        }
        return bestHolder to maxVisibleHeight
    }

    private fun stopIfActiveHolderNotPrimary(recyclerView: RecyclerView) {
        val currentActive = activeVideoHolder ?: return
        val (bestHolder, maxVisibleHeight) = findBestFullscreenHolder(recyclerView)
        if (bestHolder !== currentActive || maxVisibleHeight < recyclerView.height / 2) {
            stopActiveVideo()
        }
    }

    private fun isGalleryVideo(post: PostEntity): Boolean {
        return post.mediaType == MediaType.REDDIT_GALLERY &&
            post.gallery.firstOrNull()?.let {
                it.type == GalleryMedia.Type.VIDEO && it.url.isNotBlank()
            } == true
    }

    private fun isGifPost(post: PostEntity): Boolean {
        if (isGalleryVideo(post)) return true
        if (post.mediaType == MediaType.REDDIT_GIF || post.mediaType == MediaType.IMGUR_GIF) {
            return true
        }
        if (post.mediaType == MediaType.VIDEO) {
            val lowerUrl = post.mediaUrl.lowercase()
            if (lowerUrl.endsWith(".gif") || lowerUrl.endsWith(".gifv")) {
                return true
            }
        }
        return false
    }

    private fun canPlayInline(post: PostEntity): Boolean {
        if (isGalleryVideo(post)) return true
        if (post.type != PostType.VIDEO || post.mediaUrl.isBlank()) return false
        return post.mediaType in PLAYABLE_MEDIA_TYPES
    }

    private fun checkAndPlayCurrentFullscreenVideo() {
        val recyclerView = attachedRecyclerView ?: return
        if (layoutType != 2 || isHostPaused || !recyclerView.isShown || recyclerView.height <= 0) {
            stopActiveVideo()
            return
        }

        val (targetHolder, maxVisibleHeight) = findBestFullscreenHolder(recyclerView)
        val post = targetHolder?.boundPost
        if (
            targetHolder != null &&
            maxVisibleHeight >= recyclerView.height / 2 &&
            post != null &&
            canPlayInline(post) &&
            shouldShowPreviewForPost(post)
        ) {
            if (targetHolder.inlinePlaybackFailed) {
                return
            }
            if (activeVideoHolder === targetHolder) {
                if (videoResolveJob?.isActive == true) {
                    return
                }
                val currentPlayer = exoPlayer
                if (
                    currentPlayer != null &&
                    targetHolder.binding.videoPostPlayer.player === currentPlayer &&
                    targetHolder.binding.videoPostPlayer.isVisible
                ) {
                    if (!currentPlayer.isPlaying && currentPlayer.playWhenReady && currentPlayer.playbackState == Player.STATE_READY) {
                        currentPlayer.play()
                    }
                    return
                }
            }
            startInlineVideo(targetHolder, post)
        } else {
            stopActiveVideo()
        }
    }

    private fun startInlineVideo(
        holder: PostViewHolder.FullscreenPostViewHolder,
        post: PostEntity
    ) {
        if (isHostPaused || !canPlayInline(post)) {
            stopActiveVideo()
            return
        }

        stopActiveVideo()
        val isGallery = isGalleryVideo(post)
        val isGif = isGifPost(post)
        activeVideoHolder = holder
        holder.inlinePlaybackFailed = false
        holder.binding.loadingCradle.isVisible = true
        holder.binding.buttonTypeIndicator.isVisible = isGallery
        holder.binding.videoPostPlayer.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        holder.binding.videoPostPlayer.useController = !isGif
        if (isGif) {
            holder.binding.videoPostPlayer.hideController()
        }
        holder.binding.videoPostPlayer.isVisible = true

        videoResolveJob = adapterScope.launch {
            val context = holder.binding.root.context.applicationContext
            val httpsUrl = if (isGallery) {
                post.gallery.first().url.https
            } else {
                post.mediaUrl.https
            }
            val resolved = withContext(Dispatchers.IO) {
                when (post.mediaType) {
                    MediaType.IMGUR_GIF -> LinkUtil.getImgurVideo(httpsUrl) to null
                    MediaType.REDGIFS -> {
                        val resolvedUrl = repository.resolveRedgifsUrl(LinkUtil.getGfycatId(httpsUrl))
                        (resolvedUrl ?: httpsUrl) to null
                    }
                    MediaType.GFYCAT -> {
                        val resolvedUrl = repository.resolveGfycatUrl(LinkUtil.getGfycatId(httpsUrl))
                        (resolvedUrl ?: httpsUrl) to null
                    }
                    MediaType.STREAMABLE -> {
                        val resolvedUrl = repository.resolveStreamableUrl(LinkUtil.getStreamableShortcode(httpsUrl))
                        (resolvedUrl ?: httpsUrl) to null
                    }
                    MediaType.REDDIT_VIDEO, MediaType.REDDIT_GIF, MediaType.VIDEO, MediaType.IMGUR_VIDEO -> {
                        if (httpsUrl.contains("redgifs.com", ignoreCase = true) && !httpsUrl.contains("signature=")) {
                            val resolvedUrl = repository.resolveRedgifsUrl(LinkUtil.getGfycatId(httpsUrl))
                            (resolvedUrl ?: httpsUrl) to null
                        } else if (httpsUrl.contains("v.redd.it") && !httpsUrl.contains(".mpd") && !httpsUrl.contains(".m3u8")) {
                            (LinkUtil.getRedditVideoMpdUrl(httpsUrl) ?: httpsUrl) to null
                        } else {
                            httpsUrl to null
                        }
                    }
                    else -> httpsUrl to null
                }
            }

            if (!isActive || isHostPaused || activeVideoHolder !== holder) return@launch

            val (videoUrl, audioUrl) = resolved
            if (videoUrl.isBlank()) {
                holder.inlinePlaybackFailed = true
                holder.binding.loadingCradle.isVisible = false
                holder.binding.videoPostPlayer.keepScreenOn = false
                holder.binding.videoPostPlayer.isVisible = false
                holder.binding.buttonTypeIndicator.isVisible = true
                return@launch
            }

            val helper = exoPlayerHelper ?: ExoPlayerHelper(context).also { exoPlayerHelper = it }
            val parsedUrl = videoUrl.toHttpUrlOrNull()
            if (parsedUrl != null && parsedUrl.host.contains("redgifs", ignoreCase = true)) {
                val requestProperties = parsedUrl
                    .queryParameterNames
                    .associateWith { parsedUrl.queryParameter(it) ?: "" }
                helper.setRequestProperties(requestProperties)
            } else {
                helper.setRequestProperties(emptyMap())
            }

            val player = ExoPlayer.Builder(context)
                .setMediaSourceFactory(helper.defaultMediaSourceFactory)
                .build()
            activePlayers.add(player)
            exoPlayer = player

            var currentVideoUrl: String = videoUrl
            var currentAudioUrl: String? = audioUrl
            var triedRedditFallback = false

            fun prepareSource(vUrl: String, aUrl: String?) {
                val videoItem = helper.getMediaItem(vUrl)
                if (aUrl != null) {
                    val videoSource = helper.getMediaSource(videoItem)
                    val audioSource = helper.getMediaSource(aUrl)
                    player.setMediaSource(MergingMediaSource(videoSource, audioSource))
                } else {
                    player.setMediaSource(helper.getMediaSource(videoItem))
                }
                player.prepare()
                player.play()
            }

            player.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (activeVideoHolder === holder && exoPlayer === player) {
                        applyVideoAspectRatio(holder, videoSize)
                    }
                }

                override fun onRenderedFirstFrame() {
                    if (activeVideoHolder === holder && exoPlayer === player) {
                        applyVideoAspectRatio(holder, player.videoSize)
                        holder.binding.videoPostPlayer.post {
                            if (activeVideoHolder === holder && exoPlayer === player) {
                                applyVideoAspectRatio(holder, player.videoSize)
                            }
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (activeVideoHolder === holder && exoPlayer === player) {
                        updateKeepScreenOn(holder, player)
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (activeVideoHolder === holder && exoPlayer === player) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                holder.binding.loadingCradle.isVisible = true
                            }
                            Player.STATE_READY -> {
                                holder.inlinePlaybackFailed = false
                                holder.binding.loadingCradle.isVisible = false
                                holder.binding.videoPostPlayer.isVisible = true
                                holder.binding.buttonTypeIndicator.isVisible = isGallery
                            }
                            else -> {}
                        }
                        updateKeepScreenOn(holder, player)
                    }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    if (activeVideoHolder === holder && exoPlayer === player) {
                        var hasAudioTrack = false
                        val groups = tracks.groups
                        for (i in 0 until groups.size) {
                            for (j in 0 until groups[i].length) {
                                val mime = groups[i].getTrackFormat(j).sampleMimeType
                                if (mime != null && mime.contains("audio")) {
                                    hasAudioTrack = true
                                    break
                                }
                            }
                        }
                        holder.binding.buttonMute.isVisible = hasAudioTrack
                        holder.binding.buttonMute.isChecked = isVideoMuted
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (activeVideoHolder === holder && exoPlayer === player) {
                        if (currentAudioUrl != null) {
                            currentAudioUrl = null
                            prepareSource(currentVideoUrl, null)
                            return
                        }
                        if (!triedRedditFallback && httpsUrl.contains("v.redd.it")) {
                            triedRedditFallback = true
                            adapterScope.launch fallbackLaunch@{
                                val fallback = withContext(Dispatchers.IO) {
                                    runCatching {
                                        LinkUtil.resolveMediaUrls(httpsUrl, Dispatchers.IO)
                                    }.getOrNull()
                                }
                                if (!isActive || isHostPaused || activeVideoHolder !== holder || exoPlayer !== player) {
                                    return@fallbackLaunch
                                }
                                val fallbackVideo = fallback?.videoUrl?.takeIf { it.isNotBlank() && it != currentVideoUrl }
                                    ?: httpsUrl.takeIf { it != currentVideoUrl }
                                if (fallbackVideo != null) {
                                    currentVideoUrl = fallbackVideo
                                    currentAudioUrl = if (post.mediaType != MediaType.REDDIT_GIF) fallback?.audioUrl else null
                                    prepareSource(currentVideoUrl, currentAudioUrl)
                                } else {
                                    holder.inlinePlaybackFailed = true
                                    holder.binding.loadingCradle.isVisible = false
                                    holder.binding.videoPostPlayer.keepScreenOn = false
                                    holder.binding.videoPostPlayer.player = null
                                    holder.binding.videoPostPlayer.isVisible = false
                                    holder.binding.buttonTypeIndicator.isVisible = true
                                }
                            }
                            return
                        }
                        holder.inlinePlaybackFailed = true
                        holder.binding.loadingCradle.isVisible = false
                        holder.binding.videoPostPlayer.keepScreenOn = false
                        holder.binding.videoPostPlayer.player = null
                        holder.binding.videoPostPlayer.isVisible = false
                        holder.binding.buttonTypeIndicator.isVisible = true
                    }
                }
            })

            holder.binding.videoPostPlayer.isVisible = true
            holder.binding.videoPostPlayer.player = player
            player.repeatMode = Player.REPEAT_MODE_ALL
            player.volume = if (isVideoMuted) 0f else 1f
            prepareSource(currentVideoUrl, currentAudioUrl)
        }
    }

    private fun applyVideoAspectRatio(
        holder: PostViewHolder.FullscreenPostViewHolder,
        videoSize: VideoSize
    ) {
        if (videoSize.width <= 0 || videoSize.height <= 0) return
        val swapped = videoSize.unappliedRotationDegrees == 90 ||
            videoSize.unappliedRotationDegrees == 270
        val width = if (swapped) videoSize.height else videoSize.width
        val height = if (swapped) videoSize.width else videoSize.height
        val ratio = (width * videoSize.pixelWidthHeightRatio) / height.toFloat()
        if (ratio <= 0f || ratio.isNaN() || ratio.isInfinite()) return
        holder.binding.videoPostPlayer.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        if (videoSize.unappliedRotationDegrees == 0) {
            (holder.binding.videoPostPlayer.videoSurfaceView as? android.view.TextureView)
                ?.setTransform(null)
        }
        holder.binding.videoPostPlayer.findViewById<AspectRatioFrameLayout>(
            com.google.android.exoplayer2.ui.R.id.exo_content_frame
        )?.let { contentFrame ->
            contentFrame.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            contentFrame.setAspectRatio(0f)
            contentFrame.setAspectRatio(ratio)
            contentFrame.requestLayout()
        }
        holder.binding.videoPostPlayer.requestLayout()
    }

    private fun updateKeepScreenOn(
        holder: PostViewHolder.FullscreenPostViewHolder,
        player: ExoPlayer
    ) {
        holder.binding.videoPostPlayer.keepScreenOn =
            player.isPlaying ||
            (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
    }

    fun stopActiveVideo() {
        videoResolveJob?.cancel()
        videoResolveJob = null
        activeVideoHolder?.let { holder ->
            holder.binding.videoPostPlayer.keepScreenOn = false
            holder.binding.videoPostPlayer.player = null
            holder.binding.videoPostPlayer.isVisible = false
            holder.binding.loadingCradle.isVisible = false
            holder.binding.buttonMute.isVisible = false
            val bound = holder.boundPost
            holder.binding.buttonTypeIndicator.isVisible =
                bound?.type == PostType.VIDEO ||
                bound?.mediaType in setOf(
                    MediaType.REDDIT_GALLERY,
                    MediaType.IMGUR_ALBUM,
                    MediaType.IMGUR_GALLERY
                )
        }
        activeVideoHolder = null
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        if (activePlayers.isNotEmpty()) {
            for (p in activePlayers) {
                runCatching {
                    p.stop()
                    p.release()
                }
            }
            activePlayers.clear()
        }
    }

    fun pausePlayback() {
        isHostPaused = true
        attachedRecyclerView?.removeCallbacks(checkVideoRunnable)
        stopActiveVideo()
    }

    fun resumePlayback() {
        isHostPaused = false
        if (layoutType == 2) {
            scheduleFullscreenVideoCheck()
        }
    }

    fun releasePlayer() {
        attachedRecyclerView?.removeCallbacks(checkVideoRunnable)
        stopActiveVideo()
    }

    private fun setPostSeen(position: Int, post: PostEntity) {
        post.seen = true
        notifyItemChanged(position, post)
    }

    companion object {
        private const val VIEW_TYPE_COMPACT_IMAGE = 11
        private const val VIEW_TYPE_COMPACT_VIDEO = 12
        private const val VIEW_TYPE_COMPACT_LINK = 13
        private const val VIEW_TYPE_COMPACT_TEXT = 14
        private const val VIEW_TYPE_FULLSCREEN = 20

        private val PLAYABLE_MEDIA_TYPES = setOf(
            MediaType.REDDIT_VIDEO,
            MediaType.REDDIT_GIF,
            MediaType.VIDEO,
            MediaType.IMGUR_VIDEO,
            MediaType.IMGUR_GIF,
            MediaType.REDGIFS,
            MediaType.GFYCAT,
            MediaType.STREAMABLE
        )

        private val POST_COMPARATOR = object : DiffUtil.ItemCallback<PostEntity>() {
            override fun areItemsTheSame(oldItem: PostEntity, newItem: PostEntity): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: PostEntity, newItem: PostEntity): Boolean {
                return oldItem == newItem
            }

            override fun getChangePayload(oldItem: PostEntity, newItem: PostEntity): Any {
                return newItem
            }
        }
    }
}
