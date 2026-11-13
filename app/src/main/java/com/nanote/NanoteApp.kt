package com.nanote

import android.app.Application
import com.nanote.core.CrashCatcher
import com.nanote.data.repo.NotebookRepository
import com.nanote.ink.render.PdfPageProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Nanote application entry point: prunes expired trash (30 days) at every
 * cold start. Branding lives in res/values/strings.xml (app_name),
 * app/build.gradle.kts (applicationId) and core/Brand.kt.
 *
 * v2.0: also initializes the PDF engines once per process — the pdfium
 * wrapper's app context (DPI) and PdfBox-Android's resource loader for
 * text extraction.
 */
@HiltAndroidApp
class NanoteApp : Application() {

    @Inject
    lateinit var repo: NotebookRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // v2.0: single pdfium engine context for the whole process.
        PdfPageProvider.appContext = this
        try {
            PDFBoxResourceLoader.init(this)
        } catch (_: Throwable) {
        }
        // First line of business: catch any crash so the user can share the
        // report on the next launch (no internet permission in this app).
        try {
            CrashCatcher.install(this)
        } catch (_: Throwable) {
        }
        appScope.launch {
            try {
                repo.purgeExpiredTrash()
            } catch (e: Exception) {
                // never block startup on cleanup
            }
        }
    }
}
