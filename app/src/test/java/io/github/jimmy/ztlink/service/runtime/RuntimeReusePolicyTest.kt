package io.github.jimmy.ztlink.service.runtime

import io.github.jimmy.ztlink.model.network.NetworkId
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeReusePolicyTest {

    private val networkA = requireNotNull(NetworkId.parse("0123456789abcdef"))
    private val networkB = requireNotNull(NetworkId.parse("fedcba9876543210"))

    @Test
    fun `reuse healthy runtime for same target`() {
        assertDecision(
            currentNetworkId = networkA,
            targetNetworkId = networkA,
            planetModeMatches = true,
            kernelAlive = true,
            expected = RuntimeReuseDecision.REUSE,
        )
    }

    @Test
    fun `reuse current network when target is absent`() {
        assertDecision(
            currentNetworkId = networkA,
            targetNetworkId = null,
            planetModeMatches = true,
            kernelAlive = true,
            expected = RuntimeReuseDecision.REUSE,
        )
    }

    @Test
    fun `restart when target network changes`() {
        assertDecision(
            currentNetworkId = networkA,
            targetNetworkId = networkB,
            planetModeMatches = true,
            kernelAlive = true,
            expected = RuntimeReuseDecision.RESTART_NETWORK_CHANGED,
        )
    }

    @Test
    fun `restart when old runtime has no bound network but target exists`() {
        assertDecision(
            currentNetworkId = null,
            targetNetworkId = networkA,
            planetModeMatches = true,
            kernelAlive = true,
            expected = RuntimeReuseDecision.RESTART_NETWORK_CHANGED,
        )
    }

    @Test
    fun `restart when planet mode changed or was not recorded`() {
        assertDecision(
            currentNetworkId = networkA,
            targetNetworkId = networkA,
            planetModeMatches = false,
            kernelAlive = true,
            expected = RuntimeReuseDecision.RESTART_PLANET_MODE_CHANGED,
        )
    }

    @Test
    fun `restart when any core worker is unhealthy`() {
        assertDecision(
            currentNetworkId = networkA,
            targetNetworkId = networkA,
            planetModeMatches = true,
            kernelAlive = false,
            expected = RuntimeReuseDecision.RESTART_UNHEALTHY,
        )
    }

    private fun assertDecision(
        currentNetworkId: NetworkId?,
        targetNetworkId: NetworkId?,
        planetModeMatches: Boolean,
        kernelAlive: Boolean,
        expected: RuntimeReuseDecision,
    ) {
        assertEquals(
            expected,
            decideRuntimeReuse(
                currentNetworkId = currentNetworkId,
                targetNetworkId = targetNetworkId,
                planetModeMatches = planetModeMatches,
                kernelAlive = kernelAlive,
            ),
        )
    }
}
