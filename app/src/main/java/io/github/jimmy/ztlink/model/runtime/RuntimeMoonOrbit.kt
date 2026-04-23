package io.github.jimmy.ztlink.model.runtime

/**
 * Moon 入轨参数。
 *
 * @property moonWorldId Moon 世界 ID。
 * @property moonSeed Moon 种子值。
 */
data class RuntimeMoonOrbit(
    val moonWorldId: Long,
    val moonSeed: Long,
)
