package com.spotifry.music.ui.components

import android.provider.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoverShelfTest {
    @get:Rule val compose = createComposeRule()

    private fun scale(v: Float) { Settings.Global.putFloat(ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, v) }

    @Test fun removedAnimationsMeanNoFadeAndNormalOnesDo() {
        val still = mutableStateOf<Boolean?>(null)
        scale(0f)
        compose.setContent { still.value = rememberStill() }
        compose.waitForIdle()
        assertTrue(still.value == true)
        scale(1f)
    }

    @Test fun aCoverThatCannotBeFetchedGoesFromGreyToThePlaceholderAndStopsThere() {
        val failing = OkHttpClient.Builder().addInterceptor { throw java.io.IOException("offline") }.build()
        compose.setContent {
            CompositionLocalProvider(LocalImageClient provides failing) { CoverArt("seed", "https://img/x.jpg", null, Modifier, shelf = true) }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("cover_grey").fetchSemanticsNodes().isEmpty() }
        compose.onAllNodesWithTag("cover_grey").assertCountEquals(0)
    }

    @Test fun otherCoversKeepTheirPlaceholderFromTheStart() {
        val failing = OkHttpClient.Builder().addInterceptor { throw java.io.IOException("offline") }.build()
        compose.setContent {
            CompositionLocalProvider(LocalImageClient provides failing) { CoverArt("seed", "https://img/y.jpg", null, Modifier) }
        }
        compose.onAllNodesWithTag("cover_grey").assertCountEquals(0)
    }
}
