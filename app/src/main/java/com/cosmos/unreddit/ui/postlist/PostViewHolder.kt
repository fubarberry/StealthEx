package com.cosmos.unreddit.ui.postlist

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.size.Precision
import coil.size.Scale
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.Block
import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.data.model.PostType
import com.cosmos.unreddit.data.model.db.PostEntity
import com.cosmos.unreddit.data.model.preferences.ContentPreferences
import com.cosmos.unreddit.databinding.IncludePostFlairsBinding
import com.cosmos.unreddit.databinding.IncludePostInfoBinding
import com.cosmos.unreddit.databinding.IncludePostMetricsBinding
import com.cosmos.unreddit.databinding.ItemPostImageBinding
import com.cosmos.unreddit.databinding.ItemPostLinkBinding
import com.cosmos.unreddit.databinding.ItemPostTextBinding
import com.cosmos.unreddit.databinding.ItemPostCompactBinding
import com.cosmos.unreddit.databinding.ItemPostFullscreenBinding
import com.cosmos.unreddit.ui.common.TextDrawable
import com.cosmos.unreddit.ui.common.widget.AwardView
import com.cosmos.unreddit.ui.common.widget.RedditView
import com.cosmos.unreddit.util.ClickableMovementMethod
import com.cosmos.unreddit.util.LinkUtil
import com.cosmos.unreddit.util.extension.load
import com.cosmos.unreddit.util.extension.setRatio

