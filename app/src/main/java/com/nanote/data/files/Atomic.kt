package com.nanote.data.files

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Crash-safe file write: tmp -> fsync -> rotate old to .bak -> rename.
 * If the main file is ever found corrupt, callers fall back to the .bak.
 */
@Throws(IOException::class)
fun writeAtomic(target: File, bytes: ByteArray) {
    val parent = target.parentFile
    if (parent != null && !parent.exists()) check(parent.mkdirs()) { "Cannot create ${parent.absolutePath}" }
    val tmp = File(parent, target.name + ".tmp")
    FileOutputStream(tmp).use {
        it.write(bytes)
        it.fd.sync()
    }
    if (target.exists()) {
        val bak = File(parent, target.name + ".bak")
        if (bak.exists()) bak.delete()
        check(target.copyTo(bak, overwrite = true).exists()) { "Backup rotation failed for $target" }
    }
    check(tmp.renameTo(target)) { "Atomic rename failed for $target" }
}

/** Deletes a file and its .bak/.tmp siblings, ignoring missing files. */
fun deleteWithSiblings(target: File) {
    target.delete()
    File(target.parentFile, target.name + ".bak").delete()
    File(target.parentFile, target.name + ".tmp").delete()
}
