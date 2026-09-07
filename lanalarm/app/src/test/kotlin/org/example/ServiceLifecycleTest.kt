package org.example

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.example.lanalarm.AlarmService
import org.example.lanalarm.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * The service starting and, more importantly, stopping.
 *
 * This used to run for the life of the phone, holding a socket open for a PC
 * that was usually asleep. It now exists only while there is something to do,
 * which means "does it stop?" is a correctness question rather than a tidiness
 * one — a service that forgets to stop is the battery bug all over again.
 *
 * The other thing covered here is simply that it starts at all. Creating the
 * service builds a notification that asks the scanner what it is doing, and
 * getting that order wrong takes the whole service down on every start,
 * silently, in a `catch`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServiceLifecycleTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        AppSettings.prefs(context).edit().clear().commit()
    }

    private fun controller() = Robolectric.buildService(AlarmService::class.java)

    @Test
    fun `creating the service does not throw`() {
        val service = controller().create().get()
        assertNotNull(service)
        assertNotNull(
            "the running instance is what the UI and the dismiss screen reach for",
            AlarmService.instance
        )
    }

    @Test
    fun `a start with no action stops the service again`() {
        // Nothing to ring, nothing to scan, nothing to publish. Staying alive
        // here is exactly the behaviour that put this app top of the battery
        // screen.
        val controller = controller().create()
        controller.startCommand(0, 0)
        ShadowLooper.idleMainLooper()

        assertTrue(Shadows.shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun `a sync request with ontoplano switched off stops immediately`() {
        val controller = controller().create()
        controller.get().onStartCommand(
            Intent(context, AlarmService::class.java).setAction(AlarmService.ACTION_SYNC), 0, 0
        )
        ShadowLooper.idleMainLooper()

        assertTrue(Shadows.shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun `a listen request with no Bluetooth stops rather than lingering`() {
        // Robolectric has no BLE adapter, so the scan fails to start. The
        // service must not sit there foreground with nothing to do.
        val controller = controller().create()
        controller.get().onStartCommand(
            Intent(context, AlarmService::class.java)
                .setAction(AlarmService.ACTION_LISTEN_SCALE),
            0, 0
        )
        ShadowLooper.idleMainLooper()

        assertFalse(controller.get().isScanningScale())
        assertTrue(Shadows.shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun `destroying the service clears the instance others reach for`() {
        val controller = controller().create()
        assertNotNull(AlarmService.instance)

        controller.destroy()
        assertNull(
            "a stale instance means the dismiss screen talks to a dead service",
            AlarmService.instance
        )
    }
}
