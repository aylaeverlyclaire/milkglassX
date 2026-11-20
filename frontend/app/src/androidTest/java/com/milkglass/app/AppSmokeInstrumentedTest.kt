package com.milkglass.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()
    private fun hasText(text: String, substring: Boolean = false): Boolean =
        runCatching {
            composeRule.onAllNodesWithText(text, substring = substring, useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }.getOrDefault(false)

    private fun ensureLoggedInOnHome() {
        composeRule.waitUntil(timeoutMillis = 25_000L) {
            hasText("Lanjutkan dengan Google") || hasText("Beranda")
        }
        if (hasText("Lanjutkan dengan Google")) {
            composeRule.onAllNodesWithText("Lanjutkan dengan Google", useUnmergedTree = true)
                .onFirst()
                .performClick()
        }
        composeRule.waitUntil(timeoutMillis = 20_000L) {
            hasText("Beranda") && hasText("Milkglass")
        }
    }

    @Test
    fun appBootsLoginAndNavigatesMainTabs() {
        ensureLoggedInOnHome()

        composeRule.waitUntil(timeoutMillis = 15_000L) {
            hasText("Terpopuler") || hasText("Tayang hari ini") || hasText("Detail")
        }

        composeRule.onAllNodesWithText("Jadwal", useUnmergedTree = true).onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000L) { hasText("Jadwal Rilis") }

        composeRule.onAllNodesWithText("Sosial", useUnmergedTree = true).onFirst().performClick()
        composeRule.onAllNodesWithText("Koleksi", useUnmergedTree = true).onFirst().performClick()
        composeRule.onAllNodesWithText("Beranda", useUnmergedTree = true).onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000L) { hasText("Milkglass") }

        assertFalse("MainActivity should remain alive after tab navigation", composeRule.activity.isFinishing)
    }

    @Test
    fun openDetailAndPlayerDoesNotCrash() {
        ensureLoggedInOnHome()

        composeRule.waitUntil(timeoutMillis = 20_000L) { hasText("Detail") }
        composeRule.onAllNodesWithText("Detail", useUnmergedTree = true).onFirst().performClick()

        composeRule.waitUntil(timeoutMillis = 15_000L) {
            hasText("Mulai nonton Ep 1") ||
                hasText("Tonton ulang Ep 1") ||
                hasText("Lanjut Ep", substring = true) ||
                hasText("Selengkapnya")
        }

        val playLabel = when {
            hasText("Mulai nonton Ep 1") -> "Mulai nonton Ep 1"
            hasText("Tonton ulang Ep 1") -> "Tonton ulang Ep 1"
            hasText("Lanjut Ep", substring = true) -> "Lanjut Ep"
            else -> null
        }

        if (playLabel != null) {
            composeRule.onAllNodesWithText(playLabel, substring = (playLabel == "Lanjut Ep"), useUnmergedTree = true)
                .onFirst()
                .performClick()
            Thread.sleep(3_000L)
        }

        assertFalse("MainActivity should remain alive after opening Detail/Player", composeRule.activity.isFinishing)
    }
}
