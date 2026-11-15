package dev.ruchin.anvil.catalog.linux

import dev.ruchin.anvil.common.DebugLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Which manifest the catalog is showing. */
data class LoadedManifest(val manifest: LinuxManifest, val fromRemote: Boolean)

/**
 * Loads the Linux manifest: the copy in the repository when reachable (so new point releases
 * appear without an app update), otherwise the copy bundled in the app. A remote manifest that
 * doesn't parse, has a newer schema, or has no entries this app can verify is ignored.
 */
class LinuxManifestSource(
    client: OkHttpClient,
    private val bundled: () -> String,
    private val remoteUrl: String? = DEFAULT_REMOTE_URL,
    private val keys: PinnedKeys = PinnedKeys.load(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    remoteTimeoutMs: Long = 6_000,
) {
    private val client = client.newBuilder().callTimeout(remoteTimeoutMs, TimeUnit.MILLISECONDS).build()

    suspend fun load(): LoadedManifest = withContext(ioDispatcher) {
        remote()?.let { return@withContext LoadedManifest(it, fromRemote = true) }
        LoadedManifest(LinuxManifest.parse(bundled()).usable(keys, TAG), fromRemote = false)
    }

    private fun remote(): LinuxManifest? {
        val url = remoteUrl ?: return null
        return runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", MirrorProber.USER_AGENT).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val m = LinuxManifest.parse(resp.body.string())
                if (m.schema > LinuxManifest.SCHEMA) error("schema ${m.schema} is newer than ${LinuxManifest.SCHEMA}")
                m.usable(keys, TAG).takeIf { it.entries.isNotEmpty() } ?: error("no usable entries")
            }
        }.onFailure { DebugLog.w(TAG, "remote manifest not used: ${it.message}") }.getOrNull()
    }

    companion object {
        private const val TAG = "LinuxManifest"
        const val DEFAULT_REMOTE_URL =
            "https://raw.githubusercontent.com/Ruchin-Audichya/anvil-usb/main/app/src/main/assets/linux-manifest.json"
    }
}
