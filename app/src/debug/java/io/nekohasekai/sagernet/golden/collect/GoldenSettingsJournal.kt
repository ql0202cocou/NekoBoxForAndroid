package io.nekohasekai.sagernet.golden.collect

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.Strictness
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import java.io.File
import java.util.Base64

// 采集与实测共用：夹具所在的规则、节点、分组三表在一个事务里清空，then 在同一事务里接着写入
internal fun resetGoldenTables(then: () -> Unit = {}) {
    SagerDatabase.instance.runInTransaction {
        SagerDatabase.rulesDao.reset()
        SagerDatabase.proxyDao.reset()
        SagerDatabase.groupDao.reset()
        then()
    }
}

// 两个入口由 provider 串行调用。正式标记发布后才可写夹具，恢复成功前始终保留最初的快照。
internal class GoldenSettingsJournal(private val marker: File) {
    private val gson = GsonBuilder().disableHtmlEscaping().setStrictness(Strictness.STRICT).create()

    fun begin(): List<KeyValuePair> {
        if (marker.exists()) return read()
        val saved = PublicDatabase.kvPairDao.all()
        val rows = saved.map {
            mapOf("key" to it.key, "valueType" to it.valueType, "value" to Base64.getEncoder().encodeToString(it.value))
        }
        val tmp = File(marker.parentFile, "${marker.name}.tmp")
        try {
            tmp.outputStream().use {
                it.write(gson.toJson(rows).toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            check(tmp.renameTo(marker)) { "cannot publish ${marker.name}" }
        } finally {
            tmp.delete()
        }
        return saved
    }

    fun read(): List<KeyValuePair> {
        if (!marker.exists()) throw CollectRefusedException("no prepared session owns ${marker.name}")
        return try {
            // 兼容旧版的 JSON 数组。整份验证完再允许清理；重复键也无法恢复到原表。
            val keys = HashSet<String>()
            gson.fromJson(marker.readText(), JsonArray::class.java).map { item ->
                val row = item.asJsonObject
                KeyValuePair().apply {
                    key = row.getAsJsonPrimitive("key").also { require(it.isString) }.asString
                    require(keys.add(key))
                    valueType = row.getAsJsonPrimitive("valueType").also { require(it.isNumber) }.asString.toInt()
                    value = Base64.getDecoder().decode(row.getAsJsonPrimitive("value").also { require(it.isString) }.asString)
                }
            }
        } catch (_: Exception) {
            throw CollectRefusedException("cannot read original settings from ${marker.name}; preserve the marker for recovery")
        }
    }

    fun restore(saved: List<KeyValuePair>) {
        PublicDatabase.instance.runInTransaction {
            PublicDatabase.kvPairDao.reset()
            PublicDatabase.kvPairDao.insert(saved)
        }
        check(marker.delete()) { "cannot remove ${marker.name} after restoring settings" }
    }
}
