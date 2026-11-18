package com.novelscraper.app.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novelscraper.app.data.LibraryPrefs
import com.novelscraper.app.data.ReaderPrefs
import com.novelscraper.app.net.Net
import com.novelscraper.app.sync.SyncSettings
import com.novelscraper.app.ui.browse.SourceRow
import com.novelscraper.app.ui.components.ExpressiveReadingSetting
import com.novelscraper.app.ui.components.GpuAccelerationSetting
import com.novelscraper.app.ui.screen.ReleaseCard
import com.novelscraper.app.ui.screen.SettingsHooks
import com.novelscraper.app.ui.screen.SettingsPage
import com.novelscraper.app.update.AppUpdates
import com.novelscraper.app.update.Changelog
import kotlin.test.Test
import kotlin.test.assertTrue

/** Other screens at phone sizes (see [LayoutCheck]). */
class ScreensLayoutTest {

    @Test
    fun everySettingsPageFits() {
        Net.init()
        SyncSettings.init()
        AppUpdates.init(Net.client)
        ReaderPrefs.init()
        LibraryPrefs.init()
        SettingsHooks.narration = { GpuAccelerationSetting(); ExpressiveReadingSetting() }
        for (page in SettingsPage.entries) {
            LayoutCheck.assertFits("Settings, ${page.title},", height = 3000) { page.content() }
        }
    }

    @Test
    fun theChangelogFits() {
        val releases = Changelog.releases.take(6)
        assertTrue(releases.isNotEmpty(), "no changelog in the test resources")
        LayoutCheck.assertFits("The changelog") {
            // Scrolling, as the screen does: what is below the window is still laid out.
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                releases.forEach { ReleaseCard(it) }
            }
        }
    }

    /** An installed source with an update waiting, the most a row carries. */
    @Test
    fun aSourceRowWithItsButtonsFits() {
        LayoutCheck.assertFits("A source in Extensions") {
            Column {
                SourceRow("Free Web Novel (Mirror of a Very Long Name)", "en", "", "1.4.12 · freewebnovel.com", null) {
                    TextButton(onClick = {}) { Text("Update") }
                    TextButton(onClick = {}) { Text("Remove") }
                }
                SourceRow("Royal Road", "en", "", "2.0.1", null) {
                    TextButton(onClick = {}) { Text("Remove") }
                }
            }
        }
    }
}
