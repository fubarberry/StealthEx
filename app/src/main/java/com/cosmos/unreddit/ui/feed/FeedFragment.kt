package com.cosmos.unreddit.ui.feed

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import com.cosmos.unreddit.databinding.FragmentFeedBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class FeedFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentFeedBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFeedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initChoices()
    }

    private fun initChoices() {
        val currentFeedType = arguments?.getInt(BUNDLE_KEY_FEED_TYPE, 0) ?: 0

        when (currentFeedType) {
            0 -> binding.chipSubscribed.isChecked = true
            1 -> binding.chipPopular.isChecked = true
            2 -> binding.chipAll.isChecked = true
        }

        binding.groupFeed.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.getOrNull(0) ?: return@setOnCheckedStateChangeListener
            val selectedType = when (checkedId) {
                binding.chipSubscribed.id -> 0
                binding.chipPopular.id -> 1
                binding.chipAll.id -> 2
                else -> 0
            }
            setChoice(selectedType)
        }
    }

    private fun setChoice(feedType: Int) {
        setFragmentResult(
            REQUEST_KEY_FEED,
            bundleOf(BUNDLE_KEY_FEED to feedType)
        )
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "FeedFragment"

        const val REQUEST_KEY_FEED = "REQUEST_KEY_FEED"
        const val BUNDLE_KEY_FEED = "BUNDLE_KEY_FEED"

        private const val BUNDLE_KEY_FEED_TYPE = "BUNDLE_KEY_FEED_TYPE"

        fun show(
            fragmentManager: FragmentManager,
            currentFeedType: Int
        ) {
            FeedFragment().apply {
                arguments = bundleOf(
                    BUNDLE_KEY_FEED_TYPE to currentFeedType
                )
            }.show(fragmentManager, TAG)
        }
    }
}
