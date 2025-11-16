// RecordsRepository.kt
package com.example.model

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordsRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private fun uid(): String? = auth.currentUser?.uid

    /** ---- 공통: 안전 파서 ---- */
    private fun String?.nnDash(): String = if (this.isNullOrBlank()) "-" else this

    private fun snapshotToBall(d: DocumentSnapshot): BallAnalysis {
        val m = d.data ?: emptyMap<String, Any?>()
        val tsMs = (m["timestampMs"] as? Number)?.toLong()
        val createdAtStr = when (val ca = m["createdAt"]) {
            is com.google.firebase.Timestamp -> {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                sdf.format(ca.toDate())
            }
            is Date -> {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                sdf.format(ca)
            }
            is String -> ca
            else -> null
        }

        val thumb = (m["thumbUriLocal"] as? String)    // 새 키 (숨김 저장한 썸네일)
            ?: (m["thumbnailUri"] as? String)          // 구버전/기존 키 폴백

        return BallAnalysis(
            totalDistance = (m["totalDistance"] as? String).nnDash(),
            carryDistance  = (m["carryDistance"]  as? String).nnDash(),
            launchAngle    = (m["launchAngle"]    as? String).nnDash(),
            ballSpeed      = (m["ballSpeed"]      as? String).nnDash(),
            backspin       = (m["backspin"]       as? String).nnDash(),
            club           = (m["club"]           as? String).nnDash(),
            smashFactor    = (m["smashFactor"]    as? String).nnDash(),
            apexHeight     = (m["apexHeight"]     as? String).nnDash(),
            swingTempo     = (m["swingTempo"]     as? String).nnDash(),
            swingPath      = (m["swingPath"]      as? String).nnDash(),
            feedback       = (m["feedback"]       as? String).nnDash(),
            environment    = (m["environment"]    as? String).nnDash(),
            createdAt      = createdAtStr,
            day            = (m["day"]            as? String),
            timestampMs    = tsMs,
            ownerUid       = (m["ownerUid"]       as? String)
        ).apply {
            id = d.id
            thumbnailUri = thumb   // ✅ 여기서 썸네일 주입
        }
    }


    private fun snapshotToSwing(d: DocumentSnapshot): SwingAnalysis {
        val m = d.data ?: emptyMap<String, Any?>()

        val tsMs = (m["timestampMs"] as? Number)?.toLong()

        val createdAtStr = when (val ca = m["createdAt"]) {
            is com.google.firebase.Timestamp -> {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                sdf.format(ca.toDate())
            }
            is Date -> {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                sdf.format(ca)
            }
            is String -> ca
            else -> null
        }

        return SwingAnalysis(
            // 메타
            createdAt   = createdAtStr,
            timestampMs = tsMs,
            imageUriLocal = (m["imageUriLocal"] as? String),
            club        = (m["club"] as? String).nnDash(),
            environment = (m["environment"] as? String).nnDash(), // 없으면 "-" 처리

            // 분석 결과 (문자열로 그대로)
            swingType       = (m["swingType"]       as? String).nnDash(),
            overallFeedback = (m["overallFeedback"] as? String).nnDash(),
            takeaway        = (m["takeaway"]        as? String).nnDash(),
            transition      = (m["transition"]      as? String).nnDash(),
            impact          = (m["impact"]          as? String).nnDash(),
            followThrough   = (m["followThrough"]   as? String).nnDash(),
            keyStrength     = (m["keyStrength"]     as? String).nnDash(),
            improvement     = (m["improvement"]     as? String).nnDash(),


        ).apply {
            id = d.id
            // 목록 썸네일로 사용할 필드: Firestore는 imageUriLocal 을 저장하므로 여기에 매핑
            thumbnailUri = imageUriLocal
        }
    }

    /** ---- 쿼리 with 정렬 폴백 + 안전 매핑 ---- */
    suspend fun getBallAnalyses(limit: Long = 50): List<BallAnalysis> {
        val uid = uid() ?: return emptyList()
        val base = db.collection("users").document(uid).collection("ball-analyses")

        var snap = try {
            base.orderBy("timestampMs", Query.Direction.DESCENDING).limit(limit).get().await()
        } catch (e: Exception) {
            Log.w("RecordsRepo", "ball orderBy(timestampMs) failed: ${e.message}")
            null
        }
        if (snap == null || snap.isEmpty) {
            snap = try {
                base.orderBy("createdAt", Query.Direction.DESCENDING).limit(limit).get().await()
            } catch (e: Exception) {
                Log.w("RecordsRepo", "ball orderBy(createdAt) failed: ${e.message}")
                null
            }
        }
        if (snap == null || snap.isEmpty) {
            snap = try { base.limit(limit).get().await() } catch (e: Exception) {
                Log.w("RecordsRepo", "ball plain get failed: ${e.message}"); null
            }
        }

        val docs = snap?.documents ?: emptyList()
        val list = docs.map { d -> runCatching { snapshotToBall(d) }.getOrNull() }.filterNotNull()
        Log.d("RecordsRepo", "ball fetched=${list.size}, rawDocs=${docs.size}")
        if (docs.isNotEmpty()) Log.d("RecordsRepo", "ball firstDocKeys=${docs.first().data?.keys}")
        return list
    }

    suspend fun getSwingAnalyses(limit: Long = 50): List<SwingAnalysis> {
        val uid = uid() ?: return emptyList()
        val base = db.collection("users").document(uid).collection("swing-analyses")

        var snap = try {
            base.orderBy("timestampMs", Query.Direction.DESCENDING).limit(limit).get().await()
        } catch (e: Exception) {
            Log.w("RecordsRepo", "swing orderBy(timestampMs) failed: ${e.message}")
            null
        }
        if (snap == null || snap.isEmpty) {
            snap = try {
                base.orderBy("createdAt", Query.Direction.DESCENDING).limit(limit).get().await()
            } catch (e: Exception) {
                Log.w("RecordsRepo", "swing orderBy(createdAt) failed: ${e.message}")
                null
            }
        }
        if (snap == null || snap.isEmpty) {
            snap = try { base.limit(limit).get().await() } catch (e: Exception) {
                Log.w("RecordsRepo", "swing plain get failed: ${e.message}"); null
            }
        }

        val docs = snap?.documents ?: emptyList()
        val list = docs.map { d -> runCatching { snapshotToSwing(d) }.getOrNull() }.filterNotNull()
        Log.d("RecordsRepo", "swing fetched=${list.size}, rawDocs=${docs.size}")
        if (docs.isNotEmpty()) Log.d("RecordsRepo", "swing firstDocKeys=${docs.first().data?.keys}")
        return list
    }

    suspend fun deleteAnalysis(type: String, docId: String): Boolean {
        val uid = uid() ?: return false
        val col = if (type.lowercase() == "distance") "ball-analyses" else "swing-analyses"
        return try {
            db.collection("users").document(uid).collection(col).document(docId)
                .delete().await()
            true
        } catch (e: Exception) {
            Log.e("RecordsRepo", "deleteAnalysis failed: ${e.message}")
            false
        }
    }


}