package com.linguabubble.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Service d'accessibilité — lit le contenu des vues Android.
 *
 * Sprint 1 : squelette vide, service déclaré et connecté.
 * Sprint 2 : extraire le texte visible et le transmettre à FloatingBubbleService.
 */
class LinguaAccessibilityService : AccessibilityService() {

    companion object {
        /**
         * Référence statique à l'instance courante du service.
         * Utilisée par FloatingBubbleService pour appeler extractVisibleText().
         * Null quand le service est arrêté.
         */
        var instance: LinguaAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Configuration programmatique (complète le XML accessibility_service_config.xml)
        serviceInfo = serviceInfo.apply {
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        // Exposer l'instance pour que FloatingBubbleService puisse nous appeler
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        // Libérer la référence statique pour éviter les fuites mémoire
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Sprint 2 : traiter les événements et extraire le texte japonais
    }

    override fun onInterrupt() {
        // Appelé quand Android interrompt le service (ex : appel entrant)
    }

    /**
     * Retourne tout le texte visible à l'écran en parcourant l'arbre des vues.
     * Parcourt toutes les fenêtres accessibles (hors overlays) pour éviter
     * de lire un contexte périmé via rootInActiveWindow.
     */
    fun extractVisibleText(): String {
        val sb = StringBuilder()

        // Parcourir toutes les fenêtres en excluant les overlays et le clavier
        windows?.forEach { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                window.root?.let { collectText(it, sb) }
            }
        }

        // Fallback si aucune fenêtre accessible n'a été trouvée
        if (sb.isBlank()) {
            rootInActiveWindow?.let { collectText(it, sb) }
        }

        return sb.toString()
    }

    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        if (!node.text.isNullOrBlank()) {
            sb.append(node.text).append(" ")
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectText(it, sb) }
        }
    }
}
