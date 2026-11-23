package com.cosmos.unreddit.ui.common.widget

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.core.view.children
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.cosmos.unreddit.ui.common.PostDividerItemDecoration

class PostRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    private val dividerDecoration = PostDividerItemDecoration(context)
    private val pagerSnapHelper = PagerSnapHelper()
    private var lastFullscreenViewportHeight: Int = -1

    private val syncFullscreenLayoutRunnable = Runnable {
        if (!isAttachedToWindow || !isFullscreenMode) return@Runnable
        val viewportHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)
        if (viewportHeight <= 0) return@Runnable

        var requestedChildLayout = false
        for (child in children) {
            if (
                child.layoutParams.height == ViewGroup.LayoutParams.MATCH_PARENT &&
                child.height != viewportHeight
            ) {
                child.requestLayout()
                requestedChildLayout = true
            }
        }
        if (requestedChildLayout) {
            requestLayout()
        }

        if (scrollState == SCROLL_STATE_IDLE) {
            val lm = layoutManager ?: return@Runnable
            val snapView = pagerSnapHelper.findSnapView(lm) ?: return@Runnable
            val snapDistance = pagerSnapHelper.calculateDistanceToFinalSnap(lm, snapView) ?: return@Runnable
            if (snapDistance[0] != 0 || snapDistance[1] != 0) {
                scrollBy(snapDistance[0], snapDistance[1])
            }
        }
    }

    var isFullscreenMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                clipToPadding = value
                if (value) {
                    removeItemDecoration(dividerDecoration)
                    pagerSnapHelper.attachToRecyclerView(this)
                    lastFullscreenViewportHeight = -1
                    post(syncFullscreenLayoutRunnable)
                } else {
                    removeCallbacks(syncFullscreenLayoutRunnable)
                    pagerSnapHelper.attachToRecyclerView(null)
                    removeItemDecoration(dividerDecoration)
                    addItemDecoration(dividerDecoration)
                    lastFullscreenViewportHeight = -1
                }
            }
        }

    init {
        addItemDecoration(dividerDecoration)
        isVerticalScrollBarEnabled = false
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (isFullscreenMode) {
            val viewportHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)
            if (viewportHeight > 0) {
                var childNeedsResize = false
                for (child in children) {
                    if (
                        child.layoutParams.height == ViewGroup.LayoutParams.MATCH_PARENT &&
                        child.height != viewportHeight
                    ) {
                        childNeedsResize = true
                        break
                    }
                }
                if (viewportHeight != lastFullscreenViewportHeight || childNeedsResize) {
                    lastFullscreenViewportHeight = viewportHeight
                    removeCallbacks(syncFullscreenLayoutRunnable)
                    post(syncFullscreenLayoutRunnable)
                }
            }
        }
    }

    override fun onScrollStateChanged(state: Int) {
        super.onScrollStateChanged(state)
        if (isFullscreenMode && state == SCROLL_STATE_IDLE) {
            removeCallbacks(syncFullscreenLayoutRunnable)
            post(syncFullscreenLayoutRunnable)
        }
    }
}
