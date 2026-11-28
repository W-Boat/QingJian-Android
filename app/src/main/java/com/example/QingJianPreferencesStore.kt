package com.example

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ui.theme.QingJianAccent
import com.qingjian.core.eta.AiAccountProfile
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.qingJianDataStore: DataStore<Preferences> by preferencesDataStore(
  name = "qingjian_eta_prefs",
)

data class PersistedAppPreferences(
  val isDarkTheme: Boolean = false,
  val accent: QingJianAccent = QingJianAccent.JADE,
  val activeProfileId: String = "prof_deepseek",
  val aiProfiles: List<AiAccountProfile>? = null,
)

/**
 * Real Android DataStore persistence for Miuix appearance and Eta AI account profiles
 * (including ChatGPT Codex OAuth tokens, refresh tokens, and API credentials).
 */
class QingJianPreferencesStore(private val context: Context) {
  private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
  private val profileListType = Types.newParameterizedType(List::class.java, AiAccountProfile::class.java)
  private val profileListAdapter = moshi.adapter<List<AiAccountProfile>>(profileListType)

  private object Keys {
    val DARK_THEME = booleanPreferencesKey("ui_dark_theme")
    val ACCENT_NAME = stringPreferencesKey("ui_accent_name")
    val ACTIVE_PROFILE_ID = stringPreferencesKey("eta_active_profile_id")
    val AI_PROFILES_JSON = stringPreferencesKey("eta_ai_profiles_json")
  }

  val preferencesFlow: Flow<PersistedAppPreferences> = context.qingJianDataStore.data.map { prefs ->
    val dark = prefs[Keys.DARK_THEME] ?: false
    val accentName = prefs[Keys.ACCENT_NAME] ?: QingJianAccent.JADE.name
    val accent = runCatching { QingJianAccent.valueOf(accentName) }.getOrDefault(QingJianAccent.JADE)
    val activeId = prefs[Keys.ACTIVE_PROFILE_ID] ?: "prof_deepseek"
    val profilesJson = prefs[Keys.AI_PROFILES_JSON]
    val profiles = if (!profilesJson.isNullOrBlank()) {
      runCatching { profileListAdapter.fromJson(profilesJson) }.getOrNull()
    } else {
      null
    }
    PersistedAppPreferences(
      isDarkTheme = dark,
      accent = accent,
      activeProfileId = activeId,
      aiProfiles = profiles,
    )
  }

  suspend fun saveDarkTheme(dark: Boolean) {
    context.qingJianDataStore.edit { it[Keys.DARK_THEME] = dark }
  }

  suspend fun saveAccent(accent: QingJianAccent) {
    context.qingJianDataStore.edit { it[Keys.ACCENT_NAME] = accent.name }
  }

  suspend fun saveActiveProfileId(profileId: String) {
    context.qingJianDataStore.edit { it[Keys.ACTIVE_PROFILE_ID] = profileId }
  }

  suspend fun saveProfiles(profiles: List<AiAccountProfile>, activeProfileId: String) {
    val json = profileListAdapter.toJson(profiles)
    context.qingJianDataStore.edit {
      it[Keys.AI_PROFILES_JSON] = json
      it[Keys.ACTIVE_PROFILE_ID] = activeProfileId
    }
  }
}
