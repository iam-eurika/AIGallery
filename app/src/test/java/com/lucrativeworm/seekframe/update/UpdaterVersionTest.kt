package com.lucrativeworm.seekframe.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterVersionTest {
    @Test fun newerPatchMinorMajor() {
        assertTrue(Updater.isNewer("1.2.1", "1.2.0"))
        assertTrue(Updater.isNewer("1.3.0", "1.2.9"))
        assertTrue(Updater.isNewer("2.0.0", "1.99.99"))
    }

    @Test fun comparesNumericallyNotAlphabetically() {
        assertTrue(Updater.isNewer("1.10.0", "1.9.0"))
        assertFalse(Updater.isNewer("1.9.0", "1.10.0"))
    }

    @Test fun sameOrOlderIsNotAnUpdate() {
        assertFalse(Updater.isNewer("1.2.0", "1.2.0"))
        assertFalse(Updater.isNewer("1.2", "1.2.0"))
        assertFalse(Updater.isNewer("1.1.9", "1.2.0"))
    }

    @Test fun toleratesPrefixAndSuffix() {
        assertTrue(Updater.isNewer("v1.3.0", "1.2.0"))
        assertFalse(Updater.isNewer("1.2.0-rc1", "1.2.0"))
    }
}
