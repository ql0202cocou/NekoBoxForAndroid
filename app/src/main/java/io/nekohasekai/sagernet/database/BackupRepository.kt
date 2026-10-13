package io.nekohasekai.sagernet.database

import java.util.concurrent.Callable

// 备份导出读取数据的入口。节点、分组、规则在同一个 Room 事务里读完：导出期间别处的写入
// （订阅更新、编辑、删除）不会让三张表互相对不上。设置在另一个数据库（PublicDatabase），
// 跨库没有同一事务，也与这三张表没有引用关系，不在这里读。
//
// 线程：阻塞调用，靠 allowMainThreadQueries 才能在主线程调用，应在后台线程调用。只读，不通知监听器。
object BackupRepository {

    class Snapshot(
        val profiles: List<ProxyEntity>?,
        val groups: List<ProxyGroup>?,
        val rules: List<RuleEntity>?,
    )

    // profile 同时决定节点与分组两张表（备份里它们成对出现）；没选的部分为 null。
    // 两项都没选时不开事务，直接返回 null
    fun readSnapshot(profile: Boolean, rule: Boolean): Snapshot? {
        if (!profile && !rule) return null
        return SagerDatabase.instance.runInTransaction(Callable {
            Snapshot(
                if (profile) SagerDatabase.proxyDao.getAll() else null,
                if (profile) SagerDatabase.groupDao.allGroups() else null,
                if (rule) SagerDatabase.rulesDao.allRules() else null,
            )
        })
    }

}
