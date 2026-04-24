package io.github.jimmy.ztlink.app.ui.components.moons

enum class MoonSourceType {
    FILE,
    ORBIT,
}

enum class MoonCacheState {
    CACHED,
    WAITING_FETCH,
}

data class MoonListItem(
    val moonWorldId: Long,
    val moonSeed: Long,
    val sourceType: MoonSourceType,
    val cacheState: MoonCacheState,
) {
    val moonWorldIdHex: String
        get() = moonWorldId.toHexString()

    val moonSeedHex: String
        get() = moonSeed.toHexString()

    val canDeleteCache: Boolean
        get() = sourceType == MoonSourceType.ORBIT && cacheState == MoonCacheState.CACHED
}

data class MoonSummary(
    val totalCount: Int = 0,
    val fromFileCount: Int = 0,
    val fromOrbitCount: Int = 0,
    val cachedCount: Int = 0,
    val waitingFetchCount: Int = 0,
)

fun List<MoonListItem>.toMoonSummary(): MoonSummary {
    if (isEmpty()) {
        return MoonSummary()
    }
    return MoonSummary(
        totalCount = size,
        fromFileCount = count { it.sourceType == MoonSourceType.FILE },
        fromOrbitCount = count { it.sourceType == MoonSourceType.ORBIT },
        cachedCount = count { it.cacheState == MoonCacheState.CACHED },
        waitingFetchCount = count { it.cacheState == MoonCacheState.WAITING_FETCH },
    )
}

private fun Long.toHexString(): String {
    return java.lang.Long.toUnsignedString(this, 16)
        .padStart(10, '0')
}

