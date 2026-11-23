package com.example

import android.app.Application
import androidx.room.Room
import com.example.brew.BrewRecordingCoordinator
import com.example.data.AppPreferences
import com.example.data.BrewLabDatabase
import com.example.data.BrewRepository
import com.example.device.ScaleDeviceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BrewLabApplication : Application() {

  private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  val database: BrewLabDatabase by lazy {
    Room.databaseBuilder(
      applicationContext,
      BrewLabDatabase::class.java,
      "brewlab.db",
    ).build()
  }

  val repository: BrewRepository by lazy {
    BrewRepository(database)
  }

  val preferences: AppPreferences by lazy {
    AppPreferences(applicationContext)
  }

  val deviceManager: ScaleDeviceManager by lazy {
    ScaleDeviceManager()
  }

  val brewCoordinator: BrewRecordingCoordinator by lazy {
    BrewRecordingCoordinator(
      repository = repository,
      deviceManager = deviceManager,
      preferences = preferences,
    )
  }

  override fun onCreate() {
    super.onCreate()
    appScope.launch {
      repository.ensureSeeded()
    }
  }
}
