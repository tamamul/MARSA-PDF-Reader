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
import android.view.VelocityTracker
import android.view.View
import android.widget.OverScroller
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PdfDocumentView(context: Context) : View(context) {

    companion object {
        private const val TAG = "PdfDocumentView"

        private const val MAX_BITMAP_WIDTH = 2400
        private const val MIN_BITMAP_WIDTH = 320
        private const val RENDER_SCALE_EXTRA = 1.35f
        private const val ZOOM_BUCKET_STEP = 0.25f
        private const val MIN_ZOOM = 1f
        private const val MAX_ZOOM = 4f
        private const val ZOOM_DEBOUNCE_MS = 200L
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
    private var lastY = 0f
    private var lastX = 0f
    private var isScaling = false

    private var pinchFocusX = 0f
    private var pinchFocusY = 0f
    private var pinchStartZoom = 1f
    private var pinchStartScroll = 0f

    private val pageGap = dp(12).toFloat()
    private val sidePadding = dp(12).toFloat()

    private val bitmapCache: LruCache<Long, Bitmap>
    private val pendingRenders: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderExecutor = Executors.newFixedThreadPool(2)

    @Volatile
    private var invalidateScheduled = false

    private fun scheduleInvalidate() {
        if (isReleased) return
        if (invalidateScheduled) return

        invalidateScheduled = true
        mainHandler.post {
            if (isReleased) {
                invalidateScheduled = false
                return@post
            }
            invalidateScheduled = false
            postInvalidateOnAnimation()
        }
    }

    private fun evictStaleZoomCache() {
        val currentBucket = zoomBucket(zoom)
        val staleKeys = bitmapCache.snapshot().keys.filter { key ->
            val bucket = (key ushr 32).toInt()
            bucket != currentBucket
        }
        staleKeys.forEach { bitmapCache.remove(it) }
    }

    private val zoomDebounceTask = Runnable {
        if (isReleased) return@Runnable
        evictStaleZoomCache()
        scheduleInvalidate()
    }

    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null

    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = true
        isAntiAlias = true
        isDither = true
    }

    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(245, 245, 245)
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                scroller.abortAnimation()

                pinchFocusX = detector.focusX
                pinchFocusY = detector.focusY
                pinchStartZoom = zoom
                pinchStartScroll = scrollOffset

                mainHandler.removeCallbacks(zoomDebounceTask)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val targetZoom = (zoom * detector.scaleFactor)
                    .coerceIn(MIN_ZOOM, MAX_ZOOM)
                if (abs(targetZoom - zoom) < 0.01f) return true

                applyFocalZoom(targetZoom, pinchFocusX, pinchFocusY, pinchStartZoom, pinchStartScroll)
                postInvalidateOnAnimation()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
                mainHandler.removeCallbacks(zoomDebounceTask)
                mainHandler.postDelayed(zoomDebounceTask, ZOOM_DEBOUNCE_MS)
            }
        }
    )

    init {
        val cacheKb = (Runtime.getRuntime().maxMemory() / 1024 / 6)
            .toInt()
            .coerceAtLeast(4096)

        bitmapCache = object : LruCache<Long, Bitmap>(cacheKb) {
            override fun sizeOf(key: Long, value: Bitmap): Int {
                return (value.byteCount / 1024).coerceAtLeast(1)
            }

            override fun entryRemoved(
                evicted: Boolean,
                key: Long,
                oldValue: Bitmap,
                newValue: Bitmap?
            ) {
                if (evicted && oldValue !== newValue && !oldValue.isRecycled) {
                    oldValue.recycle()
                }
            }
        }

        isFocusable = true
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    fun setRenderer(newRenderer: PdfRenderer?) {
        if (isReleased) return

        synchronized(rendererLock) {
            generation++

            pdf = null
            pendingRenders.clear()
            bitmapCache.evictAll()
            pageSizes.clear()

            scrollOffset = 0f
            scrollXOffset = 0f
            scroller.abortAnimation()

            if (newRenderer != null) {
                try {
                    for (i in 0 until newRenderer.pageCount) {
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
                    Log.e(TAG, "Gagal membaca metadata PDF", e)
                    pageSizes.clear()
                    pdf = null
                }
            }
        }

        scheduleInvalidate()
    }

    fun fitToScreen() {
        zoom = 1f
        scrollOffset = 0f
        scrollXOffset = 0f
        scroller.abortAnimation()
        mainHandler.removeCallbacks(zoomDebounceTask)
        evictStaleZoomCache()
        scheduleInvalidate()
    }

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
            total += pageHeight(i, pageWidth)
            total += pageGap * zoom
        }
        return total
    }

    private fun maxScroll(): Int =
        max(0f, totalDocumentHeight() - height).toInt()

    private fun maxScrollX(): Int =
        max(0f, (basePageWidth() * zoom) - width).toInt()

    private fun clampScroll() {
        scrollOffset = scrollOffset.coerceIn(0f, maxScroll().toFloat())
        val maxX = maxScrollX().toFloat()
        if (maxX > 0f) {
            scrollXOffset = scrollXOffset.coerceIn(-maxX, maxX)
        } else {
            scrollXOffset = 0f
        }
    }

    private fun zoomBucket(z: Float): Int {
        return (z / ZOOM_BUCKET_STEP).toInt()
    }

    private fun cacheKey(pageIndex: Int, bucket: Int): Long {
        return (bucket.toLong() shl 32) or (pageIndex.toLong() and 0xffffffffL)
    }

    private fun applyFocalZoom(
        targetZoom: Float,
        focusX: Float,
        focusY: Float,
        _startZoom: Float,
        _startScroll: Float
    ) {
        val clamped = targetZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (clamped == zoom) return

        val docY = (scrollOffset + focusY) / zoom
        zoom = clamped
        val newScroll = docY * zoom - focusY
        scrollOffset = newScroll

        clampScroll()
    }

    private fun requestRender(index: Int) {
        if (isReleased || isScaling) return
        if (index !in pageSizes.indices) return

        val bucket = zoomBucket(zoom)
        val key = cacheKey(index, bucket)
        if (bitmapCache.get(key) != null) return
        if (!pendingRenders.add(key)) return

        val version = generation
        val snapshotZoom = bucket * ZOOM_BUCKET_STEP
        val snapshotBaseWidth = basePageWidth()

        try {
            renderExecutor.execute {
                var result: Bitmap? = null
                try {
                    synchronized(rendererLock) {
                        if (isReleased || generation != version) return@synchronized

                        val activeRenderer = pdf ?: return@synchronized
                        val page = activeRenderer.openPage(index)
                        try {
                            if (page.width <= 0 || page.height <= 0) return@synchronized

                            val desiredWidth =
                                (snapshotBaseWidth * snapshotZoom * RENDER_SCALE_EXTRA).toInt()
                            val targetWidth = desiredWidth
                                .coerceIn(MIN_BITMAP_WIDTH, MAX_BITMAP_WIDTH)

                            val ratio = page.height.toFloat() / page.width.toFloat()
                            if (!ratio.isFinite() || ratio <= 0f) return@synchronized

                            val targetHeight = (targetWidth * ratio)
                                .toInt()
                                .coerceAtLeast(1)

                            val bmp = Bitmap.createBitmap(
                                targetWidth,
                                targetHeight,
                                Bitmap.Config.ARGB_8888
                            )
                            bmp.eraseColor(Color.WHITE)

                            val destRect = Rect(0, 0, targetWidth, targetHeight)
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
                    }
                } catch (ise: IllegalStateException) {
                    Log.w(TAG, "Renderer closed saat render halaman $index", ise)
                } catch (t: Throwable) {
                    Log.e(TAG, "Gagal render halaman $index", t)
                } finally {
                    val finalBitmap = result
                    mainHandler.post {
                        pendingRenders.remove(key)

                        if (isReleased || generation != version) {
                            finalBitmap?.recycle()
                            return@post
                        }

                        if (finalBitmap != null &&
                            !finalBitmap.isRecycled &&
                            zoomBucket(zoom) == bucket
                        ) {
                            bitmapCache.put(key, finalBitmap)
                            postInvalidateOnAnimation()
                        } else {
                            finalBitmap?.recycle()
                        }
                    }
                }
            }
        } catch (re: RejectedExecutionException) {
            pendingRenders.remove(key)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(35, 42, 55))

        if (pageSizes.isEmpty()) return

        val scaledWidth = basePageWidth() * zoom
        val left = (width - scaledWidth) / 2f + scrollXOffset
        var top = pageGap * zoom - scrollOffset

        val bucket = zoomBucket(zoom)

        var firstVisible = -1
        var lastVisible = -1

        for (i in pageSizes.indices) {
            val scaledHeight = pageHeight(i, scaledWidth)
            val bottom = top + scaledHeight

            if (bottom >= 0f && top <= height.toFloat()) {
                if (firstVisible == -1) firstVisible = i
                lastVisible = i

                val rect = RectF(left, top, left + scaledWidth, bottom)
                canvas.drawRect(rect, placeholderPaint)

                val key = cacheKey(i, bucket)
                val bitmap = bitmapCache.get(key)

                if (bitmap != null && !bitmap.isRecycled) {
                    canvas.drawBitmap(bitmap, null, rect, pagePaint)
                }
            }

            top = bottom + pageGap * zoom
        }

        if (!isScaling && firstVisible >= 0) {
            for (i in firstVisible..lastVisible) requestRender(i)
            if (firstVisible > 0) requestRender(firstVisible - 1)
            if (lastVisible < pageSizes.size - 1) requestRender(lastVisible + 1)
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffset = scroller.currY.toFloat()
            clampScroll()
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isReleased) return false

        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.abortAnimation()
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                lastY = event.y
                lastX = event.x
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

                if (!isScaling && !scaleDetector.isInProgress && event.pointerCount == 1) {
                    val deltaX = event.x - lastX
                    val deltaY = event.y - lastY

                    if (abs(deltaX) > abs(deltaY)) {
                        scrollXOffset += deltaX
                        clampScroll()
                    } else {
                        scrollOffset -= deltaY
                        clampScroll()
                    }

                    postInvalidateOnAnimation()
                }

                lastX = event.x
                lastY = event.y
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                lastX = event.getX(if (event.actionIndex == 0) 1 else 0)
                lastY = event.getY(if (event.actionIndex == 0) 1 else 0)
                return true
            }

            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)

                if (!isScaling && !scaleDetector.isInProgress) {
                    val velocityY = -(velocityTracker?.yVelocity ?: 0f)
                    val velocityX = -(velocityTracker?.xVelocity ?: 0f)

                    if (abs(velocityY) > 100f) {
                        scroller.fling(
                            0, scrollOffset.toInt(),
                            0, velocityY.toInt(),
                            0, 0,
                            0, maxScroll()
                        )
                        postInvalidateOnAnimation()
                    }

                    if (abs(velocityX) > 120f) {
                        val maxX = maxScrollX().toFloat()
                        if (maxX > 0f) {
                            scrollXOffset += velocityX / 12f
                            clampScroll()
                            postInvalidateOnAnimation()
                        }
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

        mainHandler.removeCallbacks(zoomDebounceTask)
        mainHandler.removeCallbacksAndMessages(null)

        scroller.abortAnimation()
        velocityTracker?.recycle()
        velocityTracker = null

        renderExecutor.shutdownNow()
    }
}
