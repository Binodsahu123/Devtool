package com.swift.browser.extensionengine

/**
 * Canonical state groups used by every MV3 lifecycle owner.
 *
 * Keeping these groups in one place prevents subtle drift where one coordinator considers a
 * worker startable/running while another coordinator considers the same state terminal.
 */
internal object ServiceWorkerLifecycleState {
    val running = setOf(
        ServiceWorkerState.RUNNING,
        ServiceWorkerState.IDLE,
        ServiceWorkerState.ACTIVE,
        ServiceWorkerState.EVENT
    )

    val startable = setOf(
        ServiceWorkerState.REGISTERED,
        ServiceWorkerState.STOPPED,
        ServiceWorkerState.SUSPENDED,
        ServiceWorkerState.CRASHED,
        ServiceWorkerState.RESTART_PENDING,
        ServiceWorkerState.RESTARTING,
        ServiceWorkerState.DORMANT,
        ServiceWorkerState.SUSPEND
    )

    val terminal = setOf(
        ServiceWorkerState.DISABLED,
        ServiceWorkerState.UNINSTALLED,
        ServiceWorkerState.FAILED
    )

    val crashOrRestarting = setOf(
        ServiceWorkerState.CRASHED,
        ServiceWorkerState.RESTART_PENDING,
        ServiceWorkerState.RESTARTING,
        ServiceWorkerState.FAILED
    )

    val stopped = setOf(
        ServiceWorkerState.STOPPED,
        ServiceWorkerState.SUSPENDED
    )
}

internal fun ServiceWorkerState.isRunningLike(): Boolean =
    this in ServiceWorkerLifecycleState.running

internal fun ServiceWorkerState.isStartable(): Boolean =
    this in ServiceWorkerLifecycleState.startable

internal fun ServiceWorkerState.isTerminalLifecycleState(): Boolean =
    this in ServiceWorkerLifecycleState.terminal

internal fun ServiceWorkerState.isCrashOrRestarting(): Boolean =
    this in ServiceWorkerLifecycleState.crashOrRestarting

internal fun ServiceWorkerState.isStoppedLike(): Boolean =
    this in ServiceWorkerLifecycleState.stopped
