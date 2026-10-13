package com.swift.browser.extensionv8

import java.util.concurrent.ConcurrentHashMap

class V8IsolateManager {
    private val activeIsolates = ConcurrentHashMap<String, Long>()

    fun registerIsolate(extensionId: String, isolateAddress: Long) {
        activeIsolates[extensionId] = isolateAddress
    }

    fun unregisterIsolate(extensionId: String): Long? {
        return activeIsolates.remove(extensionId)
    }

    fun getIsolate(extensionId: String): Long? {
        return activeIsolates[extensionId]
    }

    fun hasIsolate(extensionId: String): Boolean {
        return activeIsolates.containsKey(extensionId)
    }

    fun clearAll() {
        activeIsolates.clear()
    }

    fun getActiveIsolateCount(): Int = activeIsolates.size
}
