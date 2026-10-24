package com.nanote.core

/**
 * Single place for brand constants used from code.
 * To rebrand the app change: `app_name` in strings.xml, `applicationId` in
 * app/build.gradle.kts and the constants here.
 */
object Brand {
    const val APP_NAME = "Nanote"
    const val APPLICATION_ID = "com.nanote"
    const val VERSION = "1.1"

    /** File extension of shareable/exportable single notebooks. */
    const val NOTE_EXT = "nanote"
    const val BACKUP_MIME = "application/zip"
    const val EXPORT_DIR = "exports"
}
