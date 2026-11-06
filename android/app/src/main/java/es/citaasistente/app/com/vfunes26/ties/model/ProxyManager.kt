// android/app/src/main/java/com/vfunes26/ties/model/ProxyManager.kt
package com.vfunes26.ties.model

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class ProxyConfig(
    val server: String,
    val username: String,
    val password: String
)

class ProxyManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("cita_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_PROXY_ENABLED = "proxy_enabled"
        private const val KEY_PROXY_LIST = "proxy_list_json"
    }

    var isProxyEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROXY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_PROXY_ENABLED, value).apply()

    fun getProxies(): List<ProxyConfig> {
        val json = prefs.getString(KEY_PROXY_LIST, null) ?: return emptyList()
        val type = object : TypeToken<List<ProxyConfig>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    fun saveProxies(proxies: List<ProxyConfig>) {
        val json = gson.toJson(proxies)
        prefs.edit().putString(KEY_PROXY_LIST, json).apply()
    }

    fun addProxy(proxy: ProxyConfig) {
        val current = getProxies().toMutableList()
        current.add(proxy)
        saveProxies(current)
    }

    fun removeProxy(proxy: ProxyConfig) {
        val current = getProxies().toMutableList()
        current.remove(proxy)
        saveProxies(current)
    }

    fun toggleProxy(): Boolean {
        isProxyEnabled = !isProxyEnabled
        return isProxyEnabled
    }
}