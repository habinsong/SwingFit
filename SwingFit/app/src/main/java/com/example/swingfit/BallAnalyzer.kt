package com.example.swingfit

import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.*
import java.util.PriorityQueue

class BallAnalyzer(
    private val ctx: Context,
    private val modelAsset: String = "ball-fp16.tflite",
    private val inputSize: Int = 640,
    private var confThresh: Float = 0.10f,
    private val maxTrail: Int = 40,
    private val cpuThreads: Int = 6,
    private val gpuEnabled: Boolean = false
) {
    companion object { private const val TAG = "BallDemo" }

    private val SKIP_INITIAL_US = 2_000_000L
    private val SUFFICIENT_TRAIL_SIZE = 5

    // ▼▼ 튜닝: 추적/보정 파라미터(완화)
    private val TRACK_AFTER_US = 300_000L      // impact 이후 추적 윈도우
    private val MISS_LIMIT = 24                // 미검출 허용 횟수 상향
    private val MIN_DT_SEC = 0.004            // 240fps까지 수용(이전 0.008)

    // ===== TFLite =====
    private val tflite: Interpreter by lazy { createInterpreter() }
    private val inputBuf: ByteBuffer =
        ByteBuffer.allocateDirect((1L * inputSize * inputSize * 3 * 4).toInt())
            .order(ByteOrder.nativeOrder())
    private val outTensor: Array<Array<FloatArray>> = Array(1) { Array(5) { FloatArray(8400) } }
    private val outputsMap: HashMap<Int, Any> = hashMapOf(0 to outTensor)
    private val letterboxBitmap: Bitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
    private val letterboxCanvas = Canvas(letterboxBitmap)
    private val letterboxPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val cropRect = Rect()
    private val dstRect = Rect()
    private val pixelsBuf = IntArray(inputSize * inputSize)

    // ===== 클럽 로프트 =====
    private var currentLoftDeg: Double = 12.0
    fun setClubByName(name: String?) {
        val loft = when (name?.trim()) {
            "드라이버"->10.5; "3번 우드"->15.0; "5번 우드"->18.0;
            "아이언 3"->19.0; "아이언 4"->21.0; "아이언 5"->24.0; "아이언 6"->27.0; "아이언 7"->31.0; "아이언 8"->35.0; "아이언 9"->40.0;
            "피칭 웨지"->45.0; "갭 웨지"->50.0; "샌드 웨지"->56.0; "로브 웨지"->60.0;
            else->12.0
        }
        currentLoftDeg = loft
        Log.d(TAG, "Launch angle set by club \"${name ?: "unknown"}\": ${"%.1f".format(loft)} deg")
    }

    // ===== 내부 유틸 =====
    private class FrameRetriever(ctx: Context, uri: Uri, videoW: Int, videoH: Int, targetWidth: Int) : AutoCloseable {
        private val mmr = MediaMetadataRetriever()
        private val scaledW: Int
        private val scaledH: Int
        init {
            mmr.setDataSource(ctx, uri)
            if (videoW > 0 && videoH > 0) {
                scaledW = targetWidth
                scaledH = (videoH.toLong() * targetWidth / videoW).toInt()
            } else { scaledW = targetWidth; scaledH = targetWidth }
        }
        fun getFrame(tsUs: Long, keyframePriority: Boolean): Bitmap? {
            val opt = if (keyframePriority) MediaMetadataRetriever.OPTION_PREVIOUS_SYNC else MediaMetadataRetriever.OPTION_CLOSEST
            return try { mmr.getScaledFrameAtTime(tsUs, opt, scaledW, scaledH) }
            catch (e: Throwable) { try { mmr.getFrameAtTime(tsUs, opt) } catch (_: Throwable) { null } }
        }
        override fun close() = mmr.release()
    }

    private fun mapAssetToBuffer(assetName: String): ByteBuffer {
        val fd = ctx.assets.openFd(assetName)
        FileInputStream(fd.fileDescriptor).use { fis ->
            val ch = fis.channel
            return ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length)
        }
    }

    private fun createInterpreter(): Interpreter {
        val bb = mapAssetToBuffer(modelAsset)
        val opts = Interpreter.Options().apply {
            setNumThreads(cpuThreads); setUseXNNPACK(true)
            @Suppress("DEPRECATION") setAllowFp16PrecisionForFp32(true)
        }
        return try { Interpreter(bb, opts) }
        catch (e: Exception) {
            Log.e(TAG, "❌ GPU delegate init failed, fallback CPU", e)
            val fb = Interpreter.Options().apply {
                setNumThreads(cpuThreads); setUseXNNPACK(true)
                @Suppress("DEPRECATION") setAllowFp16PrecisionForFp32(true)
            }
            Interpreter(bb, fb).also { Log.i(TAG, "✅ CPU interpreter fallback succeeded.") }
        }
    }

    data class InitInfo(val vw: Int, val vh: Int, val rot: Int, val durUs: Long)
    private fun meta(uri: Uri): InitInfo {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, uri)
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val r = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val dMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val vw = if (r % 180 == 0) w else h
            val vh = if (r % 180 == 0) h else w
            return InitInfo(vw, vh, r, dMs * 1000L)
        } finally { mmr.release() }
    }

    private fun clampRoi(r: RoiConfig): RoiConfig = RoiConfig(r.x0, r.y0, r.x1, r.y1).clampPortrait()

    // ===== 메인 파이프라인 =====
    suspend fun analyzeVideo(
        uri: Uri,
        initialRoi: RoiConfig,
        onMeta: (w: Int, h: Int, rotationDeg: Int, durationUs: Long) -> Unit,
        onProgress: (curUs: Long, totalUs: Long) -> Unit,
        onDetection: (tsUs: Long, det: Detection) -> Unit,
        onRoiFixed: (RoiConfig) -> Unit,
        onImpact: (impactUs: Long) -> Unit,
        onResult: (flightData: FlightData) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val info = meta(uri)
            val estStepUsGlobal = MediaMetadataRetriever().run {
                setDataSource(ctx, uri)
                val v = estimateFrameStepUs(this)
                release(); v
            }.coerceIn(5_000L, 33_000L)

            val forcedRot = 0
            onMeta(info.vw, info.vh, forcedRot, info.durUs)

            val roi = clampRoi(initialRoi)
            onRoiFixed(roi)

            val totalUs = info.durUs
            if (totalUs <= SKIP_INITIAL_US) { onProgress(totalUs, totalUs); return }

            FrameRetriever(ctx, uri, info.vw, info.vh, inputSize).use { frameRetriever ->
                var impactUs: Long? = null
                var lastDetCenterY = 1f
                var baseCenter: PointF? = null
                val trail: ArrayDeque<PointF> = ArrayDeque()

                // 1) 그리드 스캔으로 임팩트 지점 찾기
                val scanStepUs = 300_000L
                val times = mutableListOf<Long>().apply {
                    var t = SKIP_INITIAL_US
                    while (t <= totalUs) { add(t); t += scanStepUs }
                    if (isEmpty() || last() < totalUs) add(totalUs)
                }

                val relaxedConf = max(0.06f, confThresh * 0.4f)
                val baseRadius = 0.06f
                var baseIdx = -1
                var lastPresentIdx = -1

                loop@ for ((idx, ts) in times.withIndex()) {
                    onProgress(ts, totalUs)
                    val bmp = frameRetriever.getFrame(ts, true)
                    val det = bmp?.let { b ->
                        (if (baseIdx < 0) detectTop1(b, roi, confThresh, false) else detectTop1(b, roi, relaxedConf, false)).also { b.recycle() }
                    }

                    if (baseIdx < 0) {
                        if (det != null) {
                            val baseConfMin = max(0.55f, confThresh)
                            val confirmConfMin = max(0.45f, confThresh * 0.8f)
                            val confirmRadius = 0.04f
                            if (det.conf >= baseConfMin) {
                                val nextTs = if (idx + 1 <= times.lastIndex) times[idx+1] else (ts + scanStepUs).coerceAtMost(totalUs)
                                val bmp2 = frameRetriever.getFrame(nextTs, true)
                                var confirmed = false; var cCenter: PointF? = null
                                if (bmp2 != null) {
                                    val det2 = detectTop1(bmp2, roi, confirmConfMin, false)
                                    bmp2.recycle()
                                    if (det2 != null && hypot(det2.center.x - det.center.x, det2.center.y - det.center.y) <= confirmRadius) {
                                        confirmed = true; cCenter = det2.center
                                    }
                                }
                                if (confirmed) {
                                    baseCenter = cCenter?.let { PointF((det.center.x + it.x)/2f, (det.center.y + it.y)/2f) } ?: det.center
                                    baseIdx = idx
                                    lastPresentIdx = idx
                                    lastDetCenterY = baseCenter!!.y
                                    trail.addLast(baseCenter!!)
                                    if (trail.size > maxTrail) trail.removeFirst()
                                    onDetection(ts, Detection(ts, det.conf, baseCenter!!, det.bbox, trail.toMutableList()))
                                }
                            }
                        }
                    } else {
                        var usedDet: Detection? = null
                        if (det != null && baseCenter != null && hypot(det.center.x - baseCenter!!.x, det.center.y - baseCenter!!.y) <= baseRadius) {
                            usedDet = det
                        }
                        if (usedDet != null) {
                            lastPresentIdx = idx
                            lastDetCenterY = usedDet.center.y
                            trail.addLast(usedDet.center)
                            if (trail.size > maxTrail) trail.removeFirst()
                            onDetection(ts, Detection(ts, usedDet.conf, usedDet.center, usedDet.bbox, trail.toMutableList()))
                        } else {
                            if (impactUs == null) {
                                impactUs = ts
                                onImpact(impactUs!!)
                                break@loop
                            }
                        }
                    }
                }
                if (impactUs == null) {
                    if (baseIdx >= 0 && lastPresentIdx in baseIdx until times.lastIndex) {
                        impactUs = times[lastPresentIdx + 1]
                        onImpact(impactUs!!)
                    }
                }

                // 2) 임팩트 정제
                if (impactUs != null && baseCenter != null) {
                    var refinedImpact = impactUs!!
                    val presenceConf = max(0.06f, confThresh * 0.4f)
                    val hWindowCoarse = 400_000L; val sCoarse = 100_000L
                    val w0 = (refinedImpact - hWindowCoarse).coerceAtLeast(SKIP_INITIAL_US)
                    val w1 = (refinedImpact + hWindowCoarse).coerceAtMost(totalUs)
                    refineImpactByStep(frameRetriever, w0, w1, sCoarse, baseCenter, roi, totalUs, presenceConf, 0.06f, 2)?.let { refinedImpact = it }
                    val hWindowFine = 100_000L; val sFine = 50_000L
                    val f0 = (refinedImpact - hWindowFine).coerceAtLeast(SKIP_INITIAL_US)
                    val f1 = (refinedImpact + hWindowFine).coerceAtMost(totalUs)
                    refineImpactByStep(frameRetriever, f0, f1, sFine, baseCenter, roi, totalUs, presenceConf, 0.06f, 2)?.let { refinedImpact = it }
                    if (refinedImpact != impactUs) { impactUs = refinedImpact; onImpact(impactUs!!) }
                }

                // 3) 임팩트 이후 추적
                var finalDetections: List<Detection>? = null
                if (impactUs != null) {
                    val estStepUs = estStepUsGlobal
                    val trackEndUs = (impactUs!! + TRACK_AFTER_US).coerceAtMost(totalUs)
                    var curUs = impactUs!!
                    var miss = 0
                    var prevY = lastDetCenterY
                    var prevX = baseCenter?.x ?: 0.5f
                    var bestY = prevY
                    val detectedPoints = mutableListOf<Detection>()

                    while (curUs <= trackEndUs && miss < MISS_LIMIT) {
                        onProgress(curUs, totalUs)
                        var got = false
                        val bmp = frameRetriever.getFrame(curUs, false)
                        if (bmp != null) {
                            val elapsedUs = (curUs - impactUs!!).coerceAtLeast(0L)
                            val dynRoi = baseCenter?.let { buildDynamicPostImpactRoi(roi, it, elapsedUs) } ?: roi
                            var best: Detection? = null
                            var listUse = detectMany(bmp, dynRoi, 5, postImpactConfThreshold(elapsedUs))
                            if (listUse.isEmpty()) listUse = detectMany(bmp, dynRoi, 5, postImpactConfThreshold(elapsedUs), true)
                            var bestScore = -1f
                            for (cand in listUse) {
                                if ((cand.bbox?.width() ?: 0f) <= 0f || (cand.bbox?.height() ?: 0f) <= 0f) continue
                                // 상승만 강제하지 않음(하강도 허용)
                                val riseScore = (prevY - cand.center.y).coerceAtLeast(0f) * 0.2f
                                val score = cand.conf + riseScore
                                if (score > bestScore) { best = cand; bestScore = score }
                            }
                            if (best == null && miss >= 1) {
                                best = findBrightStreakByPixelAnalysis(bmp, dynRoi, info.vw, info.vh, prevY)
                            }
                            bmp.recycle()

                            if (best != null) {
                                bestY = min(bestY, best.center.y)
                                val alpha = 0.7f
                                prevY = alpha * best.center.y + (1f - alpha) * prevY
                                prevX = alpha * best.center.x + (1f - alpha) * prevX
                                trail.addLast(best.center)
                                if (trail.size > maxTrail) trail.removeFirst()
                                val newDetection = Detection(curUs, best.conf, best.center, best.bbox, trail.toMutableList())
                                detectedPoints.add(newDetection)
                                onDetection(curUs, newDetection)
                                got = true
                                miss = 0
                                if (trail.size >= SUFFICIENT_TRAIL_SIZE) break
                            }
                        }
                        if (!got) miss++
                        curUs += estStepUs
                    }
                    finalDetections = detectedPoints
                }

                val dets = finalDetections ?: emptyList()
                if (impactUs != null && baseCenter != null && dets.isNotEmpty()) {
                    val first = dets.first()
                    val firstBox = first.bbox
                    val b1Box: RectF? = if (firstBox != null && firstBox.height() > 0f && firstBox.width() > 0f) {
                        RectF(
                            baseCenter.x - (firstBox.width()/2f),
                            baseCenter.y - (firstBox.height()/2f),
                            baseCenter.x + (firstBox.width()/2f),
                            baseCenter.y + (firstBox.height()/2f)
                        )
                    } else null
                    val b1 = Detection(impactUs!!, 1f, baseCenter, b1Box, mutableListOf(baseCenter))
                    val enriched = ArrayList<Detection>(dets.size + 1).apply { add(0, b1); addAll(dets) }
                    val ok = estimateCarryFromDetections(enriched, currentLoftDeg)
                    if (ok == null) Log.w(TAG, "Local carry estimation still failed after b1 synth.")
                } else {
                    Log.w(TAG, "Local carry: impact/base or detections missing (impactUs=$impactUs, baseCenter=$baseCenter, dets=${dets.size})")
                }

                onProgress(totalUs, totalUs)
                Log.d(TAG, "Analysis complete (distance computation removed).")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "analyzeVideo failed", t)
            onError(t.message ?: "unknown error")
        }
    }

    // ===== 밝은 스트릭 보조 검출 =====
    private fun findBrightStreakByPixelAnalysis(
        frame: Bitmap, roiIn: RoiConfig, videoW: Int, videoH: Int, prevY: Float
    ): Detection? {
        val LUMINANCE_THRESHOLD = 210
        val MIN_PIXEL_COUNT = 15
        val MAX_ASPECT_RATIO = 0.35f

        val roi = clampRoi(roiIn)
        val vw = frame.width.toFloat(); val vh = frame.height.toFloat()
        val rx = (roi.x0 * vw).toInt(); val ry = (roi.y0 * vh).toInt()
        val rw = ((roi.x1 - roi.x0) * vw).toInt(); val rh = ((roi.y1 - roi.y0) * vh).toInt()
        if (rw <= 0 || rh <= 0) return null

        val roiPixels = IntArray(rw * rh)
        try { frame.getPixels(roiPixels, 0, rw, rx, ry, rw, rh) } catch (_: Exception) { return null }

        val bright = ArrayList<Point>()
        for (y in 0 until rh) for (x in 0 until rw) {
            val p = roiPixels[y * rw + x]
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val lum = (0.299*r + 0.587*g + 0.114*b).toInt()
            if (lum >= LUMINANCE_THRESHOLD) bright.add(Point(rx + x, ry + y))
        }
        if (bright.size < MIN_PIXEL_COUNT) return null

        var sumX = 0L; var sumY = 0L
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        for (p in bright) {
            sumX += p.x; sumY += p.y
            minX = min(minX, p.x); minY = min(minY, p.y)
            maxX = max(maxX, p.x); maxY = max(maxY, p.y)
        }
        val boxW = (maxX - minX).toFloat(); val boxH = (maxY - minY).toFloat()
        if (boxW <= 0f || boxH <= 0f) return null

        val normCenterY = (sumY.toFloat() / bright.size) / videoH.toFloat()
        // 상승만 강제하지 않음
        val normCenterX = (sumX.toFloat() / bright.size) / videoW.toFloat()
        val center = PointF(normCenterX, normCenterY)
        val bbox = RectF(minX / vw, minY / vh, maxX / vw, maxY / vh)
        return Detection(0L, 0.99f, center, bbox, mutableListOf(center))
    }

    // ===== 검출 =====
    private fun detectTop1(frame: Bitmap, roiIn: RoiConfig, confMin: Float, invertColors: Boolean): Detection? {
        val list = detectMany(frame, roiIn, 1, confMin, invertColors)
        return list.firstOrNull()
    }

    private fun detectMany(
        frame: Bitmap, roiIn: RoiConfig, wantTopK: Int = 5, confMin: Float = this.confThresh, invertColors: Boolean = false
    ): List<Detection> {
        val roi = clampRoi(roiIn)
        val vw = frame.width; val vh = frame.height
        val rx = (roi.x0 * vw).toInt().coerceIn(0, max(0, vw - 1))
        val ry = (roi.y0 * vh).toInt().coerceIn(0, max(0, vh - 1))
        val rw = ((roi.x1 - roi.x0) * vw).toInt().coerceAtLeast(2).coerceAtMost(vw - rx)
        val rh = ((roi.y1 - roi.y0) * vh).toInt().coerceAtLeast(2).coerceAtMost(vh - ry)

        cropRect.set(rx, ry, rx + rw, ry + rh)
        letterboxCanvas.drawColor(Color.BLACK)

        val s = min(inputSize.toFloat() / rw, inputSize.toFloat() / rh)
        val nw = (rw * s).roundToInt(); val nh = (rh * s).roundToInt()
        val left = (inputSize - nw) / 2; val top = (inputSize - nh) / 2
        dstRect.set(left, top, left + nw, top + nh)
        letterboxCanvas.drawBitmap(frame, cropRect, dstRect, letterboxPaint)

        inputBuf.rewind()
        letterboxBitmap.getPixels(pixelsBuf, 0, inputSize, 0, 0, inputSize, inputSize)
        var pIdx = 0
        val inv = if (invertColors) 1f else 0f
        while (pIdx < pixelsBuf.size) {
            val p = pixelsBuf[pIdx++]
            val r = ((p ushr 16) and 0xFF) / 255f
            val g = ((p ushr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            if (inv == 0f) { inputBuf.putFloat(r); inputBuf.putFloat(g); inputBuf.putFloat(b) }
            else { inputBuf.putFloat(1f - r); inputBuf.putFloat(1f - g); inputBuf.putFloat(1f - b) }
        }

        tflite.runForMultipleInputsOutputs(arrayOf(inputBuf as Any), outputsMap)
        val scoreArr = outTensor[0][4]; val cxArr = outTensor[0][0]; val cyArr = outTensor[0][1]
        val wArr  = outTensor[0][2]; val hArr  = outTensor[0][3]
        val rx0 = roi.x0; val ry0 = roi.y0
        val rW = (roi.x1 - roi.x0); val rH = (roi.y1 - roi.y0)

        if (wantTopK <= 1) {
            var bestScore = -1f
            var bestCenterX = 0f; var bestCenterY = 0f
            var bestW = 0f; var bestH = 0f
            var i = 0
            while (i < 8400) {
                val sc = scoreArr[i]
                if (sc >= confMin && sc > bestScore) {
                    val scale = if ((cxArr[i] in 0f..1f)) 1f else 1f / inputSize
                    bestCenterX = rx0 + (cxArr[i] * scale).coerceIn(0f, 1f) * rW
                    bestCenterY = ry0 + (cyArr[i] * scale).coerceIn(0f, 1f) * rH
                    bestW = (wArr[i] * scale).coerceIn(0f, 1f) * rW
                    bestH = (hArr[i] * scale).coerceIn(0f, 1f) * rH
                    bestScore = sc
                }
                i++
            }
            if (bestScore >= 0f) {
                val box = RectF((bestCenterX - bestW/2f), (bestCenterY - bestH/2f), (bestCenterX + bestW/2f), (bestCenterY + bestH/2f))
                val center = PointF(bestCenterX, bestCenterY)
                return listOf(Detection(0L, bestScore, center, box, mutableListOf(center)))
            }
            return emptyList()
        } else {
            val cap = max(32, wantTopK * 6)
            val heap = PriorityQueue<DetectionCandidate>(cap, compareBy { it.score })
            var i2 = 0
            while (i2 < 8400) {
                val sc = scoreArr[i2]
                if (sc >= confMin) {
                    val scale = if ((cxArr[i2] in 0f..1f)) 1f else 1f / inputSize
                    val vX = rx0 + (cxArr[i2] * scale).coerceIn(0f, 1f) * rW
                    val vY = ry0 + (cyArr[i2] * scale).coerceIn(0f, 1f) * rH
                    val vW = (wArr[i2] * scale).coerceIn(0f, 1f) * rW
                    val vH = (hArr[i2] * scale).coerceIn(0f, 1f) * rH
                    val box = RectF((vX - vW/2f), (vY - vH/2f), (vX + vW/2f), (vY + vH/2f))
                    val item = DetectionCandidate(PointF(vX, vY), box, sc)
                    if (heap.size < cap) heap.add(item) else if (heap.peek().score < sc) { heap.poll(); heap.add(item) }
                }
                i2++
            }
            if (heap.isEmpty()) return emptyList()
            val cand = ArrayList<DetectionCandidate>(heap.size)
            while (heap.isNotEmpty()) cand.add(heap.poll())
            cand.sortByDescending { it.score }
            val keep = ArrayList<DetectionCandidate>()
            val used = BooleanArray(cand.size)
            var i = 0
            while (i < cand.size && keep.size < wantTopK) {
                if (!used[i]) {
                    val a = cand[i]; keep.add(a)
                    var j = i + 1
                    while (j < cand.size) {
                        if (!used[j] && iou(a.box, cand[j].box) > 0.45f) used[j] = true
                        j++
                    }
                }
                i++
            }
            return keep.map { c -> Detection(0L, c.score, c.center, c.box, mutableListOf(c.center)) }
        }
    }

    private data class DetectionCandidate(val center: PointF, val box: RectF, val score: Float)

    private fun iou(a: RectF, b: RectF): Float {
        val ix = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val iy = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val inter = ix * iy
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun estimateFrameStepUs(mmr: MediaMetadataRetriever): Long {
        return try {
            val fpsStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            val fps = fpsStr?.toFloatOrNull()?.takeIf { it in 0.1f..240f }
            if (fps != null) (1_000_000f / fps).toLong().coerceIn(5_000L, 50_000L) else 16667L
        } catch (_: Throwable) { 16667L }
    }

    private fun refineImpactByStep(
        frameRetriever: FrameRetriever,
        startUs: Long, endUs: Long, stepUs: Long,
        baseCenter: PointF?, roi: RoiConfig,
        durUs: Long, conf: Float, radius: Float, needMiss: Int = 2
    ): Long? {
        val center = baseCenter ?: return null
        var t = max(startUs, SKIP_INITIAL_US)
        var consecutiveMiss = 0
        while (t <= endUs) {
            val bmp = frameRetriever.getFrame(t.coerceIn(0L, durUs), false)
            var present = false
            if (bmp != null) {
                val list = detectMany(bmp, roi, 5, conf, false)
                bmp.recycle()
                present = list.any { hypot(it.center.x - center.x, it.center.y - center.y) <= radius }
            }
            if (present) { consecutiveMiss = 0 }
            else {
                consecutiveMiss++
                if (consecutiveMiss >= needMiss) return (t - (needMiss - 1) * stepUs).coerceIn(startUs, endUs)
            }
            t += stepUs
        }
        return null
    }

    private fun intersectRoi(a: RoiConfig, b: RoiConfig): RoiConfig {
        val x0 = max(a.x0, b.x0); val y0 = max(a.y0, b.y0)
        val x1 = min(a.x1, b.x1); val y1 = min(a.y1, b.y1)
        return RoiConfig(
            x0.coerceIn(0f,1f), y0.coerceIn(0f,1f),
            max(x1, x0 + 0.01f).coerceIn(0f,1f),
            max(y1, y0 + 0.01f).coerceIn(0f,1f)
        ).clampPortrait()
    }

    private fun buildDynamicPostImpactRoi(main: RoiConfig, base: PointF, elapsedUs: Long): RoiConfig {
        val t = elapsedUs / 1_000_000f
        val rise = (0.46f * t).coerceAtLeast(0f)
        val halfX = (0.16f + 0.30f * t).coerceIn(0.16f, 0.38f)
        val x0 = (base.x - halfX).coerceIn(0f, 1f)
        val x1 = (base.x + halfX).coerceIn(0f, 1f)
        val y1 = main.y1
        val y0 = (base.y - (0.22f + rise)).coerceIn(0f, y1 - 0.02f)
        val dyn = RoiConfig(x0, y0, x1, y1).clampPortrait()
        return intersectRoi(main, dyn)
    }

    private fun postImpactConfThreshold(elapsedUs: Long): Float {
        return when {
            elapsedUs < 120_000L -> max(0.008f, confThresh * 0.10f)
            elapsedUs < 200_000L -> max(0.012f, confThresh * 0.12f)
            else                 -> max(0.016f, confThresh * 0.15f)
        }
    }

    private fun estimateCarryFromDetections(
        dets: List<Detection>,
        clubLoftDeg: Double
    ): Double? {
        if (dets.size < 2) {
            Log.w(TAG, "estimateCarryFromDetections: insufficient data (size=${dets.size})")
            return null
        }

        // 물리 상수
        val g = 9.80665
        val rho = 1.2
        val Cd  = 0.25
        val d   = 0.04267
        val A   = Math.PI * (d/2)*(d/2)
        val m   = 0.04593

        // 스케일(정규단위→미터) 결정
        val allHeights = dets.mapNotNull { it.bbox?.height() }.filter { it > 0f }
        val firstHnorm = when {
            dets[0].bbox?.height() ?: 0f > 0f -> dets[0].bbox!!.height()
            allHeights.isNotEmpty() -> allHeights.sorted()[allHeights.size/2] // 중앙값
            else -> 0f
        }
        if (firstHnorm <= 0f) {
            Log.w(TAG, "estimateCarryFromDetections: no valid bbox height for scale")
            return null
        }
        val metersPerNorm = d / firstHnorm

        val t1 = dets[0].tsUs / 1e6
        val x1 = dets[0].center.x
        val y1 = dets[0].center.y

        val theta = Math.toRadians(clubLoftDeg)
        val sinT = sin(theta); val sin2T = sin(2.0 * theta)
        if (sinT <= 1e-6 || sin2T <= 1e-6) {
            Log.w(TAG, "estimateCarryFromDetections: invalid loft angle ($clubLoftDeg deg)")
            return null
        }

        val pairs = ArrayList<Double>()
        var skipSmallDt = 0; var skipNegV0 = 0; var skipNaN = 0
        val dbg = StringBuilder()

        for (i in 1 until dets.size) {
            val di = dets[i]
            val t2 = di.tsUs / 1e6
            val dt = (t2 - t1)
            if (dt <= MIN_DT_SEC) { skipSmallDt++; continue }

            val x2 = di.center.x
            val y2 = di.center.y

            val dxNorm = abs(x2 - x1).toDouble()
            val dyNorm = (y1 - y2).toDouble() // 상승이면 +, 하강이면 –
            val dx = dxNorm * metersPerNorm
            val dy = dyNorm * metersPerNorm

            // v0 근사(무항력 수직성분 역추정)
            val v0 = (dy + 0.5 * g * dt * dt) / (sinT * dt)
            if (!v0.isFinite() || v0 <= 0) { skipNegV0++; continue }

            // 드래그 1차 보정
            val k = (rho * Cd * A / (2.0 * m)) * v0
            val vEff = v0 * kotlin.math.exp(-k * dt * 0.5)

            val carry = (vEff * vEff * sin2T) / g
            if (carry.isFinite() && carry > 0) {
                pairs.add(carry)
                dbg.append("b1-b${i+1}: v0≈${"%.2f".format(v0)} m/s, dt=${"%.3f".format(dt)} s, dX=${"%.2f".format(dx)} m, dY=${"%.2f".format(dy)} m → carry=${"%.1f".format(carry)} m; ")
            } else skipNaN++
        }

        if (pairs.isEmpty()) {
            Log.w(TAG, "Local carry estimation produced no valid pairs. (skipSmallDt=$skipSmallDt, skipNegV0=$skipNegV0, skipNaN=$skipNaN)")
            return null
        }

        val avg = pairs.average()
        val min = pairs.minOrNull()!!
        val max = pairs.maxOrNull()!!
        Log.i(TAG, "[LocalCarry] club≈${"%.1f".format(clubLoftDeg)}° loft, pairs=${pairs.size}, avg=${"%.1f".format(avg)} m, min=${"%.1f".format(min)} m, max=${"%.1f".format(max)} m | $dbg")
        return avg
    }
}
