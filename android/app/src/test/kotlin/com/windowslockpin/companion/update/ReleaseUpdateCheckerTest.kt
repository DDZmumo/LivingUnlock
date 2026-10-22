package com.windowslockpin.companion.update

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ReleaseUpdateCheckerTest {
    private fun fixture() = javaClass.getResourceAsStream("/github-releases.json")!!.bufferedReader().use { it.readText() }

    @Test fun choosesAndroidAssetInsteadOfNewerWindowsOnlyRelease() {
        val release = ReleaseUpdateChecker.selectRelease(fixture(), "0.2.9", true)!!
        assertEquals("v0.2.10", release.tag)
        assertTrue(release.isNewer)
        assertTrue(release.prerelease)
        assertTrue(release.downloadUrl.endsWith("LivingUnlock-Android-0.2.10-compact.apk"))
    }

    @Test fun stableChannelExcludesPreviewsAndDoesNotDowngrade() {
        val release = ReleaseUpdateChecker.selectRelease(fixture(), "0.2.10", false)!!
        assertEquals("v0.2.9", release.tag)
        assertFalse(release.isNewer)
        assertFalse(ReleaseUpdateChecker.selectRelease(fixture(), "0.2.10", true)!!.isNewer)
    }

    @Test fun noReleaseIsDifferentFromBeingUpToDate() {
        assertNull(ReleaseUpdateChecker.selectRelease("[]", "0.2.4", true))
        val entries = JSONArray(fixture())
        val previews = JSONArray()
        for (i in 0 until entries.length()) if (entries.getJSONObject(i).optBoolean("prerelease")) previews.put(entries.getJSONObject(i))
        assertNull(ReleaseUpdateChecker.selectRelease(previews.toString(), "0.2.4", false))
    }

    @Test fun numericVersionsAndPrereleasesAreOrderedCorrectly() {
        assertTrue(ReleaseVersion.parse("v0.2.10")!! > ReleaseVersion.parse("0.2.9")!!)
        assertTrue(ReleaseVersion.parse("1.0.0-rc.10")!! > ReleaseVersion.parse("1.0.0-rc.2")!!)
        assertTrue(ReleaseVersion.parse("1.0.0")!! > ReleaseVersion.parse("1.0.0-rc.10")!!)
        assertEquals(0, ReleaseVersion.parse("1.0.0+build.2")!!.compareTo(ReleaseVersion.parse("v1.0.0")!!))
        assertNull(ReleaseVersion.parse("invalid"))
    }
}
