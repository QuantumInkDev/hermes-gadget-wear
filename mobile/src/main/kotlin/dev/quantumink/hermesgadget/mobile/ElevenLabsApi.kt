package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.ClientSpeech
import dev.quantumink.hermesgadget.protocol.OggOpus
import dev.quantumink.hermesgadget.protocol.SpeechRequest
import java.io.OutputStream
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class SpeechFailure(message: String) : Exception(message)
data class SpeechQuota(val remaining: Long?)

/** Fixed production HTTPS origin; test-only injection allows isolated TLS fixtures. No logging or retries. */
class ElevenLabsApi internal constructor(
    private val origin: HttpUrl = "https://api.elevenlabs.io/".toHttpUrl(),
    client: OkHttpClient = OkHttpClient()
) : AutoCloseable {
    private val client = client.newBuilder().proxy(Proxy.NO_PROXY).followRedirects(false)
        .followSslRedirects(
            false
        ).retryOnConnectionFailure(false).connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS).build()
    private fun checked(code: Int) {
        if (code in 200..299) return
        throw SpeechFailure(
            when (code) {
                401 -> "Key rejected."
                403 -> "Key permission or subscription does not allow this request."
                429 -> "Provider quota or rate limit reached."
                else -> "Speech provider request failed."
            }
        )
    }
    fun quota(key: String): SpeechQuota {
        require(key.isNotEmpty())
        val request = Request.Builder().url(
            origin.newBuilder().addPathSegments("v1/user/subscription").build()
        ).header("xi-api-key", key).build()
        client.newCall(request).apply {
            timeout().timeout(15, TimeUnit.SECONDS)
        }.execute().use { response ->
            checked(response.code)
            val body = requireNotNull(response.body)
            val data = body.source().apply {
                require(request(65537).not() && buffer.size <= 65536)
            }.readUtf8()
            val json = Json.parseToJsonElement(data).jsonObject
            val used = json["character_count"]?.jsonPrimitive?.longOrNull
            val limit = json["character_limit"]?.jsonPrimitive?.longOrNull
            return SpeechQuota(
                if (used != null && limit != null && used >= 0 &&
                    limit >= 0
                ) {
                    (limit - used).coerceAtLeast(0)
                } else {
                    null
                }
            )
        }
    }
    override fun close() {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
    fun stream(
        settings: SpeechSettings,
        request: SpeechRequest,
        output: OutputStream,
        onCall: (Call) -> Unit
    ) {
        require(settings.mode != SpeechMode.SERVER && settings.key.isNotEmpty())
        val voice = settings.voice.ifEmpty { request.voice }
        if (!voice.matches(
                Regex("[a-zA-Z0-9_-]{1,128}")
            )
        ) {
            throw SpeechFailure("Choose a voice in the phone companion.")
        }
        val url = origin.newBuilder().addPathSegments(
            "v1/text-to-speech"
        ).addPathSegment(voice).addPathSegment("stream")
            .addQueryParameter(
                "output_format",
                if (request.format ==
                    "opus"
                ) {
                    "opus_48000_32"
                } else {
                    "pcm_16000"
                }
            ).build()
        val json =
            buildJsonObject {
                put("text", request.text)
                put("model_id", "eleven_multilingual_v2")
            }
        val call = client.newCall(
            Request.Builder().url(url).header("xi-api-key", settings.key)
                .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        )
        onCall(call)
        call.execute().use { response ->
            checked(response.code)
            val body = requireNotNull(response.body)
            require(body.contentType()?.subtype != "json")
            var frames = 0
            val started = System.nanoTime()
            fun packet(bytes: ByteArray) {
                require(!call.isCanceled() && ++frames <= 3000)
                val ahead = frames * 20000000L - (System.nanoTime() - started) - 200000000L
                if (ahead > 0) TimeUnit.NANOSECONDS.sleep(ahead)
                check(!call.isCanceled())
                ClientSpeech.packet(output, bytes)
            }
            val input = body.byteStream()
            if (request.format == "opus") {
                OggOpus.read(input, { ClientSpeech.start(output, "opus", it) }, ::packet)
            } else {
                ClientSpeech.start(output, "pcm16")
                while (true) {
                    val buffer = ByteArray(640)
                    var count = 0
                    while (count < buffer.size) {
                        val read = input.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        if (read == 0) continue
                        count += read
                    }
                    if (count == 0) break
                    require(count % 2 == 0)
                    packet(buffer.copyOf(count))
                }
            }
            require(frames > 0)
            ClientSpeech.finish(output)
        }
    }
}
