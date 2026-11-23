package com.cosmos.unreddit.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.cosmos.unreddit.R
import com.cosmos.unreddit.ui.common.widget.CheckableImageView
import kotlin.math.roundToInt

class SwipeActionTouchHelper(
    private val context: Context,
    private val onSwipeLeft: (position: Int) -> Unit,
    private val onSwipeRight: (position: Int) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

    private val saveIcon: Drawable? =
        ContextCompat.getDrawable(context, R.drawable.ic_save_filled)?.mutate()
    private val hideIcon: Drawable? =
        ContextCompat.getDrawable(context, R.drawable.ic_hide_x)?.mutate()

    private val bgPaint = Paint().apply {
        isAntiAlias = true
    }

    private val themeBgColor: Int by lazy {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true)
        typedValue.data
    }

    private val density = context.resources.displayMetrics.density
    private val horizontalMargin = (24 * density).toInt()
    private val rightFlickVelocityPxPerSec = RIGHT_FLICK_VELOCITY_DP * density

    private var lastActiveDx: Float = 0f
    private var maxRightDx: Float = 0f
    private var maxLeftDx: Float = 0f
    private var prevActiveDx: Float = 0f
    private var prevActiveTimeMs: Long = 0L
    private var lastRightVelocityPxPerSec: Float = 0f
    private var rightSwipeTriggered: Boolean = false
    private var armedHapticPlayed: Boolean = false

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        return false
    }

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
            resetSwipeState(SystemClock.uptimeMillis())
        }
    }

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
        val itemWidth = viewHolder.itemView.width
        if (lastActiveDx > 0f) {
            if (
                !rightSwipeTriggered &&
                shouldTriggerRightSwipe(
                    itemWidth = itemWidth,
                    maxRightDx = maxRightDx,
                    lastActiveDx = lastActiveDx,
                    rightVelocityPxPerSec = lastRightVelocityPxPerSec,
                    flickVelocityThresholdPxPerSec = rightFlickVelocityPxPerSec
                )
            ) {
                rightSwipeTriggered = true
                val position = viewHolder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onSwipeRight(position)
                    val saveButton = viewHolder.itemView.findViewById<CheckableImageView>(R.id.button_save)
                    if (saveButton != null) {
                        saveButton.isChecked = !saveButton.isChecked
                    }
                }
            }
            // Return > 1f so ItemTouchHelper animates the item back to 0 instead of dismissing it off-screen
            return Float.MAX_VALUE
        }
        return getLeftSwipeThreshold(
            itemWidth = itemWidth,
            maxLeftDx = maxLeftDx,
            lastActiveDx = lastActiveDx
        )
    }

    override fun getSwipeEscapeVelocity(defaultValue: Float): Float {
        if (lastActiveDx > 0f) {
            return Float.MAX_VALUE
        }
        return defaultValue * LEFT_ESCAPE_VELOCITY_MULTIPLIER
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        resetSwipeState(0L)
    }

    private fun resetSwipeState(startTimeMs: Long) {
        lastActiveDx = 0f
        maxRightDx = 0f
        maxLeftDx = 0f
        prevActiveDx = 0f
        prevActiveTimeMs = startTimeMs
        lastRightVelocityPxPerSec = 0f
        rightSwipeTriggered = false
        armedHapticPlayed = false
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val position = viewHolder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return
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
            val itemWidth = itemView.width

            if (isCurrentlyActive) {
                val now = SystemClock.uptimeMillis()
                val dt = now - prevActiveTimeMs
                if (prevActiveTimeMs > 0L && dt > 0L) {
                    val instantVx = (dX - prevActiveDx) * 1000f / dt
                    lastRightVelocityPxPerSec = if (dX > 0f) {
                        if (instantVx >= 0f) {
                            if (lastRightVelocityPxPerSec > 0f) {
                                0.6f * instantVx + 0.4f * lastRightVelocityPxPerSec
                            } else {
                                instantVx
                            }
                        } else {
                            0f
                        }
                    } else {
                        0f
                    }
                    prevActiveDx = dX
                    prevActiveTimeMs = now
                } else if (prevActiveTimeMs == 0L) {
                    prevActiveDx = dX
                    prevActiveTimeMs = now
                }

                lastActiveDx = dX
                if (dX > 0f) {
                    if (dX > maxRightDx) maxRightDx = dX
                    maxLeftDx = 0f
                } else if (dX < 0f) {
                    val leftDx = -dX
                    if (leftDx > maxLeftDx) maxLeftDx = leftDx
                    maxRightDx = 0f
                }
            }

            val isRightArmed = rightSwipeTriggered || (
                itemWidth > 0 &&
                    maxRightDx >= itemWidth * RIGHT_TRIGGER_THRESHOLD &&
                    dX >= itemWidth * CANCEL_THRESHOLD
                )
            val isLeftArmed = itemWidth > 0 &&
                maxLeftDx >= itemWidth * LEFT_TRIGGER_THRESHOLD &&
                -dX >= itemWidth * CANCEL_THRESHOLD

            if (isCurrentlyActive) {
                val isArmed = isRightArmed || isLeftArmed
                if (isArmed && !armedHapticPlayed) {
                    armedHapticPlayed = true
                    itemView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                } else if (!isArmed) {
                    armedHapticPlayed = false
                }
            }

            bgPaint.color = themeBgColor

            if (dX > 0) { // Swiping right -> reveals left
                c.drawRect(
                    itemView.left.toFloat(),
                    itemView.top.toFloat(),
                    itemView.left.toFloat() + dX,
                    itemView.bottom.toFloat(),
                    bgPaint
                )

                saveIcon?.let { icon ->
                    if (dX > horizontalMargin) {
                        val centerX = itemView.left + horizontalMargin + icon.intrinsicWidth / 2f
                        val centerY = itemView.top + itemView.height / 2f
                        drawActionIcon(c, icon, centerX, centerY, isRightArmed)
                    }
                }
            } else if (dX < 0) { // Swiping left -> reveals right
                c.drawRect(
                    itemView.right.toFloat() + dX,
                    itemView.top.toFloat(),
                    itemView.right.toFloat(),
                    itemView.bottom.toFloat(),
                    bgPaint
                )

                hideIcon?.let { icon ->
                    if (-dX > horizontalMargin) {
                        val centerX = itemView.right - horizontalMargin - icon.intrinsicWidth / 2f
                        val centerY = itemView.top + itemView.height / 2f
                        drawActionIcon(c, icon, centerX, centerY, isLeftArmed)
                    }
                }
            }
        }

        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }

    private fun drawActionIcon(
        canvas: Canvas,
        icon: Drawable,
        centerX: Float,
        centerY: Float,
        isArmed: Boolean
    ) {
        val scale = if (isArmed) ARMED_ICON_SCALE else UNARMED_ICON_SCALE
        val halfWidth = (icon.intrinsicWidth * scale / 2f).roundToInt()
        val halfHeight = (icon.intrinsicHeight * scale / 2f).roundToInt()
        val cx = centerX.roundToInt()
        val cy = centerY.roundToInt()
        icon.alpha = if (isArmed) ARMED_ICON_ALPHA else UNARMED_ICON_ALPHA
        icon.setBounds(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight)
        icon.draw(canvas)
    }

    companion object {
        internal const val RIGHT_TRIGGER_THRESHOLD = 0.15f
        internal const val LEFT_TRIGGER_THRESHOLD = 0.20f
        internal const val CANCEL_THRESHOLD = 0.06f
        internal const val LATCHED_SWIPE_THRESHOLD = 0.04f
        internal const val RIGHT_FLICK_MIN_DISTANCE = 0.08f
        internal const val RIGHT_FLICK_VELOCITY_DP = 84f
        internal const val LEFT_ESCAPE_VELOCITY_MULTIPLIER = 0.7f

        private const val ARMED_ICON_ALPHA = 255
        private const val UNARMED_ICON_ALPHA = 115
        private const val ARMED_ICON_SCALE = 1.15f
        private const val UNARMED_ICON_SCALE = 0.85f

        internal fun shouldTriggerRightSwipe(
            itemWidth: Int,
            maxRightDx: Float,
            lastActiveDx: Float,
            rightVelocityPxPerSec: Float,
            flickVelocityThresholdPxPerSec: Float
        ): Boolean {
            if (itemWidth <= 0 || lastActiveDx < itemWidth * CANCEL_THRESHOLD) {
                return false
            }
            val reachedDistance = maxRightDx >= itemWidth * RIGHT_TRIGGER_THRESHOLD
            val reachedFlick = maxRightDx >= itemWidth * RIGHT_FLICK_MIN_DISTANCE &&
                rightVelocityPxPerSec >= flickVelocityThresholdPxPerSec
            return reachedDistance || reachedFlick
        }

        internal fun getLeftSwipeThreshold(
            itemWidth: Int,
            maxLeftDx: Float,
            lastActiveDx: Float
        ): Float {
            if (
                lastActiveDx < 0f &&
                itemWidth > 0 &&
                maxLeftDx >= itemWidth * LEFT_TRIGGER_THRESHOLD &&
                -lastActiveDx >= itemWidth * CANCEL_THRESHOLD
            ) {
                // Swipe already crossed the trigger threshold and wasn't dragged back to cancel;
                // tolerate slight lift-off pullback or brief velocity reversal.
                return LATCHED_SWIPE_THRESHOLD
            }
            return LEFT_TRIGGER_THRESHOLD
        }
    }
}
