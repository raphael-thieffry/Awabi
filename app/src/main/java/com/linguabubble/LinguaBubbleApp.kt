package com.linguabubble

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Point d'entrée de l'application.
 * @HiltAndroidApp initialise le graphe de dépendances Hilt.
 */
@HiltAndroidApp
class LinguaBubbleApp : Application()
