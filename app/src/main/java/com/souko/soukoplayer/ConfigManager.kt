package com.souko.soukoplayer

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

// 创建 DataStore 实例
private val Context.dataStore by preferencesDataStore(name = "server_config")

object ConfigManager {

    // Preferences key
    private val KEY_SERVER_URL = stringPreferencesKey("server_url")
    private val KEY_USERNAME = stringPreferencesKey("username")
    private val KEY_PASSWORD = stringPreferencesKey("password")

    // 获取服务器 URL
    suspend fun getServerUrl(context: Context): String? {
        val prefs = context.dataStore.data.first()
        return prefs[KEY_SERVER_URL]
    }

    // 获取用户名
    suspend fun getUsername(context: Context): String? {
        val prefs = context.dataStore.data.first()
        return prefs[KEY_USERNAME]
    }

    // 获取密码
    suspend fun getPassword(context: Context): String? {
        val prefs = context.dataStore.data.first()
        return prefs[KEY_PASSWORD]
    }

    // 保存配置
    suspend fun saveConfig(context: Context, serverUrl: String, username: String, password: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SERVER_URL] = serverUrl
            preferences[KEY_USERNAME] = username
            preferences[KEY_PASSWORD] = password
        }
    }

    // 清空配置（用于退出登录或重置配置）
    suspend fun clearConfig(context: Context) {
        context.dataStore.edit { it.clear() }
    }
}