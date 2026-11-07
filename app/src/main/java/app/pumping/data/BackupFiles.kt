package app.pumping.data

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bounded SAF reads. Extension/MIME is never used as evidence that a file is valid. */
object BackupFiles {
    fun read(input: InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= LegacyFormat.MAX_BYTES) { "Backup exceeds 20 MB." }
            output.write(buffer, 0, count)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray()))
                .toString()
                .removePrefix("\uFEFF")
        } catch (_: Exception) {
            error("Backup must be UTF-8 text.")
        }
    }

    fun read(resolver: ContentResolver, uri: Uri): String =
        resolver.openInputStream(uri)?.use { read(it) }
            ?: error("Could not read the selected backup. Try a local file.")

    fun write(resolver: ContentResolver, uri: Uri, text: String) {
        resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("Could not write the selected backup. Choose another location.")
    }
}
