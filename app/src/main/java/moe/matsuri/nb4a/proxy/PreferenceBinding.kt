package moe.matsuri.nb4a.proxy

import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.database.EditorCache

object Type {
    const val Text = 0
    const val TextToInt = 1
    const val Bool = 3
}

class PreferenceBinding(
    val type: Int = Type.Text,
    var fieldName: String,
    var bean: Any? = null,
    var pf: PreferenceFragmentCompat? = null
) {

    var cacheName = fieldName
    var disable = false

    fun readStringFromCache(): String {
        return EditorCache.profileCacheStore.getString(cacheName) ?: ""
    }

    fun readBoolFromCache(): Boolean {
        return EditorCache.profileCacheStore.getBoolean(cacheName, false)
    }

    fun readStringToIntFromCache(): Int {
        val value = EditorCache.profileCacheStore.getString(cacheName)?.toIntOrNull() ?: 0
//        Logs.d("readStringToIntFromCache $value $cacheName -> $fieldName")
        return value
    }

    fun fromCache() {
        if (disable) return
        val f = try {
            bean!!.javaClass.getField(fieldName)
        } catch (e: Exception) {
            Logs.d("binding no field: ${e.readableMessage}")
            return
        }
        when (type) {
            Type.Text -> f.set(bean, readStringFromCache())
            Type.TextToInt -> f.set(bean, readStringToIntFromCache())
            Type.Bool -> f.set(bean, readBoolFromCache())
        }
    }

    fun writeToCache() {
        if (disable) return
        val f = try {
            bean!!.javaClass.getField(fieldName)
        } catch (e: Exception) {
            Logs.d("binding no field: ${e.readableMessage}")
            return
        }
        val value = f.get(bean)
        when (type) {
            Type.Text -> {
                if (value is String) {
//                    Logs.d("writeToCache TEXT $value $cacheName -> $fieldName")
                    EditorCache.profileCacheStore.putString(cacheName, value)
                }
            }
            Type.TextToInt -> {
                if (value is Int) {
//                    Logs.d("writeToCache TEXT2INT $value $cacheName -> $fieldName")
                    EditorCache.profileCacheStore.putString(cacheName, value.toString())
                }
            }
            Type.Bool -> {
                if (value is Boolean) {
                    EditorCache.profileCacheStore.putBoolean(cacheName, value)
                }
            }
        }
    }

    // A restored editor may replace its preference fragment after cache init.
    // Resolve against the current fragment rather than retaining its old view.
    val preference
        get() = checkNotNull(pf) { "preference fragment not set: $cacheName" }
            .findPreference<Preference>(cacheName)
            ?: error("preference not found: $cacheName")
}
