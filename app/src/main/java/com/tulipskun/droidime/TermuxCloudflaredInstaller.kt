package com.tulipskun.droidime

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

// cloudflared is a 40 MB executable per ABI, which made the APK 56 MB. The release publishes the
// three binaries as a separate zip instead; this installs the one the device needs on first use.
object TermuxCloudflaredInstaller {
    private const val LIBRARY_NAME = "libcloudflared.so"
    private const val ASSET_URL =
        "https://github.com/Tulipskun/droidime/releases/latest/download/cloudflared-binaries.zip"
    private const val VERSION_URL =
        "https://github.com/Tulipskun/droidime/releases/latest/download/cloudflared-version.txt"
    private const val MANIFEST_NAME = "checksums.sha256"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val VERSION_TIMEOUT_MS = 8_000
    private const val MAX_ZIP_BYTES = 200L * 1024 * 1024
    private const val HASH_KEY = "cloudflared_sha256"
    private const val VERSION_KEY = "cloudflared_version"
    private const val STATUS_KEY = "cloudflared_install_status"

    @Synchronized
    fun ensureInstalled(context: Context): File {
        // Older builds bundled the library; keep using it when it is there.
        val bundled = File(context.applicationInfo.nativeLibraryDir, LIBRARY_NAME)
        if (bundled.isFile && bundled.canRead() && bundled.length() > 1024 * 1024) {
            setStatus(context, "Bundled cloudflared ready")
            return bundled
        }

        val abi = supportedAbi()
            ?: throw IllegalStateException("Unsupported Android ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        val target = File(File(context.filesDir, "cloudflared-bin"), LIBRARY_NAME)
        val prefs = context.getSharedPreferences("droid_mcp", Context.MODE_PRIVATE)
        val cached = target.isFile && target.canRead() && target.length() > 1024 * 1024
        val savedVersion = prefs.getString(VERSION_KEY, null)
        val savedHash = prefs.getString(HASH_KEY, null)

        // A tiny version file decides whether the 40 MB zip is needed. Updating the app keeps
        // both preferences, so the cached binary survives app updates unless cloudflared changed.
        val remoteVersion = readRemoteVersion()
        if (cached && remoteVersion != null && remoteVersion == savedVersion) {
            setStatus(context, "Bundled cloudflared ready")
            return target
        }
        if (cached && savedHash != null && sha256(target).equals(savedHash, ignoreCase = true)) {
            // Offline, or upgrading from a build that stored only the hash: keep the verified
            // copy and record its build id so later app updates do not download it again.
            prefs.edit().putString(VERSION_KEY, remoteVersion ?: savedVersion).apply()
            setStatus(context, "Bundled cloudflared ready")
            return target
        }

        val zip = download(context)
        try {
            val hash = install(zip, abi, target)
            prefs.edit()
                .putString(HASH_KEY, hash)
                .putString(VERSION_KEY, remoteVersion)
                .apply()
        } finally {
            zip.delete()
        }
        setStatus(context, "Bundled cloudflared ready")
        return target
    }

    private fun readRemoteVersion(): String? {
        val connection = URL(VERSION_URL).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = VERSION_TIMEOUT_MS
        connection.readTimeout = VERSION_TIMEOUT_MS
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader().use { it.readText().trim() }
                .take(64).ifBlank { null }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun supportedAbi(): String? =
        Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" || it == "x86_64" }

    private fun download(context: Context): File {
        val target = File(context.cacheDir, "cloudflared-binaries.zip")
        val connection = URL(ASSET_URL).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Cloudflared download failed (HTTP ${connection.responseCode})")
            }
            FileOutputStream(target).use { output ->
                connection.inputStream.use { input -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
        if (target.length() > MAX_ZIP_BYTES) {
            target.delete()
            throw IOException("Cloudflared download is too large")
        }
        return target
    }

    private fun install(zipFile: File, abi: String, target: File): String {
        ZipFile(zipFile).use { zip ->
            val manifest = zip.getEntry(MANIFEST_NAME)
                ?: throw IOException("Cloudflared checksum manifest is missing")
            val expected = HashMap<String, String>()
            zip.getInputStream(manifest).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"), limit = 2)
                    if (parts.size == 2) expected[parts[1]] = parts[0]
                }
            }
            val entryName = "lib/$abi/$LIBRARY_NAME"
            val entry = zip.getEntry(entryName)
                ?: throw IOException("Cloudflared binary is missing for $abi")
            val digest = expected[entryName]
                ?: throw IOException("Cloudflared checksum is missing for $abi")
            target.parentFile?.mkdirs()
            val staged = File(target.parentFile, "$LIBRARY_NAME.tmp")
            zip.getInputStream(entry).use { input ->
                FileOutputStream(staged).use { output -> input.copyTo(output) }
            }
            val actual = sha256(staged)
            if (!actual.equals(digest, ignoreCase = true)) {
                staged.delete()
                throw IOException("Cloudflared checksum mismatch for $abi")
            }
            if (!staged.renameTo(target)) {
                staged.delete()
                throw IOException("Could not install cloudflared binary")
            }
            target.setExecutable(true)
            return digest
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun setStatus(context: Context, value: String) {
        context.getSharedPreferences("droid_mcp", Context.MODE_PRIVATE)
            .edit().putString(STATUS_KEY, value).apply()
    }
}
