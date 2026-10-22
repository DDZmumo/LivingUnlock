package com.windowslockpin.companion.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class ReleaseUpdate(
    val tag: String,
    val notes: String,
    val releaseUrl: String,
    val downloadUrl: String,
    val prerelease: Boolean,
    val isNewer: Boolean
)

object ReleaseUpdateChecker {
    const val RELEASES_URL = "https://github.com/DDZmumo/LivingUnlock/releases"

    suspend fun check(currentVersion: String, includePrerelease: Boolean): ReleaseUpdate? = withContext(Dispatchers.IO) {
        val connection = URL("https://api.github.com/repos/DDZmumo/LivingUnlock/releases?per_page=100")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "LivingUnlock-Android/$currentVersion")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            when (val status = connection.responseCode) {
                200 -> Unit
                403, 429 -> throw IOException("GitHub 暂时限制了请求，请稍后再试。")
                else -> throw IOException("GitHub 返回 HTTP $status，请稍后重试。")
            }
            val json = StringBuilder()
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (json.length + count > 2 * 1024 * 1024) throw IOException("更新信息过大，请直接查看 GitHub Releases。")
                    json.append(buffer, 0, count)
                }
            }
            selectRelease(json.toString(), currentVersion, includePrerelease)
        } finally { connection.disconnect() }
    }

    fun selectRelease(json: String, currentVersion: String, includePrerelease: Boolean): ReleaseUpdate? {
        val current = ReleaseVersion.parse(currentVersion) ?: throw IOException("无法识别当前应用版本。")
        val releases = JSONArray(json)
        val candidates = mutableListOf<Pair<ReleaseVersion, ReleaseUpdate>>()
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft", true)) continue
            val tag = release.optString("tag_name")
            val version = ReleaseVersion.parse(tag) ?: continue
            val prerelease = release.optBoolean("prerelease", false) || version.isPrerelease
            if (prerelease && !includePrerelease) continue
            val page = release.optString("html_url")
            if (!trustedUrl(page, "/DDZmumo/LivingUnlock/releases/tag/")) continue
            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                if (!asset.optString("name").equals("LivingUnlock-Android-${version.text}-compact.apk", ignoreCase = true)) continue
                val download = asset.optString("browser_download_url")
                if (asset.optString("state") != "uploaded" ||
                    !trustedUrl(download, "/DDZmumo/LivingUnlock/releases/download/")) continue
                val notes = if (release.isNull("body")) "" else release.optString("body")
                candidates += version to ReleaseUpdate(tag, notes, page, download, prerelease, version > current)
                break
            }
        }
        return candidates.maxByOrNull { it.first }?.second
    }

    private fun trustedUrl(value: String, prefix: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true) &&
            (uri.port == -1 || uri.port == 443) && uri.rawUserInfo == null && uri.rawPath.startsWith(prefix)
    }.getOrDefault(false)
}

class ReleaseVersion private constructor(val text: String, private val numbers: List<Int>, private val suffix: List<String>) : Comparable<ReleaseVersion> {
    val isPrerelease: Boolean get() = suffix.isNotEmpty()
    override fun compareTo(other: ReleaseVersion): Int {
        for (i in 0..2) numbers[i].compareTo(other.numbers[i]).let { if (it != 0) return it }
        if (!isPrerelease || !other.isPrerelease) return when {
            isPrerelease == other.isPrerelease -> 0
            isPrerelease -> -1
            else -> 1
        }
        for (i in 0 until minOf(suffix.size, other.suffix.size)) {
            val a = suffix[i]; val b = other.suffix[i]
            val an = a.all { it in '0'..'9' }; val bn = b.all { it in '0'..'9' }
            val c = when {
                an && bn -> {
                    val na = a.trimStart('0'); val nb = b.trimStart('0')
                    if (na.length != nb.length) na.length.compareTo(nb.length) else na.compareTo(nb)
                }
                an != bn -> if (an) -1 else 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return suffix.size.compareTo(other.suffix.size)
    }

    companion object {
        private val pattern = Regex("^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z.-]+)?$")
        fun parse(value: String): ReleaseVersion? {
            val match = pattern.matchEntire(value.trim()) ?: return null
            val numbers = (1..3).map { match.groupValues[it].toIntOrNull() ?: return null }
            val suffix = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            return ReleaseVersion(value.trim().removePrefix("v").removePrefix("V").substringBefore('+'), numbers, suffix)
        }
    }
}
