
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
import android.util.LruCache
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class PdfDocumentView(context: Context) : View(context) {

    // ---------- Renderer & page metadata ----------
    private var pdf: PdfRenderer? = null
    private val pageSizes = mutableListOf<Pair<Int, Int>>()
    private var pageCount = 0

    // ---------- View state ----------
    private var zoom = 1f
    private var scrollOffset = 0f
    private var lastY = 0f
    private var isScaling = false

    private val pageGap = dp(16).toFloat()
    private val sidePadding = dp(16).toFloat()

    // ---------- Cache (fixed) ----------
    // Ukuran cache dalam KB, sekitar 1/8 heap
    private val maxCacheKb: Int =
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(1024)

    private val bitmapCache = object : LruCache<Int, Bitmap>(maxCacheKb) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
    }

    // ---------- Threading ----------
    private val renderExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingRenders = HashSet<Int>()          // page index yang sedang dirender
    @Volatile private var isRendererClosed = false

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
        // 1) Hentikan semua render yang mungkin sedang berjalan
        isRendererClosed = true                     // tandai agar render lama berhenti
        pendingRenders.clear()
        bitmapCache.evictAll()

        // 2) Putus referensi ke renderer lama SEBELUM close
        pdf = null
        pageCount = 0
        pageSizes.clear()
        scrollOffset = 0f
        zoom = 1f

        // 3) Pasang renderer baru
        if (newRenderer != null) {
            val count = newRenderer.pageCount
            for (i in 0 until count) {
                val page = newRenderer.openPage(i)
                pageSizes.add(Pair(page.width, page.height))
                page.close()
            }
            pageCount = count
            pdf = newRenderer
            isRendererClosed = false
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
        if (bitmapCache.get(index) != null) return
        if (pendingRenders.contains(index)) return

        val renderer = pdf ?: return
        pendingRenders.add(index)

        renderExecutor.execute {
            var bitmap: Bitmap? = null
            try {
                // pastikan renderer belum ditutup
                if (isRendererClosed) return@execute

                val page = renderer.openPage(index)
                try {
                    val targetWidth = min(
                        1600,
                        (basePageWidth() * zoom * 2f).toInt().coerceAtLeast(1)
                    )
                    val ratio = page.height.toFloat() / page.width
                    val targetHeight = (targetWidth * ratio).toInt().coerceAtLeast(1)

                    bitmap = Bitmap.createBitmap(
                        targetWidth,
                        targetHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.eraseColor(Color.WHITE)
                    page.render(
                        bitmap,
                        null,
                        null,
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                    )
                } finally {
                    page.close()
                }
            } catch (_: Exception) {
                bitmap?.recycle()
                bitmap = null
            }

            val result = bitmap
            mainHandler.post {
                pendingRenders.remove(index)
                if (result != null && !isRendererClosed) {
                    bitmapCache.put(index, result)
                    invalidate()
                }
            }
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
                    // placeholder sambil menunggu render
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
        isRendererClosed = true
        pendingRenders.clear()
        bitmapCache.evictAll()
        renderExecutor.shutdownNow()
    }
}