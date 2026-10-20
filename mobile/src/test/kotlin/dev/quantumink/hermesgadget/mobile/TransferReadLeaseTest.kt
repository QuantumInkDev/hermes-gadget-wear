package dev.quantumink.hermesgadget.mobile

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferReadLeaseTest {
    @Test fun delayedProviderCompletionCannotReplaceNavigationOrADisposedScreen() {
        for (dispose in listOf(false, true)) {
            val lease = TransferReadLease()
            val generation = lease.begin()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val read = executor.submit<String> {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                "late provider document"
            }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                assertTrue(lease.current(generation))
                if (dispose) lease.dispose() else lease.invalidate()
                release.countDown()
                read.get(3, TimeUnit.SECONDS)
                assertFalse(lease.current(generation))
                if (!dispose) assertTrue(lease.current(lease.begin()))
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }
}
