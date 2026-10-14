package dev.quantumink.hermesgadget.mobile

import dev.quantumink.hermesgadget.protocol.CatalogProfile
import dev.quantumink.hermesgadget.protocol.CatalogSource
import dev.quantumink.hermesgadget.protocol.CatalogStatus
import dev.quantumink.hermesgadget.protocol.PortraitDescriptor
import dev.quantumink.hermesgadget.protocol.ProfileCatalog
import java.io.IOException
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class CatalogFailure(val status: CatalogStatus) :
    IOException("Catalog connection needs attention or is unavailable.")

class CatalogClient(client: OkHttpClient = OkHttpClient()) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .proxy(Proxy.NO_PROXY).callTimeout(8, TimeUnit.SECONDS).build()

    fun fetch(source: CatalogSource): List<CatalogProfile> = try {
        ProfileCatalog.decodeSource(
            read(source, source.url, ProfileCatalog.MAX_BYTES).toString(Charsets.UTF_8)
        )
    } catch (failure: CatalogFailure) {
        throw failure
    } catch (_: Exception) {
        throw CatalogFailure(CatalogStatus.ATTENTION)
    }

    fun portrait(
        source: CatalogSource,
        descriptor: PortraitDescriptor,
        timeoutMs: Long = 8000
    ): ByteArray {
        val address =
            source.url.toHttpUrl().resolve(descriptor.path)
                ?: throw CatalogFailure(CatalogStatus.ATTENTION)
        val origin = source.url.toHttpUrl()
        require(
            address.scheme == origin.scheme && address.host == origin.host &&
                address.port == origin.port
        )
        val data = read(source, address.toString(), descriptor.bytes, timeoutMs)
        val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") {
            "%02x".format(it)
        }
        if (data.size != descriptor.bytes ||
            hash != descriptor.sha256
        ) {
            throw CatalogFailure(CatalogStatus.ATTENTION)
        }
        return data
    }

    private fun read(
        source: CatalogSource,
        url: String,
        limit: Int,
        timeoutMs: Long = 8000
    ): ByteArray = try {
        val request = Request.Builder().url(
            url
        ).header("Authorization", "Bearer ${source.credential}").build()
        val call = http.newCall(request)
        call.timeout().timeout(timeoutMs.coerceIn(1, 8000), TimeUnit.MILLISECONDS)
        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw CatalogFailure(
                    if (response.code in
                        500..599
                    ) {
                        CatalogStatus.OFFLINE
                    } else {
                        CatalogStatus.ATTENTION
                    }
                )
            }
            val body = response.body ?: throw CatalogFailure(CatalogStatus.ATTENTION)
            if (body.contentLength() > limit) throw CatalogFailure(CatalogStatus.ATTENTION)
            val data = body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= limit) {
                    val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (data.size > limit) throw CatalogFailure(CatalogStatus.ATTENTION)
            data
        }
    } catch (failure: CatalogFailure) {
        throw failure
    } catch (_: SSLException) {
        throw CatalogFailure(CatalogStatus.ATTENTION)
    } catch (_: IOException) {
        throw CatalogFailure(CatalogStatus.OFFLINE)
    } catch (_: Exception) {
        throw CatalogFailure(CatalogStatus.ATTENTION)
    }
}
