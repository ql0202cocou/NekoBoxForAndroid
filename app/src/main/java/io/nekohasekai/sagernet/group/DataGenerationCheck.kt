package io.nekohasekai.sagernet.group

/**
 * 订阅更新跨过了一次备份恢复：executeUpdate 拿到更新锁、读分组行之前记下的数据世代号（database/DataGeneration）
 * 与写库事务里重新读到的不一致。恢复按备份原 id 插回节点与分组，更新手里按 id 算出的删除 / 覆写 / 改序号会落到
 * 恢复出来的同 id 行上，新节点也会与恢复出的节点并存，所以整次放弃：不写节点、不写 lastUpdated。
 *
 * 只在订阅更新比对：它是唯一在下载与强制解析期间（可长达数十秒）持有旧快照、再按 id 批量写回的任务。编辑器保存
 * 只在主进程，恢复期间主界面被不可取消的进度框挡住；整组测速收尾按配置比对（A04），恢复换掉的行对不上就不写。
 *
 * 不是失败也不是取消：恢复成功后两个进程马上重启，提示用户也看不到，executeUpdate 只记日志；
 * 自动更新下个周期按恢复后的数据重跑。
 */
class DataRestoredDuringUpdateException(groupId: Long) :
    Exception("Data was replaced by a backup restore during the update of group $groupId")

/** [started] 是更新开始时读到的世代号，[current] 是写库事务里读到的。不等（包括变小）就说明期间整库被换过。 */
fun isDataRestoredSince(started: Long, current: Long): Boolean = current != started
