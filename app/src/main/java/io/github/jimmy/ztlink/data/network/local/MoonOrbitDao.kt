package io.github.jimmy.ztlink.data.network.local

interface MoonOrbitDao {

    fun upsert(entity: MoonOrbitDbEntity)

    fun findByMoonWorldId(moonWorldId: Long): MoonOrbitDbEntity?

    fun listAll(): List<MoonOrbitDbEntity>

    fun deleteByMoonWorldId(moonWorldId: Long)
}

