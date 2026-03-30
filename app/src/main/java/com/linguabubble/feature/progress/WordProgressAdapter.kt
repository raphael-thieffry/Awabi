package com.linguabubble.feature.progress

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.linguabubble.core.data.local.WordProgressEntity
import com.linguabubble.databinding.ItemWordProgressBinding

class WordProgressAdapter(
    private val onReset: (WordProgressEntity) -> Unit
) : ListAdapter<WordProgressEntity, WordProgressAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemWordProgressBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemWordProgressBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entity: WordProgressEntity) {
            binding.tvWord.text       = entity.word
            binding.tvReading.text    = ""   // lecture non stockée dans WordProgress pour l'instant
            binding.tvClickCount.text = "×${entity.clickCount}"

            binding.tvExportedBadge.visibility =
                if (entity.wasExported) View.VISIBLE else View.GONE

            binding.btnReset.isEnabled = entity.clickCount > 0
            binding.btnReset.alpha     = if (entity.clickCount > 0) 1f else 0.3f
            binding.btnReset.setOnClickListener { onReset(entity) }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<WordProgressEntity>() {
        override fun areItemsTheSame(old: WordProgressEntity, new: WordProgressEntity) =
            old.word == new.word
        override fun areContentsTheSame(old: WordProgressEntity, new: WordProgressEntity) =
            old == new
    }
}
