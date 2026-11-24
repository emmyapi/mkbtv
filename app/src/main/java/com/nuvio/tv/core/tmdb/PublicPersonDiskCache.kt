package com.nuvio.tv.core.tmdb

import android.content.Context
import com.nuvio.tv.domain.model.PersonDetail
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Previously verified public credits, keyed by the same name, role and source as the memory cache. */
@Singleton
class PublicPersonDiskCache internal constructor(private val directory: File, moshi: Moshi) {
    @Inject constructor(@ApplicationContext context: Context, moshi: Moshi) :
        this(File(context.cacheDir, "public-person-v1"), moshi)

    private val adapter = moshi.adapter(PersonDetail::class.java)

    private fun entry(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return File(directory, digest.joinToString("") { "%02x".format(it) })
    }

    @Synchronized
    fun get(key: String): PersonDetail? {
        val file = entry(key)
        if (!file.isFile) return null
        if (file.length() > MAX_ENTRY_BYTES || System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS) {
            file.delete()
            return null
        }
        return try {
            adapter.fromJson(file.readText())
        } catch (_: IOException) {
            null
        } catch (_: JsonDataException) {
            null
        }
    }

    @Synchronized
    fun put(key: String, detail: PersonDetail) {
        if (detail.partialFilmography || (!directory.isDirectory && !directory.mkdirs())) return
        val bytes = adapter.toJson(detail).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_ENTRY_BYTES) return
        // The cache is process-local and writes are serialized. A failed replacement leaves
        // the previous complete entry readable, including after a process restart.
        val pending = File(directory, "pending")
        try {
            pending.writeBytes(bytes)
            if (!pending.renameTo(entry(key))) return
            var total = 0L
            directory.listFiles()?.sortedByDescending { it.lastModified() }?.forEachIndexed { index, file ->
                total += file.length()
                if (index >= 64 || total > MAX_TOTAL_BYTES) file.delete()
            }
        } catch (_: IOException) {
            // Eviction or lack of disk space must not prevent showing fresh network results.
        } finally {
            pending.delete()
        }
    }

    private companion object {
        val MAX_AGE_MS = TimeUnit.DAYS.toMillis(7)
        const val MAX_ENTRY_BYTES = 2 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 8 * 1024 * 1024
    }
}
