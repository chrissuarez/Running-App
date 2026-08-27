package com.example.runningapp.foreground

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the teardown refuses, and the one thing it does not (#315).
 *
 * The rule is one line, and it is tested because getting it the other way round is a whole Run's
 * seconds rather than a metre: the drains this gate stands in front of are what a Run lost to a
 * teardown is rebuilt from, and the buffer handed over during a teardown is everything a Run that
 * never got its row ever recorded.
 */
class TeardownGateTest {

    @Test
    fun `a live service gives the run work as it always did`() {
        assertTrue(runMayBeGivenWork(teardownBegun = false))
    }

    @Test
    fun `a teardown refuses new work for the run`() {
        // The point of the gate: a producer still alive after a bounded join, or a GPS looper that
        // was never joined at all, is refused rather than raced. That is what makes an empty scope
        // proof rather than an observation.
        assertFalse(runMayBeGivenWork(teardownBegun = true))
    }

    @Test
    fun `a teardown still lets through the finish already under way`() {
        // The teardown's own delivery of a held buffer. Refusing it would cost a real Run its whole
        // recording to fix a rescue's rounding.
        assertTrue(runMayBeGivenWork(teardownBegun = true, deliveringHeldWork = true))
    }

    @Test
    fun `held work needs no exception while the service is alive`() {
        assertTrue(runMayBeGivenWork(teardownBegun = false, deliveringHeldWork = true))
    }

    // --- The registration is atomic with the transition, not merely checked before it (#315) ---

    @Test
    fun `a producer already registering holds the teardown's transition up`() {
        // The whole of the fix in one assertion: while a producer is inside the gate registering its
        // work, the flag cannot flip underneath it. Without the shared monitor this test would see
        // beginTeardown return while the registration was still in the air — which is exactly the
        // producer that lands its write behind a drain's empty pass.
        val gate = TeardownGate()
        val insideTheGate = CountDownLatch(1)
        val letTheRegistrationFinish = CountDownLatch(1)
        val registered = AtomicBoolean(false)
        val teardownReturned = AtomicBoolean(false)
        val flagSeenByTheProducer = AtomicBoolean(true)

        val producer = Thread {
            gate.registerWorkForTheRun {
                insideTheGate.countDown()
                letTheRegistrationFinish.await(5, TimeUnit.SECONDS)
                // What a launch would have read of the world it registered into.
                flagSeenByTheProducer.set(gate.teardownBegun)
                registered.set(true)
            }
        }
        producer.start()
        assertTrue("the producer never reached the gate", insideTheGate.await(5, TimeUnit.SECONDS))

        val teardown = Thread {
            gate.beginTeardown()
            teardownReturned.set(true)
        }
        teardown.start()
        // Long enough that a gate which only wrote a volatile flag would be done and gone.
        Thread.sleep(200)
        assertFalse("the teardown flipped the flag mid-registration", teardownReturned.get())
        assertFalse("the flag flipped while a producer was registering", gate.teardownBegun)

        letTheRegistrationFinish.countDown()
        producer.join(5_000)
        teardown.join(5_000)
        assertTrue(registered.get())
        assertTrue(teardownReturned.get())
        // The registration ran entirely in the world it was allowed into.
        assertFalse(flagSeenByTheProducer.get())
    }

    @Test
    fun `once the teardown has returned nothing more can be registered`() {
        // The other half: the transition, once made, is total. This is what lets a drain's empty
        // pass be proof — there is no producer left holding an answer from before it.
        val gate = TeardownGate()
        assertTrue(gate.registerWorkForTheRun { })
        gate.beginTeardown()
        assertFalse(gate.registerWorkForTheRun { })
    }

    @Test
    fun `the work the gate refuses is never registered at all`() {
        // A refusal must not run the caller's block: the block is the launch, and a launch that
        // happened anyway would be the very child the drain cannot know about.
        val gate = TeardownGate()
        val launches = AtomicInteger()
        gate.beginTeardown()
        assertFalse(gate.registerWorkForTheRun { launches.incrementAndGet() })
        assertEquals(0, launches.get())
    }

    @Test
    fun `the finish already under way still registers through a shut gate`() {
        // The one exception, carried through the registration and not only through the rule: the
        // teardown handing over a buffer of seconds the Run recorded before any of this began.
        val gate = TeardownGate()
        val launches = AtomicInteger()
        gate.beginTeardown()
        assertTrue(gate.registerWorkForTheRun(deliveringHeldWork = true) { launches.incrementAndGet() })
        assertEquals(1, launches.get())
    }

    @Test
    fun `no work is registered after the teardown has begun, under contention`() {
        // The ticket's claim, stated as an invariant and leaned on: producers hammering the gate
        // from several threads while the teardown transitions. Once beginTeardown has returned,
        // not one further registration may run — a check-then-launch gate fails this by letting a
        // producer that read the open gate run its launch afterwards.
        val gate = TeardownGate()
        val gateShut = AtomicBoolean(false)
        val registeredAfterTheTransition = AtomicInteger()
        val stop = AtomicBoolean(false)
        val producers = (1..4).map {
            Thread {
                while (!stop.get()) {
                    gate.registerWorkForTheRun {
                        if (gateShut.get()) registeredAfterTheTransition.incrementAndGet()
                    }
                }
            }
        }
        producers.forEach { it.start() }
        Thread.sleep(50)
        gate.beginTeardown()
        gateShut.set(true)
        Thread.sleep(50)
        stop.set(true)
        producers.forEach { it.join(5_000) }

        assertEquals(0, registeredAfterTheTransition.get())
    }
}
