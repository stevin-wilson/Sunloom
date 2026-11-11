package com.sunloom.app.platform

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunloom.app.Requirement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * Article X §2: "unmetered" is what the network says it is, not which radio it uses - a phone hotspot is Wi-Fi but
 * metered, and must not be treated as free.
 */
@Requirement("X.2")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MeteredNetworkTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun connect(vararg capabilities: Int, transport: Int) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = ShadowNetworkCapabilities.newInstance()
        capabilities.forEach { shadowOf(caps).addCapability(it) }
        shadowOf(caps).addTransportType(transport)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }

    @Test
    fun `home wifi that is not metered counts as unmetered`() {
        connect(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_NOT_METERED, transport = NetworkCapabilities.TRANSPORT_WIFI)

        assertTrue(DefaultSystemWallpaperGateway(context).isUnmeteredNetwork())
    }

    @Test
    fun `a phone hotspot is wifi but metered, so it does not count`() {
        connect(NetworkCapabilities.NET_CAPABILITY_INTERNET, transport = NetworkCapabilities.TRANSPORT_WIFI)

        assertFalse(DefaultSystemWallpaperGateway(context).isUnmeteredNetwork())
    }

    @Test
    fun `cellular is metered`() {
        connect(NetworkCapabilities.NET_CAPABILITY_INTERNET, transport = NetworkCapabilities.TRANSPORT_CELLULAR)

        assertFalse(DefaultSystemWallpaperGateway(context).isUnmeteredNetwork())
    }

    @Test
    fun `Data Saver only holds the app back when it is on and the app is not exempt`() {
        assertFalse("Data Saver off", DefaultSystemWallpaperGateway.dataSaverRestricts(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED))
        assertFalse(
            "Data Saver on, but the person let this app use data in the background",
            DefaultSystemWallpaperGateway.dataSaverRestricts(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED)
        )
        assertTrue("Data Saver on and the app is not exempt", DefaultSystemWallpaperGateway.dataSaverRestricts(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED))
    }
}
