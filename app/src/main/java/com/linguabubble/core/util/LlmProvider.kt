package com.linguabubble.core.util

/**
 * Fournisseurs LLM supportés.
 * La détection se fait uniquement sur le préfixe de la clef.
 */
enum class LlmProvider(
    val displayName: String,
    val modelName: String
) {
    GITHUB_MODELS("GitHub Models · GPT-4o mini", "gpt-4o-mini"),
    GEMINI("Google · Gemini 1.5 Flash", "gemini-1.5-flash"),
    ANTHROPIC("Anthropic · Claude Haiku", "claude-haiku-4-5-20251001"),
    OPENAI("OpenAI · GPT-4o mini", "gpt-4o-mini");

    companion object {
        /**
         * Détecte le fournisseur à partir du préfixe de la clef.
         * Retourne null si le format est inconnu.
         */
        fun detect(key: String): LlmProvider? {
            val k = key.trim()
            return when {
                k.startsWith("github_pat_") || k.startsWith("ghp_") -> GITHUB_MODELS
                k.startsWith("AIza")                                  -> GEMINI
                k.startsWith("sk-ant-")                               -> ANTHROPIC
                k.startsWith("sk-")                                   -> OPENAI
                else                                                  -> null
            }
        }

        /** Longueur minimale attendue pour chaque fournisseur (heuristique). */
        fun isLengthPlausible(key: String, provider: LlmProvider): Boolean {
            val k = key.trim()
            return when (provider) {
                GITHUB_MODELS -> k.length >= 40
                GEMINI        -> k.length >= 30
                ANTHROPIC     -> k.length >= 40
                OPENAI        -> k.length >= 40
            }
        }
    }
}
