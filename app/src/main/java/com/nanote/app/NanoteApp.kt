package com.nanote.app

import android.app.Application
import com.nanote.core.common.ApplicationScope
import com.nanote.core.data.DataStoreUserPreferencesRepository
import com.nanote.core.data.UserPreferencesRepository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class NanoteApp : Application() {

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    @Inject
    lateinit var userPreferences: UserPreferencesRepository

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)

        // One-time import of the v1.0 appearance keys (theme / dynamic colors /
        // language) from the legacy SharedPreferences into DataStore.
        val legacyPrefs = getSharedPreferences("nanote_settings", MODE_PRIVATE)
        (userPreferences as? DataStoreUserPreferencesRepository)?.let { store ->
            appScope.launch { store.migrateFromLegacy(legacyPrefs) }
        }
    }
}
