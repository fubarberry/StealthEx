package com.cosmos.unreddit.ui.mediamenu

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.GalleryMedia
import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.data.model.PostType
import com.cosmos.unreddit.data.model.db.PostEntity
import com.cosmos.unreddit.data.repository.PostListRepository
import com.cosmos.unreddit.data.worker.MediaDownloadWorker
import com.cosmos.unreddit.databinding.FragmentMediaMenuBinding
import com.cosmos.unreddit.util.LinkUtil
import com.cosmos.unreddit.util.extension.isPermissionGranted
import com.cosmos.unreddit.util.extension.parcelable
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import javax.inject.Inject

@AndroidEntryPoint
class MediaMenuFragment : BottomSheetDialogFragment() {

    @Inject
    lateinit var repository: PostListRepository

    private var _binding: FragmentMediaMenuBinding? = null
    private val binding get() = _binding!!

    private var currentPost: PostEntity? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            currentPost?.let { startDownload(it) }
        } else {
            Toast.makeText(
                requireContext(),
                R.string.snackbar_permission_storage_denied_message,
                Toast.LENGTH_SHORT
            ).show()
            dismissAllowingStateLoss()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMediaMenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val post = arguments?.parcelable<PostEntity>(BUNDLE_KEY_POST)
        if (post == null) {
            dismissAllowingStateLoss()
            return
        }
        currentPost = post
        binding.post = post
        binding.executePendingBindings()
        initActions(post)
    }

    private fun initActions(post: PostEntity) {
        with(binding) {
            buttonDownload.setOnClickListener {
                requestDownload(post)
            }
            buttonShareFile.setOnClickListener {
                shareMediaFileDirectly(post)
            }
            buttonShareLink.setOnClickListener {
                shareMediaLink(post)
            }
        }
    }

    private fun requestDownload(post: PostEntity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)) {
                startDownload(post)
            } else {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startDownload(post)
        } else {
            if (isPermissionGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                startDownload(post)
            } else {
                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        _binding?.apply {
            loadingProgress.isVisible = loading
            buttonDownload.isEnabled = !loading
            buttonShareFile.isEnabled = !loading
            buttonShareLink.isEnabled = !loading
        }
    }

    private suspend fun resolveDirectMedia(post: PostEntity): Pair<String, GalleryMedia.Type>? {
        if (post.gallery.isNotEmpty()) {
            val first = post.gallery.first()
            return first.url to first.type
        }

        val rawUrl = post.mediaUrl.ifBlank { post.preview.orEmpty() }
        if (rawUrl.isBlank()) return null

        val httpsUrl = rawUrl.replaceFirst("http://", "https://")
        return if (post.type == PostType.VIDEO) {
            val resolvedUrl = withContext(Dispatchers.IO) {
                when (post.mediaType) {
                    MediaType.IMGUR_GIF -> LinkUtil.getImgurVideo(httpsUrl)
                    MediaType.REDGIFS -> repository.resolveRedgifsUrl(LinkUtil.getGfycatId(httpsUrl)) ?: httpsUrl
                    MediaType.GFYCAT -> repository.resolveGfycatUrl(LinkUtil.getGfycatId(httpsUrl)) ?: httpsUrl
                    MediaType.STREAMABLE -> repository.resolveStreamableUrl(LinkUtil.getStreamableShortcode(httpsUrl)) ?: httpsUrl
                    else -> {
                        if (httpsUrl.contains("redgifs.com", ignoreCase = true) && !httpsUrl.contains("signature=")) {
                            repository.resolveRedgifsUrl(LinkUtil.getGfycatId(httpsUrl)) ?: httpsUrl
                        } else {
                            httpsUrl
                        }
                    }
                }
            }
            resolvedUrl to GalleryMedia.Type.VIDEO
        } else {
            val imageUrl = if (post.mediaType == MediaType.IMGUR_IMAGE && !httpsUrl.contains(".")) {
                LinkUtil.getUrlFromImgurId(LinkUtil.getImageIdFromImgurLink(httpsUrl))
            } else {
                httpsUrl
            }
            imageUrl to GalleryMedia.Type.IMAGE
        }
    }

    private fun startDownload(post: PostEntity) {
        setLoading(true)
        lifecycleScope.launch {
            val resolved = runCatching { resolveDirectMedia(post) }.getOrNull()
            if (resolved == null) {
                setLoading(false)
                Toast.makeText(requireContext(), R.string.share_error_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val (url, type) = resolved
            MediaDownloadWorker.enqueueWork(
                requireContext().applicationContext,
                url,
                type
            )
            Toast.makeText(
                requireContext(),
                R.string.toast_download_started,
                Toast.LENGTH_SHORT
            ).show()
            dismissAllowingStateLoss()
        }
    }

    private fun shareMediaLink(post: PostEntity) {
        val link = post.gallery.firstOrNull()?.url
            ?: post.mediaUrl.ifBlank { post.url }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_dialog_title)))
        dismissAllowingStateLoss()
    }

    private fun shareMediaFileDirectly(post: PostEntity) {
        setLoading(true)
        lifecycleScope.launch {
            val context = requireContext().applicationContext
            val result = runCatching {
                val (directUrl, mediaType) = resolveDirectMedia(post)
                    ?: throw IOException("No media URL available")

                withContext(Dispatchers.IO) {
                    val resolved = LinkUtil.resolveMediaUrls(directUrl, Dispatchers.IO)
                    val videoUrl = resolved.videoUrl
                    val audioUrl = resolved.audioUrl

                    val tempShareDir = File(context.cacheDir, "temp_share")
                    if (!tempShareDir.exists()) {
                        tempShareDir.mkdirs()
                    } else {
                        tempShareDir.listFiles()?.forEach { it.delete() }
                    }

                    val extension = MimeTypeMap.getFileExtensionFromUrl(videoUrl).ifEmpty {
                        if (audioUrl != null || mediaType == GalleryMedia.Type.VIDEO) "mp4" else "jpg"
                    }
                    val finalFile = File(tempShareDir, "share_${System.currentTimeMillis()}.$extension")

                    val client = OkHttpClient()
                    if (audioUrl != null) {
                        val tempVideoFile = File(tempShareDir, "temp_video.mp4")
                        val tempAudioFile = File(tempShareDir, "temp_audio.mp4")
                        try {
                            val videoRequest = Request.Builder()
                                .url(videoUrl)
                                .header("User-Agent", LinkUtil.USER_AGENT)
                                .build()
                            client.newCall(videoRequest).execute().use { response ->
                                if (!response.isSuccessful) throw IOException("Failed to download video")
                                tempVideoFile.sink().buffer().use { sink ->
                                    response.body?.source()?.let { source -> sink.writeAll(source) }
                                }
                            }

                            val audioRequest = Request.Builder()
                                .url(audioUrl)
                                .header("User-Agent", LinkUtil.USER_AGENT)
                                .build()
                            client.newCall(audioRequest).execute().use { response ->
                                if (!response.isSuccessful) throw IOException("Failed to download audio")
                                tempAudioFile.sink().buffer().use { sink ->
                                    response.body?.source()?.let { source -> sink.writeAll(source) }
                                }
                            }

                            LinkUtil.muxAudioVideo(tempVideoFile, tempAudioFile, finalFile)
                        } finally {
                            tempVideoFile.delete()
                            tempAudioFile.delete()
                        }
                    } else {
                        val request = Request.Builder()
                            .url(videoUrl)
                            .header("User-Agent", LinkUtil.USER_AGENT)
                            .build()
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) throw IOException("Failed to download media")
                            finalFile.sink().buffer().use { sink ->
                                response.body?.source()?.let { source -> sink.writeAll(source) }
                            }
                        }
                    }

                    val mimeType = MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(extension)
                        ?: "application/octet-stream"
                    finalFile to mimeType
                }
            }

            if (!isAdded) return@launch
            setLoading(false)

            result.onSuccess { (file, mimeType) ->
                runCatching {
                    val authority = "${requireContext().packageName}.fileprovider"
                    val uri = FileProvider.getUriForFile(requireContext(), authority, file)
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.share_dialog_title)))
                    dismissAllowingStateLoss()
                }.onFailure {
                    Toast.makeText(requireContext(), R.string.share_error_failed, Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                Toast.makeText(requireContext(), R.string.share_error_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "MediaMenuFragment"
        private const val BUNDLE_KEY_POST = "BUNDLE_KEY_POST"

        fun show(fragmentManager: FragmentManager, post: PostEntity) {
            MediaMenuFragment().apply {
                arguments = bundleOf(BUNDLE_KEY_POST to post)
            }.show(fragmentManager, TAG)
        }
    }
}
