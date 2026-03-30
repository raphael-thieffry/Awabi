package com.linguabubble.feature.anki

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.linguabubble.core.data.local.DeckEntryEntity
import com.linguabubble.databinding.ItemDeckEntryBinding

/**
 * Adapter pour la liste du deck courant dans la MainActivity.
 */
class DeckEntryAdapter(
    private val onDelete: (DeckEntryEntity) -> Unit
) : ListAdapter<DeckEntryEntity, DeckEntryAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDeckEntryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(val binding: ItemDeckEntryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: DeckEntryEntity) {
            binding.tvKanji.text      = entry.kanji
            binding.tvReading.text    = entry.reading
            binding.tvDefinition.text = entry.definitionEn.ifBlank { entry.definitionFr }

            if (entry.jlptLevel.isNotEmpty()) {
                binding.tvJlpt.text       = entry.jlptLevel.removePrefix("jlpt-").uppercase()
                binding.tvJlpt.visibility = View.VISIBLE
            } else {
                binding.tvJlpt.visibility = View.GONE
            }

            binding.btnDelete.setOnClickListener { onDelete(entry) }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<DeckEntryEntity>() {
        override fun areItemsTheSame(old: DeckEntryEntity, new: DeckEntryEntity) =
            old.kanji == new.kanji
        override fun areContentsTheSame(old: DeckEntryEntity, new: DeckEntryEntity) =
            old == new
    }
}
