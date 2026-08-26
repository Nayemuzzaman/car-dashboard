package com.csjotlab.cardashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The application's release identity is `com.csjotlab.cardashboard`.
 *
 * This is a JVM-side guard on the two facts a package migration can silently get half-right:
 * the build's `applicationId` (what the device installs and launches as) and the package the
 * compiled classes actually live in. A migration that renames directories but leaves
 * `applicationId` alone still installs under the old name; one that changes `applicationId` but
 * leaves the sources alone ships classes under a package the app is no longer called.
 */
class PackageIdentityTest {

    @Test
    fun applicationIdIsTheCsjotlabIdentity() {
        assertEquals("com.csjotlab.cardashboard", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun compiledClassesLiveUnderTheCsjotlabPackage() {
        listOf(
            MainActivity::class.java,
            CarDashboardApplication::class.java,
        ).forEach { type ->
            assertTrue(
                "${type.name} must live under com.csjotlab.cardashboard",
                type.name.startsWith("com.csjotlab.cardashboard."),
            )
            assertFalse(
                "${type.name} must not retain the com.example identity",
                type.name.contains("com.example"),
            )
        }
    }

    @Test
    fun thisTestSourceItselfMigrated() {
        // The test tree is part of the migration surface, not an observer of it.
        assertEquals("com.csjotlab.cardashboard", javaClass.`package`?.name)
    }
}
