package io.github.jimmy.ztlink.app.ui.components.moons

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jimmy.ztlink.R
import io.github.jimmy.ztlink.app.ui.components.common.CommonUiEvent
import io.github.jimmy.ztlink.data.network.local.MoonOrbitDao
import io.github.jimmy.ztlink.data.network.local.MoonOrbitDbEntity
import io.github.jimmy.ztlink.model.runtime.RuntimeMoonOrbit
import io.github.jimmy.ztlink.model.service.ServiceStateType
import io.github.jimmy.ztlink.service.ServiceAction
import io.github.jimmy.ztlink.service.ServiceActionDispatcher
import io.github.jimmy.ztlink.service.ServiceStateStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MoonsUiState(
    val moons: List<MoonListItem> = emptyList(),
    val summary: MoonSummary = MoonSummary(),
    val activeNetworkId: String? = null,
    val serviceStateType: ServiceStateType = ServiceStateType.STOPPED,
    val isLoading: Boolean = true,
    val isOperating: Boolean = false,
)

sealed interface MoonsUiEvent : CommonUiEvent {
    data class ShowToast(
        override val messageRes: Int,
    ) : MoonsUiEvent, CommonUiEvent.ShowToast

    data class CopyMoonWorldId(
        override val text: String,
        override val successMsgRes: Int,
        override val label: String = "moon_world_id",
    ) : MoonsUiEvent, CommonUiEvent.CopyToClipboard
}

