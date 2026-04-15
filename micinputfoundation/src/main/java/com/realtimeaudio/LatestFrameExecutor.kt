package com.realtimeaudio

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A tiny helper that enforces "latest-frame wins" behavior.
 *
 * If the worker is busy, we drop queued work and keep only the newest task.
 * This prevents latency tails / backlog under load.
 */
internal class LatestFrameExecutor(threadName: String) {
    private val queue = ArrayBlockingQueue<Runnable>(1)

    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        queue,
        { r -> Thread(r, threadName).apply { priority = Thread.NORM_PRIORITY } },
        RejectedExecutionHandler { r, ex ->
            // Queue is full: drop the oldest queued task (if any) and try to enqueue the latest.
            ex.queue.poll()
            ex.execute(r)
        }
    ).apply {
        prestartAllCoreThreads()
    }

    fun executeLatest(task: Runnable) {
        executor.execute(task)
    }

    fun shutdownNow() {
        executor.shutdownNow()
    }

    internal fun queuedCountForTests(): Int = queue.size
}

