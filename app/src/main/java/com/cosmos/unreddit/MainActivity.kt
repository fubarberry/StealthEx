package com.cosmos.unreddit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.cosmos.unreddit.MainActivity.BottomNavigationState.LEFT_HANDED
import com.cosmos.unreddit.MainActivity.BottomNavigationState.NOT_INITIALIZED
import com.cosmos.unreddit.MainActivity.BottomNavigationState.RIGHT_HANDED
import com.cosmos.unreddit.databinding.ActivityMainBinding
import com.cosmos.unreddit.ui.postlist.PostListFragment
import com.cosmos.unreddit.util.HideBottomViewBehavior
import com.cosmos.unreddit.util.LinkUtil
import com.cosmos.unreddit.util.extension.clearWindowInsetsListener
import com.cosmos.unreddit.util.extension.currentNavigationFragment
import com.cosmos.unreddit.util.extension.launchRepeat
import com.cosmos.unreddit.util.extension.unredditApplication
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), NavController.OnDestinationChangedListener {

    private lateinit var binding: ActivityMainBinding

    private val viewModel: UiViewModel by viewModels()

    private lateinit var navController: NavController

    private var bottomNavigationState: BottomNavigationState = NOT_INITIALIZED

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(unredditApplication.appTheme)
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initNavigation()

        if (savedInstanceState == null && intent != null) {
            if (!handleSharedTextIntent(intent)) {
                handleRedditShareIntent(intent)
            }
        }

        launchRepeat(Lifecycle.State.STARTED) {
            launch {
                viewModel.navigationVisibility.collect { show ->
                    if (bottomNavigationState != NOT_INITIALIZED) {
                        showNavigation(show)
                    }
                }
            }

            launch {
                viewModel.navigationAutoHide.collect { autoHide ->
                    if (bottomNavigationState != NOT_INITIALIZED) {
                        val layoutParams = binding.bottomNavigation.layoutParams
                            as? CoordinatorLayout.LayoutParams
                        val behavior = layoutParams?.behavior as? HideBottomViewBehavior
                        if (viewModel.navigationVisibility.value) {
                            behavior?.run {
                                enabled = autoHide
                                if (!autoHide) {
                                    slideIn(binding.bottomNavigation, true)
                                }
                            }
                        } else {
                            behavior?.enabled = false
                        }
                    }
                }
            }

            launch {
                viewModel.leftHandedMode.collect { leftHandedMode ->
                    when (bottomNavigationState) {
                        NOT_INITIALIZED -> initBottomNavigationView(leftHandedMode)
                        RIGHT_HANDED -> if (leftHandedMode) initBottomNavigationView(true)
                        LEFT_HANDED -> if (!leftHandedMode) initBottomNavigationView(false)
                    }
                }
            }
        }
    }

    private fun initNavigation() {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
                as NavHostFragment
        navController = navHostFragment.navController.apply {
            addOnDestinationChangedListener(this@MainActivity)
        }

        binding.bottomNavigation.run {
            setupWithNavController(navController)
            setOnItemReselectedListener {
                when (it.itemId) {
                    R.id.home -> (currentNavigationFragment as? PostListFragment)?.scrollToTop()
                    else -> {
                        // Ignore
                    }
                }
            }
        }
    }

    private fun initBottomNavigationView(leftHandedMode: Boolean) {
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavigation) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            view.run {
                updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = insets.bottom +
                            resources.getDimension(R.dimen.bottom_navigation_margin).toInt()
                }

                clearWindowInsetsListener()
            }

            windowInsets
        }

        binding.bottomNavigation.updateLayoutParams<CoordinatorLayout.LayoutParams> {
            gravity = if (leftHandedMode) {
                Gravity.BOTTOM or Gravity.START
            } else {
                Gravity.BOTTOM or Gravity.END
            }
            behavior = HideBottomViewBehavior<BottomNavigationView>(leftHandedMode).apply {
                enabled = viewModel.navigationAutoHide.value
            }
        }

        val radius = resources.getDimension(R.dimen.bottom_navigation_radius)
        val bottomNavigationBackground = binding.bottomNavigation.background
                as? MaterialShapeDrawable

        bottomNavigationBackground?.run {
            val builder = shapeAppearanceModel.toBuilder()

            if (leftHandedMode) {
                builder.apply {
                    setTopRightCorner(CornerFamily.ROUNDED, radius)
                    setBottomRightCorner(CornerFamily.ROUNDED, radius)

                    setTopLeftCorner(CornerFamily.CUT, 0F)
                    setBottomLeftCorner(CornerFamily.CUT, 0F)
                }
            } else {
                builder.apply {
                    setTopRightCorner(CornerFamily.CUT, 0F)
                    setBottomRightCorner(CornerFamily.CUT, 0F)

                    setTopLeftCorner(CornerFamily.ROUNDED, radius)
                    setBottomLeftCorner(CornerFamily.ROUNDED, radius)
                }
            }

            shapeAppearanceModel = builder.build()
        }

        // Wait for the view to be ready to show/hide it (otherwise width could be 0)
        binding.bottomNavigation.post {
            showNavigation(viewModel.navigationVisibility.value, false)
        }

        bottomNavigationState = if (leftHandedMode) LEFT_HANDED else RIGHT_HANDED
    }

    private fun showNavigation(show: Boolean, animate: Boolean = true) {
        val layoutParams = binding.bottomNavigation.layoutParams as CoordinatorLayout.LayoutParams
        val behavior = layoutParams.behavior as HideBottomViewBehavior?

        if (show) {
            behavior?.run {
                enabled = viewModel.navigationAutoHide.value
                slideIn(binding.bottomNavigation, animate)
            }
        } else {
            behavior?.run {
                enabled = false
                slideOut(binding.bottomNavigation, animate)
            }
        }
    }

    override fun onDestinationChanged(
        controller: NavController,
        destination: NavDestination,
        arguments: Bundle?
    ) {
        when (destination.id) {
            R.id.postListFragment -> {
                viewModel.setNavigationVisibility(true)
                showNavigation(true)
            }
            R.id.subscriptionsFragment,
            R.id.profileFragment,
            R.id.preferencesFragment -> {
                viewModel.setNavigationAutoHide(true)
                viewModel.setNavigationVisibility(true)
                showNavigation(true)
            }

            else -> viewModel.setNavigationVisibility(false)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            if (!handleSharedTextIntent(intent) &&
                !handleRedditShareIntent(intent) &&
                intent.action == Intent.ACTION_VIEW &&
                intent.data != null
            ) {
                navController.handleDeepLink(intent)
            }
        }
    }

    private fun handleSharedTextIntent(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain") return false
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return false
        val extractedUrl = extractRedditUrl(sharedText)
        if (extractedUrl == null) {
            Toast.makeText(this, R.string.toast_no_reddit_link_found, Toast.LENGTH_SHORT).show()
            return true
        }
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(extractedUrl))
        if (!handleRedditShareIntent(viewIntent)) {
            if (!navController.handleDeepLink(viewIntent)) {
                Toast.makeText(this, R.string.toast_no_reddit_link_found, Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    private fun extractRedditUrl(text: String): String? {
        val match = REDDIT_URL_REGEX.find(text) ?: return null
        val raw = match.value.trimEnd('.', ',', ')', ']', '>', '"', '\'')
        return if (raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true)
        ) {
            raw
        } else {
            "https://$raw"
        }
    }

    private fun handleRedditShareIntent(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (host != "reddit.com" && !host.endsWith(".reddit.com")) return false
        val segments = uri.pathSegments
        val isShareLink = segments.size >= 4 &&
            segments[2].equals("s", ignoreCase = true) &&
            (segments[0].equals("r", ignoreCase = true) ||
                segments[0].equals("u", ignoreCase = true) ||
                segments[0].equals("user", ignoreCase = true))
        if (!isShareLink) return false

        lifecycleScope.launch {
            val resolvedUrl = withContext(Dispatchers.IO) {
                resolveRedirectUrl(uri.toString())
            }
            if (resolvedUrl != null && !isFinishing && !isDestroyed) {
                val resolvedIntent = Intent(Intent.ACTION_VIEW, Uri.parse(resolvedUrl))
                navController.handleDeepLink(resolvedIntent)
            }
        }
        return true
    }

    private fun resolveRedirectUrl(url: String): String? {
        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        return runCatching {
            val headReq = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", LinkUtil.USER_AGENT)
                .build()
            client.newCall(headReq).execute().use { response ->
                val finalUrl = response.request.url.toString()
                if (finalUrl != url && !finalUrl.contains("/s/")) {
                    return@runCatching finalUrl
                }
            }
            val getReq = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", LinkUtil.USER_AGENT)
                .build()
            client.newCall(getReq).execute().use { response ->
                response.request.url.toString().takeIf { it != url }
            }
        }.getOrNull()
    }

    override fun onDestroy() {
        super.onDestroy()
        bottomNavigationState = NOT_INITIALIZED
    }

    private enum class BottomNavigationState {
        NOT_INITIALIZED, RIGHT_HANDED, LEFT_HANDED
    }

    companion object {
        private val REDDIT_URL_REGEX = Regex(
            "(?:https?://)?(?:[a-zA-Z0-9-]+\\.)?(?:reddit\\.com|redd\\.it)/[^\\s]+",
            RegexOption.IGNORE_CASE
        )
    }
}
