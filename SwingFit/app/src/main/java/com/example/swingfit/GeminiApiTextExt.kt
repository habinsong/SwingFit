// GeminiApiTextExt.kt
package com.example.swingfit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 기존 GeminiApi.kt는 변경하지 않음.
 * 텍스트 프롬프트 전용 호출을 확장 함수로 제공.
 */
suspend fun GeminiApi.generateWithText(
    prompt: String,
    systemInstruction: String? = null
): String = withContext(Dispatchers.IO) {
    // ====== 내부 필드 안전 접근 (리플렉션 + 폴백) ======
    val apiKey: String = runCatching {
        this@generateWithText.javaClass.getDeclaredField("apiKey").apply { isAccessible = true }
            .get(this@generateWithText) as String
    }.getOrElse {
        // 마지막 폴백: BuildConfig 또는 예외
        // 필요 시 프로젝트 키로 교체
        throw IllegalStateException("Gemini API key not accessible.")
    }

    val baseUrl: String = runCatching {
        this@generateWithText.javaClass.getDeclaredField("baseUrl").apply { isAccessible = true }
            .get(this@generateWithText) as String
    }.getOrElse { "https://generativelanguage.googleapis.com" }

    val jsonMediaType: MediaType = runCatching {
        this@generateWithText.javaClass.getDeclaredField("jsonMediaType").apply { isAccessible = true }
            .get(this@generateWithText) as MediaType
    }.getOrElse { "application/json; charset=utf-8".toMediaType() }

    val client: OkHttpClient = runCatching {
        // 1) 필드명이 'client'로 존재하는 경우
        this@generateWithText.javaClass.getDeclaredField("client").apply { isAccessible = true }
            .get(this@generateWithText) as OkHttpClient
    }.getOrElse {
        // 2) Kotlin lazy 백킹필드 'client$delegate'로 존재하는 경우
        runCatching {
            val f = this@generateWithText.javaClass.getDeclaredField("client\$delegate")
            f.isAccessible = true
            val lazyObj = f.get(this@generateWithText) as kotlin.Lazy<*>
            lazyObj.value as OkHttpClient
        }.getOrElse {
            // 3) 최종 폴백 클라이언트 (원본과 유사한 타임아웃/로깅)
            OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                })
                .build()
        }
    }

    // ====== 요청 구성 ======
    val model = "gemini-2.5-flash"
    val requestUrl = "$baseUrl/v1beta/models/$model:generateContent?key=$apiKey"

    val combinedPrompt = if (!systemInstruction.isNullOrBlank()) {
        "$systemInstruction\n\n$prompt"
    } else prompt

    val bodyObj = JSONObject().apply {
        val userContent = JSONObject().apply {
            val parts = JSONArray()
            parts.put(JSONObject().put("text", combinedPrompt))
            put("parts", parts)
        }
        put("contents", JSONArray().put(userContent))
    }

    val req = Request.Builder()
        .url(requestUrl)
        .post(bodyObj.toString().toRequestBody(jsonMediaType))
        .header("Content-Type", "application/json; charset=utf-8")
        .build()

    client.newCall(req).execute().use { resp ->
        val bodyStr = resp.body?.string().orEmpty()
        if (resp.isSuccessful) {
            val text = JSONObject(bodyStr)
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()
            if (text.isNotBlank()) return@withContext text
            throw IllegalStateException("API로부터 빈 응답을 받았습니다.\n$bodyStr")
        } else {
            throw IllegalStateException("generate 실패: ${resp.code} ${resp.message}\n$bodyStr")
        }
    }
}