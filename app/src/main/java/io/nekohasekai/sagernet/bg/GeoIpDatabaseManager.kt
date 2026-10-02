/******************************************************************************
 *                                                                            *
 * Exclave Next addition: local MaxMind database management.                  *
 *                                                                            *
 * Owns the GeoLite2-*.mmdb files inside externalAssets: install, update,      *
 * import from a file, delete, and reopen the readers afterwards.             *
 *                                                                            *
 * Two rules keep a bad download from killing annotation:                    *
 *   1. Everything is written to <name>.tmp and only replaces the live file    *
 *      after DatabaseReader has opened it successfully — a truncated or     *
 *      mislabeled download is rejected with the old database still in use.    *
 *   2. The ETag of the source is stored in <name>.etag, so an update that is    *
 *      already current costs a 304 instead of 8-12 MB.                        *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg

import com.maxmind.geoip2.DatabaseReader
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date

/** One local database on disk, ready to be shown in the settings screen. */
data class InstalledDatabase(
    val fileName: String,
    val exists: Boolean,
    val sizeBytes: Long = 0L,
    val buildDate: Date? = null,
    val databaseType: String = "",
    val etag: String = "",
    val error: String? = null,
)

/** Result of an update attempt, so the UI can say what actually happened. */
sealed class DieUpdateResult {
    object Updated : DieUpdateResult()
    object AlreadyCurrent : DieUpdateResult()
    data class Failed(val reason: String) : DieUpdateResult()
}

object GeoIpDatabaseManager {

    private val mmdbFiles = listOf(GeoIpDefaults.COUNTRY_DB, GeoIpDefaults.ASN_DB)

    fun databasesDir(): File = SagerNet.application.externalAssets

    /**
     * Read the state of every known database, plus any .mmdb the user dropped in
     * by hand. A corrupted file surfaces as [InstalledDatabase.error] instead of
     * breaking the list.
     */
    fun list(): List<InstalledDatabase> {
        val dir = databasesDir()
        val names = LinkedHashSet(mmdbFiles)
        dir.listFiles { f -> f.isFile && f.name.endsWith(".mmdb") }?.forEach { names.add(it.name) }

        return names.sorted().map { inspect(File(dir, it)) }
    }

    private fun inspect(file: File): InstalledDatabase {
        if (!file.isFile) return InstalledDatabase(file.name, exists = false)
        return try {
            DatabaseReader.Builder(file).build().use { reader ->
                val metadata = reader.metadata
                InstalledDatabase(
                    fileName = file.name,
                    exists = true,
                    sizeBytes = file.length(),
                    buildDate = metadata.buildDate,
                    databaseType = metadata.databaseType,
                    etag = readEtagFile(file),
                )
            }
        } catch (e: Exception) {
            InstalledDatabase(
                fileName = file.name,
                exists = true,
                sizeBytes = file.length(),
                etag = readEtagFile(file),
                error = e.message ?: "unireadable database",
            )
        }
    }

    /** Fails when the file cannot be opened as a MaxMind database. */
    fun validate(file: File) {
        DatabaseReader.Builder(file).build().use { }
    }

    /**
     * Download [url] into [fileName]. Returns [DieUpdateResult.AlreadyCurrent] when
     * the server reports the stored ETag, and leaves the existing file alone on any
     * failure. The working database is never truncated: bytes land in <name>.tmp
     * and the rename happens only after DatabaseReader has accepted the file.
     *
     * NOTE ON THE TRANSPORT: this deliberately does NOT go through
     * Libexclavecore.newHttpClient(). That binding returns an error for every
     * status except 200 and exposes no status code and no content length
     * (see http.go in the core module: `if response.StatusCode != http.StatusOK
     * { return nil, errors.New(httpResp.errorString()) }`), so a 304 Not Modified
     * — the entire point of the ETag cache — is unrepresentable there. It is also
     * the only reason HttpURLConnection is used: ApiResolver already works this
     * way, so this is the house pattern, not an exception. The cost is that a
     * database update bypasses the tunnel when the VPN is up; MMDB sources are
     * public mirrors, and a user who needs this routed can point the entry URL at
     * a reachable mirror.
     */
    suspend fun updateFromUrl(
        fileName: String,
        url: String,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ): DieUpdateResult {
        if (url.isBlank()) return DieUpdateResult.Failed("no URL configured")
        val dir = databasesDir()
        val target = File(dir, fileName)
        val tmp = File(dir, "$fileName.tmp")
        val staleEtag = readEtagFile(target)

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = DOWNLOAD_TIMEOUT_MS
                readTimeout = DOWNLOAD_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Exclave")
                if (staleEtag.isNotEmpty()) setRequestProperty("If-None-Match", staleEtag)
            }
            val code = conn.responseCode
            when {
                code == HttpURLConnection.HTTP_NOT_MODIFIED -> return DieUpdateResult.AlreadyCurrent
                code != HttpURLConnection.HTTP_OK -> return DieUpdateResult.Failed("HTTP $code from $url")
            }

            val declared = conn.contentLengthLong
            onProgress(0L, declared)

            tmp.delete()
            var received = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        received += n
                        onProgress(received, declared)
                    }
                }
            }

            try {
                validate(tmp)
            } catch (e: Exception) {
                tmp.delete()
                return DieUpdateResult.Failed("not a MaxMind database: ${e.message}")
            }

            if (!tmp.renameTo(target)) {
                tmp.delete()
                return DieUpdateResult.Failed("could not replace $fileName")
            }
            conn.getHeaderField("ETag")?.let { writeEtagFile(target, it) }
            GeoIpAnnotator.reload()
            DieUpdateResult.Updated
        } catch (e: Exception) {
            tmp.delete()
            Logs.w("GeoIP update $fileName: ${e.message}")
            DieUpdateResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    /** Install a .mmdb chosen by the user through the file picker. */
    fun importStreamTo(stream: InputStream, fileName: String): InstalledDatabase {
        val dir = databasesDir()
        val target = File(dir, fileName)
        val tmp = File(dir, "$fileName.tmp")
        try {
            tmp.outputStream().use { out -> stream.copyTo(out) }
            validate(tmp)
            if (!tmp.renameTo(target)) throw GeoIpLookupException("cannot replace $fileName")
            deleteEtagFile(target)
            GeoIpAnnotator.reload()
            return inspect(target)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    fun delete(fileName: String): Boolean {
        val file = File(databasesDir(), fileName)
        val removed = file.delete()
        deleteEtagFile(file)
        GeoIpAnnotator.reload()
        return removed
    }

    /** Current ETag of [file], or "" when the source never sent one. */
    fun readEtagFile(file: File): String {
        val etag = etagFileFor(file)
        return if (etag.isFile) etag.readText().trim() else ""
    }

    private fun writeEtagFile(file: File, etag: String) {
        try {
            etagFileFor(file).writeText(etag)
        } catch (e: Exception) {
            Logs.w("GeoIP etag write: ${e.message}")
        }
    }

    private fun deleteEtagFile(file: File) {
        val etag = etagFileFor(file)
        if (etag.isFile) etag.delete()
    }

    private fun etagFileFor(file: File): File = File(file.parentFile, "${file.name}.etag")

    private const val DOWNLOAD_TIMEOUT_MS = 30_000
}
