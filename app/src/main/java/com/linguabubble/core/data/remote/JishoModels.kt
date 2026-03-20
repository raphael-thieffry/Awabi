package com.linguabubble.core.data.remote

import com.google.gson.annotations.SerializedName

/**
 * Data classes pour parser la réponse JSON de l'API Jisho.
 * URL exemple : https://jisho.org/api/v1/search/words?keyword=食べる
 */

data class JishoResponse(
    @SerializedName("data") val data: List<JishoWord> = emptyList()
)

data class JishoWord(
    @SerializedName("slug") val slug: String = "",
    @SerializedName("jlpt") val jlpt: List<String> = emptyList(),
    @SerializedName("japanese") val japanese: List<JishoJapanese> = emptyList(),
    @SerializedName("senses") val senses: List<JishoSense> = emptyList()
)

data class JishoJapanese(
    @SerializedName("word") val word: String? = null,
    @SerializedName("reading") val reading: String? = null
)

data class JishoSense(
    @SerializedName("english_definitions") val english_definitions: List<String> = emptyList(),
    @SerializedName("parts_of_speech") val parts_of_speech: List<String> = emptyList()
)
