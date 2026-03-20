package com.linguabubble.core.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Interface Retrofit pour l'API non-officielle de Jisho.
 * Base URL : https://jisho.org/
 * Pas de clé API requise.
 */
interface JishoApiService {

    /**
     * Recherche les définitions d'un mot japonais.
     * @param keyword mot à rechercher (kanji, hiragana, romaji)
     */
    @GET("api/v1/search/words")
    suspend fun searchWord(@Query("keyword") keyword: String): JishoResponse
}
