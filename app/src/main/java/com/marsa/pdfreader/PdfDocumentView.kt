package com.marsa.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.max
import kotlin.math.min

class PdfDocumentView(context: Context) : View(context) {

    companion object {
        private const val TAG = "PdfDocumentView"
        private const val MAX_TARGET_WIDTH = 1600
        private const val MIN_TARGET_WIDTH = 320
    }

    // ---------- Renderer & page metadata ----------
    private var pdf: PdfRenderer? = null
    private val pageSizes = mutableListOf<Pair<Int, Int>>()

    // ---------- View state ----------
    private var zoom = 1f
    private var scrollOffset = 0f
    private var lastY = 0f
    private var isScaling = false

    private val pageGap = dp(16).toFloat()
    private val sidePadding = dp(16).toFloat()

    // ---------- Cache ----------
    // maxCacheKb dalam KB, sekitar 1/8 heap
    private val maxCacheKb: Int =
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(1024)

    private val bitmapCache = object : LruCache<Int, Bitmap>(maxCacheKb) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
    }

    // ---------- Threading ----------
    private val renderExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    // ConcurrentHashMap.newKeySet() aman diakses multi-thread
    private val pendingRenders: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    @Volatile private var isReleased = false

    // ---------- Paint ----------
    private val pagePaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
    )
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 220, 220)
    }

    // ---------- Scale gesture ----------
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(1f, 4f)
                clampScroll()
                invalidate()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        }
    )

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        isFocusable = true
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // ============================================================
    // Public API
    // ============================================================

    fun setRenderer(newRenderer: PdfRenderer?) {
        if (isReleased) return

        // 1) Bersihkan state lama
        pendingRenders.clear()
        bitmapCache.evictAll()

        pdf = null
        pageSizes.clear()
        scrollOffset = 0f
        zoom = 1f

        // 2) Baca ukuran halaman. Jika ada halaman rusak, buang renderer.
        if (newRenderer != null) {
            try {
                val count = newRenderer.pageCount
                for (i in 0 until count) {
                    val page = newRenderer.openPage(i)
                    try {
                        if (page.width > 0 && page.height > 0) {
                            pageSizes.add(Pair(page.width, page.height))
                        }
                    } finally {
                        page.close()
                    }
                }
                pdf = newRenderer
            } catch (e: Exception) {
                Log.e(TAG, "Gagal membaca page metadata", e)
                pageSizes.clear()
                pdf = null
            }
        }

        invalidate()
    }

    fun fitToScreen() {
        zoom = 1f
        scrollOffset = 0f
        invalidate()
    }

    // ============================================================
    // Geometry
    // ============================================================

    private fun basePageWidth(): Float =
        (width - sidePadding * 2).coerceAtLeast(1f)

    private fun pageHeight(index: Int, pageWidth: Float): Float {
        val size = pageSizes[index]
        return pageWidth * size.second / size.first
    }

    private fun totalDocumentHeight(): Float {
        if (pageSizes.isEmpty()) return 0f
        val pageWidth = basePageWidth() * zoom
        var total = pageGap * zoom
        for (i in pageSizes.indices) {
            total += pageHeight(i, pageWidth) + pageGap * zoom
        }
        return total
    }

    private fun clampScroll() {
        val maximum = max(0f, totalDocumentHeight() - height)
        scrollOffset = scrollOffset.coerceIn(0f, maximum)
    }

    // ============================================================
    // Background rendering
    // ============================================================

    private fun requestRenderIfNeeded(index: Int) {
    if (isReleased) return
    if (index < 0 || index >= pageSizes.size) return
    if (bitmapCache.get(index) != null) return
    if (!pendingRenders.add(index)) return

    val renderer = pdf ?: run {
        pendingRenders.remove(index)
        return
    }

    val snapshotZoom = zoom
    val snapshotBaseWidth = basePageWidth().coerceAtLeast(1f)

    try {
        renderExecutor.execute {
            var result: Bitmap? = null
            try {
                if (isReleased) return@execute

                val page = renderer.openPage(index)
                try {
                    if (page.width <= 0 || page.height <= 0) return@execute

                    val targetWidth = min(
                        MAX_TARGET_WIDTH,
                        (snapshotBaseWidth * snapshotZoom * 2f)
                            .toInt()
                            .coerceIn(MIN_TARGET_WIDTH, MAX_TARGET_WIDTH)
                    )
                    val ratio = page.height.toFloat() / page.width.toFloat()
                    if (!ratio.isFinite() || ratio <= 0f) return@execute

                    val targetHeight = (targetWidth * ratio).toInt().coerceAtLeast(1)

                    // ARGB_8888 lebih aman daripada RGB_565
                    val bmp = Bitmap.createBitmap(
                        targetWidth, targetHeight, Bitmap.Config.ARGB_8888
                    )
                    bmp.eraseColor(Color.WHITE)

                    // destClip EKSPLISIT — wajib di banyak device
                    val destRect = android.graphics.Rect(0, 0, targetWidth, targetHeight)
                    page.render(
                        bmp,
                        destRect,
                        null,
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                    )
                    result = bmp
                } finally {
                    try { page.close() } catch (_: Exception) {}
                }
            } catch (ise: IllegalStateException) {
                Log.w(TAG, "Renderer closed saat render halaman $index", ise)
            } catch (t: Throwable) {
                Log.e(TAG, "Gagal render halaman $index", t)
            } finally {
                // ✅ SELALU bersihkan pendingRenders, apapun yang terjadi
                val finalBitmap = result
                mainHandler.post {
                    pendingRenders.remove(index)
                    if (isReleased) {
                        finalBitmap?.recycle()
                        return@post
                    }
                    if (finalBitmap != null &&
                        !finalBitmap.isRecycled &&
                        index < pageSizes.size
                    ) {
                        bitmapCache.put(index, finalBitmap)
                        invalidate()
                    } else {
                        // Kalau render gagal, biar bisa dicoba lagi nanti
                        invalidate()
                    }
                }
            }
        }
    } catch (re: RejectedExecutionException) {
        pendingRenders.remove(index)
    }
}

    // ============================================================
    // Drawing
    // ============================================================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(35, 42, 55))

        if (pageSizes.isEmpty()) return

        val scaledWidth = basePageWidth() * zoom
        val left = (width - scaledWidth) / 2f
        var top = pageGap * zoom - scrollOffset

        for (i in pageSizes.indices) {
            val scaledHeight = pageHeight(i, scaledWidth)
            val bottom = top + scaledHeight

            if (bottom >= 0 && top <= height) {
                val rect = RectF(left, top, left + scaledWidth, bottom)

                val bitmap = bitmapCache.get(i)
                if (bitmap != null && !bitmap.isRecycled) {
                    canvas.drawRect(rect, backgroundPaint)
                    canvas.drawBitmap(bitmap, null, rect, pagePaint)
                } else {
                    canvas.drawRect(rect, placeholderPaint)
                    requestRenderIfNeeded(i)
                }
            }

            top = bottom + pageGap * zoom
        }
    }

    // ============================================================
    // Touch / scroll
    // ============================================================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isReleased) return false

        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isScaling && !scaleDetector.isInProgress) {
                    scrollOffset -= event.y - lastY
                    clampScroll()
                    invalidate()
                }
                lastY = event.y
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                performClick()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ============================================================
    // Lifecycle
    // ============================================================

    fun release() {
        if (isReleased) return
        isReleased = true

        pdf = null
        pendingRenders.clear()
        bitmapCache.evictAll()

        mainHandler.removeCallbacksAndMessages(null)
        renderExecutor.shutdownNow()
    }
}