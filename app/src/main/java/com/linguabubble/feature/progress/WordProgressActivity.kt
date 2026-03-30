package com.linguabubble.feature.progress

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.linguabubble.R
import com.linguabubble.databinding.ActivityWordProgressBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Activité dédiée à la gestion des compteurs de clics.
 * - Barre de recherche pour filtrer les mots
 * - Reset global / reset des non-exportés
 * - Reset individuel par mot
 */
@AndroidEntryPoint
class WordProgressActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWordProgressBinding
    private val viewModel: WordProgressViewModel by viewModels()
    private lateinit var adapter: WordProgressAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWordProgressBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupList()
        setupSearch()
        setupButtons()
        observeList()
    }

    private fun setupList() {
        adapter = WordProgressAdapter(
            onReset = { entity ->
                viewModel.resetOne(entity.word)
                Toast.makeText(this, getString(R.string.reset_word_done, entity.word), Toast.LENGTH_SHORT).show()
            }
        )
        binding.rvWords.apply {
            layoutManager = LinearLayoutManager(this@WordProgressActivity)
            adapter        = this@WordProgressActivity.adapter
            addItemDecoration(DividerItemDecoration(this@WordProgressActivity, DividerItemDecoration.VERTICAL))
        }
    }

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.setQuery(newText.orEmpty())
                return true
            }
        })
    }

    private fun setupButtons() {
        binding.btnResetAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.reset_all_title))
                .setMessage(getString(R.string.reset_all_message))
                .setPositiveButton(getString(R.string.btn_reset_confirm)) { _, _ ->
                    viewModel.resetAll()
                    Toast.makeText(this, getString(R.string.reset_all_done), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.btnResetNonExported.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.reset_non_exported_title))
                .setMessage(getString(R.string.reset_non_exported_message))
                .setPositiveButton(getString(R.string.btn_reset_confirm)) { _, _ ->
                    viewModel.resetNonExported()
                    Toast.makeText(this, getString(R.string.reset_non_exported_done), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun observeList() {
        lifecycleScope.launch {
            viewModel.filtered.collect { list ->
                adapter.submitList(list)
                val total = list.size
                val withClicks = list.count { it.clickCount > 0 }
                binding.tvCount.text = getString(R.string.word_progress_count, total, withClicks)
            }
        }
    }
}
