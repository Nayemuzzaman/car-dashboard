package com.csjotlab.cardashboard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that BOTH packages migrated: the app under test and the instrumentation APK.
 *
 * The instrumentation package is derived from `applicationId` + `.test`, so it moves only if the
 * application identity really moved. Asserting it here is what distinguishes a genuine migration
 * from a source-tree rename that left the installed identity behind.
 */
@RunWith(AndroidJUnit4::class)
class PackageIdentityInstrumentedTest {

    @Test
    fun appUnderTestIsInstalledAsCsjotlab() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.csjotlab.cardashboard", target.packageName)
    }

    @Test
    fun instrumentationPackageMigratedAlongsideTheApp() {
        val test = InstrumentationRegistry.getInstrumentation().context
        assertEquals("com.csjotlab.cardashboard.test", test.packageName)
    }

    @Test
    fun launcherActivityResolvesUnderTheNewIdentity() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = target.packageManager.getLaunchIntentForPackage("com.csjotlab.cardashboard")
        assertTrue(
            "the new package must expose a launchable MAIN/LAUNCHER activity",
            intent != null,
        )
        assertEquals("com.csjotlab.cardashboard", intent!!.component?.packageName)
        assertEquals(
            "com.csjotlab.cardashboard.MainActivity",
            intent.component?.className,
        )
    }
}
