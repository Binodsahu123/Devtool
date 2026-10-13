package com.swift.browser.extensionv8

import java.util.ArrayDeque

class V8EventLoop(private val scheduler: V8TaskScheduler) {
    private val microtaskQueue = ArrayDeque<Runnable>()

    fun queueMicrotask(extensionId: String, task: Runnable) {
        synchronized(microtaskQueue) {
            microtaskQueue.addLast(task)
        }
        scheduler.postTask(extensionId) {
            drainMicrotasks()
        }
    }

    fun drainMicrotasks() {
        while (true) {
            val task = synchronized(microtaskQueue) {
                if (microtaskQueue.isEmpty()) null else microtaskQueue.removeFirst()
            } ?: break
            try {
                task.run()
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }
}
