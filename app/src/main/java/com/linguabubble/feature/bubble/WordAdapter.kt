package com.linguabubble.feature.bubble

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.linguabubble.core.domain.model.WordEntry
import com.linguabubble.databinding.ItemWordBinding

/**
 * Adapter RecyclerView pour les mots japonais détectés.
 * Chaque item cycle à travers 3 états au tap :
 *   0 → kanji + badge JLPT
 *   1 → prononciation (hiragana)
 *   2 → définition EN
 */
class WordAdapter : ListAdapter<WordEntry, WordAdapter.WordViewHolder>(WordDiffCallback()) {

    /** État de chaque item, indexé par son kanji. 0=kanji 1=lecture 2=définition. */
    private val states = mutableMapOf<String, Int>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WordViewHolder {
        val binding = ItemWordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return WordViewHolder(binding)
    }

    override fun onBindViewHolder(holder: WordViewHolder, position: Int) {
        val word = getItem(position)
        holder.bind(word, states[word.kanji] ?: 0)
        holder.itemView.setOnClickListener {
            val next = ((states[word.kanji] ?: 0) + 1) % 3
            states[word.kanji] = next
            holder.bind(word, next)
        }
    }

    class WordViewHolder(val binding: ItemWordBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(word: WordEntry, state: Int) {
            binding.tvKanji.text      = word.kanji
            binding.tvReading.text    = word.reading
            binding.tvDefinition.text = word.definitionEn

            if (word.jlptLevel.isNotEmpty()) {
                binding.tvJlpt.text       = word.jlptLevel.removePrefix("jlpt-").uppercase()
                binding.tvJlpt.visibility = View.VISIBLE
            } else {
                binding.tvJlpt.visibility = View.GONE
            }

            binding.layoutKanji.visibility      = if (state == 0) View.VISIBLE else View.GONE
            binding.layoutReading.visibility    = if (state == 1) View.VISIBLE else View.GONE
            binding.layoutDefinition.visibility = if (state == 2) View.VISIBLE else View.GONE
        }
    }

    private class WordDiffCallback : DiffUtil.ItemCallback<WordEntry>() {
        override fun areItemsTheSame(old: WordEntry, new: WordEntry) = old.kanji == new.kanji
        override fun areContentsTheSame(old: WordEntry, new: WordEntry) = old == new
    }
}
