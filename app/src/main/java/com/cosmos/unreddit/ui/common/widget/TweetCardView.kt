package com.cosmos.unreddit.ui.common.widget

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import coil.size.Scale
import com.cosmos.unreddit.R
import com.cosmos.unreddit.util.LinkUtil
import com.cosmos.unreddit.util.TweetResolver
import com.cosmos.unreddit.util.extension.load
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.RelativeCornerSize
import com.google.android.material.shape.ShapeAppearanceModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

class TweetCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.materialCardViewStyle
) : MaterialCardView(context, attrs, defStyleAttr) {

    private val avatarView: ShapeableImageView
    private val authorNameView: TextView
    private val authorHandleView: TextView
    private val badgeView: TextView
    private val bodyTextView: TextView
    private val mediaContainer: FrameLayout
    private val mediaImageView: ShapeableImageView
    private val mediaTypeIndicator: CardButton
    private val quoteCard: MaterialCardView
    private val quoteAuthorView: TextView
    private val quoteTextView: TextView
    private val quoteMediaImageView: ShapeableImageView
    private val metricsTextView: TextView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var resolveJob: Job? = null
    private var boundUrl: String? = null

    init {
        radius = dpToPx(15f)
        cardElevation = dpToPx(2f)
        setCardBackgroundColor(ContextCompat.getColor(context, R.color.card_background_color))
        strokeColor = ContextCompat.getColor(context, R.color.chip_background_color)
        strokeWidth = dpToPxInt(1f)
        isClickable = true
        isFocusable = true

        val padding = dpToPxInt(12f)
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(padding, padding, padding, padding)
        }

        // Header row: Avatar + Name/Handle + X badge
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val avatarSize = dpToPxInt(36f)
        avatarView = ShapeableImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize)
            scaleType = ImageView.ScaleType.CENTER_CROP
            shapeAppearanceModel = ShapeAppearanceModel.Builder()
                .setAllCornerSizes(RelativeCornerSize(0.5f))
                .build()
        }

        val authorColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginStart = dpToPxInt(10f)
                marginEnd = dpToPxInt(8f)
            }
        }

        authorNameView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text_color))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        authorHandleView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(context, R.color.text_color_secondary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        authorColumn.addView(authorNameView)
        authorColumn.addView(authorHandleView)

        badgeView = TextView(context).apply {
            text = "𝕏"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text_color_secondary))
        }

        headerLayout.addView(avatarView)
        headerLayout.addView(authorColumn)
        headerLayout.addView(badgeView)

        // Tweet body text
        bodyTextView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPxInt(8f)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(context, R.color.text_color))
            setLineSpacing(0f, 1.15f)
        }

        // Media preview container
        mediaContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPxInt(10f)
            }
            visibility = View.GONE
        }

        val mediaCornerRadius = dpToPx(10f)
        mediaImageView = ShapeableImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            adjustViewBounds = true
            maxHeight = dpToPxInt(280f)
            minimumHeight = dpToPxInt(140f)
            scaleType = ImageView.ScaleType.CENTER_CROP
            shapeAppearanceModel = ShapeAppearanceModel.Builder()
                .setAllCorners(CornerFamily.ROUNDED, mediaCornerRadius)
                .build()
        }

        val indicatorSize = context.resources.getDimensionPixelSize(R.dimen.app_bar_button_size)
        mediaTypeIndicator = CardButton(context).apply {
            layoutParams = FrameLayout.LayoutParams(indicatorSize, indicatorSize).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                val m = dpToPxInt(12f)
                setMargins(m, m, m, m)
            }
            isClickable = false
            isFocusable = false
            visibility = View.GONE
        }

        mediaContainer.addView(mediaImageView)
        mediaContainer.addView(mediaTypeIndicator)

        // Quote tweet card
        quoteCard = MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPxInt(8f)
            }
            radius = dpToPx(10f)
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.background))
            strokeColor = ContextCompat.getColor(context, R.color.chip_background_color)
            strokeWidth = dpToPxInt(1f)
            visibility = View.GONE
        }

        val quoteLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val qPad = dpToPxInt(10f)
            setPadding(qPad, qPad, qPad, qPad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        quoteAuthorView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text_color))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        quoteTextView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPxInt(4f)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(context, R.color.text_color))
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        }

        quoteMediaImageView = ShapeableImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPxInt(140f)
            ).apply {
                topMargin = dpToPxInt(6f)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            shapeAppearanceModel = ShapeAppearanceModel.Builder()
                .setAllCorners(CornerFamily.ROUNDED, dpToPx(8f))
                .build()
            visibility = View.GONE
        }

        quoteLayout.addView(quoteAuthorView)
        quoteLayout.addView(quoteTextView)
        quoteLayout.addView(quoteMediaImageView)
        quoteCard.addView(quoteLayout)

        // Footer metrics row
        metricsTextView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPxInt(8f)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(context, R.color.text_color_secondary))
            visibility = View.GONE
        }

        rootLayout.addView(headerLayout)
        rootLayout.addView(bodyTextView)
        rootLayout.addView(mediaContainer)
        rootLayout.addView(quoteCard)
        rootLayout.addView(metricsTextView)

        addView(rootLayout)
    }

    fun bindTweetUrl(
        url: String,
        blurMedia: Boolean = false,
        onCardClick: (String) -> Unit,
        onMediaClick: (TweetResolver.ResolvedTweet) -> Unit
    ) {
        boundUrl = url
        resolveJob?.cancel()

        setOnClickListener {
            onCardClick(url)
        }

        val cached = TweetResolver.getCachedTweet(url)
        if (cached != null) {
            renderResolvedTweet(cached, blurMedia, onCardClick, onMediaClick)
            return
        }

        renderLoadingState(url)

        resolveJob = scope.launch {
            val resolved = TweetResolver.resolveTweet(url)
            if (boundUrl != url) return@launch
            if (resolved != null) {
                renderResolvedTweet(resolved, blurMedia, onCardClick, onMediaClick)
            } else {
                renderFallbackState(url)
            }
        }
    }

    private fun renderLoadingState(url: String) {
        val handle = extractHandleFromUrl(url)
        avatarView.isVisible = false
        authorNameView.text = handle?.let { "@$it" } ?: "Twitter / X"
        authorHandleView.isVisible = false
        bodyTextView.isVisible = true
        bodyTextView.text = "Loading tweet…"
        mediaContainer.isVisible = false
        quoteCard.isVisible = false
        metricsTextView.isVisible = false
    }

    private fun renderFallbackState(url: String) {
        val handle = extractHandleFromUrl(url)
        avatarView.isVisible = false
        authorNameView.text = handle?.let { "@$it" } ?: "Twitter / X"
        authorHandleView.isVisible = false
        bodyTextView.isVisible = true
        bodyTextView.text = url
        mediaContainer.isVisible = false
        quoteCard.isVisible = false
        metricsTextView.isVisible = false
    }

    private fun renderResolvedTweet(
        tweet: TweetResolver.ResolvedTweet,
        blurMedia: Boolean,
        onCardClick: (String) -> Unit,
        onMediaClick: (TweetResolver.ResolvedTweet) -> Unit
    ) {
        if (!tweet.authorAvatarUrl.isNullOrBlank()) {
            avatarView.isVisible = true
            avatarView.load(tweet.authorAvatarUrl, false) {
                scale(Scale.FILL)
            }
        } else {
            avatarView.isVisible = false
        }

        authorNameView.text = tweet.authorName.ifBlank { "@${tweet.authorHandle}" }
        if (tweet.authorHandle.isNotBlank()) {
            authorHandleView.isVisible = true
            authorHandleView.text = "@${tweet.authorHandle}"
        } else {
            authorHandleView.isVisible = false
        }

        if (tweet.text.isNotBlank()) {
            bodyTextView.isVisible = true
            bodyTextView.text = tweet.text
        } else {
            bodyTextView.isVisible = false
        }

        val previewUrl = tweet.previewImageUrl
        if (tweet.hasMedia && !previewUrl.isNullOrBlank()) {
            mediaContainer.isVisible = true
            mediaImageView.load(previewUrl, blurMedia) {
                scale(Scale.FILL)
                error(R.drawable.preview_image_fallback)
                fallback(R.drawable.preview_image_fallback)
            }
            when {
                tweet.hasVideo -> {
                    mediaTypeIndicator.isVisible = true
                    mediaTypeIndicator.setIcon(R.drawable.ic_play)
                }
                tweet.mediaCount > 1 -> {
                    mediaTypeIndicator.isVisible = true
                    mediaTypeIndicator.setIcon(R.drawable.ic_gallery)
                }
                else -> {
                    mediaTypeIndicator.isVisible = false
                }
            }
            mediaContainer.setOnClickListener {
                onMediaClick(tweet)
            }
        } else {
            mediaContainer.isVisible = false
            mediaContainer.setOnClickListener(null)
        }

        val quote = tweet.quoteTweet
        if (quote != null) {
            quoteCard.isVisible = true
            quoteAuthorView.text = if (quote.authorHandle.isNotBlank()) {
                "${quote.authorName} (@${quote.authorHandle})"
            } else {
                quote.authorName
            }
            quoteTextView.isVisible = quote.text.isNotBlank()
            quoteTextView.text = quote.text
            val quotePreview = quote.previewImageUrl
            if (quote.hasMedia && !quotePreview.isNullOrBlank()) {
                quoteMediaImageView.isVisible = true
                quoteMediaImageView.load(quotePreview, blurMedia) {
                    scale(Scale.FILL)
                }
            } else {
                quoteMediaImageView.isVisible = false
            }
            quoteCard.setOnClickListener {
                if (quote.hasMedia) {
                    onMediaClick(quote)
                } else {
                    onCardClick(quote.url)
                }
            }
        } else {
            quoteCard.isVisible = false
            quoteCard.setOnClickListener(null)
        }

        val metricsParts = mutableListOf<String>()
        if (tweet.replies > 0) metricsParts.add("💬 ${formatCount(tweet.replies)}")
        if (tweet.retweets > 0) metricsParts.add("🔁 ${formatCount(tweet.retweets)}")
        if (tweet.likes > 0) metricsParts.add("❤️ ${formatCount(tweet.likes)}")

        if (metricsParts.isNotEmpty()) {
            metricsTextView.isVisible = true
            metricsTextView.text = metricsParts.joinToString("   ")
        } else {
            metricsTextView.isVisible = false
        }
    }

    private fun extractHandleFromUrl(url: String): String? {
        val regex = Regex(
            "(?:twitter\\.com|x\\.com|fxtwitter\\.com|vxtwitter\\.com|fixupx\\.com|nitter\\.net)/([A-Za-z0-9_]+)/status/\\d+",
            RegexOption.IGNORE_CASE
        )
        val handle = regex.find(url)?.groupValues?.getOrNull(1)
        return handle?.takeIf { !it.equals("i", ignoreCase = true) }
    }

    private fun formatCount(count: Int): String {
        return when {
            count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000f)
            count >= 1_000 -> String.format(Locale.US, "%.1fK", count / 1_000f)
            else -> count.toString()
        }
    }

    private fun dpToPx(dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }

    private fun dpToPxInt(dp: Float): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
