package com.daftar.kredi

import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A restored backup: the book, plus invoice photos by file name. */
class Restored(val data: BackupData, val photos: Map<String, ByteArray>)

/**
 * The backup file is a zip holding data.json and the invoice photos under invoices/.
 * Older backups were the bare JSON; those still restore (without photos).
 */
object BackupArchive {

    private const val DATA = "data.json"
    private const val PHOTOS = "invoices/"
    private const val MAX_PHOTO_BYTES = 20 * 1024 * 1024

    fun write(out: OutputStream, data: BackupData, photoFile: (String) -> File?) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(DATA))
            zip.write(Backup.toJson(data).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            data.entries.map { it.photo }.filter { it.isNotEmpty() }.distinct().forEach { name ->
                val f = photoFile(name) ?: return@forEach
                zip.putNextEntry(ZipEntry(PHOTOS + name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Throws [IllegalArgumentException] when [bytes] is neither kind of backup. */
    fun read(bytes: ByteArray): Restored {
        val isZip = bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (!isZip) return Restored(Backup.fromJson(bytes.toString(Charsets.UTF_8)), emptyMap())

        var json: String? = null
        val photos = mutableMapOf<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when {
                        entry.name == DATA -> json = zip.readBytes().toString(Charsets.UTF_8)
                        entry.name.startsWith(PHOTOS) -> {
                            val name = entry.name.removePrefix(PHOTOS)
                            // Only names the app itself generates: nothing can be written outside the photo folder.
                            if (name.isNotEmpty() && PhotoName.isValid(name)) {
                                val data = zip.readBytes()
                                require(data.size <= MAX_PHOTO_BYTES) { "photo too big" }
                                photos[name] = data
                            }
                        }
                    }
                }
            }
        } catch (e: java.io.IOException) {
            throw IllegalArgumentException("bad zip", e)
        }
        val data = Backup.fromJson(json ?: throw IllegalArgumentException("no $DATA in backup"))
        val used = data.entries.map { it.photo }.toSet()
        return Restored(data, photos.filterKeys { it in used })
    }
}
