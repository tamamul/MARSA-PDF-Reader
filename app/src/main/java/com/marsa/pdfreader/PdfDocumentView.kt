
package com.marsa.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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
import kotlin.math.max
import kotlin.math.min

class PdfDocumentView(context: Context) : View(context) {

    companion object {
        private const val TAG = "PdfDocumentView"
        private const val MAX_WIDTH = 1600
        private const val MIN_WIDTH = 320
    }

    private val rendererLock = Any()

    @Volatile
    private var pdf: PdfRenderer? = null

    private val pageSizes = mutableListOf<Pair<Int, Int>>()

    @Volatile
    private var generation = 0L

    @Volatile
    private var isReleased = false

    private var zoom = 1f
    private var scrollOffset = 0f
    private var lastY = 0f
    private var isScaling = false

    private val pageGap = dp(16).toFloat()
    private val sidePadding = dp(16).toFloat()

    private val mainHandler = Handler(Looper.getMainLooper())

    // Satu thread: PdfRenderer tidak dipakai bersamaan.
    private val renderExecutor = Executors.newSingleThreadExecutor()

    // Kunci render terdiri dari versi dokumen + nomor halaman.
    private val pendingRenders: MutableSet<Long> =
        ConcurrentHashMap.newKeySet()

    private val maxCacheKb =
        (Runtime.getRuntime().maxMemory() / 1024 / 8)
            .toInt()
            .coerceAtLeast(4096)

    private val bitmapCache =
        object : LruCache<Int, Bitmap>(maxCacheKb) {
            override fun sizeOf(key: Int, value: Bitmap): Int {
                return (value.byteCount / 1024).coerceAtLeast(1)
            }
        }

