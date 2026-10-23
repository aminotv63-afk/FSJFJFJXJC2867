package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("متصفح الألعاب", appName)

    val gameUrl = context.getString(R.string.game_url)
    assertEquals("https://v2.fiveplay.lol/launcher.html", gameUrl)
    assertEquals(MainActivity.GAME_LAUNCHER_URL, gameUrl)

    val directUrl = context.getString(R.string.direct_game_url)
    assertEquals(MainActivity.DIRECT_GAME_URL, directUrl)
  }
}
