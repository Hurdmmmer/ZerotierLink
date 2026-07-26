package io.github.jimmy.ztlink.service.observer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkChangeObserverTest {

    @Test
    fun `new owner invalidates previous owner and receives previous resource`() {
        val ownership = ObservationOwnership<Any>()
        val firstResource = Any()
        val secondResource = Any()

        val firstLease = ownership.acquire(firstResource).first
        val (secondLease, replacedResource) = ownership.acquire(secondResource)

        assertSame(firstResource, replacedResource)
        assertFalse(ownership.isActive(firstLease))
        assertTrue(ownership.isActive(secondLease))
    }

    @Test
    fun `stale owner cannot release active resource`() {
        val ownership = ObservationOwnership<Any>()
        val firstLease = ownership.acquire(Any()).first
        val secondResource = Any()
        val secondLease = ownership.acquire(secondResource).first

        assertNull(ownership.release(firstLease))
        assertTrue(ownership.isActive(secondLease))
        assertSame(secondResource, ownership.release(secondLease))
        assertFalse(ownership.isActive(secondLease))
    }

    @Test
    fun `same transport with different network identity dispatches change`() {
        val tracker = UnderlyingNetworkTransitionTracker()
        tracker.reset(
            UnderlyingNetworkIdentity(
                handle = 101L,
                transport = NetworkTransport.WIFI,
            ),
        )

        val event = tracker.update(
            UnderlyingNetworkIdentity(
                handle = 202L,
                transport = NetworkTransport.WIFI,
            ),
        )

        assertEquals("wifi_network_changed", event?.reason)
        assertEquals(NetworkTransport.WIFI, event?.from)
        assertEquals(NetworkTransport.WIFI, event?.to)
    }

    @Test
    fun `same network identity does not dispatch duplicate event`() {
        val tracker = UnderlyingNetworkTransitionTracker()
        val identity = UnderlyingNetworkIdentity(
            handle = 101L,
            transport = NetworkTransport.WIFI,
        )
        tracker.reset(identity)

        assertNull(tracker.update(identity))
    }

    @Test
    fun `wifi replaces still available cellular candidate`() {
        val cellular = UnderlyingNetworkIdentity(
            handle = 301L,
            transport = NetworkTransport.CELLULAR,
        )
        val wifi = UnderlyingNetworkIdentity(
            handle = 101L,
            transport = NetworkTransport.WIFI,
        )

        val selected = selectPreferredUnderlyingNetwork(
            candidates = listOf(cellular, wifi),
            identityOf = { it },
        )

        assertEquals(wifi, selected)
    }

    @Test
    fun `same transport candidates use stable handle ordering`() {
        val olderWifi = UnderlyingNetworkIdentity(
            handle = 101L,
            transport = NetworkTransport.WIFI,
        )
        val newerWifi = UnderlyingNetworkIdentity(
            handle = 202L,
            transport = NetworkTransport.WIFI,
        )

        val selected = selectPreferredUnderlyingNetwork(
            candidates = listOf(newerWifi, olderWifi),
            identityOf = { it },
        )

        assertEquals(newerWifi, selected)
    }

    @Test
    fun `no network transition is silent and next network is dispatched`() {
        val tracker = UnderlyingNetworkTransitionTracker()
        tracker.reset(
            UnderlyingNetworkIdentity(
                handle = 101L,
                transport = NetworkTransport.WIFI,
            ),
        )

        assertNull(tracker.update(null))
        val event = tracker.update(
            UnderlyingNetworkIdentity(
                handle = 303L,
                transport = NetworkTransport.CELLULAR,
            ),
        )

        assertEquals("none_to_cellular", event?.reason)
        assertEquals(NetworkTransport.NONE, event?.from)
        assertEquals(NetworkTransport.CELLULAR, event?.to)
    }
}
