package com.cosmos.unreddit.ui.postlist

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.data.model.db.PostEntity
import com.cosmos.unreddit.data.model.preferences.ContentPreferences
import com.cosmos.unreddit.databinding.IncludePostFlairsBinding
import com.cosmos.unreddit.databinding.IncludePostInfoBinding
import com.cosmos.unreddit.databinding.IncludePostMetricsBinding
import com.cosmos.unreddit.databinding.ItemPostImageBinding
import com.cosmos.unreddit.databinding.ItemPostLinkBinding
import com.cosmos.unreddit.databinding.ItemPostTextBinding
import com.cosmos.unreddit.databinding.ItemPostCompactBinding
import com.cosmos.unreddit.ui.common.widget.AwardView
import com.cosmos.unreddit.util.ClickableMovementMethod
import com.cosmos.unreddit.util.extension.load
import com.cosmos.unreddit.util.extension.setRatio

abstract class PostViewHolder(
    itemView: View,
    private val postInfoBinding: IncludePostInfoBinding,
    private val postMetricsBinding: IncludePostMetricsBinding,
    private val postFlairsBinding: IncludePostFlairsBinding,
    listener: PostListAdapter.Listener
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

        postMetricsBinding.buttonMore.setOnClickListener {
            listener.onMenuClick(bindingAdapterPosition)
        }

        postMetricsBinding.buttonSave.setOnClickListener {
            listener.onSaveClick(bindingAdapterPosition)
        }
    }

    open fun bind(
        postEntity: PostEntity,
        contentPreferences: ContentPreferences
    ) {
        postMetricsBinding.post = postEntity
        postFlairsBinding.post = postEntity

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

            binding.imagePostLinkPreview.load(
                postEntity.preview,
                !postEntity.shouldShowPreview(contentPreferences)
            ) {
                error(R.drawable.preview_link_fallback)
                fallback(R.drawable.preview_link_fallback)
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

            val paramsTitle = binding.textPostTitle.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            val paramsPreview = binding.imagePostPreview.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams

            if (leftHandedMode) {
                paramsPreview.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                paramsPreview.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                paramsPreview.startToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                paramsPreview.endToStart = binding.textPostTitle.id

                paramsTitle.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                paramsTitle.endToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                paramsTitle.startToEnd = binding.imagePostPreview.id
                paramsTitle.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            } else {
                paramsPreview.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                paramsPreview.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                paramsPreview.startToEnd = binding.textPostTitle.id
                paramsPreview.endToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET

                paramsTitle.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                paramsTitle.endToStart = binding.imagePostPreview.id
                paramsTitle.startToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                paramsTitle.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            }
            binding.textPostTitle.layoutParams = paramsTitle
            binding.imagePostPreview.layoutParams = paramsPreview

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

            val paramsTitle = binding.textPostTitle.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            val paramsPreview = binding.imagePostPreview.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams

            if (leftHandedMode) {
                paramsPreview.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                paramsPreview.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                paramsPreview.startToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                paramsPreview.endToStart = binding.textPostTitle.id

                paramsTitle.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                paramsTitle.endToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                paramsTitle.startToEnd = binding.imagePostPreview.id
                paramsTitle.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            } else {
                paramsPreview.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                paramsPreview.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                paramsPreview.startToEnd = binding.textPostTitle.id
                paramsPreview.endToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET

                paramsTitle.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                paramsTitle.endToStart = binding.imagePostPreview.id
                paramsTitle.startToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                paramsTitle.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            }
            binding.textPostTitle.layoutParams = paramsTitle
            binding.imagePostPreview.layoutParams = paramsPreview

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

            val paramsTitle = binding.textPostTitle.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            val paramsPreview = binding.imagePostPreview.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams

            if (leftHandedMode) {
                paramsPreview.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                paramsPreview.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                paramsPreview.startToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                paramsPreview.endToStart = binding.textPostTitle.id

                paramsTitle.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                paramsTitle.endToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                paramsTitle.startToEnd = binding.imagePostPreview.id
                paramsTitle.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            } else {
                paramsPreview.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                paramsPreview.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                paramsPreview.startToEnd = binding.textPostTitle.id
                paramsPreview.endToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET

                paramsTitle.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                paramsTitle.endToStart = binding.imagePostPreview.id
                paramsTitle.startToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                paramsTitle.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            }
            binding.textPostTitle.layoutParams = paramsTitle
            binding.imagePostPreview.layoutParams = paramsPreview

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

    class PollPostViewHolder() {

    }
}
