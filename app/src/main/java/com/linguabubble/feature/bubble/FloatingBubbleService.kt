package com.linguabubble.feature.bubble

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Choreographer
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.exp
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.linguabubble.BuildConfig
import com.linguabubble.MainActivity
import com.linguabubble.R
import com.linguabubble.accessibility.LinguaAccessibilityService
import com.linguabubble.core.data.local.WordProgressDao
import com.linguabubble.core.util.DebugStore
import com.linguabubble.core.util.DictionaryScanner
import com.linguabubble.core.domain.model.WordEntry
import com.linguabubble.databinding.OverlayBubbleBinding
import com.linguabubble.databinding.OverlayCloseZoneBinding
import com.linguabubble.databinding.OverlayPanelBinding
import com.linguabubble.feature.anki.CurrentDeckRepository
import com.linguabubble.ocr.OcrProcessor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import javax.inject.Inject

/**
 * Service en avant-plan qui affiche la bulle flottante.
 *
 * Sprint 1 : bulle draggable + panneau placeholder au tap.
 * Sprint 2 : tap → capture texte AccessibilityService → DictionaryScanner (longest-match JMdict)
 *             → lookup Jisho (WordRepository) → affichage des mots dans le panneau.
 * Sprint 3 : OCR réel via MediaProjection + ML Kit japonais (on-device).
 *             L'AccessibilityService reste en fallback si l'OCR échoue.
 *             Ajout deck courant (appui long), ordre par clickCount, × fermer, bouton MainActivity.
 */
@AndroidEntryPoint
class FloatingBubbleService : Service() {

    companion object {
        var isRunning = false
            private set

        const val EXTRA_MP_RESULT_CODE = "mp_result_code"
        const val EXTRA_MP_DATA        = "mp_data"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "bubble_channel"
    }

    // ─── Injection Hilt ───────────────────────────────────────────────────────

    @Inject lateinit var wordRepository: WordRepository
    @Inject lateinit var dictionaryScanner: DictionaryScanner
    @Inject lateinit var ocrProcessor: OcrProcessor
    @Inject lateinit var wordProgressDao: WordProgressDao
    @Inject lateinit var currentDeckRepository: CurrentDeckRepository
    @Inject lateinit var analyzeGrammarUseCase: com.linguabubble.core.domain.usecase.AnalyzeGrammarUseCase

    // ─── MediaProjection ──────────────────────────────────────────────────────

    private var mediaProjection: MediaProjection? = null

    // ─── Coroutines ───────────────────────────────────────────────────────────

    private val serviceJob   = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val _words = MutableStateFlow<List<WordEntry>>(emptyList())
    val words: StateFlow<List<WordEntry>> = _words

    private val _isLoading = MutableStateFlow(false)

    /** Dernier texte brut OCR — envoyé au LLM si l'utilisateur tape "Analyser". */
    private var lastRawText: String = ""

    // ─── UI ───────────────────────────────────────────────────────────────────

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleBinding: OverlayBubbleBinding
    private lateinit var bubbleParams: WindowManager.LayoutParams

    private var panelBinding: OverlayPanelBinding? = null
    private var panelParams:  WindowManager.LayoutParams? = null
    private var isPanelVisible = false

    private var closeZoneBinding: OverlayCloseZoneBinding? = null
    private var closeZoneParams:  WindowManager.LayoutParams? = null

    private var wordAdapter: WordAdapter? = null

    // État du drag / physique
    private var dragStartX     = 0
    private var dragStartY     = 0
    private var touchStartX    = 0f
    private var touchStartY    = 0f
    private var isDragging     = false
    private var velocityTracker: VelocityTracker? = null
    private var physicsAnimator: ValueAnimator?   = null

