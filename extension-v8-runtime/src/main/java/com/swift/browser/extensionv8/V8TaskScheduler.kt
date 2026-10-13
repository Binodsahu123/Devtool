package com.swift.browser.extensionv8

import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class V8TaskScheduler {
    private val workerThreads = ConcurrentHashMap<String, HandlerThread>()
    private val workerHandlers = ConcurrentHashMap<String, Handler>()
    private val nextTimerId = AtomicInteger(1)
    private val activeTimers = ConcurrentHashMap<String, MutableMap<Int, Runnable>>()

    fun getOrCreateHandler(extensionId: String): Handler {
        return workerHandlers.getOrPut(extensionId) {
            val thread = HandlerThread("V8WorkerThread-$extensionId").apply { start() }
            workerThreads[extensionId] = thread
            Handler(thread.looper)
        }
    }

    fun postTask(extensionId: String, task: Runnable) {
        getOrCreateHandler(extensionId).post(task)
    }

    fun scheduleTimeout(extensionId: String, delayMs: Long, callback: Runnable): Int {
        val timerId = nextTimerId.getAndIncrement()
        val handler = getOrCreateHandler(extensionId)
        val timerMap = activeTimers.getOrPut(extensionId) { ConcurrentHashMap() }

        val runnable = Runnable {
            timerMap.remove(timerId)
            callback.run()
        }
        timerMap[timerId] = runnable
        handler.postDelayed(runnable, delayMs)
        return timerId
    }

    fun scheduleInterval(extensionId: String, periodMs: Long, callback: Runnable): Int {
        val timerId = nextTimerId.getAndIncrement()
        val handler = getOrCreateHandler(extensionId)
        val timerMap = activeTimers.getOrPut(extensionId) { ConcurrentHashMap() }

        val runnable = object : Runnable {
            override fun run() {
                callback.run()
                if (timerMap.containsKey(timerId)) {
                    handler.postDelayed(this, periodMs)
                }
            }
        }
        timerMap[timerId] = runnable
        handler.postDelayed(runnable, periodMs)
        return timerId
    }

    fun clearInterval(extensionId: String, timerId: Int) {
        val timerMap = activeTimers[extensionId] ?: return
        val runnable = timerMap.remove(timerId)
        if (runnable != null) {
            workerHandlers[extensionId]?.removeCallbacks(runnable)
        }
    }

    fun stopWorkerScheduler(extensionId: String) {
        val timerMap = activeTimers.remove(extensionId)
        val handler = workerHandlers.remove(extensionId)
        if (timerMap != null && handler != null) {
            for (runnable in timerMap.values) {
                handler.removeCallbacks(runnable)
            }
        }
        val thread = workerThreads.remove(extensionId)
        thread?.quitSafely()
    }

    fun stopAll() {
        val keys = ArrayList(workerHandlers.keys)
        for (key in keys) {
            stopWorkerScheduler(key)
        }
    }
}
