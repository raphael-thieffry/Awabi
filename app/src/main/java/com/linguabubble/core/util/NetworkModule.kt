package com.linguabubble.core.util

import com.linguabubble.BuildConfig
import com.linguabubble.core.data.remote.JishoApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

/**
 * Module Hilt qui fournit les dépendances réseau.
 * OkHttpClient → Retrofit → JishoApiService
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val JISHO_BASE_URL = "https://jisho.org/"

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BODY
                    })
                    // Interceptor qui stocke les réponses dans DebugStore
                    addInterceptor(Interceptor { chain ->
                        val request  = chain.request()
                        val response = chain.proceed(request)
                        val body     = response.peekBody(2048).string()
                        // Extraire le mot-clé depuis l'URL (param "keyword")
                        val keyword  = request.url.queryParameter("keyword") ?: "?"
                        DebugStore.logJisho(keyword, body)
                        response
                    })
                }
            }
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(JISHO_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideJishoApiService(retrofit: Retrofit): JishoApiService {
        return retrofit.create(JishoApiService::class.java)
    }
}
