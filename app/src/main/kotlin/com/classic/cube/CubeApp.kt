package com.classic.cube

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.classic.cube.data.PrefsRepository
import com.classic.cube.data.SavedGameRepository
import com.classic.cube.data.StatsRepository

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Hand-rolled dependency container; the app is small enough not to need a DI framework. */
class CubeApp : Application() {
    val prefs by lazy { PrefsRepository(dataStore) }
    val savedGames by lazy { SavedGameRepository(dataStore) }
    val stats by lazy { StatsRepository(dataStore) }
}
