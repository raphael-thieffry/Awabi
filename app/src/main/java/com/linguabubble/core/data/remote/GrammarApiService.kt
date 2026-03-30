package com.linguabubble.core.data.remote

import com.linguabubble.core.util.LlmProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client HTTP multi-fournisseur pour l'analyse grammaticale LLM.
 * Supporte : GitHub Models, Google Gemini, Anthropic Claude, OpenAI.
 */
@Singleton
class GrammarApiService @Inject constructor() {

    private val json = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Envoie le prompt + texte au LLM identifié par [provider].
     * Lance une exception en cas d'erreur réseau ou HTTP non-2xx.
     */
    suspend fun complete(
        systemPrompt: String,
        userMessage: String,
        apiKey: String,
        provider: LlmProvider
    ): String = withContext(Dispatchers.IO) {
        when (provider) {
            LlmProvider.GITHUB_MODELS -> completeOpenAiCompat(
                systemPrompt, userMessage, apiKey,
                "https://models.inference.ai.azure.com/chat/completions",
                provider.modelName
            )
            LlmProvider.OPENAI -> completeOpenAiCompat(
                systemPrompt, userMessage, apiKey,
                "https://api.openai.com/v1/chat/completions",
                provider.modelName
            )
            LlmProvider.GEMINI   -> completeGemini(systemPrompt, userMessage, apiKey)
            LlmProvider.ANTHROPIC -> completeAnthropic(systemPrompt, userMessage, apiKey)
        }
    }

    // ─── OpenAI-compatible (GitHub Models + OpenAI) ───────────────────────────

    private fun completeOpenAiCompat(
        systemPrompt: String,
        userMessage: String,
        apiKey: String,
        url: String,
        model: String
    ): String {
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.3)
            put("max_tokens", 1024)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userMessage))
            })
        }.toString()

        val response = client.newCall(
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(json))
                .build()
        ).execute()

        val rb = response.body?.string() ?: throw Exception("Réponse vide du serveur")
        if (!response.isSuccessful) throw Exception("Erreur ${response.code} : $rb")

        return JSONObject(rb)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }

    // ─── Google Gemini ────────────────────────────────────────────────────────

    private fun completeGemini(systemPrompt: String, userMessage: String, apiKey: String): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                "${LlmProvider.GEMINI.modelName}:generateContent?key=$apiKey"

        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userMessage)))
            }))
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 1024)
                put("temperature", 0.3)
            })
        }.toString()

        val response = client.newCall(
            Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(json))
                .build()
        ).execute()

        val rb = response.body?.string() ?: throw Exception("Réponse vide du serveur")
        if (!response.isSuccessful) throw Exception("Erreur Gemini ${response.code} : $rb")

        return JSONObject(rb)
            .getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
    }

    // ─── Anthropic Claude ─────────────────────────────────────────────────────

    private fun completeAnthropic(systemPrompt: String, userMessage: String, apiKey: String): String {
        val body = JSONObject().apply {
            put("model", LlmProvider.ANTHROPIC.modelName)
            put("max_tokens", 1024)
            put("system", systemPrompt)
            put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", userMessage)
            ))
        }.toString()

        val response = client.newCall(
            Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(json))
                .build()
        ).execute()

        val rb = response.body?.string() ?: throw Exception("Réponse vide du serveur")
        if (!response.isSuccessful) throw Exception("Erreur Anthropic ${response.code} : $rb")

        return JSONObject(rb)
            .getJSONArray("content")
            .getJSONObject(0)
            .getString("text")
    }
}
