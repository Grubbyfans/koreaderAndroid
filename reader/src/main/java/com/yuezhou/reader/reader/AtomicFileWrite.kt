package com.yuezhou.reader.reader

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Same-directory replacement keeps the previous contents readable until the new file is ready. */
internal fun File.writeAtomically(text: String) {
    val staging = File.createTempFile(".library-", ".tmp", checkNotNull(parentFile))
    try {
        FileOutputStream(staging).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Files.move(staging.toPath(), toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        staging.delete()
    }
}
