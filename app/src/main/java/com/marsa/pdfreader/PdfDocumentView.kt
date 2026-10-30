
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
        private const val MAX_BITMAP_WIDTH = 2000
        private const val MIN_ZOOM = 1f
        private const val MAX_ZOOM = 4f
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
    private var scrollXOffset = 0f

    private var lastX = 0f
    private var lastY = 0f

    private var isScaling = false

    private val pageGap = dp(12).toFloat()
    private val sidePadding = dp(12).toFloat()

    private val mainHandler = Handler(Looper.getMainLooper())

    private val renderExecutor = Executors.newSingleThreadExecutor()

    private val pendingRenders: MutableSet<Int> =
        ConcurrentHashMap.newKeySet()

    private val bitmapCache: LruCache<Int, Bitmap>

    private val scroller = OverScroller(context)

    private var velocityTracker: VelocityTracker? = null

    private val pagePaint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            isFilterBitmap = true
            isDither = true
        }

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

                    val newZoom = (
                        zoom * detector.scaleFactor
                    ).coerceIn(MIN_ZOOM, MAX_ZOOM)

                    applyFocalZoom(
                        newZoom,
                        detector.focusX,
                        detector.focusY
                    )

                    postInvalidateOnAnimation()

                    return true
                }

                override fun onScaleEnd(
                    detector: ScaleGestureDetector
                ) {
                    isScaling = false
                    invalidate()
                }
            }
        )

    init {

        val cacheKb = (
            Runtime.getRuntime().maxMemory() / 1024 / 6
        ).toInt().coerceAtLeast(8192)

        bitmapCache = object : LruCache<Int, Bitmap>(cacheKb) {

            override fun sizeOf(
                key: Int,
                value: Bitmap
            ): Int {
                return (value.byteCount / 1024).coerceAtLeast(1)
            }

            override fun entryRemoved(
                evicted: Boolean,
                key: Int,
                oldValue: Bitmap,
                newValue: Bitmap?
            ) {
                if (
                    evicted &&
                    oldValue !== newValue &&
                    !oldValue.isRecycled
                ) {
                    oldValue.recycle()
                }
            }
        }

        isFocusable = true
    }

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density
        ).toInt()
    }

    // ==================================================
    // PDF RENDERER
    // ==================================================

    fun setRenderer(newRenderer: PdfRenderer?) {

        if (isReleased) return

        synchronized(rendererLock) {

            generation++

            pdf = null

            pendingRenders.clear()
            bitmapCache.evictAll()

            pageSizes.clear()

            zoom = 1f
            scrollOffset = 0f
            scrollXOffset = 0f

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

                    Log.e(TAG, "Gagal membaca PDF", e)

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
        scrollXOffset = 0f

        scroller.abortAnimation()

        invalidate()
    }

    // ==================================================
    // UKURAN HALAMAN
    // ==================================================

    private fun basePageWidth(): Float {
        return (
            width - sidePadding * 2
        ).coerceAtLeast(1f)
    }

    private fun pageHeight(
        index: Int,
        pageWidth: Float
    ): Float {

        val size = pageSizes[index]

        return pageWidth *
            size.second.toFloat() /
            size.first.toFloat()
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

    // Jarak geser kiri dan kanan dari posisi tengah.
    private fun maxScrollX(): Int {

        return max(
            0f,
            (basePageWidth() * zoom - width) / 2f
        ).toInt()
    }

    private fun clampScroll() {

        scrollOffset = scrollOffset.coerceIn(
            0f,
            maxScroll().toFloat()
        )

        val maxX = maxScrollX().toFloat()

        scrollXOffset = scrollXOffset.coerceIn(
            -maxX,
            maxX
        )
    }

    // ==================================================
    // ZOOM DENGAN TITIK FOKUS
    // ==================================================

    private fun applyFocalZoom(
        newZoom: Float,
        focusX: Float,
        focusY: Float
    ) {

        val targetZoom = newZoom.coerceIn(
            MIN_ZOOM,
            MAX_ZOOM
        )

        if (targetZoom == zoom) return

        val oldZoom = zoom

        val oldLeft =
            (width - basePageWidth() * oldZoom) / 2f +
                scrollXOffset

        val documentX =
            (focusX - oldLeft) / oldZoom

        val documentY =
            (scrollOffset + focusY) / oldZoom

        zoom = targetZoom

        val newLeft =
            (width - basePageWidth() * zoom) / 2f

        scrollXOffset =
            focusX - newLeft - documentX * zoom

        scrollOffset =
            documentY * zoom - focusY

        clampScroll()
    }

    // ==================================================
    // RENDER SATU KALI PER HALAMAN
    // ==================================================

    private fun requestRender(index: Int) {

        if (isReleased) return
        if (index !in pageSizes.indices) return

        // Tidak ada cache berdasarkan tingkat zoom.
        if (bitmapCache.get(index) != null) return

        if (!pendingRenders.add(index)) return

        val version = generation

        // Lebar ditentukan sekali, bukan berubah mengikuti zoom.
        val targetWidth = min(
            MAX_BITMAP_WIDTH,
            max(
                800,
                ((width - sidePadding * 2) * 2f).toInt()
            )
        )

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

                        val renderer = pdf
                            ?: return@synchronized

                        val page = renderer.openPage(index)

                        try {

                            val ratio =
                                page.height.toFloat() /
                                    page.width.toFloat()

                            if (
                                !ratio.isFinite() ||
                                ratio <= 0f
                            ) {
                                return@synchronized
                            }

                            val targetHeight = (
                                targetWidth * ratio
                            ).toInt().coerceAtLeast(1)

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

                    pendingRenders.remove(index)

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

            pendingRenders.remove(index)

            Log.e(TAG, "Antrean render gagal", e)
        }
    }

    // ==================================================
    // MENGGAMBAR PDF
    // ==================================================

    override fun onDraw(canvas: Canvas) {

        super.onDraw(canvas)

        canvas.drawColor(Color.rgb(35, 42, 55))

        if (pageSizes.isEmpty()) return

        val scaledWidth = basePageWidth() * zoom

        val left =
            (width - scaledWidth) / 2f +
                scrollXOffset

        var top = pageGap * zoom - scrollOffset

        var firstVisible = -1
        var lastVisible = -1

        for (i in pageSizes.indices) {

            val scaledHeight = pageHeight(
                i,
                scaledWidth
            )

            val bottom = top + scaledHeight

            if (
                bottom >= 0f &&
                top <= height.toFloat()
            ) {

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

                // Latar halaman selalu putih.
                canvas.drawRect(
                    rect,
                    placeholderPaint
                )

                // Bitmap yang sama digunakan untuk semua zoom.
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

        // Render halaman terlihat dan tetangganya.
        if (firstVisible >= 0) {

            for (i in firstVisible..lastVisible) {
                requestRender(i)
            }

            requestRender(firstVisible - 1)
            requestRender(lastVisible + 1)
        }
    }

    // ==================================================
    // SCROLL FLING
    // ==================================================

    override fun computeScroll() {

        if (scroller.computeScrollOffset()) {

            scrollXOffset = scroller.currX.toFloat()
            scrollOffset = scroller.currY.toFloat()

            clampScroll()

            postInvalidateOnAnimation()
        }
    }

    // ==================================================
    // SENTUHAN DAN GESER
    // ==================================================

    override fun onTouchEvent(
        event: MotionEvent
    ): Boolean {

        if (isReleased) return false

        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {

                scroller.abortAnimation()

                velocityTracker?.recycle()

                velocityTracker =
                    VelocityTracker.obtain()

                velocityTracker?.addMovement(event)

                lastX = event.x
                lastY = event.y

                parent?.requestDisallowInterceptTouchEvent(true)

                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {

                velocityTracker?.addMovement(event)

                return true
            }

            MotionEvent.ACTION_MOVE -> {

                velocityTracker?.addMovement(event)

                if (
                    !scaleDetector.isInProgress &&
                    !isScaling &&
                    event.pointerCount == 1
                ) {

                    val dx = event.x - lastX
                    val dy = event.y - lastY

                    // Zoom normal: scroll vertikal.
                    // Zoom diperbesar: bisa geser horizontal
                    // sekaligus vertikal.
                    if (zoom > 1.01f) {
                        scrollXOffset += dx
                    }

                    scrollOffset -= dy

                    clampScroll()

                    postInvalidateOnAnimation()
                }

                lastX = event.x
                lastY = event.y

                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {

                val remainingIndex =
                    if (event.actionIndex == 0) 1 else 0

                if (remainingIndex < event.pointerCount) {

                    lastX = event.getX(remainingIndex)
                    lastY = event.getY(remainingIndex)
                }

                return true
            }

            MotionEvent.ACTION_UP -> {

                velocityTracker?.addMovement(event)

                velocityTracker?.computeCurrentVelocity(1000)

                if (
                    !scaleDetector.isInProgress &&
                    !isScaling
                ) {

                    val velocityX =
                        velocityTracker?.xVelocity ?: 0f

                    val velocityY =
                        -(velocityTracker?.yVelocity ?: 0f)

                    if (
                        kotlin.math.abs(velocityX) > 100f ||
                        kotlin.math.abs(velocityY) > 100f
                    ) {

                        scroller.fling(
                            scrollXOffset.toInt(),
                            scrollOffset.toInt(),
                            if (zoom > 1.01f) velocityX.toInt() else 0,
                            velocityY.toInt(),
                            -maxScrollX(),
                            maxScrollX(),
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
