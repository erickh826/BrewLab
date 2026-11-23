package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.BrewLabDatabase
import com.example.data.BrewRepository
import com.example.data.SeedData
import com.example.device.DemoScaleDataSource
import com.example.domain.model.SourceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read app name from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("BrewLab", appName)
  }

  @Test
  fun `room repository seeds demo recipe and sessions and persists edits`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, BrewLabDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val repo = BrewRepository(db)
      repo.ensureSeeded()

      val recipes = repo.recipes.first()
      assertEquals(1, recipes.size)
      assertTrue(recipes.first().isDemoTemplate)

      val sessions = repo.sessions.first()
      assertEquals(2, sessions.size)
      assertTrue(sessions.all { it.isSeededDemo && it.sourceType == SourceType.DEMO })

      // Duplicate and edit recipe without affecting existing session snapshots
      val copy = repo.duplicateRecipe(recipes.first())
      assertNotEquals(recipes.first().id, copy.id)

      val originalSessionSnapshotName = sessions.first().recipeSnapshot.name
      repo.saveRecipe(recipes.first().copy(name = "已修改的新配方名稱"), isNew = false)
      val sessionsAfterEdit = repo.sessions.first()
      assertEquals(originalSessionSnapshotName, sessionsAfterEdit.first().recipeSnapshot.name)
    } finally {
      db.close()
    }
  }

  @Test
  fun `demo scale data source connects, tares, and disconnects cleanly`() = runBlocking {
    val demo = DemoScaleDataSource()
    assertEquals(SourceType.DEMO, demo.sourceType)
    demo.connect()
    assertTrue(demo.connectionState.value.isConnected)
    demo.tare()
    demo.disconnect()
    assertEquals("未連線", demo.connectionState.value.label)
  }
}
