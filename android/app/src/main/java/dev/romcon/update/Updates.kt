package dev.romcon.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest

/** The newest RomCon release on GitHub. [apkUrl] is null if it has no APK; [apkSha256] is lower-case hex, or null if GitHub didn't say. */
data class Release(val version: String, val pageUrl: String, val apkUrl: String?, val apkSha256: String?)

enum class UpdateStage { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, FAILED }

data class UpdateStatus(
    val stage: UpdateStage = UpdateStage.IDLE,
    /** The newest release, once a check found one. */
    val latest: Release? = null,
    /** Set when [latest] is newer than this app. */
    val hasUpdate: Boolean = false,
    /** While downloading: how far it got, or null if the size isn't known. */
    val progress: Float? = null,
    val error: String? = null,
) {
    val busy: Boolean get() = stage == UpdateStage.CHECKING || stage == UpdateStage.DOWNLOADING
}

class UpdateException(message: String) : IOException(message)

/** Looks on GitHub for a newer RomCon, downloads its APK and hands it to Android's installer. */
object Updates {
    private const val REPO = "mikep49/romcon"
    private const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"
    private const val APK_ASSET = "RomCon.apk"

    /** Asks GitHub for the newest release. Blocks; call it off the main thread. */
    fun fetchLatest(currentVersion: String): Release {
        val connection = open(LATEST_API, currentVersion)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            checkResponse(connection)
            return parseRelease(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    fun parseRelease(json: String): Release {
        val root = JSONObject(json)
        val tag = root.optString("tag_name")
        if (parseVersion(tag) == null) throw UpdateException("The newest release has no version number.")
        val assets = root.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }.firstOrNull { it.optString("name").equals(APK_ASSET, ignoreCase = true) }
        // GitHub gives each asset's digest as "sha256:<hex>".
        val digest = apk?.optString("digest")?.takeIf { it.startsWith("sha256:", ignoreCase = true) }?.substring(7)?.lowercase()
        return Release(
            version = tag.trimStart('v', 'V'),
            pageUrl = root.optString("html_url").ifBlank { RELEASES_PAGE },
            apkUrl = apk?.optString("browser_download_url")?.ifBlank { null },
            apkSha256 = digest,
        )
    }

    /** Downloads the release's APK to [file], checking it against GitHub's digest. Blocks; call it off the main thread. */
    fun download(release: Release, currentVersion: String, file: File, onProgress: (Float?) -> Unit) {
        val url = release.apkUrl ?: throw UpdateException("The new version doesn't include an APK.")
        file.parentFile?.mkdirs()
        val connection = open(url, currentVersion)
        try {
            checkResponse(connection)
            val total = connection.contentLengthLong
            val sha = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var reported = -1
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        sha.update(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (percent != reported) {
                            reported = percent
                            onProgress(if (total > 0) done.toFloat() / total else null)
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw UpdateException("The download stopped part way.")
            val hex = sha.digest().joinToString("") { "%02x".format(it) }
            if (release.apkSha256 != null && hex != release.apkSha256) {
                file.delete()
                throw UpdateException("The download doesn't match the release, so it wasn't used.")
            }
        } catch (e: IOException) {
            file.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    /** Opens Android's installer on a downloaded APK. Android asks the user to allow installs from RomCon the first time. */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Where the APK is downloaded to; it lives in the cache, so Android can clear it. */
    fun apkFile(context: Context): File = File(File(context.cacheDir, "updates"), APK_ASSET)

    /** A user-facing reason for a failed check or download. */
    fun describe(e: Exception): String = when (e) {
        is UpdateException -> e.message ?: "Something went wrong."
        is UnknownHostException -> "No internet connection."
        is SocketTimeoutException -> "GitHub didn't answer."
        else -> e.message ?: "Something went wrong."
    }

    private fun open(url: String, currentVersion: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "RomCon-Android/$currentVersion")
        }

    private fun checkResponse(connection: HttpURLConnection) {
        when (val code = connection.responseCode) {
            HttpURLConnection.HTTP_OK -> Unit
            HttpURLConnection.HTTP_NOT_FOUND -> throw UpdateException("There are no releases yet.")
            HttpURLConnection.HTTP_FORBIDDEN, 429 -> throw UpdateException("GitHub is busy. Try again later.")
            else -> throw UpdateException("GitHub answered $code.")
        }
    }
}

/** "v1.2.3" or "1.2" as numbers; null if it doesn't start with one. */
fun parseVersion(tag: String?): List<Int>? {
    val digits = tag?.trimStart('v', 'V')?.takeWhile { it.isDigit() || it == '.' } ?: return null
    val parts = digits.split('.').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    return parts.map { it.toIntOrNull() ?: return null }
}

/** True if version [candidate] is newer than [current]. Missing parts count as 0, so 1.1 equals 1.1.0. */
fun isNewer(candidate: String, current: String): Boolean {
    val a = parseVersion(candidate) ?: return false
    val b = parseVersion(current) ?: return true
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}