abstract class PostViewHolder(
    itemView: View,
    private val postInfoBinding: IncludePostInfoBinding,
    private val postMetricsBinding: IncludePostMetricsBinding,
    private val postFlairsBinding: IncludePostFlairsBinding,
    protected val listener: PostListAdapter.Listener
) : RecyclerView.ViewHolder(itemView) {

    private val title = itemView.findViewById<TextView>(R.id.text_post_title)
    private val awards = itemView.findViewById<AwardView>(R.id.awards)

    init {
        itemView.apply {
            setOnClickListener {
                listener.onClick(bindingAdapterPosition)
            }
            setOnLongClickListener {
                listener.onClick(bindingAdapterPosition, true)
                return@setOnLongClickListener true
            }
        }

        postMetricsBinding.imageCommentsIcon.setOnClickListener {
            listener.onClick(bindingAdapterPosition)
        }

        postMetricsBinding.textPostComments.setOnClickListener {
            listener.onClick(bindingAdapterPosition)
        }

        postMetricsBinding.buttonMore.setOnClickListener {
            listener.onMenuClick(bindingAdapterPosition)
        }

        postMetricsBinding.buttonSave.setOnClickListener {
            listener.onSaveClick(bindingAdapterPosition)
        }

        postMetricsBinding.buttonHide.setOnClickListener {
            listener.onHideClick(bindingAdapterPosition)
        }
    }

    open fun bind(
        postEntity: PostEntity,
        contentPreferences: ContentPreferences
    ) {
        postMetricsBinding.post = postEntity
        postFlairsBinding.post = postEntity
        postMetricsBinding.buttonHide.visibility = View.GONE

        postInfoBinding.run {
            this.post = postEntity
            textPostAuthor.text = postEntity.author
            textSubreddit.text = postEntity.subreddit
            textPostDate.visibility = View.VISIBLE
            textDomain.visibility = View.GONE
        }
        postMetricsBinding.textPostDateCompact.visibility = View.GONE

        title.apply {
            text = postEntity.title
            setTextColor(ContextCompat.getColor(context, postEntity.textColor))
        }

        postMetricsBinding.setRatio(postEntity.ratio)

        awards.apply {
            if (postEntity.awards.isNotEmpty()) {
                visibility = View.VISIBLE
                setAwards(postEntity.awards, postEntity.totalAwards)
            } else {
                visibility = View.GONE
            }
        }

        postInfoBinding.textPostAuthor.apply {
            setTextColor(ContextCompat.getColor(context, postEntity.posterType.color))
        }

        when {
            postEntity.hasFlairs -> {
                postFlairsBinding.root.visibility = View.VISIBLE
                postFlairsBinding.postFlair.apply {
                    if (!postEntity.flair.isEmpty()) {
                        visibility = View.VISIBLE

                        setFlair(postEntity.flair)
                    } else {
                        visibility = View.GONE
                    }
                }
            }

            postEntity.isSelf -> {
                postFlairsBinding.root.visibility = View.GONE
            }

            else -> {
                postFlairsBinding.postFlair.visibility = View.GONE
            }
        }

        when {
            postEntity.crosspost != null -> {
                postInfoBinding.groupCrosspost.isVisible = true
                postInfoBinding.textCrosspostSubreddit.text = postEntity.crosspost.subreddit
                postInfoBinding.textCrosspostAuthor.text = postEntity.crosspost.author
            }

            postEntity.crosspostScrap != null -> {
                postInfoBinding.groupCrosspost.isVisible = true
                postInfoBinding.textCrosspostSubreddit.text = postEntity.crosspostScrap?.subreddit
                postInfoBinding.textCrosspostAuthor.text = postEntity.crosspostScrap?.author
            }

            else -> postInfoBinding.groupCrosspost.isVisible = false
        }

        postMetricsBinding.buttonSave.isChecked = postEntity.saved
    }

    open fun update(post: PostEntity) {
        title.setTextColor(ContextCompat.getColor(title.context, post.textColor))
        postMetricsBinding.buttonSave.isChecked = post.saved
    }

    class ImagePostViewHolder(
        private val binding: ItemPostImageBinding,
        listener: PostListAdapter.Listener
    ) : PostViewHolder(
        binding.root,
        binding.includePostInfo,
        binding.includePostMetrics,
        binding.includePostFlairs,
        listener
    ) {

        init {
            binding.imagePostPreview.setOnClickListener {
                listener.onMediaClick(bindingAdapterPosition)
            }
        }

        override fun bind(
            postEntity: PostEntity,
            contentPreferences: ContentPreferences
        ) {
            super.bind(postEntity, contentPreferences)

            binding.imagePostPreview.load(
                postEntity.preview,
                !postEntity.shouldShowPreview(contentPreferences)
            ) {
                error(R.drawable.preview_image_fallback)
                fallback(R.drawable.preview_image_fallback)
            }

            binding.buttonTypeIndicator.apply {
                when (postEntity.mediaType) {
                    MediaType.REDDIT_GALLERY, MediaType.IMGUR_ALBUM, MediaType.IMGUR_GALLERY -> {
                        visibility = View.VISIBLE
                        setIcon(R.drawable.ic_gallery)
                    }

                    else -> {
                        visibility = View.GONE
                    }
                }
            }
        }
    }

    class VideoPostViewHolder(
        private val binding: ItemPostImageBinding,
        listener: PostListAdapter.Listener
    ) : PostViewHolder(
        binding.root,
        binding.includePostInfo,
        binding.includePostMetrics,
        binding.includePostFlairs,
        listener
    ) {

        init {
            binding.imagePostPreview.setOnClickListener {
                listener.onMediaClick(bindingAdapterPosition)
            }
        }

        override fun bind(
            postEntity: PostEntity,
            contentPreferences: ContentPreferences
        ) {
            super.bind(postEntity, contentPreferences)

            binding.imagePostPreview.load(
                postEntity.preview,
                !postEntity.shouldShowPreview(contentPreferences)
            ) {
                error(R.drawable.preview_video_fallback)
                fallback(R.drawable.preview_video_fallback)
            }

            binding.buttonTypeIndicator.apply {
                visibility = View.VISIBLE
                setIcon(R.drawable.ic_play)
            }
        }
    }

    class TextPostViewHolder(
        private val binding: ItemPostTextBinding,
        listener: PostListAdapter.Listener,
        clickableMovementMethod: ClickableMovementMethod
    ) : PostViewHolder(
        binding.root,
        binding.includePostInfo,
        binding.includePostMetrics,
        binding.includePostFlairs,
        listener
    ) {

        init {
            binding.textPostSelf.movementMethod = clickableMovementMethod
            binding.textPostSelf.setOnLongClickListener {
                listener.onClick(bindingAdapterPosition, true)
                true
            }
        }

        override fun bind(
            postEntity: PostEntity,
            contentPreferences: ContentPreferences
        ) {
            super.bind(postEntity, contentPreferences)

            val previewText = postEntity.previewText

            binding.textPostSelf.apply {
                if (postEntity.shouldShowPreview(contentPreferences) && previewText != null) {
                    binding.textPostSelfCard.visibility = View.VISIBLE
                    setText(previewText, false)
                    setTextColor(ContextCompat.getColor(context, postEntity.textColor))
                } else {
                    binding.textPostSelfCard.visibility = View.GONE
                }
            }
        }

        override fun update(post: PostEntity) {
            super.update(post)
            if (binding.textPostSelfCard.isVisible) {
                binding.textPostSelf.apply {
                    setTextColor(ContextCompat.getColor(context, post.textColor))
                }
            }
        }
    }

    class LinkPostViewHolder(
        private val binding: ItemPostLinkBinding,
        listener: PostListAdapter.Listener
    ) : PostViewHolder(
        binding.root,
        binding.includePostInfo,
        binding.includePostMetrics,
        binding.includePostFlairs,
        listener
    ) {

        init {
            binding.imagePostLinkPreview.setOnClickListener {
                listener.onMediaClick(bindingAdapterPosition)
            }
        }

        override fun bind(
            postEntity: PostEntity,
            contentPreferences: ContentPreferences
        ) {
            super.bind(postEntity, contentPreferences)

            if (LinkUtil.isTwitterStatusLink(postEntity.url)) {
                binding.imagePostLinkPreview.dispose()
                binding.imagePostLinkPreview.setImageDrawable(null)
                binding.imagePostLinkPreview.visibility = View.GONE
                binding.tweetCard.visibility = View.VISIBLE
                val blurMedia = !postEntity.shouldShowPreview(contentPreferences)
                binding.tweetCard.bindTweetUrl(
                    url = postEntity.url,
                    blurMedia = blurMedia,
                    onCardClick = { listener.onClick(bindingAdapterPosition) },
                    onMediaClick = { listener.onMediaClick(bindingAdapterPosition) }
                )
            } else {
                binding.tweetCard.visibility = View.GONE
                binding.imagePostLinkPreview.visibility = View.VISIBLE
                binding.imagePostLinkPreview.load(
                    postEntity.preview,
                    !postEntity.shouldShowPreview(contentPreferences)
                ) {
                    error(R.drawable.preview_link_fallback)
                    fallback(R.drawable.preview_link_fallback)
                }
            }
        }
    }

    protected fun applyCompactLeftHandedMode(
        binding: ItemPostCompactBinding,
        leftHandedMode: Boolean
    ) {
        val topRow = binding.topRow
        val isThumbnailFirst = topRow.getChildAt(0) === binding.thumbnailContainer
        if (leftHandedMode != isThumbnailFirst) {
            topRow.removeView(binding.thumbnailContainer)
            if (leftHandedMode) {
                topRow.addView(binding.thumbnailContainer, 0)
            } else {
                topRow.addView(binding.thumbnailContainer)
            }
        }
    }

    class CompactImagePostViewHolder(
        private val binding: ItemPostCompactBinding,
        listener: PostListAdapter.Listener
    ) : PostViewHolder(
        binding.root,
        binding.includePostInfo,
        binding.includePostMetrics,
        binding.includePostFlairs,
        listener
    ) {

        init {
            binding.imagePostPreview.setOnClickListener {
                listener.onMediaClick(bindingAdapterPosition)
            }
        }

        fun bind(
            postEntity: PostEntity,
            contentPreferences: ContentPreferences,
            leftHandedMode: Boolean
        ) {
            super.bind(postEntity, contentPreferences)
            binding.includePostInfo.textPostDate.visibility = View.GONE
            binding.includePostInfo.textDomain.visibility = View.VISIBLE
            binding.includePostMetrics.textPostDateCompact.visibility = View.VISIBLE

            binding.includePostFlairs.hideDomain = true
            binding.includePostFlairs.textDomain.visibility = View.GONE
            binding.includePostInfo.textPostAuthor.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            binding.includePostInfo.textSubreddit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)

            applyCompactLeftHandedMode(binding, leftHandedMode)

            binding.imagePostPreview.load(
                postEntity.preview,
                !postEntity.shouldShowPreview(contentPreferences)
            ) {
                error(R.drawable.preview_image_fallback)
                fallback(R.drawable.preview_image_fallback)
            }

            binding.buttonTypeIndicator.apply {
                when (postEntity.mediaType) {
                    MediaType.REDDIT_GALLERY, MediaType.IMGUR_ALBUM, MediaType.IMGUR_GALLERY -> {
                        visibility = View.VISIBLE
                        setIcon(R.drawable.ic_gallery)
                    }
                    else -> {
                        visibility = View.GONE
                    }
                }
            }
        }
    }

    class CompactVideoPostViewHolder(
        private val binding: ItemPostCompactBinding,
        listener: PostListAdapter.Listener
    ) : PostViewHolder(
        binding.root,
        binding.includePostInfo,
        binding.includePostMetrics,
        binding.includePostFlairs,
        listener
    ) {

        init {
            binding.imagePostPreview.setOnClickListener {
                listener.onMediaClick(bindingAdapterPosition)
            }
        }

        fun bind(
            postEntity: PostEntity,
            contentPreferences: ContentPreferences,
            leftHandedMode: Boolean
        ) {
            super.bind(postEntity, contentPreferences)
            binding.includePostInfo.textPostDate.visibility = View.GONE
            binding.includePostInfo.textDomain.visibility = View.VISIBLE
            binding.includePostMetrics.textPostDateCompact.visibility = View.VISIBLE

            binding.includePostFlairs.hideDomain = true
            binding.includePostFlairs.textDomain.visibility = View.GONE
            binding.includePostInfo.textPostAuthor.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            binding.includePostInfo.textSubreddit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)

            applyCompactLeftHandedMode(binding, leftHandedMode)

            binding.imagePostPreview.load(
                postEntity.preview,
                !postEntity.shouldShowPreview(contentPreferences)
            ) {
                error(R.drawable.preview_video_fallback)
                fallback(R.drawable.preview_video_fallback)
            }

            binding.buttonTypeIndicator.apply {
                visibility = View.VISIBLE
                setIcon(R.drawable.ic_play)
            }
        }
    }

    class CompactLinkPostViewHolder(
        private val binding: ItemPostCompactBinding,
        listener: PostListAdapter.Listener
    ) : PostViewHolder(
        binding.root,
        binding.includePostInfo,
        binding.includePostMetrics,
        binding.includePostFlairs,
        listener
    ) {

        init {
            binding.imagePostPreview.setOnClickListener {
                listener.onMediaClick(bindingAdapterPosition)
            }
        }

        fun bind(
            postEntity: PostEntity,
            contentPreferences: ContentPreferences,
            leftHandedMode: Boolean
        ) {
            super.bind(postEntity, contentPreferences)
            binding.includePostInfo.textPostDate.visibility = View.GONE
            binding.includePostInfo.textDomain.visibility = View.VISIBLE
            binding.includePostMetrics.textPostDateCompact.visibility = View.VISIBLE

            binding.includePostFlairs.hideDomain = true
            binding.includePostFlairs.textDomain.visibility = View.GONE
            binding.includePostInfo.textPostAuthor.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            binding.includePostInfo.textSubreddit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)

            applyCompactLeftHandedMode(binding, leftHandedMode)

            binding.imagePostPreview.load(
                postEntity.preview,
                !postEntity.shouldShowPreview(contentPreferences)
            ) {
                error(R.drawable.preview_link_fallback)
                fallback(R.drawable.preview_link_fallback)
            }

            binding.buttonTypeIndicator.apply {
                visibility = View.VISIBLE
                setIcon(R.drawable.ic_link)
            }
        }
    }

    class CompactTextPostViewHolder(
        private val binding: ItemPostCompactBinding,
        listener: PostListAdapter.Listener
    ) : PostViewHolder(
        binding.root,
        binding.includePostInfo,
        binding.includePostMetrics,
        binding.includePostFlairs,
        listener
    ) {

        init {
            binding.imagePostPreview.setOnClickListener {
                listener.onClick(bindingAdapterPosition)
            }
        }

        fun bind(
            postEntity: PostEntity,
            contentPreferences: ContentPreferences,
            leftHandedMode: Boolean
        ) {
            super.bind(postEntity, contentPreferences)
            binding.includePostInfo.textPostDate.visibility = View.GONE
            binding.includePostInfo.textDomain.visibility = View.VISIBLE
            binding.includePostMetrics.textPostDateCompact.visibility = View.VISIBLE

            binding.includePostFlairs.hideDomain = true
            binding.includePostFlairs.textDomain.visibility = View.GONE
            binding.includePostInfo.textPostAuthor.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            binding.includePostInfo.textSubreddit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)

            applyCompactLeftHandedMode(binding, leftHandedMode)

            val context = binding.root.context
            val bgColor = ContextCompat.getColor(context, R.color.card_background_color)
            val txtColor = ContextCompat.getColor(context, R.color.colorPrimary)
            val textDrawable = TextDrawable("txt", txtColor, bgColor)
            binding.imagePostPreview.dispose()
            binding.imagePostPreview.setImageDrawable(textDrawable)

            binding.buttonTypeIndicator.apply {
                visibility = View.GONE
            }
        }
    }

    class FullscreenPostViewHolder(
        val binding: ItemPostFullscreenBinding,
        listener: PostListAdapter.Listener,
        onLinkClickListener: RedditView.OnLinkClickListener?,
        onMuteToggle: (Boolean) -> Unit
    ) : PostViewHolder(
        binding.root,
        binding.includePostInfo,
        binding.includePostMetrics,
        binding.includePostFlairs,
        listener
    ) {

        var boundPost: PostEntity? = null
            private set

        var inlinePlaybackFailed: Boolean = false

        init {
            binding.imagePostPreview.setOnClickListener {
                if (boundPost?.type == PostType.TEXT) {
                    listener.onClick(bindingAdapterPosition)
                } else {
                    listener.onMediaClick(bindingAdapterPosition)
                }
            }
            binding.videoPostPlayer.findViewById<View>(com.google.android.exoplayer2.ui.R.id.exo_progress)
                ?.setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN,
                        android.view.MotionEvent.ACTION_MOVE -> {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    false
                }
            binding.videoPostPlayer.findViewById<View>(R.id.exo_fullscreen_button)?.apply {
                isVisible = true
                setOnClickListener {
                    listener.onFullscreenVideoClick(bindingAdapterPosition)
                }
            }
            binding.textPostSelf.setOnLinkClickListener(onLinkClickListener)
            binding.textPostSelf.setOnClickListener {
                listener.onClick(bindingAdapterPosition)
            }
            binding.textPostSelf.setOnLongClickListener {
                listener.onClick(bindingAdapterPosition, true)
                true
            }
            binding.textPostSelfCard.setOnClickListener {
                listener.onClick(bindingAdapterPosition)
            }
            binding.textPostSelfCard.setOnLongClickListener {
                listener.onClick(bindingAdapterPosition, true)
                true
            }
            binding.buttonMute.setOnCheckedChangeListener { _, isMuted ->
                onMuteToggle(isMuted)
            }
        }

        fun bind(
            postEntity: PostEntity,
            contentPreferences: ContentPreferences,
            isMuted: Boolean,
            isRevealed: Boolean = false
        ) {
            super.bind(postEntity, contentPreferences)
            boundPost = postEntity
            inlinePlaybackFailed = false

            binding.includePostMetrics.buttonHide.visibility = View.VISIBLE

            binding.videoPostPlayer.player = null
            binding.videoPostPlayer.isVisible = false
            binding.loadingCradle.isVisible = false
            binding.buttonMute.isVisible = false
            binding.buttonMute.isChecked = isMuted
            binding.tweetCardContainer.isVisible = false
            binding.imagePostPreview.dispose()
            binding.imagePostPreview.setImageDrawable(null)
            binding.textPostSelf.removeAllViews()

            val showPreview = postEntity.shouldShowPreview(contentPreferences) || isRevealed

            when (postEntity.type) {
                PostType.IMAGE -> {
                    binding.textPostSelfCard.isVisible = false
                    binding.imagePostPreview.isVisible = true
                    binding.imagePostPreview.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    val imageUrl = if (
                        showPreview &&
                        (postEntity.mediaType == MediaType.IMAGE || postEntity.mediaType == MediaType.IMGUR_IMAGE) &&
                        postEntity.mediaUrl.isNotBlank()
                    ) {
                        postEntity.mediaUrl
                    } else {
                        postEntity.preview
                    }
                    binding.imagePostPreview.load(imageUrl, !showPreview) {
                        scale(Scale.FIT)
                        precision(Precision.INEXACT)
                        placeholder(null)
                        error(R.drawable.image_fallback)
                        fallback(R.drawable.image_fallback)
                    }
                    binding.buttonTypeIndicator.apply {
                        when (postEntity.mediaType) {
                            MediaType.REDDIT_GALLERY, MediaType.IMGUR_ALBUM, MediaType.IMGUR_GALLERY -> {
                                visibility = View.VISIBLE
                                setIcon(R.drawable.ic_gallery)
                            }
                            else -> {
                                visibility = View.GONE
                            }
                        }
                    }
                }
                PostType.VIDEO -> {
                    binding.textPostSelfCard.isVisible = false
                    binding.imagePostPreview.isVisible = true
                    binding.imagePostPreview.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    binding.imagePostPreview.load(postEntity.preview, !showPreview) {
                        scale(Scale.FIT)
                        precision(Precision.INEXACT)
                        placeholder(null)
                        error(R.drawable.video_fallback)
                        fallback(R.drawable.video_fallback)
                    }
                    binding.buttonTypeIndicator.apply {
                        visibility = View.VISIBLE
                        setIcon(R.drawable.ic_play)
                    }
                }
                PostType.TEXT -> {
                    binding.buttonTypeIndicator.visibility = View.GONE
                    val previewText = postEntity.previewText
                    if (showPreview && postEntity.selfRedditText.isNotEmpty()) {
                        binding.imagePostPreview.isVisible = false
                        binding.textPostSelfCard.isVisible = true
                        (binding.textPostSelf.parent as? View)?.scrollTo(0, 0)
                        binding.textPostSelf.apply {
                            setText(postEntity.selfRedditText, true)
                            setTextColor(ContextCompat.getColor(context, postEntity.textColor))
                        }
                    } else if (showPreview && previewText != null) {
                        binding.imagePostPreview.isVisible = false
                        binding.textPostSelfCard.isVisible = true
                        (binding.textPostSelf.parent as? View)?.scrollTo(0, 0)
                        binding.textPostSelf.apply {
                            setPreviewText(Block.TextBlock(previewText))
                            setTextColor(ContextCompat.getColor(context, postEntity.textColor))
                        }
                    } else {
                        binding.textPostSelfCard.isVisible = false
                        binding.imagePostPreview.isVisible = true
                        val context = binding.root.context
                        val bgColor = ContextCompat.getColor(context, R.color.card_background_color)
                        val txtColor = ContextCompat.getColor(context, R.color.colorPrimary)
                        binding.imagePostPreview.setImageDrawable(TextDrawable("txt", txtColor, bgColor))
                    }
                }
                PostType.LINK -> {
                    binding.textPostSelfCard.isVisible = false
                    val isTwitter = LinkUtil.isTwitterStatusLink(postEntity.url)
                    if (isTwitter) {
                        binding.imagePostPreview.isVisible = false
                        binding.buttonTypeIndicator.isVisible = false
                        binding.tweetCardContainer.isVisible = true
                        val blurMedia = !showPreview
                        binding.tweetCard.bindTweetUrl(
                            url = postEntity.url,
                            blurMedia = blurMedia,
                            onCardClick = { listener.onClick(bindingAdapterPosition) },
                            onMediaClick = { listener.onMediaClick(bindingAdapterPosition) }
                        )
                    } else {
                        binding.tweetCardContainer.isVisible = false
                        binding.imagePostPreview.isVisible = true
                        binding.imagePostPreview.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        binding.includePostInfo.textDomain.visibility = View.VISIBLE
                        binding.imagePostPreview.load(postEntity.preview, !showPreview) {
                            scale(Scale.FIT)
                            precision(Precision.INEXACT)
                            placeholder(null)
                            error(R.drawable.link_fallback)
                            fallback(R.drawable.link_fallback)
                        }
                        binding.buttonTypeIndicator.apply {
                            visibility = View.VISIBLE
                            setIcon(R.drawable.ic_link)
                        }
                    }
                }
            }
        }

        override fun update(post: PostEntity) {
            super.update(post)
            boundPost = post
            if (binding.textPostSelfCard.isVisible) {
                binding.textPostSelf.apply {
                    setTextColor(ContextCompat.getColor(context, post.textColor))
                }
            }
        }
    }

    class PollPostViewHolder() {

    }
}
