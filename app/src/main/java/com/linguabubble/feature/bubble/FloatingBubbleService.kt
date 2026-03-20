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
import com.linguabubble.core.util.DebugStore
import com.linguabubble.core.util.KuromojiTokenizer
import com.linguabubble.databinding.OverlayBubbleBinding
import com.linguabubble.databinding.OverlayPanelBinding
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
 * Sprint 2 : tap → capture texte AccessibilityService → tokenisation Kuromoji
 *             → lookup Jisho (WordRepository) → affichage des mots dans le panneau.
 * Sprint 3 : OCR réel via MediaProjection + ML Kit japonais (on-device).
 *             L'AccessibilityService reste en fallback si l'OCR échoue.
 */
@AndroidEntryPoint
class FloatingBubbleService : Service() {

    companion object {
        /** Permet à MainActivity de savoir si le service tourne. */
        var isRunning = false
            private set

        const val EXTRA_MP_RESULT_CODE = "mp_result_code"
        const val EXTRA_MP_DATA        = "mp_data"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "bubble_channel"
    }

    // ─── Injection Hilt ───────────────────────────────────────────────────────

    @Inject lateinit var wordRepository: WordRepository
    @Inject lateinit var kuromojiTokenizer: KuromojiTokenizer
    @Inject lateinit var ocrProcessor: OcrProcessor

    // ─── MediaProjection ──────────────────────────────────────────────────────

    private var mediaProjection: MediaProjection? = null

    // ─── Coroutines ───────────────────────────────────────────────────────────

    private val serviceJob   = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val _words = MutableStateFlow<List<com.linguabubble.core.domain.model.WordEntry>>(emptyList())
    val words: StateFlow<List<com.linguabubble.core.domain.model.WordEntry>> = _words

    private val _isLoading = MutableStateFlow(false)

    // ─── UI ───────────────────────────────────────────────────────────────────

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleBinding: OverlayBubbleBinding
    private lateinit var bubbleParams: WindowManager.LayoutParams

    private var panelBinding: OverlayPanelBinding? = null
    private var panelParams:  WindowManager.LayoutParams? = null
    private var isPanelVisible = false

    private var wordAdapter: WordAdapter? = null

    // État du drag / physique
    private var dragStartX     = 0
    private var dragStartY     = 0
    private var touchStartX    = 0f
    private var touchStartY    = 0f
    private var isDragging     = false
    private var velocityTracker: VelocityTracker? = null
    private var physicsAnimator: ValueAnimator?   = null

