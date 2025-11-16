package com.example.swingfit

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 분석 완료 후 재생과 동기화할 타임스탬프 포함 포인트 */
data class TimedPoint_swing(val x: Float, val y: Float, val tsUs: Long)

/** 라이브 수집용 포인트(분석 중) */
data class TrailPoint_swing(val x: Float, val y: Float)

/**
 * 분석 중에는 addPoint()로 들어오는 점들을 바로 곡선으로 그림.
 * 분석 완료 후에는 setAnalyzedTimeline()으로 받은 타임라인을 기반으로,
 * updatePlaybackPositionUs()로 들어오는 재생 위치에 맞춰 0→끝까지 반복 표시.
 */
class Swing_OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // 0=hand, 1=head, 2=shaft (라이브 모드에서만 사용)
    private val trailsLive = arrayOf(
        mutableListOf<TrailPoint_swing>(),
        mutableListOf<TrailPoint_swing>(),
        mutableListOf<TrailPoint_swing>()
    )

    /** PlayerView 내부 배치에 따른 최종 뷰 좌표 변환 파라미터 */
    @Volatile var viewScale: Float = 1f
    @Volatile var viewOffX: Float = 0f
    @Volatile var viewOffY: Float = 0f

    // 색/페인트
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF00C853") // 기본은 초록
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val pDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val GREEN = Color.parseColor("#00C853")
    private val BLUE  = Color.parseColor("#3D5AFE")
    private val RED   = Color.parseColor("#FF1744")
    private val EPS_Y = 2f // y-방향 변화량 임계값(픽셀)

    // ─────────────────────────────────────────────
    // 모드 전환 관련: 라이브 vs 재생 연동
    // ─────────────────────────────────────────────

    private var analyzedTimeline: MutableList<TimedPoint_swing>? = null
    private var analyzedDurationUs: Long = 0L
    @Volatile private var currentTsUs: Long = 0L
    @Volatile private var replayEnabled: Boolean = false

    /** 분석 중 라이브 데이터 초기화 */
    fun clearTrails() {
        trailsLive.forEach { it.clear() }
        // 분석 완료 모드(재생 연동)도 초기화
        analyzedTimeline = null
        analyzedDurationUs = 0L
        replayEnabled = false
        invalidate()
    }

    /** 분석 중: 비디오 좌표계를 입력으로 받아 오버레이 좌표로 변환하여 누적 */
    fun addPoint(classId: Int, xVideo: Float, yVideo: Float) {
        if (classId !in 0..2) return
        if (replayEnabled) return // 분석 완료 모드에서는 라이브 무시
        val vx = viewOffX + xVideo * viewScale
        val vy = viewOffY + yVideo * viewScale
        trailsLive[classId].add(TrailPoint_swing(vx, vy))
        invalidate()
    }

    /** 분석 완료: 타임라인(비디오 좌표)과 총 길이(µs)를 넘겨주면 재생 연동 모드 진입 */
    fun setAnalyzedTimeline(pointsVideo: List<TimedPoint_swing>, totalDurationUs: Long) {
        val list = mutableListOf<TimedPoint_swing>()
        // 비디오→뷰 좌표 변환을 미리 해두면 onDraw가 가벼워짐
        for (p in pointsVideo) {
            val vx = viewOffX + p.x * viewScale
            val vy = viewOffY + p.y * viewScale
            list.add(TimedPoint_swing(vx, vy, p.tsUs))
        }
        analyzedTimeline = list
        analyzedDurationUs = max(1L, totalDurationUs)
        replayEnabled = true
        invalidate()
    }

    /** 재생기에서 현재 포지션(µs)을 주기적으로 넣어줌 (loop 고려하여 % 처리) */
    fun updatePlaybackPositionUs(posUs: Long) {
        if (!replayEnabled) return
        currentTsUs = if (analyzedDurationUs > 0) {
            // 루프 재생이므로 모듈로
            val m = posUs % analyzedDurationUs
            if (m < 0) m + analyzedDurationUs else m
        } else posUs
        invalidate()
    }

    /**
     * 스냅샷/내보내기 용: 비디오 좌표계 포인트를 입력받아 주어진 캔버스에
     * Catmull–Rom + 초록→파랑 그라데이션(다운스윙 구간은 빨강)으로 렌더링합니다.
     * View 스케일/오프셋은 적용하지 않습니다.
     */
    fun drawTrajectoryForExport(canvas: Canvas, videoPts: List<TimedPoint_swing>) {
        if (videoPts.size < 2) return
        val pts = ArrayList<TrailPoint_swing>(videoPts.size)
        for (tp in videoPts) {
            pts.add(TrailPoint_swing(tp.x, tp.y))
        }
        // 기존 내부 렌더러(부드러운 Catmull–Rom + 그라데이션 + 빨강) 사용
        drawGradientCatmull(canvas, pts)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (replayEnabled) {
            drawReplayingTimeline(canvas)
        } else {
            // 라이브 모드에서는 헤드(1)만 그림 (요구사항 따라 변경 가능)
            drawGradientCatmull(canvas, trailsLive[1])
        }
    }

    // ─────────────────────────────────────────────
    // 재생 연동 모드: currentTsUs 까지의 곡선을 Catmull–Rom으로 그림
    // ─────────────────────────────────────────────

    private fun drawReplayingTimeline(canvas: Canvas) {
        val tl = analyzedTimeline ?: return
        if (tl.size == 0) return

        // currentTsUs 이하만 취한 부분 궤적 생성
        val cutoff = currentTsUs
        var n = 0
        while (n < tl.size && tl[n].tsUs <= cutoff) n++
        if (n == 0) {
            // 아직 시작 전: 첫 점만 점 표시
            val p0 = tl.first()
            canvas.drawCircle(p0.x, p0.y, 6f, pDot)
            return
        }

        // 부분 리스트(0..n-1)를 TrailPoint로 변환해서 Catmull–Rom
        val seg = ArrayList<TrailPoint_swing>(n)
        for (i in 0 until n) seg.add(TrailPoint_swing(tl[i].x, tl[i].y))
        drawGradientCatmull(canvas, seg)
    }

    /** y-변화 추세 기반으로 '첫 상승 이후 첫 하강~다시 상승 직전' 구간을인덱스로 반환
     *  - pts: View 좌표계의 샘플 포인트들(시간 순)
     *  - 반환: 구간이 없으면 null
     *  - 주: 화면 y축은 기기 좌표에서 아래로 증가하지만, 여기서는 단순히 '초기 추세 → 반대 추세 → 다시 초기 추세'로 정의합니다.
     */
    private fun detectDownswingRange(pts: List<TrailPoint_swing>): IntRange? {
        if (pts.size < 5) return null
        // 1) 초기 추세 파악
        var initSign = 0
        for (i in 1 until pts.size) {
            val dy = pts[i].y - pts[i - 1].y
            val s = when {
                dy >  EPS_Y -> 1   // y가 증가하는 추세
                dy < -EPS_Y -> -1  // y가 감소하는 추세
                else -> 0
            }
            if (s != 0) { initSign = s; break }
        }
        if (initSign == 0) return null

        // 2) 첫 반대 추세(다운스윙 시작) 지점 찾기
        var downStart = -1
        var lastSign = initSign
        for (i in 1 until pts.size) {
            val dy = pts[i].y - pts[i - 1].y
            val s = when {
                dy >  EPS_Y -> 1
                dy < -EPS_Y -> -1
                else -> 0
            }
            if (s != 0) lastSign = s
            if (downStart == -1 && s != 0 && s == -initSign) {
                // 초기 추세와 반대 부호로 전환된 첫 지점(i-1..i 구간)
                downStart = i - 1
                break
            }
        }
        if (downStart == -1) return null

        // 3) 다시 초기 추세로 전환되는 지점(다운스윙 종료, 즉 임팩트~최저점 이후 상승 시작 직전)
        var downEnd = pts.size - 1
        lastSign = -initSign
        for (i in downStart + 1 until pts.size) {
            val dy = pts[i].y - pts[i - 1].y
            val s = when {
                dy >  EPS_Y -> 1
                dy < -EPS_Y -> -1
                else -> 0
            }
            if (s != 0) {
                if (s == initSign) {
                    // 다시 초기 추세로 돌아오기 직전 구간을 종단으로 설정
                    downEnd = i - 1
                    break
                }
                lastSign = s
            }
        }
        if (downEnd <= downStart) return null
        return (downStart..downEnd)
    }

    // ─────────────────────────────────────────────
    // 공용: Catmull–Rom 곡선 + 초록→파랑 그라데이션
    // ─────────────────────────────────────────────

    private fun drawGradientCatmull(canvas: Canvas, pts: List<TrailPoint_swing>) {
        if (pts.isEmpty()) return
        if (pts.size == 1) {
            val p = pts[0]
            canvas.drawCircle(p.x, p.y, 6f, pDot)
            return
        }

        // 다운스윙(첫 하강~다시 상승 직전) 구간 탐지
        val redRange: IntRange? = detectDownswingRange(pts)

        val path = buildCatmullRomPath(pts)
        val pm = PathMeasure(path, false)
        val totalLen = pm.length
        if (totalLen <= 0f) return

        // 길이 기준 균등 샘플링
        val samples = (totalLen / 10f).toInt().coerceIn(24, 128)
        val pos = FloatArray(2)
        val prev = FloatArray(2)
        pm.getPosTan(0f, prev, null)

        // 샘플 인덱스를 원본 점 인덱스에 근사 매핑
        val maxIdx = (pts.size - 1).coerceAtLeast(1)

        for (i in 1..samples) {
            val t = i / samples.toFloat()              // 0..1
            val dist = totalLen * t
            pm.getPosTan(dist, pos, null)

            // 근사 원본 인덱스(선두 세그먼트 기준)
            val approxIdx = ((t * maxIdx)).toInt().coerceIn(0, maxIdx)

            if (redRange != null && approxIdx in redRange) {
                // 다운스윙 구간: 빨강 고정
                pStroke.color = RED
            } else {
                // 기본: 초록→파랑 그라데이션
                pStroke.color = interpolateColor(GREEN, BLUE, t)
            }

            canvas.drawLine(prev[0], prev[1], pos[0], pos[1], pStroke)
            prev[0] = pos[0]; prev[1] = pos[1]
        }
        // 끝점 강조
        canvas.drawCircle(prev[0], prev[1], 6f, pDot)
    }

    /** Catmull–Rom(Uniform) → cubic path */
    private fun buildCatmullRomPath(pts: List<TrailPoint_swing>): Path {
        val path = Path()
        path.moveTo(pts[0].x, pts[0].y)
        if (pts.size == 2) {
            path.lineTo(pts[1].x, pts[1].y)
            return path
        }
        // 경계 안정화 (양 끝 복제)
        val p = ArrayList<TrailPoint_swing>(pts.size + 2)
        p.add(pts.first())
        p.addAll(pts)
        p.add(pts.last())

        for (i in 1 until p.size - 2) {
            val p0 = p[i - 1]
            val p1 = p[i]
            val p2 = p[i + 1]
            val p3 = p[i + 2]
            val c1x = p1.x + (p2.x - p0.x) / 6f
            val c1y = p1.y + (p2.y - p0.y) / 6f
            val c2x = p2.x - (p3.x - p1.x) / 6f
            val c2y = p2.y - (p3.y - p1.y) / 6f
            path.cubicTo(c1x, c1y, c2x, c2y, p2.x, p2.y)
        }
        return path
    }

    /** 두 색상을 0..1 비율로 보간 */
    private fun interpolateColor(startColor: Int, endColor: Int, t: Float): Int {
        val sr = (startColor shr 16) and 0xFF
        val sg = (startColor shr 8) and 0xFF
        val sb = startColor and 0xFF
        val er = (endColor shr 16) and 0xFF
        val eg = (endColor shr 8) and 0xFF
        val eb = endColor and 0xFF
        val r = (sr + ((er - sr) * t)).toInt()
        val g = (sg + ((eg - sg) * t)).toInt()
        val b = (sb + ((eb - sb) * t)).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}