// 사용안함. 사유 : 그냥 프레임디코더보다 느림.






package com.example.swingfit

import android.content.Context
import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

private data class PointI(val x: Int, val y: Int)

/**
 * MediaCodec 연속 디코딩 + YUV_420_888을 곧장 640x640 float32 텐서로 채움.
 * - Bitmap/JPEG 변환 없음 (초고속 경로)
 * - 회전(0/90/180/270)과 레터박스(scale/pad)까지 내부에서 적용
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class Swing_FrameDecoderFast(
    private val context: Context,
    private val uri: Uri,
    private val inputSize: Int = 640
) : AutoCloseable {

    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var trackIndex = -1

    var rotationDegrees: Int = 0
        private set
    var width: Int = 0           // 원본(회전 전) w
        private set
    var height: Int = 0          // 원본(회전 전) h
        private set
    var durationUs: Long = 0
        private set

    private var inputDone = false
    private var outputDone = false

    fun prepare() {
        extractor.setDataSource(context, uri, null)
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("video/")) continue

            trackIndex = i
            extractor.selectTrack(i)

            width = fmt.getInteger(MediaFormat.KEY_WIDTH)
            height = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            rotationDegrees = when {
                fmt.containsKey(MediaFormat.KEY_ROTATION) ->
                    fmt.getInteger(MediaFormat.KEY_ROTATION)
                fmt.containsKey("rotation-degrees") ->
                    fmt.getInteger("rotation-degrees")
                else -> 0
            }
            durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                fmt.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            // 표준 YUV 포맷으로 요청
            fmt.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(fmt, /*surface*/ null, /*crypto*/ null, /*flags*/ 0)
                start()
            }
            break
        }
        require(trackIndex >= 0) { "No video track found" }
    }

    data class FrameTensor(
        val buffer: ByteBuffer,     // float32 [1, 640, 640, 3], 0..1
        val presentationUs: Long,
        // 역변환용(원본 비디오 좌표 복원)
        val srcW: Int,              // 회전 적용 후의 프레임 W
        val srcH: Int,              // 회전 적용 후의 프레임 H
        val scale: Float,           // min(640/srcW, 640/srcH)
        val padX: Float,            // 레터박스 X 패딩
        val padY: Float,            // 레터박스 Y 패딩
        val appliedRotation: Int    // 0/90/180/270 (이미 이 회전을 반영해 텐서에 씀)
    )

    /**
     * 다음 프레임을 디코딩하고, 곧장 float32 텐서를 만들어 반환.
     * 내부에서 Image를 닫고 버퍼를 반환하므로 호출측은 buffer만 사용하면 됨.
     */
    fun nextFrameTensor(): FrameTensor? {
        val c = codec ?: return null
        if (outputDone) return null

        val info = MediaCodec.BufferInfo()

        while (true) {
            // 1) 입력 공급
            if (!inputDone) {
                val inIdx = c.dequeueInputBuffer(5_000) // 5ms
                if (inIdx >= 0) {
                    val buf = c.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        c.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val pts = extractor.sampleTime
                        val flags = extractor.sampleFlags
                        c.queueInputBuffer(inIdx, 0, size, pts, flags)
                        extractor.advance()
                    }
                }
            }

            // 2) 출력 수거
            val outIdx = c.dequeueOutputBuffer(info, 10_000) // 10ms
            when {
                outIdx >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }

                    val img = c.getOutputImage(outIdx)
                    val ptsUs = info.presentationTimeUs

                    var tensor: FrameTensor? = null
                    if (img != null) {
                        try {
                            tensor = yuvToLetterboxedTensor(img, rotationDegrees, inputSize)
                        } finally {
                            // 반드시 Image 먼저 닫는다
                            img.close()
                        }
                    }
                    // 그 다음 버퍼를 해제
                    c.releaseOutputBuffer(outIdx, false)

                    if (tensor != null) {
                        // pts 설정
                        return tensor.copy(presentationUs = ptsUs)
                    }
                    // null이면 계속 폴링
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // 출력 포맷 변동
                    val fmt = c.outputFormat
                    // width/height는 회전 전 기준 (여기선 그대로 둠)
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (inputDone) return null // EoS
                    Thread.yield()
                }
            }
        }
    }

    override fun close() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        try { extractor.release() } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // YUV → 640×640 float32 텐서 (0..1, NHWC), 회전 & 레터박스 적용
    // ─────────────────────────────────────────────────────────────────────

    private fun yuvToLetterboxedTensor(
        image: android.media.Image,
        rotation: Int,
        dst: Int
    ): FrameTensor {
        require(image.format == ImageFormat.YUV_420_888)

        val srcW0 = image.width
        val srcH0 = image.height

// 회전 정규화(0/90/180/270)
        val r0 = rotation % 360
        val normRot = if (r0 < 0) r0 + 360 else r0

// 회전 적용 후의 소스 폭/높이 (구조분해/Pair 사용 X)
        var srcW = srcW0
        var srcH = srcH0
        if (normRot == 90 || normRot == 270) {
            srcW = srcH0
            srcH = srcW0
        }

        val scale = min(dst.toFloat() / srcW, dst.toFloat() / srcH)
        val newW = (srcW * scale).toInt().coerceAtLeast(1)
        val newH = (srcH * scale).toInt().coerceAtLeast(1)
        val padX = (dst - newW) / 2f
        val padY = (dst - newH) / 2f

        val out = ByteBuffer.allocateDirect(1 * dst * dst * 3 * 4).order(ByteOrder.nativeOrder())
        // 미리 0(=검은색)으로 채우기 (패딩 영역)
        repeat(dst * dst * 3) { out.putFloat(0f) }
        out.rewind()

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixStride = vPlane.pixelStride

        // 출력 그리드 → 회전 후 소스 좌표(최근접) → 회전 전 원본 좌표 → YUV 샘플 → RGB → out에 기록
        // NHWC: index = ((y*dst + x)*3 + c)
        fun putPixel(dstX: Int, dstY: Int, r: Float, g: Float, b: Float) {
            val base = ((dstY * dst + dstX) * 3)
            out.putFloat(base * 4, r)
            out.putFloat((base + 1) * 4, g)
            out.putFloat((base + 2) * 4, b)
        }

        // 최근접 리사이즈: dst 공간 안의 유효(패딩 제외) 좌표만 순회
        for (dy in 0 until newH) {
            val syR = (dy / scale) // 회전 후 소스 y(float)
            val syi = syR.toInt().coerceIn(0, srcH - 1)
            val dstY = (padY + dy).toInt()
            for (dx in 0 until newW) {
                val sxR = (dx / scale)
                val sxi = sxR.toInt().coerceIn(0, srcW - 1)
                val dstX = (padX + dx).toInt()

                // 회전 후 좌표(sxi, syi) → 회전 전 원본 좌표(ox, oy)
                val p = mapRotatedToOriginal(sxi, syi, rotation, srcW0, srcH0)
                val ox = p.x
                val oy = p.y

                // YUV 샘플
                val y = readPlane(yBuf, yRowStride, yPixStride, ox, oy)
                val u = readPlane(uBuf, uRowStride, uPixStride, ox / 2, oy / 2)
                val v = readPlane(vBuf, vRowStride, vPixStride, ox / 2, oy / 2)

                // YUV → RGB (BT.601)
                val yf = (y and 0xFF) - 16
                val uf = (u and 0xFF) - 128
                val vf = (v and 0xFF) - 128

                val yMul = 1.164f * yf
                var r = (yMul + 1.596f * vf) / 255f
                var g = (yMul - 0.392f * uf - 0.813f * vf) / 255f
                var b = (yMul + 2.017f * uf) / 255f

                // 0..1 클램프
                r = r.coerceIn(0f, 1f)
                g = g.coerceIn(0f, 1f)
                b = b.coerceIn(0f, 1f)

                putPixel(dstX, dstY, r, g, b)
            }
        }

        return FrameTensor(
            buffer = out.apply { position(0) },
            presentationUs = 0L, // 호출부에서 설정
            srcW = srcW,
            srcH = srcH,
            scale = scale,
            padX = padX,
            padY = padY,
            appliedRotation = ((rotation % 360) + 360) % 360
        )
    }

    private fun mapRotatedToOriginal(
        xr: Int, yr: Int, rotation: Int, origW: Int, origH: Int
    ): PointI {
        val r0 = rotation % 360
        val rot = if (r0 < 0) r0 + 360 else r0
        return when (rot) {
            90  -> PointI(origW - 1 - yr, xr)
            270 -> PointI(yr, origH - 1 - xr)
            180 -> PointI(origW - 1 - xr, origH - 1 - yr)
            else -> PointI(xr, yr) // 0도
        }
    }
    private fun readPlane(
        buf: ByteBuffer,
        rowStride: Int,
        pixStride: Int,
        x: Int,
        y: Int
    ): Int {
        val pos = y * rowStride + x * pixStride
        return buf.get(pos).toInt() and 0xFF
    }
}