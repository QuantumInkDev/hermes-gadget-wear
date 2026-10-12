package dev.quantumink.hermesgadget.wear

import android.util.AtomicFile
import dev.quantumink.hermesgadget.protocol.AnimationDescriptor
import dev.quantumink.hermesgadget.protocol.PortraitAnimations
import java.io.File

/** Private, nonsecret cache bindings; enrollment remains in its separate encrypted vault. */
class AnimationIndex(private val file: File) {
    @Synchronized fun read(): Map<String, AnimationDescriptor> {
        val atomic = AtomicFile(file)
        if (!file.exists() && !File(file.path + ".bak").exists()) return emptyMap()
        return atomic.openRead().use {
            require(it.channel.size() <= PortraitAnimations.MAX_BYTES)
            PortraitAnimations.decode(it.readBytes().toString(Charsets.UTF_8))
        }
    }

    @Synchronized fun save(records: Map<String, AnimationDescriptor>) {
        val bytes = PortraitAnimations.encode(records).toByteArray()
        check(file.parentFile!!.exists() || file.parentFile!!.mkdirs())
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (
            failure: Exception
        ) {
            atomic.failWrite(output)
            throw failure
        }
    }

    @Synchronized fun reset() {
        AtomicFile(file).delete()
    }
}
