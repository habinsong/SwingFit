package com.example.swingfit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream

/** 연속 프레임 디코더 (CPU 전용, YUV_420_888) — Image 수명 안전 처리 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class Swing_FrameDecoder(
    private val context: Context,
    private val uri: Uri
) : AutoCloseable {

    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var trackIndex = -1

    var rotationDegrees: Int = 0
        private set
    var width: Int = 0
        private set
    var height: Int = 0
        private set
    var durationUs: Long = 0
        private set

    private var inputDone = false
    private var outputDone = false

    fun prepare() {
        extractor.setDataSource(context, uri, null)
        // 비디오 트랙 선택
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

            // YUV 포맷 요청
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

    data class FrameBmp(
        val bitmap: Bitmap,
        val presentationUs: Long
    )

    /**
     * 다음 출력 프레임을 Bitmap으로 변환해 반환.
     * 내부에서 Image.close() → releaseOutputBuffer()까지 **올바른 순서로** 처리하여
     * 호출측은 Bitmap만 사용하면 됨.
     */
    fun nextFrameBitmap(): FrameBmp? {
        val c = codec ?: return null
        if (outputDone) return null

        val info = MediaCodec.BufferInfo()

        while (true) {
            // 1) 입력 공급
            if (!inputDone) {
                val inIndex = c.dequeueInputBuffer(5_000) // 5ms
                if (inIndex >= 0) {
                    val buf = c.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(buf, 0)
                    if (sampleSize < 0) {
                        c.queueInputBuffer(
                            inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        val pts = extractor.sampleTime
                        val flags = extractor.sampleFlags
                        c.queueInputBuffer(inIndex, 0, sampleSize, pts, flags)
                        extractor.advance()
                    }
                }
            }

            // 2) 출력 수거
            val outIndex = c.dequeueOutputBuffer(info, 10_000) // 10ms
            when {
                outIndex >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }

                    // ★ 여기서 Image 취득 → NV21 → JPEG → Bitmap 변환 → Image.close() → releaseOutputBuffer()
                    val image = c.getOutputImage(outIndex)
                    val ptsUs = info.presentationTimeUs

                    val bmp: Bitmap? = if (image != null) {
                        try {
                            nv21ToBitmap(yuv420ToNV21(image), image.width, image.height)
                        } finally {
                            // 반드시 Image 먼저 닫고
                            image.close()
                        }
                    } else null

                    // 그 다음 버퍼 해제
                    c.releaseOutputBuffer(outIndex, /*render*/ false)

                    if (bmp != null) {
                        return FrameBmp(bmp, ptsUs)
                    }
                    // 이미지가 null이면 계속 폴링
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = c.outputFormat
                    width = fmt.getInteger(MediaFormat.KEY_WIDTH)
                    height = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                }
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
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
    // YUV → NV21 → JPEG → Bitmap (간편/안전 경로)
    // ─────────────────────────────────────────────────────────────────────

    private fun yuv420ToNV21(image: android.media.Image): ByteArray {
        val w = image.width
        val h = image.height
        val ySize = w * h
        val uvSize = w * h / 2
        val out = ByteArray(ySize + uvSize)
        var offset = 0

        // Y
        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride
        for (row in 0 until h) {
            val rowStart = row * yRowStride
            if (yPixStride == 1) {
                yBuf.position(rowStart)
                yBuf.get(out, offset, w)
                offset += w
            } else {
                var col = 0
                while (col < w) {
                    out[offset++] = yBuf.get(rowStart + col * yPixStride)
                    col++
                }
            }
        }

        // NV21: VU interleaved
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vPixStride = vPlane.pixelStride

        val chromaH = h / 2
        val chromaW = w / 2
        for (row in 0 until chromaH) {
            val uRowStart = row * uRowStride
            val vRowStart = row * vRowStride
            for (col in 0 until chromaW) {
                val u = uBuf.get(uRowStart + col * uPixStride)
                val v = vBuf.get(vRowStart + col * vPixStride)
                out[offset++] = v
                out[offset++] = u
            }
        }
        return out
    }

    private fun nv21ToBitmap(nv21: ByteArray, w: Int, h: Int): Bitmap {
        val yuv = android.graphics.YuvImage(nv21, ImageFormat.NV21, w, h, null)
        val baos = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, w, h), 90, baos)
        val data = baos.toByteArray()
        baos.close()
        return BitmapFactory.decodeByteArray(data, 0, data.size)
    }
}