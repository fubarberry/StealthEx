package com.cosmos.unreddit.ui.postdetails

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import com.cosmos.unreddit.data.remote.api.reddit.RedditCookieJar
import com.cosmos.unreddit.databinding.FragmentRedditWebviewBinding
import com.cosmos.unreddit.ui.common.FullscreenBottomSheetFragment
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class RedditWebViewDialogFragment : FullscreenBottomSheetFragment() {

    private var _binding: FragmentRedditWebviewBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var cookieJar: RedditCookieJar

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRedditWebviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onShow(dialog: DialogInterface?) {
        super.onShow(dialog)
        behavior?.isDraggable = false
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.backCard.setOnClickListener {
            finishAndSync()
        }
        binding.buttonDone.setOnClickListener {
            finishAndSync()
        }

        val url = arguments?.getString(KEY_URL) ?: DEFAULT_URL

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    return false
                }

                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    super.onPageFinished(view, finishedUrl)
                    runCatching { CookieManager.getInstance().flush() }
                    cookieJar.syncFromWebView()
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    _binding?.progress?.apply {
                        progress = newProgress
                        isVisible = newProgress < 100
                    }
                }
            }

            loadUrl(url)
        }
    }

    private fun finishAndSync() {
        runCatching { CookieManager.getInstance().flush() }
        cookieJar.syncFromWebView()
        setFragmentResult(REQUEST_KEY_WEBVIEW, bundleOf(BUNDLE_KEY_REFRESH to true))
        dismissAllowingStateLoss()
    }

    override fun onDismiss(dialog: DialogInterface) {
        runCatching { CookieManager.getInstance().flush() }
        cookieJar.syncFromWebView()
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        binding.webView.apply {
            stopLoading()
            destroy()
        }
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "RedditWebViewDialogFragment"
        const val REQUEST_KEY_WEBVIEW = "REQUEST_KEY_WEBVIEW"
        const val BUNDLE_KEY_REFRESH = "BUNDLE_KEY_REFRESH"

        private const val KEY_URL = "KEY_URL"
        private const val DEFAULT_URL = "https://www.reddit.com/"

        fun show(fragmentManager: FragmentManager, permalinkOrUrl: String?) {
            if (fragmentManager.findFragmentByTag(TAG) != null) return
            val fullUrl = when {
                permalinkOrUrl.isNullOrBlank() -> DEFAULT_URL
                permalinkOrUrl.startsWith("http") -> permalinkOrUrl
                permalinkOrUrl.startsWith("/") -> "https://www.reddit.com$permalinkOrUrl"
                else -> "https://www.reddit.com/$permalinkOrUrl"
            }
            RedditWebViewDialogFragment().apply {
                arguments = bundleOf(KEY_URL to fullUrl)
            }.show(fragmentManager, TAG)
        }
    }
}
