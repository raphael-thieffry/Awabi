package com.linguabubble.feature.bubble

import android.graphics.Color
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
 *
 * Couleurs selon clickCount :
 *   0 click  → fond bleu (nouveau mot)
 *   1-4 clics → fond amber/jaune (déjà vu)
 *   5+ clics  → fond grisé discret (bien connu)
 *
 * - Tap court  : cycle kanji → lecture → définition, incrémente clickCount via [onWordClicked]
 *                Le re-tri n'a lieu qu'à la prochaine capture, pas immédiatement.
 * - Appui long : ajoute au deck courant via [onWordLongPressed] + coche verte
 */
class WordAdapter(
    private val onWordClicked: (WordEntry) -> Unit,
    private val onWordLongPressed: (WordEntry) -> Unit
) : ListAdapter<WordEntry, WordAdapter.WordViewHolder>(WordDiffCallback()) {

    /**
     * Maximum de clics parmi les mots du scan courant.
     * Recalculé à chaque submitList. Sert de borne haute pour le gradient.
     * Si 0 → tous les mots sont nouveaux → tout en bleu.
     */
    private var currentMax = 0

    /** État d'affichage de chaque item. 0=kanji 1=lecture 2=définition. */
    private val states = mutableMapOf<String, Int>()

    /**
     * clickCounts locaux : mis à jour immédiatement au tap pour la couleur/badge,
     * sans déclencher de re-tri (le re-tri n'arrive qu'à la prochaine capture).
     */
    private val localClickCounts = mutableMapOf<String, Int>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WordViewHolder {
        val binding = ItemWordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return WordViewHolder(binding)
    }

    override fun onBindViewHolder(holder: WordViewHolder, position: Int) {
        val word = getItem(position)
        // Utilise le clickCount local s'il existe (mis à jour en session), sinon celui du modèle
        val displayClickCount = localClickCounts[word.kanji] ?: word.clickCount
        holder.bind(word, states[word.kanji] ?: 0, displayClickCount, currentMax)

        holder.itemView.setOnClickListener {
            val next = ((states[word.kanji] ?: 0) + 1) % 3
            states[word.kanji] = next
            val newCount = (localClickCounts[word.kanji] ?: word.clickCount) + 1
            localClickCounts[word.kanji] = newCount
            holder.bind(word, next, newCount, currentMax)
            onWordClicked(word)
        }

        holder.itemView.setOnLongClickListener {
            holder.showAddedOverlay()
            onWordLongPressed(word)
            true
        }
    }

    /** Appelé quand la liste est remplacée (nouvelle capture) : reset états locaux + recalcul de N. */
    override fun submitList(list: List<WordEntry>?) {
        val newKanjis = list?.map { it.kanji }?.toSet() ?: emptySet()
        states.keys.retainAll(newKanjis)
        localClickCounts.keys.retainAll(newKanjis)
        // N = max des clics dans le scan courant (0 si tout est neuf → tout bleu)
        currentMax = list?.maxOfOrNull { it.clickCount } ?: 0
        super.submitList(list)
    }

    class WordViewHolder(val binding: ItemWordBinding) :
        RecyclerView.ViewHolder(binding.root) {

        // 0 click : bleu (nouveau mot)
        private val colorBgNew      = Color.parseColor("#0D2744")
        private val colorTextNew    = Color.parseColor("#90CAF9")
        private val colorStrokeNew  = Color.parseColor("#1565C0")

        // 1 click : aspect normal (point de départ du gradient)
        private val colorBgNormal      = Color.parseColor("#1A1A1A")
        private val colorTextNormal    = Color.parseColor("#FFFFFF")
        private val colorReadingNormal = Color.parseColor("#BB86FC")
        private val colorDefNormal     = Color.parseColor("#E0E0E0")
        private val colorStrokeNormal  = Color.parseColor("#333333")

        // 15 clics : grisé discret (point d'arrivée du gradient)
        private val colorBgKnown      = Color.parseColor("#0F0F0F")
        private val colorTextKnown    = Color.parseColor("#3A3A3A")
        private val colorReadingKnown = Color.parseColor("#2A2A2A")
        private val colorDefKnown     = Color.parseColor("#2A2A2A")
        private val colorStrokeKnown  = Color.parseColor("#1A1A1A")

        fun bind(word: WordEntry, state: Int, displayClickCount: Int, maxClicks: Int) {
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

            // ─── Couleurs ───────────────────────────────────────────────────
            when {
                displayClickCount == 0 || maxClicks == 0 -> {
                    // Nouveau mot (ou scan entièrement neuf) : bleu
                    binding.root.setCardBackgroundColor(colorBgNew)
                    binding.root.strokeColor = colorStrokeNew
                    binding.tvKanji.setTextColor(colorTextNew)
                    binding.tvReading.setTextColor(colorReadingNormal)
                    binding.tvDefinition.setTextColor(colorDefNormal)
                }
                else -> {
                    // Gradient normal → grisé : t=0 au 1er clic, t=1 au max du scan
                    val t = ((displayClickCount - 1).toFloat() / maxOf(maxClicks - 1, 1)).coerceIn(0f, 1f)
                    binding.root.setCardBackgroundColor(lerp(colorBgNormal,      colorBgKnown,      t))
                    binding.root.strokeColor =           lerp(colorStrokeNormal, colorStrokeKnown,  t)
                    binding.tvKanji.setTextColor(        lerp(colorTextNormal,   colorTextKnown,    t))
                    binding.tvReading.setTextColor(      lerp(colorReadingNormal, colorReadingKnown, t))
                    binding.tvDefinition.setTextColor(   lerp(colorDefNormal,    colorDefKnown,     t))
                }
            }

            // ─── Badge click count ──────────────────────────────────────────
            if (displayClickCount > 0) {
                binding.tvClickBadge.visibility = View.VISIBLE
                binding.tvClickBadge.text       = if (displayClickCount > 9) "9+" else displayClickCount.toString()
            } else {
                binding.tvClickBadge.visibility = View.GONE
            }

            binding.overlayAdded.visibility = View.GONE
        }

        /** Interpolation linéaire entre deux couleurs ARGB. */
        private fun lerp(from: Int, to: Int, t: Float): Int {
            val r = (Color.red(from)   + (Color.red(to)   - Color.red(from))   * t).toInt()
            val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt()
            val b = (Color.blue(from)  + (Color.blue(to)  - Color.blue(from))  * t).toInt()
            return Color.rgb(r, g, b)
        }

        /** Affiche la coche verte pendant 1.2 s puis la cache. */
        fun showAddedOverlay() {
            binding.overlayAdded.visibility = View.VISIBLE
            binding.overlayAdded.postDelayed({
                binding.overlayAdded.visibility = View.GONE
            }, 1200)
        }
    }

    private class WordDiffCallback : DiffUtil.ItemCallback<WordEntry>() {
        override fun areItemsTheSame(old: WordEntry, new: WordEntry) = old.kanji == new.kanji
        override fun areContentsTheSame(old: WordEntry, new: WordEntry) = old == new
    }
}
