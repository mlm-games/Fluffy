package app.fluffy.shell

import app.fluffy.data.repository.SettingsRepository
import app.fluffy.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

class RemountApprover(private val settings: SettingsRepository) {

    data class Approval(val granted: Boolean, val remember: Boolean)

    data class Request(
        val mount: String,
        private val result: CompletableDeferred<Boolean>,
    ) {
        internal var remember: Boolean = false

        internal fun resolve(granted: Boolean) {
            result.complete(granted)
        }
    }

    private val mutex = Mutex()
    private val uiActive = AtomicBoolean(false)
    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request

    fun setUiActive(active: Boolean) {
        uiActive.set(active)
    }

    suspend fun approve(mount: String): Approval = mutex.withLock {
        if (settings.settingsFlow.first().remountPromptDone) return Approval(true, false)
        if (!uiActive.get()) return Approval(false, false)
        val deferred = CompletableDeferred<Boolean>()
        val request = Request(mount, deferred)
        _request.value = request
        val granted = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { deferred.await() } ?: false
        val remember = request.remember
        if (_request.value === request) _request.value = null
        Approval(granted, remember && granted)
    }

    suspend fun respond(request: Request, granted: Boolean, remember: Boolean) {
        request.remember = remember && granted
        request.resolve(granted)
        if (_request.value === request) _request.value = null
    }

    suspend fun remember() {
        runCatching {
            settings.updateSettings { it.copy(remountPromptDone = true) }
        }.onFailure { AppLog.w("RemountApprover", "Could not remember choice", it) }
    }

    private companion object {
        const val APPROVAL_TIMEOUT_MS = 60_000L
    }
}