    private val pagePaint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val placeholderPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(245, 245, 245)
        }

    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

                override fun onScaleBegin(
                    detector: ScaleGestureDetector
                ): Boolean {
                    isScaling = true
                    return true
                }

                override fun onScale(
                    detector: ScaleGestureDetector
                ): Boolean {
                    zoom = (zoom * detector.scaleFactor)
                        .coerceIn(1f, 4f)

                    clampScroll()
                    invalidate()
                    return true
                }

                override fun onScaleEnd(
                    detector: ScaleGestureDetector
                ) {
                    isScaling = false
                }
            }
        )

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        isFocusable = true
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    // ==================================================
    // RENDERER
    // ==================================================

    fun setRenderer(newRenderer: PdfRenderer?) {

        if (isReleased) return

        synchronized(rendererLock) {

            // Naikkan versi dokumen.
            // Hasil render dokumen sebelumnya otomatis ditolak.
            generation++

            pdf = null
            pendingRenders.clear()
            bitmapCache.evictAll()

            pageSizes.clear()
            scrollOffset = 0f
            zoom = 1f

            if (newRenderer != null) {

                try {
                    for (i in 0 until newRenderer.pageCount) {

                        val page = newRenderer.openPage(i)

                        try {
                            pageSizes.add(
                                Pair(page.width, page.height)
                            )
                        } finally {
                            page.close()
                        }
                    }

                    pdf = newRenderer

                } catch (e: Exception) {

                    Log.e(TAG, "Gagal membaca halaman PDF", e)

                    pageSizes.clear()
                    pdf = null
                }
            }
        }

        invalidate()
    }

    fun fitToScreen() {
        zoom = 1f
        scrollOffset = 0f
        invalidate()
    }

    // ==================================================
    // UKURAN HALAMAN
    // ==================================================

    private fun basePageWidth(): Float {
        return (width - sidePadding * 2).coerceAtLeast(1f)
    }

    private fun pageHeight(
        index: Int,
        pageWidth: Float
    ): Float {

        val size = pageSizes[index]

        return pageWidth * size.second / size.first
    }

    private fun totalDocumentHeight(): Float {

        if (pageSizes.isEmpty()) return 0f

        val pageWidth = basePageWidth() * zoom

        var total = pageGap * zoom

        for (i in pageSizes.indices) {

            total += pageHeight(i, pageWidth)
            total += pageGap * zoom
        }

        return total
    }

    private fun clampScroll() {

        val maximum = max(
            0f,
            totalDocumentHeight() - height
        )

        scrollOffset = scrollOffset.coerceIn(0f, maximum)
    }

    // ==================================================
    // RENDER BACKGROUND
    // ==================================================

    private fun requestRender(index: Int) {

        if (isReleased) return
        if (index !in pageSizes.indices) return
        if (bitmapCache.get(index) != null) return

        val version = generation

        val renderKey =
            (version shl 32) or (index.toLong() and 0xffffffffL)

        if (!pendingRenders.add(renderKey)) return

        val targetBaseWidth = basePageWidth()
        val targetZoom = zoom

        try {

            renderExecutor.execute {

                var result: Bitmap? = null

                try {

                    synchronized(rendererLock) {

                        // Pastikan masih dokumen yang sama.
                        if (isReleased || generation != version) {
                            return@synchronized
                        }

                        val activeRenderer = pdf
                            ?: return@synchronized

                        val page = activeRenderer.openPage(index)

                        try {

                            val targetWidth = min(
                                MAX_WIDTH,
                                (targetBaseWidth * targetZoom * 2f)
                                    .toInt()
                                    .coerceIn(MIN_WIDTH, MAX_WIDTH)
                            )

                            val ratio =
                                page.height.toFloat() /
                                    page.width.toFloat()

                            if (ratio <= 0f || !ratio.isFinite()) {
                                return@synchronized
                            }

                            val targetHeight =
                                (targetWidth * ratio)
                                    .toInt()
                                    .coerceAtLeast(1)

                            val bitmap = Bitmap.createBitmap(
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

Log.d(
    TAG,
    "Render berhasil: halaman=$index, ukuran=${bitmap.width}x${bitmap.height}"
)

                            result = bitmap

                        } finally {
                            page.close()
                        }
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Render halaman $index gagal",
                        e
                    )
                }

                val finalBitmap = result

                mainHandler.post {

                    pendingRenders.remove(renderKey)

                    if (
                        isReleased ||
                        generation != version
                    ) {
                        finalBitmap?.recycle()
                        return@post
                    }

                    if (
                        finalBitmap != null &&
                        !finalBitmap.isRecycled
                    ) {
                        bitmapCache.put(index, finalBitmap)
                        invalidate()
                    }
                }
            }

        } catch (e: Exception) {

            pendingRenders.remove(renderKey)

            Log.e(TAG, "Antrean render gagal", e)
        }
    }

    // ==================================================
    // GAMBAR HALAMAN
    // ==================================================

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

                val rect = RectF(
                    left,
                    top,
                    left + scaledWidth,
                    bottom
                )

                val bitmap = bitmapCache.get(i)

                if (
                    bitmap != null &&
                    !bitmap.isRecycled
                ) {

                    canvas.drawBitmap(
                        bitmap,
                        null,
                        rect,
                        pagePaint
                    )

                } else {

                    canvas.drawRect(
                        rect,
                        placeholderPaint
                    )

                    requestRender(i)
                }
            }

            top = bottom + pageGap * zoom
        }
    }

    // ==================================================
    // SCROLL DAN ZOOM
    // ==================================================

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

                if (
                    !isScaling &&
                    !scaleDetector.isInProgress
                ) {

                    scrollOffset -= event.y - lastY

                    clampScroll()
                    invalidate()
                }

                lastY = event.y

                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {

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

    // ==================================================
    // RELEASE
    // ==================================================

    fun release() {

        if (isReleased) return

        synchronized(rendererLock) {

            isReleased = true
            generation++

            pdf = null
            pageSizes.clear()
            pendingRenders.clear()

            bitmapCache.evictAll()
        }

        renderExecutor.shutdownNow()

        mainHandler.removeCallbacksAndMessages(null)
    }
}
