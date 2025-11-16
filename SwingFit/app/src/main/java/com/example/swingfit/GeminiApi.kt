package com.example.swingfit

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.apply
import kotlin.io.copyTo
import kotlin.io.use
import kotlin.text.endsWith
import kotlin.text.isNotBlank
import kotlin.text.isNullOrBlank
import kotlin.text.lowercase
import kotlin.text.orEmpty
import kotlin.text.startsWith
import okhttp3.Request


class GeminiApi {
    private val apiKey: String = GEMINI_API_KEY

    companion object {
        private const val GEMINI_API_KEY = ""
    }

    private val baseUrl = "https://generativelanguage.googleapis.com"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()
    }

    // --- MIME 정규화: 다양한 동영상 포맷 대응 ---
    private fun normalizeMime(input: String?, fileName: String? = null): String {
        val lower = (input ?: "").lowercase()
        if (lower.isNotBlank()) {
            return when (lower) {
                // iOS/macOS MOV
                "video/quicktime" -> "video/quicktime"
                "video/x-msvideo" -> "video/avi"
                "video/x-flv" -> "video/x-flv"
                "video/x-matroska", "video/mkv" -> "video/x-matroska"
                "audio/x-wav" -> "audio/wav"
                else -> lower
            }
        }
        val name = (fileName ?: "").lowercase()
        return when {
            name.endsWith(".mov") -> "video/quicktime"
            name.endsWith(".mp4") -> "video/mp4"
            name.endsWith(".mpeg") || name.endsWith(".mpg") -> "video/mpeg"
            name.endsWith(".avi") -> "video/avi"
            name.endsWith(".flv") -> "video/x-flv"
            name.endsWith(".webm") -> "video/webm"
            name.endsWith(".wmv") -> "video/wmv"
            name.endsWith(".3gp") || name.endsWith(".3gpp") -> "video/3gpp"
            else -> "application/octet-stream"
        }
    }

    // 업로드 시 받은 uri가 유효한지 검사 (https://.../v1beta/files/{id} 또는 gs://)
    private fun isValidFileUri(uri: String): Boolean {
        return uri.startsWith("$baseUrl/v1beta/files/") || uri.startsWith("gs://")
    }

    /**
     * Resumable 업로드:
     * 1) start → 2) upload, finalize
     * 성공 시 응답의 file.uri(일반적으로 HTTPS)를 그대로 반환합니다.
     * gs:// 로 바뀔 때까지 기다리지 않습니다.
     */
    suspend fun uploadVideo(contentResolver: ContentResolver, uri: Uri, mime: String): String =
        withContext(Dispatchers.IO) {
            val cacheFile = File.createTempFile("upload_", ".tmp")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
            } ?: throw kotlin.IllegalStateException("비디오 스트림을 열 수 없습니다.")

            val fileLength = cacheFile.length()
            val normalizedMime = normalizeMime(mime)

            // 1) 업로드 세션 시작
            val startRequestBody =
                """{"file":{"display_name":"VIDEO"}}""".toRequestBody(jsonMediaType)
            val startReq = Request.Builder()
                .url("$baseUrl/upload/v1beta/files")
                .post(startRequestBody)
                .header("x-goog-api-key", apiKey)
                .header("X-Goog-Upload-Protocol", "resumable")
                .header("X-Goog-Upload-Command", "start")
                .header("X-Goog-Upload-Header-Content-Length", fileLength.toString())
                .header("X-Goog-Upload-Header-Content-Type", normalizedMime)
                .header("Content-Type", "application/json")
                .build()

            val uploadUrl = client.newCall(startReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string()
                    throw kotlin.IllegalStateException("업로드 시작 실패: ${resp.code} ${resp.message}\n$err")
                }
                resp.header("X-Goog-Upload-URL")
                    ?: throw kotlin.IllegalStateException("업로드 URL을 찾을 수 없습니다.")
            }

            // 2) 실제 데이터 업로드 + finalize
            val fileRequestBody = cacheFile.asRequestBody(normalizedMime.toMediaTypeOrNull())
            val uploadReq = Request.Builder()
                .url(uploadUrl)
                .post(fileRequestBody)
                .header("Content-Length", fileLength.toString())
                .header("X-Goog-Upload-Offset", "0")
                .header("X-Goog-Upload-Command", "upload, finalize")
                .header("X-Goog-Upload-Protocol", "resumable")
                .build()

            client.newCall(uploadReq).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw kotlin.IllegalStateException("업로드 실패: ${resp.code} ${resp.message}\n$bodyStr")
                }
                val obj = JSONObject(bodyStr)
                val fileObj = obj.optJSONObject("file")
                    ?: throw kotlin.IllegalStateException("파일 메타데이터를 찾을 수 없습니다: $bodyStr")

                // 업로드 응답의 file.uri를 그대로 사용 (일반적으로 https://…/v1beta/files/{id})
                val directUri = fileObj.optString("uri", "")
                if (isValidFileUri(directUri)) {
                    return@use directUri
                }

                // 간혹 uri가 비어 있고 name만 올 때는 HTTPS 경로로 구성
                val name = fileObj.optString("name", "")
                if (name.startsWith("files/")) {
                    return@use "$baseUrl/v1beta/$name"
                }

                throw kotlin.IllegalStateException("유효한 file.uri 또는 files/* name을 찾지 못했습니다: $bodyStr")
            }
        }

    /**
     * 파일 참조(file_uri=HTTPS/GS) + 텍스트 파트로 요청합니다.
     * 간헐적 처리 지연을 대비해 400 INVALID_ARGUMENT 발생 시 짧게 재시도(최대 3회)합니다.
     */
    suspend fun generateWithVideo(
        fileUri: String,
        mime: String,
        prompt: String,
        systemInstruction: String?
    ): String = withContext(Dispatchers.IO) {
        val model = "gemini-2.5-pro"
        val modelPath = "/v1beta/models/$model:generateContent"
        val requestUrl = "$baseUrl$modelPath?key=$apiKey"

        val combinedPrompt = if (!systemInstruction.isNullOrBlank()) {
            "$systemInstruction\n\n$prompt"
        } else prompt

        val normalizedMime = normalizeMime(mime)

        // 요청 바디 생성 함수
        fun buildBody(): String {
            val bodyObj = JSONObject().apply {
                val userContent = JSONObject().apply {
                    val parts = JSONArray()
                    // 파일 파트
                    parts.put(
                        JSONObject().apply {
                            put(
                                "file_data",
                                JSONObject()
                                    .put("mime_type", normalizedMime)
                                    .put("file_uri", fileUri) // https://… 또는 gs:// 모두 허용
                            )
                        }
                    )
                    // 텍스트 파트
                    parts.put(JSONObject().put("text", combinedPrompt))
                    put("parts", parts)
                }
                put("contents", JSONArray().put(userContent))
            }
            return bodyObj.toString()
        }

        // 최대 3회 재시도
        repeat(3) { attempt ->
            val req = Request.Builder()
                .url(requestUrl)
                .post(buildBody().toRequestBody(jsonMediaType))
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
                    throw kotlin.IllegalStateException("API로부터 빈 응답을 받았습니다.\n$bodyStr")
                }

                // 400이고 파일 준비 지연 가능성 있으면 짧게 재시도
                if (resp.code == 400 && attempt < 2) {
                    try {
                        Thread.sleep(1200)
                    } catch (_: InterruptedException) {
                    }
                    return@use
                }
                throw kotlin.IllegalStateException("generate 실패: ${resp.code} ${resp.message}\n$bodyStr")
            }
        }
        // 논리상 도달 불가
        throw kotlin.IllegalStateException("예상치 못한 오류")
    }


}