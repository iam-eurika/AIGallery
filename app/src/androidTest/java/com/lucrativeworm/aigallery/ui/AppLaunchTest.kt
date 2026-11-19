package com.lucrativeworm.aigallery.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lucrativeworm.aigallery.GalleryApp
import com.lucrativeworm.aigallery.MainActivity
import com.lucrativeworm.aigallery.data.Db
import com.lucrativeworm.aigallery.data.Folder
import io.objectbox.kotlin.boxFor
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole app, started the way a user starts it: the real Application builds its graph and
 * database, and a fresh install without photo access lands on the welcome screen.
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun applicationGraphIsWired() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as GalleryApp
        val graph = app.graph
        assertTrue(graph.indexer.progress.value.total >= 0)
        assertTrue(graph.models.choice.value.name.isNotEmpty())
    }

    @Test
    fun freshInstallShowsTheWelcomeScreen() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val hasAccess = listOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        ).any { ctx.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        // Only meaningful before photo access or a folder was given; the test runner's install is fresh.
        assumeTrue(!hasAccess && Db.store.boxFor<Folder>().count() == 0L)

        compose.onNodeWithText("Your photos,\nfinally findable.").assertIsDisplayed()
        compose.onNodeWithText("Open my library").assertIsDisplayed()
        compose.onNodeWithText("Choose specific folders instead").assertIsDisplayed()
    }
}
