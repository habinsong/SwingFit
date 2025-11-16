package com.example.swingfit

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Matrix
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/** model.kt 의 Detection 과 이름 충돌 방지용 */
data class Detection_swing(
    val x1_swing: Float,
    val y1_swing: Float,
    val x2_swing: Float,
    val y2_swing: Float,
    val score_swing: Float,
    val cls_swing: Int
)

class Swing_TFLiteHelper(
    ctx: Context,
    modelAssetName_swing: String = "hand_fp16.tflite",
    private val inputSize_swing: Int = 640,
    threads_swing: Int = 6,
    private val confThresh_swing: Float = 0.10f,
    private val iouThresh_swing: Float = 0.45f
) {
    private val interpreter_swing: Interpreter

    init {
        val options = Interpreter.Options().apply {
            // CPU only (GPU delegate 미사용)
            setNumThreads(threads_swing)
        }
        val afd: AssetFileDescriptor = ctx.assets.openFd(modelAssetName_swing)
        val fis = FileInputStream(afd.fileDescriptor)
        val mapped = fis.channel.map(
            FileChannel.MapMode.READ_ONLY,
            afd.startOffset,
            afd.declaredLength
        )
        fis.close()
        afd.close()
        interpreter_swing = Interpreter(mapped, options)
    }

    fun close() = interpreter_swing.close()

    /** 회전 보정된 프레임 입력 → 원본 영상 좌표의 Detection_swing 리스트 반환 */
    fun detect(src_swing: Bitmap): List<Detection_swing> {
        val prep = preprocess_swing(src_swing)
        val input = prep.buffer_swing
        val scale = prep.scale_swing
        val padX = prep.padX_swing
        val padY = prep.padY_swing
        val srcW = src_swing.width.toFloat()
        val srcH = src_swing.height.toFloat()

        val output = Array(1) { Array(300) { FloatArray(6) } }
        interpreter_swing.run(input, output)

        val kept = mutableListOf<Detection_swing>()
        for (i in 0 until 300) {
            val row = output[0][i]
            val nx1 = row[0]; val ny1 = row[1]
            val nx2 = row[2]; val ny2 = row[3]
            val s = row[4]
            val cId = row[5].toInt()

            // ★ 헤드(1)만 유지
            if (cId != 1) continue
            if (s < confThresh_swing) continue

            // 정규화(0..1) → 640 좌표
            val x1p = nx1 * inputSize_swing
            val y1p = ny1 * inputSize_swing
            val x2p = nx2 * inputSize_swing
            val y2p = ny2 * inputSize_swing

            // letterbox 역변환 → 원본 좌표
            val X1 = ((x1p - padX) / scale).coerceIn(0f, srcW)
            val Y1 = ((y1p - padY) / scale).coerceIn(0f, srcH)
            val X2 = ((x2p - padX) / scale).coerceIn(0f, srcW)
            val Y2 = ((y2p - padY) / scale).coerceIn(0f, srcH)

            if (X2 > X1 && Y2 > Y1) {
                kept.add(
                    Detection_swing(
                        x1_swing = X1,
                        y1_swing = Y1,
                        x2_swing = X2,
                        y2_swing = Y2,
                        score_swing = s,
                        cls_swing = cId
                    )
                )
            }
        }

        // ★ 헤드만 있으므로 단일 NMS 호출
        return nms_swing(kept.sortedByDescending { it.score_swing }.toMutableList(), iouThresh_swing)
    }

    // ───────── 내부 구현 ─────────

    private data class Prep_swing(
        val buffer_swing: ByteBuffer,
        val scale_swing: Float,
        val padX_swing: Float,
        val padY_swing: Float
    )

    /** “최적화 이전” 스타일: letterbox + per-row getPixels 로 입력 만들기 (float32, [0..1]) */
    private fun preprocess_swing(src: Bitmap): Prep_swing {
        val W = src.width
        val H = src.height
        val scale = min(inputSize_swing.toFloat() / W, inputSize_swing.toFloat() / H)
        val newW = (W * scale).toInt()
        val newH = (H * scale).toInt()
        val padX = (inputSize_swing - newW) / 2f
        val padY = (inputSize_swing - newH) / 2f

        val mat = Matrix().apply { postScale(newW / W.toFloat(), newH / H.toFloat()) }
        val resized = Bitmap.createBitmap(src, 0, 0, W, H, mat, true)

        val input = ByteBuffer.allocateDirect(1 * inputSize_swing * inputSize_swing * 3 * 4)
            .order(ByteOrder.nativeOrder())

        val row = IntArray(newW)
        val padXInt = padX.toInt()
        val padYInt = padY.toInt()

        for (y in 0 until inputSize_swing) {
            val inPadY = (y < padYInt) || (y >= padYInt + newH)
            if (inPadY) {
                repeat(inputSize_swing) { input.putFloat(0f); input.putFloat(0f); input.putFloat(0f) }
                continue
            }
            val ry = y - padYInt
            resized.getPixels(row, 0, newW, 0, ry, newW, 1)

            repeat(padXInt) { input.putFloat(0f); input.putFloat(0f); input.putFloat(0f) }
            for (rx in 0 until newW) {
                val color = row[rx]
                val r = ((color ushr 16) and 0xFF) / 255f
                val g = ((color ushr 8) and 0xFF) / 255f
                val b = (color and 0xFF) / 255f
                input.putFloat(r); input.putFloat(g); input.putFloat(b)
            }
            val rightPad = inputSize_swing - (padXInt + newW)
            repeat(rightPad) { input.putFloat(0f); input.putFloat(0f); input.putFloat(0f) }
        }
        resized.recycle()
        input.rewind()
        return Prep_swing(input, scale, padX, padY)
    }

    private fun nms_swing(dets: MutableList<Detection_swing>, iouTh_swing: Float): List<Detection_swing> {
        val out = mutableListOf<Detection_swing>()
        val picked = BooleanArray(dets.size)
        for (i in dets.indices) {
            if (picked[i]) continue
            val a = dets[i]
            out.add(a)
            for (j in i + 1 until dets.size) {
                if (picked[j]) continue
                val b = dets[j]
                if (iou_swing(a, b) > iouTh_swing) picked[j] = true
            }
        }
        return out
    }

    private fun iou_swing(a: Detection_swing, b: Detection_swing): Float {
        val xx1 = max(a.x1_swing, b.x1_swing)
        val yy1 = max(a.y1_swing, b.y1_swing)
        val xx2 = min(a.x2_swing, b.x2_swing)
        val yy2 = min(a.y2_swing, b.y2_swing)
        val w = max(0f, xx2 - xx1)
        val h = max(0f, yy2 - yy1)
        val inter = w * h
        val areaA = (a.x2_swing - a.x1_swing) * (a.y2_swing - a.y1_swing)
        val areaB = (b.x2_swing - b.x1_swing) * (b.y2_swing - b.y1_swing)
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }
}