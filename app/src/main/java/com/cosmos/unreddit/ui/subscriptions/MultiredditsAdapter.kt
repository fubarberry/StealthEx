package com.cosmos.unreddit.ui.subscriptions

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.db.MultiredditWithSubreddits
import com.cosmos.unreddit.databinding.ItemMultiredditBinding

class MultiredditsAdapter(
    private val listener: (MultiredditWithSubreddits) -> Unit
) : ListAdapter<MultiredditWithSubreddits, MultiredditsAdapter.MultiredditViewHolder>(
    MULTIREDDIT_COMPARATOR
) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MultiredditViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return MultiredditViewHolder(ItemMultiredditBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: MultiredditViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MultiredditViewHolder(
        private val binding: ItemMultiredditBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MultiredditWithSubreddits) {
            val context = binding.root.context
            binding.multiredditAvatar.setText(item.multireddit.name)
            binding.multiredditName.text = item.multireddit.name

            val names = item.subredditNames
            binding.multiredditSubreddits.text = if (names.isEmpty()) {
                context.getString(R.string.multireddit_no_subreddits)
            } else {
                val countLabel = if (names.size == 1) {
                    context.getString(R.string.multireddit_subreddit_count_one)
                } else {
                    context.getString(R.string.multireddit_subreddit_count_other, names.size)
                }
                val preview = names.take(4).joinToString(", ") { "r/$it" }
                "$countLabel • $preview"
            }

            itemView.setOnClickListener {
                listener(item)
            }
        }
    }

    companion object {
        private val MULTIREDDIT_COMPARATOR = object : DiffUtil.ItemCallback<MultiredditWithSubreddits>() {
            override fun areItemsTheSame(
                oldItem: MultiredditWithSubreddits,
                newItem: MultiredditWithSubreddits
            ): Boolean {
                return oldItem.multireddit.id == newItem.multireddit.id
            }

            override fun areContentsTheSame(
                oldItem: MultiredditWithSubreddits,
                newItem: MultiredditWithSubreddits
            ): Boolean {
                return oldItem == newItem
            }
        }
    }
}
