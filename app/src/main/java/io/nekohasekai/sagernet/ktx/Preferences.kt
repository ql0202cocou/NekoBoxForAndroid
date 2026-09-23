package io.nekohasekai.sagernet.ktx

import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import kotlin.reflect.KProperty

fun RoomPreferenceDataStore.string(
    name: String,
    defaultValue: () -> String = { "" },
) = PreferenceProxy(name, defaultValue, ::getString, ::putString)

fun RoomPreferenceDataStore.boolean(
    name: String,
    defaultValue: () -> Boolean = { false },
) = PreferenceProxy(name, defaultValue, ::getBoolean, ::putBoolean)

fun RoomPreferenceDataStore.int(
    name: String,
    defaultValue: () -> Int = { 0 },
) = PreferenceProxy(name, defaultValue, ::getInt, ::putInt)

fun RoomPreferenceDataStore.stringSet(
    name: String,
    defaultValue: () -> Set<String> = { setOf() },
) = PreferenceProxy(name, defaultValue, ::getStringSet, { key, value ->
    putStringSet(key, value.toMutableSet())
})

fun RoomPreferenceDataStore.stringToInt(
    name: String,
    defaultValue: () -> Int = { 0 },
) = PreferenceProxy(name, defaultValue, { key ->
    getString(key)?.toIntOrNull()
}, { key, value -> putString(key, "$value") })

fun RoomPreferenceDataStore.stringToIntIfExists(
    name: String,
    defaultValue: () -> Int = { 0 },
) = PreferenceProxy(name, defaultValue, { key ->
    getString(key)?.toIntOrNull()
}, { key, value -> putString(key, value.takeIf { it > 0 }?.toString() ?: "") })

fun RoomPreferenceDataStore.long(
    name: String,
    defaultValue: () -> Long = { 0L },
) = PreferenceProxy(name, defaultValue, ::getLong, ::putLong)

class PreferenceProxy<T>(
    val name: String,
    val defaultValue: () -> T,
    val getter: (String) -> T?,
    val setter: (String, value: T) -> Unit,
) {

    operator fun setValue(thisObj: Any?, property: KProperty<*>, value: T) = setter(name, value)

    // 惰性默认值：getter 只探测已存的值（null = 缺失），命中时不再求值
    // defaultValue —— selectedGroup 的默认值带锁 + DAO 查询，key 已存在时
    // 白跑一次的代价远高于一次空读
    operator fun getValue(thisObj: Any?, property: KProperty<*>) = getter(name) ?: defaultValue()

}