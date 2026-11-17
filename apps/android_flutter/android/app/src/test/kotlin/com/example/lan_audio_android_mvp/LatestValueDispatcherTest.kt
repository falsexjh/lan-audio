package com.example.lan_audio_android_mvp

import org.junit.Assert.*
import org.junit.Test

class LatestValueDispatcherTest {
    private class Scheduler {
        var now = 0L
        val jobs = linkedMapOf<Runnable, Long>()
        var maximumPending = 0
        fun post(task: Runnable, delay: Long) {
            jobs[task] = now + delay
            maximumPending = maxOf(maximumPending, jobs.size)
        }
        fun advance(to: Long) {
            while (true) {
                val next = jobs.entries.minByOrNull { it.value } ?: break
                if (next.value > to) break
                now = next.value
                jobs.remove(next.key)
                next.key.run()
            }
            now = to
        }
        fun dispatcher(urgent: (Int, Int) -> Boolean = { _, _ -> false }) = LatestValueDispatcher(
            initial = 0, intervalMs = 250L, clockMs = { now },
            post = ::post, cancel = { jobs.remove(it); Unit }, urgent = urgent,
        )
    }

    @Test fun detachedTelemetryDoesNotScheduleAnyWork() {
        val scheduler = Scheduler()
        val dispatcher = scheduler.dispatcher()
        repeat(10000) { dispatcher.publish(it) }
        assertTrue(scheduler.jobs.isEmpty())
        assertEquals(9999, dispatcher.current())
    }

    @Test fun burstSerializesOnlyTheNewestValueOnce() {
        val scheduler = Scheduler()
        val dispatcher = scheduler.dispatcher()
        val received = mutableListOf<Int>()
        dispatcher.attach { received.add(it) }
        repeat(1000) { dispatcher.publish(it) }
        assertEquals(1, scheduler.jobs.size)
        scheduler.advance(0)
        assertEquals(listOf(999), received)
    }

    @Test fun continuous200HzInputIsBoundedToFourTelemetryCallbacksPerSecond() {
        val scheduler = Scheduler()
        val dispatcher = scheduler.dispatcher()
        val received = mutableListOf<Int>()
        dispatcher.attach { received.add(it) }
        scheduler.advance(0)
        for (i in 1..6000) {
            scheduler.advance(i * 5L)
            dispatcher.publish(i)
        }
        scheduler.advance(30250)
        assertEquals(122, received.size) // initial + 120 intervals + trailing newest value
        assertEquals(6000, received.last())
        assertEquals(1, scheduler.maximumPending)
    }

    @Test fun stateChangePreemptsTheTelemetryDeadline() {
        val scheduler = Scheduler()
        val dispatcher = scheduler.dispatcher { _, next -> next < 0 }
        val received = mutableListOf<Int>()
        dispatcher.attach { received.add(it) }
        scheduler.advance(0)
        scheduler.advance(10)
        dispatcher.publish(1)
        dispatcher.publish(-1)
        scheduler.advance(10)
        assertEquals(listOf(0, -1), received)
        assertTrue(scheduler.jobs.isEmpty())
    }

    @Test fun detachAndReattachDiscardStaleQueuedWorkAndReplayCurrentState() {
        val scheduler = Scheduler()
        val dispatcher = scheduler.dispatcher()
        val old = mutableListOf<Int>()
        val current = mutableListOf<Int>()
        dispatcher.attach { old.add(it) }
        val stale = scheduler.jobs.keys.single()
        dispatcher.detach()
        dispatcher.publish(42)
        dispatcher.attach { current.add(it) }
        stale.run() // cancellation may race a dequeued platform callback
        scheduler.advance(0)
        assertTrue(old.isEmpty())
        assertEquals(listOf(42), current)
    }

    @Test fun listenerCanPublishTheNextValue() {
        val scheduler = Scheduler()
        val dispatcher = scheduler.dispatcher()
        val received = mutableListOf<Int>()
        dispatcher.attach { value ->
            received.add(value)
            if (value == 0) dispatcher.publish(1)
        }
        scheduler.advance(250)
        assertEquals(listOf(0, 1), received)
    }

    @Test fun diagnosticGateHandlesInitialSampleResetAndClockRollback() {
        val gate = IntervalGate(250)
        assertTrue(gate.shouldRun(0))
        assertFalse(gate.shouldRun(249))
        assertTrue(gate.shouldRun(250))
        assertTrue(gate.shouldRun(10))
        gate.reset()
        assertTrue(gate.shouldRun(10))
    }
}
