package dev.quantumink.hermesgadget.wear

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import dev.quantumink.hermesgadget.protocol.ConnectionObserver
import dev.quantumink.hermesgadget.protocol.ConnectionState
import dev.quantumink.hermesgadget.protocol.ConversationEffect
import dev.quantumink.hermesgadget.protocol.DeviceIdentity
import dev.quantumink.hermesgadget.protocol.DirectConnection
import dev.quantumink.hermesgadget.protocol.Endpoint
import dev.quantumink.hermesgadget.protocol.PetEffect
import dev.quantumink.hermesgadget.protocol.PetManifest
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Explicit synthetic localhost SDK fixture; no persisted identity, profile or enrollment. */
class PetOverlayDeliveryTest {
    @Test fun actualSdkNegotiatesAndTransfersHashBoundOverlayAndOldClientFallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val url =
            requireNotNull(
                InstrumentationRegistry.getArguments().getString("syntheticOverlayEndpoint")
            )
        require(url.startsWith("ws://localhost:"))
        val cache = PetCache(context)
        for (updated in listOf(false, true)) {
            val completed = CountDownLatch(2)
            val manifest = AtomicReference<PetManifest>()
            val failure = AtomicReference<Throwable>()
            val observer = object : ConnectionObserver {
                override fun stateChanged(state: ConnectionState) = Unit
                override fun effect(effect: ConversationEffect, generation: Long) = Unit
                override fun pet(effect: PetEffect) {
                    try {
                        when (effect) {
                            is PetEffect.Manifest -> {
                                manifest.set(effect.value)
                                cache.saveManifest(
                                    url,
                                    effect.value
                                )?.rows?.values?.flatten()?.forEach(Bitmap::recycle)
                            }
                            is PetEffect.Asset -> {
                                cache.saveAsset(
                                    url,
                                    requireNotNull(manifest.get()),
                                    effect.sheet,
                                    effect.bytes
                                )?.rows?.values?.flatten()?.forEach(Bitmap::recycle)
                                completed.countDown()
                            }
                        }
                    } catch (
                        error: Throwable
                    ) {
                        failure.set(error)
                        repeat(2) { completed.countDown() }
                    }
                }
            }
            val caps = buildJsonObject {
                put(
                    "pet",
                    buildJsonObject {
                        put("asset_channel", 4)
                        if (updated) put("expression_overlays", 1)
                    }
                )
            }
            try {
                DirectConnection(
                    Endpoint.parse(url, true, true),
                    DeviceIdentity.generate(),
                    observer,
                    capabilities = caps
                ).use { connection ->
                    connection.connect()
                    assertTrue("SDK transfer timed out", completed.await(15, TimeUnit.SECONDS))
                    failure.get()?.let { throw AssertionError("SDK asset callback failed", it) }
                    val received = requireNotNull(manifest.get())
                    val talking = received.sheets.flatMap { it.rows }.firstOrNull {
                        it.name ==
                            "talking"
                    }
                    if (updated) assertNotNull(talking?.overlay) else assertNull(talking)
                    val atlas = requireNotNull(cache.load(url))
                    assertEquals(updated, "talking" in atlas.rows)
                    assertTrue("listening" in atlas.rows && "sleeping" in atlas.rows)
                    atlas.rows.values.flatten().forEach(Bitmap::recycle)
                    val directory = File(context.filesDir, "o2-overlay-captures").apply { mkdirs() }
                    File(directory, "sdk-manifest-$updated.json").writeText(received.encoded)
                }
            } finally {
                cache.remove(url)
            }
        }
    }
}
