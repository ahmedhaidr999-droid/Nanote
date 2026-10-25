package com.nanote.core

/**
 * Single place for brand constants used from code.
 * To rebrand the app change: `app_name` in strings.xml, `applicationId` in
 * app/build.gradle.kts and the constants here.
 */
object Brand {
    const val APP_NAME = "Nanote"
    const val APPLICATION_ID = "com.nanote"
    const val VERSION = "1.4"

    /**
     * Public download page for updates. The app has zero permissions and
     * never makes network requests itself — "Check for updates" (Settings →
     * About) simply opens this URL in the user's browser, keeping the app
     * fully offline. Updates downloaded from there install over the current
     * version in place (same signing key + increasing versionCode in CI).
     */
    const val RELEASES_URL = "https://github.com/ahmedhaidr999-droid/Nanote/releases/latest"

    /** File extension of shareable/exportable single notebooks. */
    const val NOTE_EXT = "nanote"
    const val BACKUP_MIME = "application/zip"
    const val EXPORT_DIR = "exports"
}
