package com.nanote

import android.app.Application
import com.nanote.core.CrashCatcher
import com.nanote.data.repo.NotebookRepository
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
 */
@HiltAndroidApp
class NanoteApp : Application() {

    @Inject
    lateinit var repo: NotebookRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
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
