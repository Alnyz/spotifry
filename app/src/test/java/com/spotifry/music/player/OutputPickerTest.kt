package com.spotifry.music.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class OutputPickerTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    // Android 13 has no switcher for apps: the row shows only when the volume panel (with its output choice) can open
    @Test @Config(sdk = [33]) fun onAndroid13ThePanelOrNothing() {
        assertFalse(hasOutputPicker(context))
        val info = ActivityInfo().apply { packageName = "com.android.settings"; name = "Panel" }
        shadowOf(context.packageManager).addOrUpdateActivity(info)
        shadowOf(context.packageManager).addIntentFilterForActivity(
            android.content.ComponentName(info.packageName, info.name), android.content.IntentFilter(Settings.Panel.ACTION_VOLUME).apply { addCategory(Intent.CATEGORY_DEFAULT) },
        )
        assertTrue(hasOutputPicker(context))
    }

    @Test @Config(sdk = [34]) fun fromAndroid14TheSystemSwitcherIsThere() = assertTrue(hasOutputPicker(context))
    @Test @Config(sdk = [35]) fun onAndroid15Too() = assertTrue(hasOutputPicker(context))
}
