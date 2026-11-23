package com.cosmos.unreddit.util

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout

class DrawerContent @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MotionLayout(context, attrs, defStyleAttr), DrawerLayout.DrawerListener {

    private lateinit var drawerLayout: DrawerLayout

    private fun ensureDrawerLayout() {
        drawerLayout = parent as? DrawerLayout
            ?: throw IllegalStateException("This $this must be added to a DrawerLayout")
    }

    override fun onDrawerStateChanged(newState: Int) {
        // Ignore
    }

    override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
        progress = slideOffset
    }

    override fun onDrawerClosed(drawerView: View) {
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
    }

    override fun onDrawerOpened(drawerView: View) {
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureDrawerLayout()
        drawerLayout.addDrawerListener(this)
        val lockMode = if (drawerLayout.isDrawerOpen(GravityCompat.END)) {
            DrawerLayout.LOCK_MODE_UNLOCKED
        } else {
            DrawerLayout.LOCK_MODE_LOCKED_CLOSED
        }
        drawerLayout.setDrawerLockMode(lockMode)
    }
}
