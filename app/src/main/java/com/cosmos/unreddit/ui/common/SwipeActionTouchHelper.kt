package com.cosmos.unreddit.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.cosmos.unreddit.R

class SwipeActionTouchHelper(
    private val context: Context,
    private val onSwipeLeft: (position: Int) -> Unit,
    private val onSwipeRight: (position: Int) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

    private val saveIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_save_filled)?.let {
        val wrapped = DrawableCompat.wrap(it).mutate()
        DrawableCompat.setTint(wrapped, Color.WHITE)
        wrapped
    }

    private val hideIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_close)?.let {
        val wrapped = DrawableCompat.wrap(it).mutate()
        DrawableCompat.setTint(wrapped, Color.WHITE)
        wrapped
    }

    private val bgPaint = Paint().apply {
        isAntiAlias = true
    }

    private val likeBgColor = ContextCompat.getColor(context, R.color.colorSecondary)
    private val hideBgColor = Color.parseColor("#9E9E9E")

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        return false
    }

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
        return 0.3f
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val position = viewHolder.bindingAdapterPosition
        if (direction == ItemTouchHelper.LEFT) {
            onSwipeLeft(position)
        } else if (direction == ItemTouchHelper.RIGHT) {
            onSwipeRight(position)
        }
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
            val itemView = viewHolder.itemView

            if (dX > 0) { // Swiping right -> reveals left
                bgPaint.color = likeBgColor
                c.drawRect(
                    itemView.left.toFloat(),
                    itemView.top.toFloat(),
                    itemView.left.toFloat() + dX,
                    itemView.bottom.toFloat(),
                    bgPaint
                )

                saveIcon?.let { icon ->
                    val iconHeight = icon.intrinsicHeight
                    val iconWidth = icon.intrinsicWidth
                    val iconMargin = (itemView.height - iconHeight) / 2
                    val iconTop = itemView.top + iconMargin
                    val iconLeft = itemView.left + iconMargin
                    val iconRight = iconLeft + iconWidth
                    val iconBottom = iconTop + iconHeight

                    if (dX > iconMargin) {
                        icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                        icon.draw(c)
                    }
                }
            } else if (dX < 0) { // Swiping left -> reveals right
                bgPaint.color = hideBgColor
                c.drawRect(
                    itemView.right.toFloat() + dX,
                    itemView.top.toFloat(),
                    itemView.right.toFloat(),
                    itemView.bottom.toFloat(),
                    bgPaint
                )

                hideIcon?.let { icon ->
                    val iconHeight = icon.intrinsicHeight
                    val iconWidth = icon.intrinsicWidth
                    val iconMargin = (itemView.height - iconHeight) / 2
                    val iconTop = itemView.top + iconMargin
                    val iconRight = itemView.right - iconMargin
                    val iconLeft = iconRight - iconWidth
                    val iconBottom = iconTop + iconHeight

                    if (-dX > iconMargin) {
                        icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                        icon.draw(c)
                    }
                }
            }
        }

        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }
}
