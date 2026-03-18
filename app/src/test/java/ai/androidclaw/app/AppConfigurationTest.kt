package ai.androidclaw.app

import ai.androidclaw.R
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppConfigurationTest {
    @Test
    fun `manifest declares required network permissions`() {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        val packageInfo =
            application.packageManager.getPackageInfo(
                application.packageName,
                PackageManager.GET_PERMISSIONS,
            )
        val requestedPermissions = packageInfo.requestedPermissions.orEmpty().toSet()

        assertTrue(requestedPermissions.contains("android.permission.INTERNET"))
        assertTrue(requestedPermissions.contains("android.permission.ACCESS_NETWORK_STATE"))
    }

    @Test
    fun `required security and backup resources are bundled`() {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()

        application.resources.getXml(R.xml.network_security_config).close()
        application.resources.getXml(R.xml.backup_rules).close()
        application.resources.getXml(R.xml.data_extraction_rules).close()
    }
}
