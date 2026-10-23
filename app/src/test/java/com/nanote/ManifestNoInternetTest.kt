package com.nanote

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hard privacy requirement: Nanote is offline-only. The merged manifest must
 * never request INTERNET (nor legacy storage permissions — storage access
 * goes exclusively through SAF and the photo picker).
 *
 * The Gradle tasks `checkManifestNoInternet` and `scanApkNoInternet*` enforce
 * the same property at build time; this test guards the merged manifest that
 * Robolectric assembles from app + library manifests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ManifestNoInternetTest {

    @Test
    fun mergedManifestRequestsNoPermissions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = packageInfo.requestedPermissions ?: arrayOf<String>()

        assertThat(requested).asList().doesNotContain(android.Manifest.permission.INTERNET)
        assertThat(requested).asList().doesNotContain(android.Manifest.permission.ACCESS_NETWORK_STATE)
        assertThat(requested).asList().doesNotContain(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        assertThat(requested).asList().doesNotContain(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        assertThat(requested).asList().doesNotContain(android.Manifest.permission.READ_MEDIA_IMAGES)
    }

    @Test
    fun allowBackupIsDisabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.applicationInfo
        assertThat(info.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP).isEqualTo(0)
    }
}
