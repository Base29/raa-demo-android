package com.realtimeaudio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LatestFrameExecutorTest {

    @Test
    fun latestFrameWins_dropsQueuedWork_underLoad() {
        val exec = LatestFrameExecutor("LatestFrameExecutorTest")
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        val ran = AtomicInteger(0)

        // Block the worker with a long task.
        exec.executeLatest(Runnable {
            started.countDown()
            Thread.sleep(150)
        })
        assertTrue(started.await(500, TimeUnit.MILLISECONDS))

        // Enqueue multiple tasks while worker is busy; queue capacity is 1.
        for (i in 0 until 20) {
            exec.executeLatest(Runnable { ran.incrementAndGet() })
        }

        // After the blocker, only ~1 "latest" should run (not 20).
        exec.executeLatest(Runnable { done.countDown() })
        assertTrue(done.await(1, TimeUnit.SECONDS))
        assertTrue("Expected bounded execution, got ${ran.get()}", ran.get() <= 3)

        exec.shutdownNow()
    }
}

