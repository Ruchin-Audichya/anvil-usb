package dev.ruchin.anvil.app.ui.windows

import android.content.Context
import android.net.ConnectivityManager
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ruchin.anvil.app.jobs.JobManager
import dev.ruchin.anvil.catalog.windows.MicrosoftIsoResolver
import dev.ruchin.anvil.catalog.windows.WindowsCatalogSession
import dev.ruchin.anvil.catalog.windows.WindowsDownloadLink
import dev.ruchin.anvil.catalog.windows.WindowsLanguage
import dev.ruchin.anvil.catalog.windows.WindowsProduct
import dev.ruchin.anvil.common.AnvilException
import dev.ruchin.anvil.common.DebugLog
import dev.ruchin.anvil.download.ResumableDownloader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import javax.inject.Inject

data class WindowsUiState(
    val product: WindowsProduct = WindowsProduct.WINDOWS_11_X64,
    val loading: Boolean = false,
    val session: WindowsCatalogSession? = null,
    val language: WindowsLanguage? = null,
    val resolving: Boolean = false,
    val link: WindowsDownloadLink? = null,
    val sizeBytes: Long? = null,
    val freeBytes: Long = 0,
    val metered: Boolean = false,
    val alreadyDownloaded: File? = null,
    val error: String? = null,
    val confirmMeteredDownload: Boolean = false,
) {
    val enoughSpace get() = sizeBytes == null || freeBytes > sizeBytes + 200L * 1024 * 1024
}

@HiltViewModel
class WindowsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolver: MicrosoftIsoResolver,
    private val client: OkHttpClient,
    val jobs: JobManager,
) : ViewModel() {
    private val _ui = MutableStateFlow(WindowsUiState())
    val ui: StateFlow<WindowsUiState> = _ui.asStateFlow()
    private var loadJob: Job? = null

    private val isoDir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "isos").apply { mkdirs() }

    init { selectProduct(WindowsProduct.WINDOWS_11_X64) }

    fun selectProduct(product: WindowsProduct) {
        loadJob?.cancel()
        _ui.update { WindowsUiState(product = product, loading = true) }
        loadJob = viewModelScope.launch {
            try {
                val session = resolver.openSession(product)
                val deviceLanguage = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH)
                val lang = session.pickLanguage(deviceLanguage)
                _ui.update { it.copy(loading = false, session = session, language = lang) }
            } catch (e: AnvilException) {
                _ui.update { it.copy(loading = false, error = e.userMessage) }
            } catch (e: Exception) {
                DebugLog.e(TAG, "openSession failed", e)
                _ui.update { it.copy(loading = false, error = "Couldn't reach Microsoft: ${e.message}") }
            }
        }
    }

    fun selectLanguage(language: WindowsLanguage) = _ui.update { it.copy(language = language, link = null, sizeBytes = null) }

    /** Resolves the signed link, the size, and runs the pre-flight checks (storage, metered network). */
    fun prepare() {
        val s = _ui.value
        val session = s.session ?: return
        val language = s.language ?: return
        _ui.update { it.copy(resolving = true, error = null) }
        viewModelScope.launch {
            try {
                val link = resolver.resolve(session, language)
                val size = withContext(Dispatchers.IO) { fetchSize(link.url) }
                val free = StatFs(isoDir.path).availableBytes
                val metered = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false
                val existing = File(isoDir, link.fileName).takeIf { it.exists() && (size == null || it.length() == size) }
                _ui.update { it.copy(resolving = false, link = link, sizeBytes = size, freeBytes = free, metered = metered, alreadyDownloaded = existing) }
            } catch (e: AnvilException) {
                _ui.update { it.copy(resolving = false, error = e.userMessage) }
            } catch (e: Exception) {
                DebugLog.e(TAG, "resolve failed", e)
                _ui.update { it.copy(resolving = false, error = "Couldn't get the download link: ${e.message}") }
            }
        }
    }

    fun startDownload(confirmedMetered: Boolean = false) {
        val s = _ui.value
        val link = s.link ?: return
        if (s.metered && !confirmedMetered) {
            _ui.update { it.copy(confirmMeteredDownload = true) }
            return
        }
        _ui.update { it.copy(confirmMeteredDownload = false) }
        jobs.startWindowsDownload(link, File(isoDir, link.fileName))
    }

    fun dismissMetered() = _ui.update { it.copy(confirmMeteredDownload = false) }

    private fun fetchSize(url: String): Long? = runCatching {
        client.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").build()).execute().use { r ->
            ResumableDownloader.parseContentRangeTotal(r.header("Content-Range"))
        }
    }.getOrNull()

    companion object { private const val TAG = "WindowsVM" }
}