    // Spring drag
    private var fingerTargetX      = 0f
    private var fingerTargetY      = 0f
    private var springFrameCallback: Choreographer.FrameCallback? = null
    private val SPRING_FACTOR      = 0.22f

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning     = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        createBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_MP_RESULT_CODE, -1) ?: -1
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_MP_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_MP_DATA)
        }

        if (resultCode == android.app.Activity.RESULT_OK && data != null) {
            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    ocrProcessor.stop()
                    mediaProjection = null
                }
            }, Handler(Looper.getMainLooper()))
            ocrProcessor.start(mediaProjection!!)
        }

        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Redimensionne le VirtualDisplay sans recréer la MediaProjection (interdit par Android)
        ocrProcessor.onConfigurationChanged()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceJob.cancel()
        ocrProcessor.stop()
        mediaProjection?.stop()
        mediaProjection = null
        if (::bubbleBinding.isInitialized) windowManager.removeView(bubbleBinding.root)
        panelBinding?.let { windowManager.removeView(it.root) }
        closeZoneBinding?.let { runCatching { windowManager.removeView(it.root) } }
    }

    // ─── Création de la bulle ─────────────────────────────────────────────────

    private val themedContext by lazy { ContextThemeWrapper(this, R.style.Theme_Awabi_Overlay) }

    private val bubbleSizePx get() = (44 * resources.displayMetrics.density + 0.5f).toInt()
    private val edgePaddingPx get() = (20 * resources.displayMetrics.density + 0.5f).toInt()

    private fun createBubble() {
        bubbleBinding = OverlayBubbleBinding.inflate(LayoutInflater.from(themedContext))

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }

        windowManager.addView(bubbleBinding.root, bubbleParams)

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        bubbleBinding.root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    physicsAnimator?.cancel()
                    physicsAnimator = null
                    dragStartX     = bubbleParams.x
                    dragStartY     = bubbleParams.y
                    touchStartX    = event.rawX
                    touchStartY    = event.rawY
                    fingerTargetX  = dragStartX.toFloat()
                    fingerTargetY  = dragStartY.toFloat()
                    isDragging     = false
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain()
                    velocityTracker!!.addMovement(event)
                    startSpringDrag()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val dx = (event.rawX - touchStartX).toInt()
                    val dy = (event.rawY - touchStartY).toInt()
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                        showCloseZone()
                    }
                    if (isDragging) {
                        fingerTargetX = (dragStartX + dx).toFloat()
                        fingerTargetY = (dragStartY + dy).toFloat()
                        updateCloseZoneHighlight()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    stopSpringDrag()
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vx = velocityTracker?.xVelocity ?: 0f
                    val vy = velocityTracker?.yVelocity ?: 0f
                    velocityTracker?.recycle()
                    velocityTracker = null
                    if (!isDragging) {
                        onBubbleTapped()
                    } else {
                        val droppedOnClose = isOverCloseZone()
                        hideCloseZone()
                        if (droppedOnClose) stopSelf() else flingWithPhysics(vx, vy)
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ─── Spring drag (Choreographer) ──────────────────────────────────────────

    private fun startSpringDrag() {
        stopSpringDrag()
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val screenWidth  = windowManager.currentWindowMetrics.bounds.width()
                val screenHeight = windowManager.currentWindowMetrics.bounds.height()
                val bubbleW = bubbleBinding.root.width.takeIf  { it > 0 } ?: bubbleSizePx
                val bubbleH = bubbleBinding.root.height.takeIf { it > 0 } ?: bubbleSizePx
                val ep = edgePaddingPx
                val curX = bubbleParams.x.toFloat()
                val curY = bubbleParams.y.toFloat()
                val newX = (curX + (fingerTargetX - curX) * SPRING_FACTOR)
                    .coerceIn(ep.toFloat(), (screenWidth - bubbleW - ep).toFloat())
                val newY = (curY + (fingerTargetY - curY) * SPRING_FACTOR)
                    .coerceIn(ep.toFloat(), (screenHeight - bubbleH - ep).toFloat())
                bubbleParams.x = newX.toInt()
                bubbleParams.y = newY.toInt()
                windowManager.updateViewLayout(bubbleBinding.root, bubbleParams)
                if (isPanelVisible) {
                    panelParams!!.y = bubbleParams.y + bubbleSizePx + 8
                    windowManager.updateViewLayout(panelBinding!!.root, panelParams!!)
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        springFrameCallback = cb
        Choreographer.getInstance().postFrameCallback(cb)
    }

    private fun stopSpringDrag() {
        springFrameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        springFrameCallback = null
    }

    // ─── Drop zone "fermer" ───────────────────────────────────────────────────

    /** Hauteur de la zone de drop en pixels. */
    private val closeZoneHeightPx get() = (72 * resources.displayMetrics.density + 0.5f).toInt()

    private fun showCloseZone() {
        if (closeZoneBinding != null) {
            closeZoneBinding!!.root.visibility = View.VISIBLE
            return
        }
        closeZoneBinding = OverlayCloseZoneBinding.inflate(LayoutInflater.from(themedContext))
        val screenHeight = windowManager.currentWindowMetrics.bounds.height()
        val screenWidth  = windowManager.currentWindowMetrics.bounds.width()

        closeZoneParams = WindowManager.LayoutParams(
            screenWidth,
            closeZoneHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = screenHeight - closeZoneHeightPx
        }
        windowManager.addView(closeZoneBinding!!.root, closeZoneParams!!)
    }

    private fun hideCloseZone() {
        closeZoneBinding?.root?.visibility = View.GONE
        closeZoneBinding?.closeTarget?.setBackgroundResource(R.drawable.bg_close_zone_target)
    }

    /** Met en surbrillance la cible si la bulle est au-dessus de la zone. */
    private fun updateCloseZoneHighlight() {
        val over = isOverCloseZone()
        closeZoneBinding?.closeTarget?.setBackgroundResource(
            if (over) R.drawable.bg_close_zone_target_active
            else      R.drawable.bg_close_zone_target
        )
    }

    /** Retourne true si le centre bas de la bulle est dans la zone de fermeture. */
    private fun isOverCloseZone(): Boolean {
        val screenHeight  = windowManager.currentWindowMetrics.bounds.height()
        val zoneTop       = screenHeight - closeZoneHeightPx
        val bubbleBottom  = bubbleParams.y + (bubbleBinding.root.height.takeIf { it > 0 } ?: bubbleSizePx)
        return bubbleBottom >= zoneTop
    }

    // ─── Physique 2D + snap magnétique ───────────────────────────────────────

    private fun flingWithPhysics(vx: Float, vy: Float) {
        val screenWidth  = windowManager.currentWindowMetrics.bounds.width()
        val screenHeight = windowManager.currentWindowMetrics.bounds.height()
        val bubbleW = bubbleBinding.root.width.takeIf  { it > 0 } ?: bubbleSizePx
        val bubbleH = bubbleBinding.root.height.takeIf { it > 0 } ?: bubbleSizePx
        val ep   = edgePaddingPx.toFloat()
        val maxX = (screenWidth  - bubbleW - ep)
        val maxY = (screenHeight - bubbleH - ep)

        var velX      = vx
        var velY      = vy
        var posX      = bubbleParams.x.toFloat()
        var posY      = bubbleParams.y.toFloat()
        var lastNanos = System.nanoTime()

        physicsAnimator = ValueAnimator.ofInt(0, 1).apply {
            duration    = 3000L
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val now = System.nanoTime()
                val dt  = ((now - lastNanos) / 1_000_000_000f).coerceAtMost(0.05f)
                lastNanos = now

                val decay = exp(-3.5f * dt)
                velX *= decay
                velY *= decay

                posX += velX * dt
                posY += velY * dt

                if (posY < ep)    { posY = ep;    velY =  abs(velY) * 0.4f }
                if (posY > maxY)  { posY = maxY;  velY = -abs(velY) * 0.4f }
                posX = posX.coerceIn(ep, maxX)

                bubbleParams.x = posX.toInt()
                bubbleParams.y = posY.toInt()
                windowManager.updateViewLayout(bubbleBinding.root, bubbleParams)
                if (isPanelVisible) {
                    panelParams!!.x = bubbleParams.x
                    panelParams!!.y = bubbleParams.y + 220
                    windowManager.updateViewLayout(panelBinding!!.root, panelParams!!)
                }

                if (abs(velX) < 60f && abs(velY) < 60f) {
                    anim.cancel()
                    snapToEdge(velX)
                }
            }
            start()
        }
    }

    private fun snapToEdge(velocityX: Float = 0f) {
        val screenWidth = windowManager.currentWindowMetrics.bounds.width()
        val bubbleWidth = bubbleBinding.root.width.takeIf { it > 0 } ?: bubbleSizePx

        val ep = edgePaddingPx
        val velocityThreshold = 600f
        val snapRight = when {
            velocityX >  velocityThreshold -> true
            velocityX < -velocityThreshold -> false
            else -> bubbleParams.x + bubbleWidth / 2 >= screenWidth / 2
        }
        val targetX  = if (snapRight) screenWidth - bubbleWidth - ep else ep
        val duration = if (abs(velocityX) > velocityThreshold) 150L else 250L

        physicsAnimator = ValueAnimator.ofInt(bubbleParams.x, targetX).apply {
            this.duration = duration
            interpolator  = DecelerateInterpolator(1.5f)
            addUpdateListener { anim ->
                bubbleParams.x = anim.animatedValue as Int
                windowManager.updateViewLayout(bubbleBinding.root, bubbleParams)
                if (isPanelVisible) {
                    panelParams!!.x = bubbleParams.x
                    panelParams!!.y = bubbleParams.y + 220
                    windowManager.updateViewLayout(panelBinding!!.root, panelParams!!)
                }
            }
            start()
        }
    }

    // ─── Actions de la bulle ──────────────────────────────────────────────────

    private fun onBubbleTapped() {
        animateBubbleTap()
        if (isPanelVisible) {
            hidePanel()
        } else {
            showPanel()
            captureAndAnalyze()
        }
    }

    private fun animateBubbleTap() {
        val v = bubbleBinding.ivBubble
        v.animate().cancel()
        v.animate()
            .alpha(0.4f)
            .setDuration(70)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                v.animate()
                    .alpha(1f)
                    .setDuration(150)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }.start()
    }

    /**
     * Pipeline d'analyse :
     *  1. Masque l'overlay
     *  2. OCR MediaProjection → ML Kit japonais
     *  3. Fallback AccessibilityService
     *  4. DictionaryScanner (longest-match JMdict) → lookup → tri par clickCount ASC
     */
    private fun captureAndAnalyze() {
        serviceScope.launch {
            _words.value    = emptyList()
            _isLoading.value = true
            if (BuildConfig.DEBUG) DebugStore.reset()

            bubbleBinding.root.alpha  = 0f
            panelBinding?.root?.alpha = 0f
            delay(150)

            try {
                val text = withContext(Dispatchers.IO) {
                    val ocr = if (ocrProcessor.isReady) ocrProcessor.extractText() else ""
                    if (ocr.isNotBlank()) ocr
                    else LinguaAccessibilityService.instance?.extractVisibleText().orEmpty()
                }
                if (BuildConfig.DEBUG) DebugStore.rawText = text
                lastRawText = text
                if (text.isBlank()) { _words.value = emptyList(); return@launch }

                val tokens = withContext(Dispatchers.IO) { dictionaryScanner.scan(text) }
                if (BuildConfig.DEBUG) DebugStore.tokens = tokens
                if (tokens.isEmpty()) { _words.value = emptyList(); return@launch }

                val raw = withContext(Dispatchers.IO) { wordRepository.lookupWords(tokens) }
                // Mots jamais cliqués en premier, plus cliqués à la fin
                _words.value = raw.sortedBy { it.clickCount }
            } catch (e: Exception) {
                _words.value = emptyList()
            } finally {
                bubbleBinding.root.alpha  = 1f
                panelBinding?.root?.alpha = 1f
                _isLoading.value = false
            }
        }
    }

    // ─── Gestion du clic sur un mot (incrémente clickCount + re-tri) ──────────

    private fun onWordClicked(word: WordEntry) {
        // Incrémente en DB uniquement — le re-tri n'a lieu qu'à la prochaine capture.
        // L'adapter gère le badge/couleur localement sans re-trier la liste.
        serviceScope.launch {
            withContext(Dispatchers.IO) {
                wordProgressDao.incrementClickCount(word.kanji)
            }
        }
    }

    // ─── Gestion de l'appui long (ajout au deck) ──────────────────────────────

    private fun onWordLongPressed(word: WordEntry) {
        serviceScope.launch {
            currentDeckRepository.add(word)
        }
    }

    // ─── Panneau ──────────────────────────────────────────────────────────────

    private fun showPanel() {
        if (panelBinding == null) {
            panelBinding = OverlayPanelBinding.inflate(LayoutInflater.from(themedContext))

            val screenWidth = windowManager.currentWindowMetrics.bounds.width()
            panelParams = WindowManager.LayoutParams(
                screenWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = bubbleParams.y + bubbleSizePx + 8
            }

            wordAdapter = WordAdapter(
                onWordClicked      = { word -> onWordClicked(word) },
                onWordLongPressed  = { word -> onWordLongPressed(word) }
            )
            panelBinding!!.rvWords.apply {
                layoutManager = LinearLayoutManager(themedContext, RecyclerView.HORIZONTAL, false)
                adapter        = wordAdapter
            }

            // Bouton ouvrir MainActivity
            panelBinding!!.btnOpenMain.setOnClickListener {
                val intent = Intent(this@FloatingBubbleService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
            }

            // Bouton Analyser — appel LLM sur le dernier texte OCR capturé
            panelBinding!!.btnAnalyze.setOnClickListener { triggerGrammarAnalysis() }

            if (BuildConfig.DEBUG) {
                panelBinding!!.btnDebug.visibility = View.VISIBLE
                var debugVisible = false
                panelBinding!!.btnDebug.setOnClickListener {
                    debugVisible = !debugVisible
                    panelBinding!!.scrollDebug.visibility = if (debugVisible) View.VISIBLE else View.GONE
                    if (debugVisible) panelBinding!!.tvDebug.text = DebugStore.summary()
                }
            }

            observeWords()
            observeDeckCount()
            windowManager.addView(panelBinding!!.root, panelParams!!)

            windowManager.removeView(bubbleBinding.root)
            windowManager.addView(bubbleBinding.root, bubbleParams)
        } else {
            panelParams!!.y = bubbleParams.y + bubbleSizePx + 8
            windowManager.updateViewLayout(panelBinding!!.root, panelParams!!)
            panelBinding!!.root.visibility = View.VISIBLE
        }
        isPanelVisible = true
    }

    private fun observeWords() {
        serviceScope.launch {
            _isLoading.collect { loading ->
                panelBinding?.progressBar?.visibility = if (loading) View.VISIBLE else View.GONE
                if (loading) {
                    panelBinding?.rvWords?.visibility = View.GONE
                    panelBinding?.tvEmpty?.visibility = View.GONE
                }
            }
        }
        serviceScope.launch {
            words.collect { wordList ->
                wordAdapter?.submitList(wordList)
                panelBinding?.rvWords?.visibility = if (wordList.isNotEmpty()) View.VISIBLE else View.GONE
                panelBinding?.tvEmpty?.visibility = if (wordList.isEmpty() && !_isLoading.value) View.VISIBLE else View.GONE
            }
        }
    }

    private fun observeDeckCount() {
        serviceScope.launch {
            currentDeckRepository.count.collect { count ->
                val tv = panelBinding?.tvDeckCount ?: return@collect
                if (count > 0) {
                    tv.text = "$count mot${if (count > 1) "s" else ""} dans le deck"
                    tv.visibility = View.VISIBLE
                } else {
                    tv.visibility = View.GONE
                }
            }
        }
    }

    // ─── Analyse grammaticale LLM ──────────────────────────────────────────────

    private fun triggerGrammarAnalysis() {
        val panel = panelBinding ?: return

        // Réinitialiser l'état
        panel.layoutGrammar.visibility = View.VISIBLE
        panel.progressGrammar.visibility = View.VISIBLE
        panel.tvMainSentence.visibility = View.GONE
        panel.scrollGrammar.visibility = View.GONE
        panel.btnToggleCorrected.visibility = View.GONE
        panel.tvCorrected.visibility = View.GONE

        if (lastRawText.isBlank()) {
            panel.progressGrammar.visibility = View.GONE
            panel.tvMainSentence.visibility = View.VISIBLE
            panel.tvMainSentence.text = "Lance d'abord une capture (tap sur la bulle)."
            return
        }

        serviceScope.launch {
            try {
                val result = analyzeGrammarUseCase.analyze(lastRawText)

                panel.progressGrammar.visibility = View.GONE

                // Phrase principale — gras, plus grande
                panel.tvMainSentence.text = result.main
                panel.tvMainSentence.visibility = View.VISIBLE

                // Analyse grammaticale — markdown simple
                panel.tvGrammar.text = renderMarkdown(result.grammar)
                panel.scrollGrammar.visibility = View.VISIBLE

                // Traduction FR
                panel.tvTranslation.text = result.translation
                panel.tvTranslation.visibility = View.VISIBLE

                // Texte corrigé — caché, toggle
                panel.btnToggleCorrected.visibility = View.VISIBLE
                panel.tvCorrected.text = result.corrected
                var correctedVisible = false
                panel.btnToggleCorrected.setOnClickListener {
                    correctedVisible = !correctedVisible
                    panel.tvCorrected.visibility = if (correctedVisible) View.VISIBLE else View.GONE
                    panel.btnToggleCorrected.text = if (correctedVisible) "▼ Texte corrigé OCR" else "▶ Texte corrigé OCR"
                }

            } catch (e: Exception) {
                panel.progressGrammar.visibility = View.GONE
                panel.tvMainSentence.text = "Erreur : ${e.message}"
                panel.tvMainSentence.visibility = View.VISIBLE
            }
        }
    }

    /** Convertit **gras** et *italique* en Spannable via Html. */
    private fun renderMarkdown(md: String): android.text.Spanned {
        val html = md
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
            .replace(Regex("\\*(.+?)\\*"), "<i>$1</i>")
            .replace(Regex("^[-•] (.+)$", RegexOption.MULTILINE), "• $1")
            .replace("\n", "<br>")
        return android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT)
    }

    private fun hidePanel() {
        panelBinding?.root?.visibility = View.GONE
        panelBinding?.scrollDebug?.visibility = View.GONE
        panelBinding?.tvDebug?.text = ""
        panelBinding?.layoutGrammar?.visibility = View.GONE
        panelBinding?.tvMainSentence?.text = ""
        panelBinding?.tvGrammar?.text = ""
        panelBinding?.tvTranslation?.text = ""
        panelBinding?.tvTranslation?.visibility = View.GONE
        panelBinding?.tvCorrected?.text = ""
        panelBinding?.btnToggleCorrected?.visibility = View.GONE
        panelBinding?.tvCorrected?.visibility = View.GONE
        isPanelVisible = false
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Awabi",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Service de bulle flottante pour l'apprentissage du japonais"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Awabi")
            .setContentText("Bulle active — tapez pour analyser l'écran")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