@HiltViewModel
class MoonsViewModel @Inject constructor(
    private val actionDispatcher: ServiceActionDispatcher,
    private val serviceStateStore: ServiceStateStore,
    private val moonOrbitDao: MoonOrbitDao,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState: MutableStateFlow<MoonsUiState> = MutableStateFlow(MoonsUiState())
    val uiState: StateFlow<MoonsUiState> = _uiState.asStateFlow()

    private val _uiEvents: MutableSharedFlow<MoonsUiEvent> = MutableSharedFlow(extraBufferCapacity = 32)
    val uiEvents: SharedFlow<MoonsUiEvent> = _uiEvents.asSharedFlow()

    init {
        observeServiceState()
        loadMoons()
    }

    fun refreshMoons() {
        loadMoons()
    }

    fun addMoonByOrbit(
        moonWorldIdInput: String,
        moonSeedInput: String,
    ) {
        val moonWorldId = parseMoonHex(moonWorldIdInput)
        if (moonWorldId == null) {
            emitToast(R.string.moon_world_id_wrong_format)
            return
        }
        val moonSeed = parseMoonHex(moonSeedInput)
        if (moonSeed == null) {
            emitToast(R.string.moon_seed_wrong_format)
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isOperating = true) }
            try {
                val exists = withContext(Dispatchers.IO) {
                    moonOrbitDao.findByMoonWorldId(moonWorldId) != null
                }
                if (exists) {
                    emitToast(R.string.moon_orbit_exist)
                    return@launch
                }

                val now = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    moonOrbitDao.upsert(
                        MoonOrbitDbEntity(
                            moonWorldId = moonWorldId,
                            moonSeed = moonSeed,
                            fromFile = false,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                }

                tryOrbitWhenConnected(
                    moons = listOf(
                        RuntimeMoonOrbit(
                            moonWorldId = moonWorldId,
                            moonSeed = moonSeed,
                        ),
                    ),
                    reason = "ui_moons_add_orbit",
                )
                emitToast(R.string.moon_orbit_add_success)
                loadMoons(showLoading = false)
            } catch (_: Throwable) {
                emitToast(R.string.network_action_failed)
            } finally {
                _uiState.update { it.copy(isOperating = false) }
            }
        }
    }

    fun importMoonFromUri(sourceUri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isOperating = true) }
            try {
                val result = withContext(Dispatchers.IO) {
                    importMoonFromUriInternal(sourceUri)
                }
                when (result) {
                    is ImportMoonResult.Success -> {
                        tryOrbitWhenConnected(
                            moons = listOf(
                                RuntimeMoonOrbit(
                                    moonWorldId = result.moonWorldId,
                                    moonSeed = result.moonSeed,
                                ),
                            ),
                            reason = "ui_moons_import_file",
                        )
                        emitToast(R.string.moon_orbit_add_success)
                        loadMoons(showLoading = false)
                    }

                    ImportMoonResult.Duplicate -> emitToast(R.string.moon_orbit_exist)
                    ImportMoonResult.CannotReadSource -> emitToast(R.string.cannot_open_moon)
                    ImportMoonResult.InvalidFormat -> emitToast(R.string.moon_wrong_file_format)
                    ImportMoonResult.WriteFailed -> emitToast(R.string.cannot_open_moon)
                }
            } catch (_: Throwable) {
                emitToast(R.string.cannot_open_moon)
            } finally {
                _uiState.update { it.copy(isOperating = false) }
            }
        }
    }

    fun deleteMoonOrbit(moonWorldId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isOperating = true) }
            try {
                withContext(Dispatchers.IO) {
                    moonOrbitDao.deleteByMoonWorldId(moonWorldId)
                    moonCacheFile(moonWorldId).delete()
                }
                emitToast(R.string.moon_orbit_delete_success)
                loadMoons(showLoading = false)
            } catch (_: Throwable) {
                emitToast(R.string.network_action_failed)
            } finally {
                _uiState.update { it.copy(isOperating = false) }
            }
        }
    }

    fun deleteMoonCache(moonWorldId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isOperating = true) }
            try {
                withContext(Dispatchers.IO) {
                    moonCacheFile(moonWorldId).delete()
                }
                emitToast(R.string.cached_moon_file_delete)
                loadMoons(showLoading = false)
            } catch (_: Throwable) {
                emitToast(R.string.network_action_failed)
            } finally {
                _uiState.update { it.copy(isOperating = false) }
            }
        }
    }

    fun copyMoonWorldId(moonWorldId: Long) {
        _uiEvents.tryEmit(
            MoonsUiEvent.CopyMoonWorldId(
                text = moonWorldId.toMoonHexString(),
                successMsgRes = R.string.moon_copy_world_id_success,
            ),
        )
    }

    private fun observeServiceState() {
        viewModelScope.launch {
            serviceStateStore.state.collectLatest { state ->
                val activeNetworkId = when (state.type) {
                    ServiceStateType.STARTING,
                    ServiceStateType.CONNECTING,
                    ServiceStateType.CONNECTED,
                    ServiceStateType.MONITOR_ONLY,
                    -> state.networkId?.value

                    ServiceStateType.STOPPED,
                    ServiceStateType.STOPPING,
                    ServiceStateType.ERROR,
                    -> null
                }
                _uiState.update { old ->
                    old.copy(
                        activeNetworkId = activeNetworkId,
                        serviceStateType = state.type,
                    )
                }
            }
        }
    }

    private fun loadMoons(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) {
                _uiState.update { it.copy(isLoading = true) }
            }
            val items = withContext(Dispatchers.IO) {
                moonOrbitDao.listAll().map { entity ->
                    MoonListItem(
                        moonWorldId = entity.moonWorldId,
                        moonSeed = entity.moonSeed,
                        sourceType = if (entity.fromFile) MoonSourceType.FILE else MoonSourceType.ORBIT,
                        cacheState = if (moonCacheFile(entity.moonWorldId).exists()) {
                            MoonCacheState.CACHED
                        } else {
                            MoonCacheState.WAITING_FETCH
                        },
                    )
                }
            }
            _uiState.update { old ->
                old.copy(
                    moons = items,
                    summary = items.toMoonSummary(),
                    isLoading = false,
                )
            }
        }
    }

    private suspend fun tryOrbitWhenConnected(
        moons: List<RuntimeMoonOrbit>,
        reason: String,
    ) {
        val stateType = _uiState.value.serviceStateType
        val canOrbitNow = stateType == ServiceStateType.CONNECTED || stateType == ServiceStateType.MONITOR_ONLY
        if (!canOrbitNow || moons.isEmpty()) {
            return
        }
        runCatching {
            actionDispatcher.dispatch(
                ServiceAction.OrbitMoons(
                    moons = moons,
                    reason = reason,
                ),
            )
        }
    }

    private fun importMoonFromUriInternal(sourceUri: Uri): ImportMoonResult {
        val tempFile = File(appContext.cacheDir, TEMP_MOON_FILE_NAME)
        return try {
            val copied = appContext.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(tempFile, false).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
                true
            } ?: false
            if (!copied) {
                return ImportMoonResult.CannotReadSource
            }
            val moonWorldId = parseMoonWorldIdFromFile(tempFile)
                ?: return ImportMoonResult.InvalidFormat
            val duplicate = moonOrbitDao.findByMoonWorldId(moonWorldId) != null
            if (duplicate) {
                return ImportMoonResult.Duplicate
            }

            val moonDir = File(appContext.filesDir, MOON_DIR_NAME)
            if (!moonDir.exists() && !moonDir.mkdirs()) {
                return ImportMoonResult.WriteFailed
            }
            val moonFile = moonCacheFile(moonWorldId)
            FileInputStream(tempFile).use { input ->
                FileOutputStream(moonFile, false).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }

            val now = System.currentTimeMillis()
            moonOrbitDao.upsert(
                MoonOrbitDbEntity(
                    moonWorldId = moonWorldId,
                    moonSeed = moonWorldId,
                    fromFile = true,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            ImportMoonResult.Success(
                moonWorldId = moonWorldId,
                moonSeed = moonWorldId,
            )
        } catch (_: Throwable) {
            ImportMoonResult.WriteFailed
        } finally {
            tempFile.delete()
        }
    }

    private fun moonCacheFile(moonWorldId: Long): File {
        return File(
            appContext.filesDir,
            MoonOrbitDbEntity.MOON_FILE_PATH_FORMAT.format(moonWorldId),
        )
    }

    private fun parseMoonWorldIdFromFile(file: File): Long? {
        if (!file.exists() || file.length() < MOON_FILE_HEADER_AND_WORLD_ID_BYTES) {
            return null
        }
        return try {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(MOON_FILE_HEADER_AND_WORLD_ID_BYTES)
                val readSize = input.read(buffer)
                if (readSize != MOON_FILE_HEADER_AND_WORLD_ID_BYTES) {
                    return null
                }
                if (buffer[0] != MOON_FILE_HEADER_BYTE) {
                    return null
                }
                var moonWorldId = 0L
                for (index in 1 until buffer.size) {
                    moonWorldId = (moonWorldId shl 8) or (buffer[index].toLong() and 0xffL)
                }
                moonWorldId.takeIf { it in 0..MAX_MOON_HEX_VALUE }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseMoonHex(input: String): Long? {
        val normalized = input.trim()
            .removePrefix("0x")
            .removePrefix("0X")
        if (normalized.isBlank()) {
            return null
        }
        return runCatching { normalized.toLong(radix = 16) }
            .getOrNull()
            ?.takeIf { it in 0..MAX_MOON_HEX_VALUE }
    }

    private fun emitToast(@StringRes messageRes: Int) {
        _uiEvents.tryEmit(MoonsUiEvent.ShowToast(messageRes))
    }

    private sealed interface ImportMoonResult {
        data class Success(
            val moonWorldId: Long,
            val moonSeed: Long,
        ) : ImportMoonResult

        data object Duplicate : ImportMoonResult

        data object CannotReadSource : ImportMoonResult

        data object InvalidFormat : ImportMoonResult

        data object WriteFailed : ImportMoonResult
    }

    private companion object {
        private const val MAX_MOON_HEX_VALUE: Long = 0xffffffffffL
        private const val MOON_FILE_HEADER_AND_WORLD_ID_BYTES: Int = 9
        private const val MOON_FILE_HEADER_BYTE: Byte = 0x7f
        private const val TEMP_MOON_FILE_NAME: String = "moon-import.tmp"
        private const val MOON_DIR_NAME: String = "moons.d"
    }
}

private fun Long.toMoonHexString(): String {
    return java.lang.Long.toUnsignedString(this, 16).padStart(10, '0')
}
