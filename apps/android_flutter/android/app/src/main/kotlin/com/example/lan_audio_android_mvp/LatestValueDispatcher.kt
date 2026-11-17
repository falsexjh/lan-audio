package com.example.lan_audio_android_mvp

/** Coalesces telemetry before serialization, with at most one queued callback. */
internal class LatestValueDispatcher<T : Any>(
    initial: T,
    private val intervalMs: Long,
    private val clockMs: () -> Long,
    private val post: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val urgent: (T, T) -> Boolean = { _, _ -> false },
) {
    private val lock = Any()
    private var latest = initial
    private var listener: ((T) -> Unit)? = null
    private var pending: Runnable? = null
    private var deliveredAt: Long? = null

    fun current(): T = synchronized(lock) { latest }
    fun attach(callback: (T) -> Unit) = synchronized(lock) {
        pending?.let(cancel)
        pending = null
        listener = callback
        deliveredAt = null
        schedule(0)
    }
    fun detach() = synchronized(lock) {
        listener = null
        pending?.let(cancel)
        pending = null
        deliveredAt = null
    }
    fun publish(value: T) = synchronized(lock) {
        val immediate = urgent(latest, value)
        latest = value
        if (listener == null) return@synchronized
        if (pending != null) {
            if (!immediate) return@synchronized
            cancel(pending!!)
            pending = null
        }
        val elapsed = deliveredAt?.let { (clockMs() - it).coerceAtLeast(0) }
        schedule(if (immediate || elapsed == null) 0 else (intervalMs - elapsed).coerceAtLeast(0))
    }
    private fun schedule(delayMs: Long) {
        val task = object : Runnable {
            override fun run() {
                val delivery = synchronized(lock) {
                    if (pending !== this) return
                    pending = null
                    val callback = listener ?: return
                    deliveredAt = clockMs()
                    callback to latest
                }
                delivery.first(delivery.second)
            }
        }
        pending = task
        post(task, delayMs)
    }
}

internal class IntervalGate(private val intervalMs: Long) {
    private var lastRunAt: Long? = null
    fun shouldRun(nowMs: Long): Boolean {
        val previous = lastRunAt
        if (previous != null && nowMs >= previous && nowMs - previous < intervalMs) return false
        lastRunAt = nowMs
        return true
    }
    fun reset() { lastRunAt = null }
}
