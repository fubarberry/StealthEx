package com.cosmos.unreddit.ui.layout

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import com.cosmos.unreddit.databinding.FragmentLayoutBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class LayoutFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentLayoutBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initChoices()
    }

    private fun initChoices() {
        val currentLayoutType = arguments?.getInt(BUNDLE_KEY_LAYOUT_TYPE, 0) ?: 0

        when (currentLayoutType) {
            0 -> binding.chipCard.isChecked = true
            1 -> binding.chipCompact.isChecked = true
            2 -> binding.chipFullscreen.isChecked = true
        }

        binding.groupLayout.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.getOrNull(0) ?: return@setOnCheckedStateChangeListener
            val selectedType = when (checkedId) {
                binding.chipCard.id -> 0
                binding.chipCompact.id -> 1
                binding.chipFullscreen.id -> 2
                else -> 0
            }
            setChoice(selectedType)
        }
    }

    private fun setChoice(layoutType: Int) {
        setFragmentResult(
            REQUEST_KEY_LAYOUT,
            bundleOf(BUNDLE_KEY_LAYOUT to layoutType)
        )
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "LayoutFragment"

        const val REQUEST_KEY_LAYOUT = "REQUEST_KEY_LAYOUT"
        const val BUNDLE_KEY_LAYOUT = "BUNDLE_KEY_LAYOUT"

        private const val BUNDLE_KEY_LAYOUT_TYPE = "BUNDLE_KEY_LAYOUT_TYPE"

        fun show(
            fragmentManager: FragmentManager,
            currentLayoutType: Int
        ) {
            LayoutFragment().apply {
                arguments = bundleOf(
                    BUNDLE_KEY_LAYOUT_TYPE to currentLayoutType
                )
            }.show(fragmentManager, TAG)
        }
    }
}