    // Spring drag : cible du doigt, la bulle rattrape progressivement
    private var fingerTargetX      = 0f
    private var fingerTargetY      = 0f
    private var springFrameCallback: Choreographer.FrameCallback? = null
    private val SPRING_FACTOR      = 0.22f  // ~22 % de rattrapage par frame à 60fps

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning     = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        // Sur API 29+, déclarer les types de service (requis par Android 14 pour mediaProjection)
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
            // Nettoyer proprement si la projection est révoquée par l'OS
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    ocrProcessor.stop()
                    mediaProjection = null
                }
            }, Handler(Looper.getMainLooper()))
            // Démarrer le VirtualDisplay une seule fois — il restera actif tout le service
            ocrProcessor.start(mediaProjection!!)
        }

        return START_NOT_STICKY
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
    }

    // ─── Création de la bulle ─────────────────────────────────────────────────

    private val themedContext by lazy { ContextThemeWrapper(this, R.style.Theme_LinguaBubble_Overlay) }

    /** Taille de la bulle en pixels (44dp). */
    private val bubbleSizePx get() = (44 * resources.displayMetrics.density + 0.5f).toInt()

    /** Marge minimale par rapport aux bords de l'écran (couvre la zone gesture navigation ~20dp). */
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
                    }
                    if (isDragging) {
                        // Le Choreographer déplace la bulle vers ces coordonnées en douceur
                        fingerTargetX = (dragStartX + dx).toFloat()
                        fingerTargetY = (dragStartY + dy).toFloat()
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
                    if (!isDragging) onBubbleTapped() else flingWithPhysics(vx, vy)
                    true
                }
                else -> false
            }
        }
    }

    // ─── Spring drag (Choreographer) ──────────────────────────────────────────

    /**
     * Démarre une boucle Choreographer qui rapproche la bulle de [fingerTargetX/Y]
     * à chaque frame. Le facteur SPRING_FACTOR (~22 %) donne un léger retard naturel
     * sans être trop visqueux.
     */
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

    // ─── Physique 2D + snap magnétique ───────────────────────────────────────

    /**
     * Lance la bulle avec la vélocité du doigt en px/s.
     * Friction exponentielle (vitesse divisée par ~2 toutes les 200 ms).
     * Rebond amorti sur les bords haut / bas.
     * Quand la vitesse est négligeable → snap au bord gauche ou droit.
     */
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

                // Friction : k=3.5 → vitesse × 0.5 toutes les ~200 ms
                val decay = exp(-3.5f * dt)
                velX *= decay
                velY *= decay

                posX += velX * dt
                posY += velY * dt

                // Rebond sur les bords haut / bas (amortissement 40 %), avec marge gesture
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

                // Vitesse négligeable → snap final au bord le plus proche
                if (abs(velX) < 60f && abs(velY) < 60f) {
                    anim.cancel()
                    snapToEdge(velX)
                }
            }
            start()
        }
    }

    /**
     * Anime la bulle vers le bord gauche ou droit.
     * Si la vélocité dépasse le seuil, elle choisit le bord ; sinon, c'est la position.
     */
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
        val targetX = if (snapRight) screenWidth - bubbleWidth - ep else ep
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

    /** Assombrissement rapide au tap (sans squish ni overshoot). */
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
     *  1. Masque l'overlay (pour ne pas l'inclure dans le screenshot OCR)
     *  2. Capture l'écran via MediaProjection → ML Kit OCR japonais
     *  3. Fallback sur AccessibilityService si l'OCR échoue ou n'est pas disponible
     *  4. Kuromoji tokenisation → lookup Jisho → mise à jour de l'UI
     */
    private fun captureAndAnalyze() {
        serviceScope.launch {
            _words.value    = emptyList()
            _isLoading.value = true
            if (BuildConfig.DEBUG) DebugStore.reset()

            // Masquer l'overlay pour qu'il n'apparaisse pas dans le screenshot
            bubbleBinding.root.alpha  = 0f
            panelBinding?.root?.alpha = 0f
            delay(150) // laisser le temps au WindowManager de dessiner le frame suivant

            try {
                val text = withContext(Dispatchers.IO) {
                    val ocr = if (ocrProcessor.isReady) ocrProcessor.extractText() else ""
                    if (ocr.isNotBlank()) ocr
                    else LinguaAccessibilityService.instance?.extractVisibleText().orEmpty()
                }
                if (BuildConfig.DEBUG) DebugStore.rawText = text
                if (text.isBlank()) { _words.value = emptyList(); return@launch }

                val tokens = withContext(Dispatchers.IO) { kuromojiTokenizer.tokenize(text) }
                if (BuildConfig.DEBUG) DebugStore.tokens = tokens
                if (tokens.isEmpty()) { _words.value = emptyList(); return@launch }

                _words.value = withContext(Dispatchers.IO) { wordRepository.lookupWords(tokens) }
            } catch (e: Exception) {
                _words.value = emptyList()
            } finally {
                bubbleBinding.root.alpha  = 1f
                panelBinding?.root?.alpha = 1f
                _isLoading.value = false
            }
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

            wordAdapter = WordAdapter()
            panelBinding!!.rvWords.apply {
                layoutManager = LinearLayoutManager(themedContext, RecyclerView.HORIZONTAL, false)
                adapter        = wordAdapter
            }

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
            windowManager.addView(panelBinding!!.root, panelParams!!)

            // Remettre la bulle au-dessus du panneau (Z-order = dernier ajouté = au-dessus)
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

    private fun hidePanel() {
        panelBinding?.root?.visibility = View.GONE
        // Réinitialiser la section debug pour la prochaine ouverture
        panelBinding?.scrollDebug?.visibility = View.GONE
        panelBinding?.tvDebug?.text = ""
        isPanelVisible = false
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LinguaBubble",
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
            .setContentTitle("LinguaBubble")
            .setContentText("Bulle active — tapez pour analyser l'écran")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
