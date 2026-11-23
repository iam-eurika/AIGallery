package com.lucrativeworm.seekframe.update

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.core.content.FileProvider
import com.lucrativeworm.seekframe.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Looks for a newer release on GitHub, and on the user's say-so downloads the APK built for this
 * phone, checks it against the release's SHA256SUMS.txt, and hands it to Android's installer.
 *
 * Checks happen at most once a day and only when the user leaves "Check for updates" on. Debug
 * builds never check: they are a different package, so a release APK couldn't update them anyway.
 */
@Singleton
class Updater @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {
    data class Release(
        val version: String,
        val notes: String,
        val pageUrl: String,
        val apkName: String,
        val apkUrl: String,
        val apkBytes: Long,
        val sumsUrl: String?,
    )

    sealed interface State {
        data object Idle : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val bytes: Long, val total: Long) : State
        data class Ready(val release: Release, val apk: File) : State
        data class Failed(val release: Release, val message: String) : State
    }

    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val dir = File(context.cacheDir, "updates")

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Result of the last check started from Settings, for its status line. */
    private val _lastCheck = MutableStateFlow<String?>(null)
    val lastCheck: StateFlow<String?> = _lastCheck.asStateFlow()

    val installedVersion: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"

    private val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private var download: Job? = null

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
    }

    /** On app open: quietly ask GitHub, if allowed and it's been a day. A skipped version stays quiet. */
    fun checkOnLaunch() {
        if (debuggable || !_enabled.value) return
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_EVERY_MS) {
            // Still offer what we found last time, unless it was skipped.
            prefs.getString(KEY_CACHED, null)?.let(::parseRelease)?.let(::offer)
            return
        }
        scope.launch { runCatching { fetchLatest() }.getOrNull()?.let(::offer) }
    }

    /** From Settings: always asks, and shows the result even for a version skipped earlier. */
    fun checkNow() {
        if (_state.value is State.Downloading) return
        _lastCheck.value = "Checking…"
        scope.launch {
            val release = runCatching { fetchLatest() }.getOrElse {
                _lastCheck.value = "Couldn't reach GitHub. Check your connection."
                return@launch
            }
            when {
                release == null || !isNewer(release.version, installedVersion) ->
                    _lastCheck.value = "You're on the latest version"
                else -> {
                    prefs.edit().remove(KEY_IGNORED).apply()
                    _lastCheck.value = "Version ${release.version} is available"
                    _state.value = State.Available(release)
                }
            }
        }
    }

    /** "Ignore": don't bring this version up again. A later release will be offered as usual. */
    fun ignore() {
        val release = current() ?: return
        prefs.edit().putString(KEY_IGNORED, release.version).apply()
        cancelDownload()
        _state.value = State.Idle
    }

    /** Hide the sheet for now without skipping the version. */
    fun dismiss() {
        if (_state.value is State.Downloading) return
        _state.value = State.Idle
    }

    fun download() {
        val release = current() ?: return
        if (download?.isActive == true) return
        download = scope.launch {
            try {
                val apk = fetchApk(release)
                _state.value = State.Ready(release, apk)
                install()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.value = State.Failed(release, t.message ?: "Download failed")
            }
        }
    }

    fun cancelDownload() {
        val release = current() ?: return
        download?.cancel()
        download = null
        File(dir, "${release.apkName}.part").delete()
        _state.value = State.Available(release)
    }

    /** Opens Android's installer. The first time, Android asks to allow installs from this app. */
    fun install() {
        val ready = _state.value as? State.Ready ?: return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", ready.apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun current(): Release? = when (val s = _state.value) {
        is State.Available -> s.release
        is State.Downloading -> s.release
        is State.Ready -> s.release
        is State.Failed -> s.release
        State.Idle -> null
    }

    private fun offer(release: Release) {
        if (!isNewer(release.version, installedVersion)) {
            // Already installed (or older): last cycle's leftovers can go.
            dir.deleteRecursively()
            return
        }
        if (prefs.getString(KEY_IGNORED, null) == release.version) return
        if (_state.value == State.Idle) _state.value = State.Available(release)
    }

    private suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            prefs.edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .putString(KEY_CACHED, body)
                .apply()
            parseRelease(body)
        } finally {
            conn.disconnect()
        }
    }

    /** The latest release's APK for this phone: its own ABI if published, else the universal one. */
    private fun parseRelease(json: String): Release? = runCatching {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val version = o.getString("tag_name").removePrefix("v")
        val assets = o.getJSONArray("assets")
        val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
        val sums = apks.firstOrNull { it.getString("name") == "SHA256SUMS.txt" }
        val byName = apks.filter { it.getString("name").endsWith(".apk") }
        val apk = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
            byName.firstOrNull { it.getString("name").endsWith("-$abi.apk") }
        } ?: byName.firstOrNull { it.getString("name").endsWith("-universal.apk") } ?: return null
        Release(
            version = version,
            notes = o.optString("body").trim(),
            pageUrl = o.optString("html_url"),
            apkName = apk.getString("name"),
            apkUrl = apk.getString("browser_download_url"),
            apkBytes = apk.optLong("size"),
            sumsUrl = sums?.getString("browser_download_url"),
        )
    }.getOrNull()

    private suspend fun fetchApk(release: Release): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        // Only one update is kept around: older downloads go.
        dir.listFiles()?.filter { !it.name.startsWith(release.apkName) }?.forEach { it.delete() }
        val target = File(dir, release.apkName)
        val expected = release.sumsUrl?.let { expectedSha(it, release.apkName) }
        if (target.exists() && (expected == null || target.sha256() == expected)) return@withContext target

        val partial = File(dir, "${release.apkName}.part")
        var resumeFrom = partial.takeIf { it.exists() }?.length() ?: 0L
        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) resumeFrom = 0L
            else if (code != HttpURLConnection.HTTP_PARTIAL) throw IOException("HTTP $code")
            val total = (resumeFrom + conn.contentLengthLong.coerceAtLeast(0)).takeIf { it > resumeFrom } ?: release.apkBytes
            var bytes = resumeFrom
            var lastEmit = 0L
            _state.value = State.Downloading(release, bytes, total)
            conn.inputStream.use { input ->
                FileOutputStream(partial, resumeFrom > 0).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        bytes += n
                        if (bytes - lastEmit > 512 * 1024) {
                            lastEmit = bytes
                            _state.value = State.Downloading(release, bytes, total)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (expected != null && partial.sha256() != expected) {
            partial.delete()
            throw IOException("The download was corrupted. Try again.")
        }
        if (!partial.renameTo(target)) throw IOException("Couldn't save the update")
        target
    }

    /** The checksum CI published for [apkName], from lines like "<sha256>  <file>". */
    private fun expectedSha(sumsUrl: String, apkName: String): String? {
        val conn = (URL(sumsUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().readLines()
                .map { it.trim().split(Regex("\\s+"), limit = 2) }
                .firstOrNull { it.size == 2 && it[1].trimStart('*') == apkName }
                ?.get(0)?.lowercase()
                ?: throw IOException("No checksum published for $apkName")
        } finally {
            conn.disconnect()
        }
    }

    private fun File.sha256(): String {
        val md = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val LATEST_URL = "https://api.github.com/repos/iam-eurika/SeekFrame/releases/latest"
        private const val CHECK_EVERY_MS = 24L * 60 * 60 * 1000
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_CACHED = "cached_release"
        private const val KEY_IGNORED = "ignored_version"

        /** "1.10.0" > "1.9.2"; missing parts count as 0, and anything after a '-' is ignored. */
        fun isNewer(candidate: String, installed: String): Boolean {
            fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(installed)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
