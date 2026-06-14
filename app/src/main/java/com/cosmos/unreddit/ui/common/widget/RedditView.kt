package com.cosmos.unreddit.ui.common.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.text.Spanned
import androidx.annotation.ColorInt
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.view.children
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.Block.*
import com.cosmos.unreddit.data.model.HtmlBlock
import com.cosmos.unreddit.data.model.RedditText
import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.util.ClickableMovementMethod
import com.cosmos.unreddit.util.LinkUtil
import com.cosmos.unreddit.util.extension.load

class RedditView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayoutCompat(context, attrs, defStyleAttr), ClickableMovementMethod.OnClickListener {

    interface OnLinkClickListener {
        fun onLinkClick(link: String)

        fun onLinkLongClick(link: String)
    }

    private val childParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    private val clickableMovementMethod = ClickableMovementMethod(this)

    private var onLinkClickListener: OnLinkClickListener? = null

    var onExpandAllListener: (() -> Unit)? = null
    private var isMediaExpanded: Boolean = false
    private var currentRedditText: RedditText? = null

    init {
        orientation = VERTICAL
    }

    fun setText(redditText: RedditText, isMediaExpanded: Boolean = false) {
        this.currentRedditText = redditText
        this.isMediaExpanded = isMediaExpanded
        removeAllViews()

        val blocks = redditText.blocks
        for (block in blocks) {
            when (block.type) {
                HtmlBlock.BlockType.TEXT -> {
                    addText(block.block as TextBlock, isMediaExpanded)
                }
                HtmlBlock.BlockType.CODE -> {
                    addCode(block.block as TextBlock)
                }
                HtmlBlock.BlockType.TABLE -> {
                    addTable(block.block as TableBlock)
                }
            }
        }
    }

    fun setPreviewText(textBlock: TextBlock) {
        removeAllViews()
        addText(textBlock)
    }

    fun setTextColor(@ColorInt color: Int) {
        for (child in children) {
            if (child is RedditTextView) {
                child.setTextColor(color)
            }
        }
    }

    private fun addText(textBlock: TextBlock, isMediaExpanded: Boolean = false) {
        addText(textBlock.text, isMediaExpanded)
    }

    private fun addText(charSequence: CharSequence, isMediaExpanded: Boolean = false) {
        val spans = (charSequence as? Spanned)?.getSpans(
            0,
            charSequence.length,
            android.text.style.URLSpan::class.java
        ) ?: emptyArray()

        if (!isMediaExpanded || spans.isEmpty()) {
            val redditTextView = RedditTextView(context).apply {
                layoutParams = childParams
                text = charSequence
                movementMethod = this@RedditView.clickableMovementMethod
            }
            addView(redditTextView)
            return
        }

        val spansWithIndex = spans.map { span ->
            val start = (charSequence as Spanned).getSpanStart(span)
            val end = charSequence.getSpanEnd(span)
            val url = span.url
            val type = when {
                url.startsWith("expand_all:") -> SpanType.EXPAND_ALL
                LinkUtil.getLinkType(url) in listOf(
                    MediaType.IMAGE,
                    MediaType.IMGUR_IMAGE,
                    MediaType.IMGUR_GIF,
                    MediaType.REDDIT_GIF
                ) || url.contains("giphy.com") -> SpanType.IMAGE
                else -> SpanType.OTHER
            }
            ParsedSpan(span, start, end, url, type)
        }.filter { it.type != SpanType.OTHER }.sortedBy { it.start }

        if (spansWithIndex.isEmpty()) {
            val redditTextView = RedditTextView(context).apply {
                layoutParams = childParams
                text = charSequence
                movementMethod = this@RedditView.clickableMovementMethod
            }
            addView(redditTextView)
            return
        }

        var lastEnd = 0
        for (parsedSpan in spansWithIndex) {
            if (parsedSpan.start > lastEnd) {
                val textSegment = charSequence.subSequence(lastEnd, parsedSpan.start)
                if (textSegment.isNotBlank()) {
                    val redditTextView = RedditTextView(context).apply {
                        layoutParams = childParams
                        text = textSegment
                        movementMethod = this@RedditView.clickableMovementMethod
                    }
                    addView(redditTextView)
                }
            }

            if (parsedSpan.type == SpanType.IMAGE) {
                addInlineMedia(parsedSpan.url)
            }

            lastEnd = parsedSpan.end
        }

        if (lastEnd < charSequence.length) {
            val textSegment = charSequence.subSequence(lastEnd, charSequence.length)
            if (textSegment.isNotBlank()) {
                val redditTextView = RedditTextView(context).apply {
                    layoutParams = childParams
                    text = textSegment
                    movementMethod = this@RedditView.clickableMovementMethod
                }
                addView(redditTextView)
            }
        }
    }

    private fun addInlineMedia(url: String) {
        val imageView = ImageView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                context.resources.getDimensionPixelSize(R.dimen.post_image_height)
            ).apply {
                topMargin = context.resources.getDimensionPixelSize(R.dimen.comment_offset).toInt() / 2
                bottomMargin = context.resources.getDimensionPixelSize(R.dimen.comment_offset).toInt() / 2
            }
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            load(url, false)
            setOnClickListener {
                onLinkClick(url)
            }
        }
        addView(imageView)
    }

    private enum class SpanType {
        IMAGE, EXPAND_ALL, OTHER
    }

    private data class ParsedSpan(
        val span: android.text.style.URLSpan,
        val start: Int,
        val end: Int,
        val url: String,
        val type: SpanType
    )

    private fun addCode(codeBlock: TextBlock) {
        val redditTextView = RedditTextView(context).apply {
            layoutParams = childParams
            text = codeBlock.text
        }
        addView(wrapWithScrollView(redditTextView))
    }

    private fun addTable(tableBlock: TableBlock) {
        addView(wrapWithScrollView(tableBlock.getTableLayout(context, clickableMovementMethod)))
    }

    private fun wrapWithScrollView(view: View): View {
        return HorizontalScrollView(context).apply {
            layoutParams = childParams
            overScrollMode = OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            addView(view)
        }
    }

    fun setOnLinkClickListener(onLinkClickListener: OnLinkClickListener?) {
        this.onLinkClickListener = onLinkClickListener
    }

    override fun onLinkClick(link: String) {
        if (link == "expand_all:") {
            isMediaExpanded = true
            onExpandAllListener?.invoke()
            currentRedditText?.let { setText(it, true) }
        } else {
            onLinkClickListener?.onLinkClick(link)
        }
    }

    override fun onLinkLongClick(link: String) {
        onLinkClickListener?.onLinkLongClick(link)
    }

    override fun onClick() {
        performClick()
    }

    override fun onLongClick() {
        performLongClick()
    }
}
