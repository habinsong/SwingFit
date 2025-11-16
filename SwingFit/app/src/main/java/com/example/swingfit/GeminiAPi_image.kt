package com.example.gemini_demo

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

class GeminiAPi_image {
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

    // --- MIME 정규화: 이미지/동영상 포맷 대응 (현재는 이미지(PNG)만 사용) ---
    private fun normalizeMime(input: String?, fileName: String? = null): String {
        // 1) 우선 제공된 MIME 문자열을 신뢰하되, 흔한 별칭을 정규화
        val lower = (input ?: "").lowercase()
        if (lower.isNotBlank()) {
            return when (lower) {
                // 이미지 우선
                "image/png" -> "image/png"
                "image/jpg" -> "image/jpeg"
                "image/jpeg" -> "image/jpeg"
                "image/webp" -> "image/webp"
                // 과거 비디오/오디오 별칭 (현재 사용하지 않지만 하위호환 정규화 유지)
                "video/quicktime" -> "video/quicktime"
                "video/x-msvideo" -> "video/avi"
                "video/x-flv" -> "video/x-flv"
                "video/x-matroska", "video/mkv" -> "video/x-matroska"
                "audio/x-wav" -> "audio/wav"
                else -> lower
            }
        }
        // 2) 파일명 확장자로 추론
        val name = (fileName ?: "").lowercase()
        return when {
            name.endsWith(".png") -> "image/png"
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".webp") -> "image/webp"
            // 이하 비디오는 더 이상 사용하지 않지만, 안전한 기본값을 위해 남김
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

    // 업로드 시 받은 uri가 유효한지 검사 (https://…/v1beta/files/{id} 또는 gs://)
    private fun isValidFileUri(uri: String): Boolean {
        if (uri.isBlank()) return false
        val u = uri.trim()
        return u.startsWith("$baseUrl/v1beta/files/") || u.startsWith("gs://")
    }

    /**
     * 이미지(PNG 권장) 업로드 (Resumable):
     * 1) start → 2) upload, finalize
     * 성공 시 응답의 file.uri(HTTPS)를 그대로 반환합니다.
     */
    suspend fun uploadImage(
        contentResolver: ContentResolver,
        uri: Uri,
        mime: String = "image/png"
    ): String = withContext(Dispatchers.IO) {
        val cacheFile = File.createTempFile("upload_", ".tmp")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("이미지 스트림을 열 수 없습니다.")

        val fileLength = cacheFile.length()
        val normalizedMime = normalizeMime(mime, fileName = null)

        val startRequestBody = """{"file":{"display_name":"IMAGE"}}""".toRequestBody(jsonMediaType)
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
                throw IllegalStateException("업로드 시작 실패: ${resp.code} ${resp.message}\n$err")
            }
            resp.header("X-Goog-Upload-URL")
                ?: throw IllegalStateException("업로드 URL을 찾을 수 없습니다.")
        }

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
                throw IllegalStateException("업로드 실패: ${resp.code} ${resp.message}\n$bodyStr")
            }
            val obj = JSONObject(bodyStr)
            val fileObj = obj.optJSONObject("file")
                ?: throw IllegalStateException("파일 메타데이터를 찾을 수 없습니다: $bodyStr")

            val directUri = fileObj.optString("uri", "")
            if (isValidFileUri(directUri)) {
                return@use directUri
            }

            val name = fileObj.optString("name", "")
            if (name.startsWith("files/")) {
                return@use "$baseUrl/v1beta/$name"
            }

            throw IllegalStateException("유효한 file.uri 또는 files/* name을 찾지 못했습니다: $bodyStr")
        }
    }

    @Deprecated("비디오 업로드는 중단되었습니다. uploadImage(...)를 사용하십시오.")
    suspend fun uploadVideo(
        contentResolver: ContentResolver,
        uri: Uri,
        mime: String
    ): String = uploadImage(contentResolver, uri, mime = "image/png")

    /**
     * 파일 참조(file_uri=HTTPS/GS) + 텍스트 파트로 요청합니다.
     * 현재는 이미지(PNG)만 사용합니다.
     * 간헐적 처리 지연을 대비해 400 INVALID_ARGUMENT 발생 시 짧게 재시도(최대 3회)합니다.
     */
    suspend fun generateWithImage(
        fileUri: String,
        mime: String = "image/png",
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

        fun buildBody(): String {
            val bodyObj = JSONObject().apply {
                val userContent = JSONObject().apply {
                    val parts = JSONArray()
                    parts.put(
                        JSONObject().apply {
                            put(
                                "file_data",
                                JSONObject()
                                    .put("mime_type", normalizedMime)
                                    .put("file_uri", fileUri)
                            )
                        }
                    )
                    parts.put(JSONObject().put("text", combinedPrompt))
                    put("parts", parts)
                }
                put("contents", JSONArray().put(userContent))
            }
            return bodyObj.toString()
        }

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
                    throw IllegalStateException("API로부터 빈 응답을 받았습니다.\n$bodyStr")
                }
                if (resp.code == 400 && attempt < 2) {
                    try { Thread.sleep(1200) } catch (_: InterruptedException) {}
                    return@use
                }
                throw IllegalStateException("generate 실패: ${resp.code} ${resp.message}\n$bodyStr")
            }
        }
        throw IllegalStateException("예상치 못한 오류")
    }

    @Deprecated("비디오 입력은 중단되었습니다. generateWithImage(...)를 사용하십시오.")
    suspend fun generateWithVideo(
        fileUri: String,
        mime: String,
        prompt: String,
        systemInstruction: String?
    ): String = generateWithImage(fileUri, mime = "image/png", prompt = prompt, systemInstruction = systemInstruction)
}