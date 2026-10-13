package io.nekohasekai.sagernet.database

// 界面层访问路由规则数据的入口：转发 ProfileManager 里的规则部分与规则 DAO（规则的写入实现仍在
// ProfileManager，监听器接口是 ProfileManager.RuleListener）；线程契约见 ProfileRepository。
//
// 线程：非 suspend 的方法（getRule、updateRuleOrders、updateRuleEnabled）是阻塞调用，靠
// allowMainThreadQueries 才能在主线程调用，新代码应放到后台线程；suspend 方法不自行换调度器。
//
// 事务：只有 updateRuleOrders 整批一个事务；其余都是单条语句，各自提交。
//
// 通知：createRule、updateRule、deleteRule、deleteRules、clearRules 写完通知 RuleListener
// （createRule 的 post=false 除外，仅供默认规则初始化）。updateRuleOrders 与 updateRuleEnabled
// 只写列，不通知。getRules 在首次使用时创建默认规则，getRule 只读，不触发默认规则。
object RuleRepository {

    fun addListener(listener: ProfileManager.RuleListener) = ProfileManager.addListener(listener)

    fun removeListener(listener: ProfileManager.RuleListener) = ProfileManager.removeListener(listener)

    // 按 id 读一行，不存在返回 null；直接查 DAO，不触发默认规则创建
    fun getRule(ruleId: Long): RuleEntity? = SagerDatabase.rulesDao.getById(ruleId)

    // 全部规则（按 userOrder）；首次调用会先创建默认规则，见 ProfileManager.getRules
    suspend fun getRules(): List<RuleEntity> = ProfileManager.getRules()

    suspend fun createRule(rule: RuleEntity): RuleEntity = ProfileManager.createRule(rule)

    suspend fun updateRule(rule: RuleEntity) = ProfileManager.updateRule(rule)

    suspend fun deleteRule(ruleId: Long) = ProfileManager.deleteRule(ruleId)

    suspend fun deleteRules(rules: List<RuleEntity>) = ProfileManager.deleteRules(rules)

    suspend fun clearRules() = ProfileManager.clearRules()

    fun updateRuleOrders(rules: Collection<RuleEntity>) = ProfileManager.updateRuleOrders(rules)

    fun updateRuleEnabled(ruleId: Long, enabled: Boolean) = ProfileManager.updateRuleEnabled(ruleId, enabled)

}
