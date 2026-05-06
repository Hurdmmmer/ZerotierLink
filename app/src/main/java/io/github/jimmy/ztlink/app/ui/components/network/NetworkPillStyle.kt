package io.github.jimmy.ztlink.app.ui.components.network

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.github.jimmy.ztlink.app.ui.theme.ZtSemanticColors

internal enum class NetworkPillSemantic {
    SUCCESS,
    INFRA,
    RELAY,
    WARNING,
    OFFLINE,
    DANGER,
}

internal data class NetworkPillColors(
    val tone: Color,
    val container: Color,
    val border: Color,
)

private const val LIGHT_CONTAINER_ALPHA = 0.14f
private const val DARK_CONTAINER_ALPHA = 0.24f
private const val LIGHT_BORDER_ALPHA = 0.32f
private const val DARK_BORDER_ALPHA = 0.44f

internal fun resolveNetworkPillColors(
    semantic: NetworkPillSemantic,
    semanticColors: ZtSemanticColors,
    colorScheme: androidx.compose.material3.ColorScheme,
    isDarkSurface: Boolean,
): NetworkPillColors {
    val tone = when (semantic) {
        NetworkPillSemantic.SUCCESS -> semanticColors.connected
        NetworkPillSemantic.INFRA -> semanticColors.root
        NetworkPillSemantic.RELAY -> semanticColors.relay
        NetworkPillSemantic.WARNING -> lerp(
            colorScheme.tertiary,
            colorScheme.error,
            if (isDarkSurface) 0.12f else 0.08f,
        )
        NetworkPillSemantic.OFFLINE -> semanticColors.inactive
        NetworkPillSemantic.DANGER -> semanticColors.errorStrong
    }
    val containerAlpha = if (isDarkSurface) DARK_CONTAINER_ALPHA else LIGHT_CONTAINER_ALPHA
    val borderAlpha = if (isDarkSurface) DARK_BORDER_ALPHA else LIGHT_BORDER_ALPHA
    return NetworkPillColors(
        tone = tone,
        container = tone.copy(alpha = containerAlpha),
        border = tone.copy(alpha = borderAlpha),
    )
}

internal fun NetworkStatus.toPillSemantic(): NetworkPillSemantic = when (this) {
    NetworkStatus.CONNECTED -> NetworkPillSemantic.SUCCESS
    NetworkStatus.MONITORING -> NetworkPillSemantic.INFRA
    NetworkStatus.REQUESTING_CONFIGURATION -> NetworkPillSemantic.INFRA
    NetworkStatus.AUTHENTICATION_REQUIRED -> NetworkPillSemantic.WARNING
    NetworkStatus.NO_CONNECTION -> NetworkPillSemantic.WARNING
    NetworkStatus.DISCONNECTED -> NetworkPillSemantic.OFFLINE
    NetworkStatus.ACCESS_DENIED,
    NetworkStatus.NOT_FOUND,
        -> NetworkPillSemantic.DANGER
}

internal fun NetworkStatus.isPendingStatus(): Boolean {
    return this == NetworkStatus.REQUESTING_CONFIGURATION ||
        this == NetworkStatus.AUTHENTICATION_REQUIRED
}

internal fun PlanetRouteType.toPillSemantic(): NetworkPillSemantic {
    return when (this) {
        PlanetRouteType.OFFICIAL -> NetworkPillSemantic.INFRA
        PlanetRouteType.NON_OFFICIAL -> NetworkPillSemantic.RELAY
    }
}
