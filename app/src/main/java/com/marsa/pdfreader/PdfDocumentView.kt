
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
import android.view.VelocityTracker
import android.view.View
import android.widget.OverScroller
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class PdfDocumentView(context: Context) : View(context) {

    companion object {
        private const val TAG = "PdfDocumentView"
        private const val MAX_WIDTH = 1800
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

    private val pageGap = dp(12).toFloat()
    private val sidePadding = dp(12).toFloat()

    private val mainHandler = Handler(Looper.getMainLooper())

    private val renderExecutor = Executors.newSingleThreadExecutor()

    private val pendingRenders: MutableSet<Long> =
        ConcurrentHashMap.newKeySet()

    private val bitmapCache: LruCache<Int, Bitmap>

    private val scroller = OverScroller(context)

    private var velocityTracker: VelocityTracker? = null

    private val pagePaint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val placeholderPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }

    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

                override fun onScaleBegin(
                    detector: ScaleGestureDetector
                ): Boolean {
                    isScaling = true
                    scroller.abortAnimation()
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
        val cacheKb =
            (Runtime.getRuntime().maxMemory() / 1024 / 8)
                .toInt()
                .coerceAtLeast(4096)

        bitmapCache = object : LruCache<Int, Bitmap>(cacheKb) {
            override fun sizeOf(key: Int, value: Bitmap): Int {
                return (value.byteCount / 1024).coerceAtLeast(1)
            }
        }

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

            generation++

            pdf = null
            pendingRenders.clear()
            bitmapCache.evictAll()

            pageSizes.clear()

            scrollOffset = 0f
            zoom = 1f

            scroller.abortAnimation()

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
        scroller.abortAnimation()
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

    private fun maxScroll(): Int {
        return max(
            0f,
            totalDocumentHeight() - height
        ).toInt()
    }

    private fun clampScroll() {
        scrollOffset = scrollOffset.coerceIn(
            0f,
            maxScroll().toFloat()
        )
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
            (version shl 32) or
                (index.toLong() and 0xffffffffL)

        if (!pendingRenders.add(renderKey)) return

        val targetBaseWidth = basePageWidth()

        try {

            renderExecutor.execute {

                var result: Bitmap? = null

                try {

                    synchronized(rendererLock) {

                        if (
                            isReleased ||
                            generation != version
                        ) {
                            return@synchronized
                        }

                        val activeRenderer = pdf
                            ?: return@synchronized

                        val page = activeRenderer.openPage(index)

                        try {

                            // Render dibuat lebih besar dari lebar layar
                            // agar hasil tetap tajam saat diperbesar.
                            val targetWidth = min(
                                MAX_WIDTH,
                                (targetBaseWidth * 2f)
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

        var firstVisible = -1
        var lastVisible = -1

        for (i in pageSizes.indices) {

            val scaledHeight = pageHeight(i, scaledWidth)
            val bottom = top + scaledHeight

            if (bottom >= 0f && top <= height.toFloat()) {

                if (firstVisible == -1) {
                    firstVisible = i
                }

                lastVisible = i

                val rect = RectF(
                    left,
                    top,
                    left + scaledWidth,
                    bottom
                )

                // Selalu gambar bidang putih yang stabil.
                canvas.drawRect(rect, placeholderPaint)

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
                }
            }

            top = bottom + pageGap * zoom
        }

        // Prioritaskan halaman yang terlihat.
        if (firstVisible >= 0) {

            for (i in firstVisible..lastVisible) {
                requestRender(i)
            }

            // Siapkan halaman sebelum dan sesudahnya.
            requestRender(firstVisible - 1)
            requestRender(lastVisible + 1)
        }
    }

    // ==================================================
    // SCROLL ANIMATION
    // ==================================================

    override fun computeScroll() {

        if (scroller.computeScrollOffset()) {

            scrollOffset = scroller.currY.toFloat()

            clampScroll()

            postInvalidateOnAnimation()
        }
    }

    // ==================================================
    // SENTUHAN, SCROLL DAN PINCH ZOOM
    // ==================================================

    override fun onTouchEvent(event: MotionEvent): Boolean {

        if (isReleased) return false

        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {

                scroller.abortAnimation()

                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()

                velocityTracker?.addMovement(event)

                lastY = event.y

                parent?.requestDisallowInterceptTouchEvent(true)

                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                isScaling = true
                velocityTracker?.addMovement(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {

                velocityTracker?.addMovement(event)

                if (
                    !isScaling &&
                    !scaleDetector.isInProgress &&
                    event.pointerCount == 1
                ) {

                    val deltaY = event.y - lastY

                    scrollOffset -= deltaY

                    clampScroll()

                    postInvalidateOnAnimation()
                }

                lastY = event.y

                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {

                lastY = event.getY(
                    if (event.actionIndex == 0) 1 else 0
                )

                return true
            }

            MotionEvent.ACTION_UP -> {

                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)

                if (
                    !isScaling &&
                    !scaleDetector.isInProgress
                ) {

                    val velocityY =
                        -(velocityTracker?.yVelocity ?: 0f)

                    if (kotlin.math.abs(velocityY) > 100f) {

                        scroller.fling(
                            0,
                            scrollOffset.toInt(),
                            0,
                            velocityY.toInt(),
                            0,
                            0,
                            0,
                            maxScroll()
                        )

                        postInvalidateOnAnimation()
                    }
                }

                velocityTracker?.recycle()
                velocityTracker = null

                isScaling = false

                parent?.requestDisallowInterceptTouchEvent(false)

                performClick()

                return true
            }

            MotionEvent.ACTION_CANCEL -> {

                velocityTracker?.recycle()
                velocityTracker = null

                isScaling = false

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

        scroller.abortAnimation()

        velocityTracker?.recycle()
        velocityTracker = null

        renderExecutor.shutdownNow()

        mainHandler.removeCallbacksAndMessages(null)
    }
}
