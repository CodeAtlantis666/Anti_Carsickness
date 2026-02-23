package com.antcarsickness

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

class ScreenLumaSampler(
    private val context: Context
) {
    companion object {
        private const val PROCESS_INTERVAL_MS = 45L
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val lock = Any()
    private var colorWidth = 0
    private var colorHeight = 0
    private var colorMap: IntArray? = null
    private var lastProcessMs = 0L

    fun start(resultCode: Int, dataIntent: android.content.Intent): Boolean {
        return runCatching {
            stop()

            val dm = context.resources.displayMetrics
            val captureWidth = (dm.widthPixels / 6).coerceIn(120, 280)
            val captureHeight = (dm.heightPixels / 6).coerceIn(200, 500)

            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projectionInstance = manager.getMediaProjection(resultCode, dataIntent) ?: return false

            val t = HandlerThread("luma-sampler")
            t.start()
            val h = Handler(t.looper)

            val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ r ->
                runCatching {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProcessMs < PROCESS_INTERVAL_MS) {
                        r.acquireLatestImage()?.close()
                        return@setOnImageAvailableListener
                    }
                    lastProcessMs = now

                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val rowStride = plane.rowStride
                        val pixelStride = plane.pixelStride
                        val width = image.width
                        val height = image.height

                        if (width <= 0 || height <= 0 || pixelStride <= 0 || rowStride <= 0) {
                            return@runCatching
                        }

                        val out = IntArray(width * height)
                        val alphaOffset = detectAlphaOffset(buffer, rowStride, pixelStride, width, height)
                        val limit = buffer.limit()
                        for (y in 0 until height) {
                            val row = y * rowStride
                            for (x in 0 until width) {
                                val offset = row + x * pixelStride
                                if (offset < 0 || offset + 2 >= limit) continue
                                val c0 = buffer.get(offset).toInt() and 0xFF
                                val c1 = buffer.get(offset + 1).toInt() and 0xFF
                                val c2 = buffer.get(offset + 2).toInt() and 0xFF
                                val c3 = if (pixelStride >= 4 && offset + 3 < limit) {
                                    buffer.get(offset + 3).toInt() and 0xFF
                                } else {
                                    0xFF
                                }
                                val (rByte, gByte, bByte) = when {
                                    pixelStride < 4 -> Triple(c0, c1, c2)
                                    alphaOffset == 0 -> Triple(c1, c2, c3)
                                    alphaOffset == 1 -> Triple(c0, c2, c3)
                                    alphaOffset == 2 -> Triple(c0, c1, c3)
                                    else -> Triple(c0, c1, c2)
                                }
                                out[y * width + x] =
                                    (0xFF shl 24) or
                                        (rByte shl 16) or
                                        (gByte shl 8) or
                                        bByte
                            }
                        }
                        synchronized(lock) {
                            colorWidth = width
                            colorHeight = height
                            colorMap = out
                        }
                    } finally {
                        image.close()
                    }
                }.onFailure {
                    r.acquireLatestImage()?.close()
                }
            }, h)

            val vd = projectionInstance.createVirtualDisplay(
                "motion-cues-luma",
                captureWidth,
                captureHeight,
                dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                h
            )

            projection = projectionInstance
            thread = t
            handler = h
            imageReader = reader
            virtualDisplay = vd
            true
        }.getOrElse {
            stop()
            false
        }
    }

    private fun detectAlphaOffset(
        buffer: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int
    ): Int {
        if (pixelStride < 4) return -1
        val limit = buffer.limit()
        val sums = LongArray(4)
        val sqSums = LongArray(4)
        val samples = 36
        for (i in 0 until samples) {
            val sx = ((i % 6) + 1) * width / 7
            val sy = ((i / 6) + 1) * height / 7
            val offset = sy * rowStride + sx * pixelStride
            for (ch in 0 until 4) {
                if (offset < 0 || offset + ch >= limit) continue
                val v = buffer.get(offset + ch).toInt() and 0xFF
                sums[ch] += v.toLong()
                sqSums[ch] += (v * v).toLong()
            }
        }

        var bestIdx = -1
        var bestVariance = Float.MAX_VALUE
        var bestMean = -1f
        for (ch in 0 until 4) {
            val mean = sums[ch].toFloat() / samples.toFloat()
            val meanSq = sqSums[ch].toFloat() / samples.toFloat()
            val variance = (meanSq - mean * mean).coerceAtLeast(0f)

            // Alpha tends to be nearly constant and close to 255 (sometimes 0 on odd vendors).
            val likelyOpaqueAlpha = mean >= 235f && variance <= 120f
            val likelyZeroAlpha = mean <= 20f && variance <= 120f
            if (likelyOpaqueAlpha || likelyZeroAlpha) {
                val betterVariance = variance < bestVariance
                val tieButBetterMean = variance == bestVariance && mean > bestMean
                if (betterVariance || tieButBetterMean) {
                    bestIdx = ch
                    bestVariance = variance
                    bestMean = mean
                }
            }
        }
        return bestIdx
    }

    fun stop() {
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        runCatching { thread?.quitSafely() }

        virtualDisplay = null
        imageReader = null
        projection = null
        handler = null
        thread = null

        synchronized(lock) {
            colorMap = null
            colorWidth = 0
            colorHeight = 0
        }
    }

    fun sampleColor(x: Float, y: Float, screenW: Float, screenH: Float): Int? {
        if (screenW <= 0f || screenH <= 0f) {
            return null
        }
        val localMap: IntArray
        val w: Int
        val h: Int
        synchronized(lock) {
            localMap = colorMap ?: return null
            w = colorWidth
            h = colorHeight
        }
        if (w <= 1 || h <= 1) {
            return null
        }

        val nx = (x / screenW).coerceIn(0f, 1f)
        val ny = (y / screenH).coerceIn(0f, 1f)
        val ix = (nx * (w - 1)).toInt()
        val iy = (ny * (h - 1)).toInt()

        val c = localMap[iy * w + ix]
        val r = ((c shr 16) and 0xFF).coerceIn(0, 255)
        val g = ((c shr 8) and 0xFF).coerceIn(0, 255)
        val b = (c and 0xFF).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
