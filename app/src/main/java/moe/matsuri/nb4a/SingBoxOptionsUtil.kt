package moe.matsuri.nb4a

import io.nekohasekai.sagernet.database.DataStore
import moe.matsuri.nb4a.SingBoxOptions.RuleSet

object SingBoxOptionsUtil {

    fun domainStrategy(tag: String): String {
        fun auto2(key: String, newS: String): String {
            return (DataStore.configurationStore.getString(key) ?: "").replace("auto", newS)
        }
        return when (tag) {
            "dns-remote" -> {
                auto2("domain_strategy_for_remote", "")
            }

            "dns-direct" -> {
                auto2("domain_strategy_for_direct", "")
            }

            // server
            else -> {
                auto2("domain_strategy_for_server", "prefer_ipv4")
            }
        }
    }

}

// 按前缀把域名规则分拣到各字段：geosite: 进 rule_set，full: / domain: / regexp: /
// keyword: 各归其位，无前缀按 domain_suffix；除 rule_set 外都转小写
private class DomainRuleLists(list: List<String>) {
    val ruleSet = mutableListOf<String>()
    val domain = mutableListOf<String>()
    val domainSuffix = mutableListOf<String>()
    val domainRegex = mutableListOf<String>()
    val domainKeyword = mutableListOf<String>()

    init {
        list.forEach {
            if (it.startsWith("geosite:")) {
                ruleSet.plusAssign(it)
            } else if (it.startsWith("full:")) {
                domain.plusAssign(it.removePrefix("full:").lowercase())
            } else if (it.startsWith("domain:")) {
                domainSuffix.plusAssign(it.removePrefix("domain:").lowercase())
            } else if (it.startsWith("regexp:")) {
                domainRegex.plusAssign(it.removePrefix("regexp:").lowercase())
            } else if (it.startsWith("keyword:")) {
                domainKeyword.plusAssign(it.removePrefix("keyword:").lowercase())
            } else {
                domainSuffix.plusAssign(it.lowercase())
            }
        }
    }
}

// 原地去掉空白项；剩下空列表时返回 null（该字段不输出）
private fun MutableList<String>?.nonBlankOrNull(): MutableList<String>? =
    this?.apply { removeIf { it.isNullOrBlank() } }?.takeIf { it.isNotEmpty() }

fun SingBoxOptions.DNSRule_DefaultOptions.makeSingBoxRule(list: List<String>) {
    val lists = DomainRuleLists(list)
    rule_set = lists.ruleSet.nonBlankOrNull()
    domain = lists.domain.nonBlankOrNull()
    domain_suffix = lists.domainSuffix.nonBlankOrNull()
    domain_regex = lists.domainRegex.nonBlankOrNull()
    domain_keyword = lists.domainKeyword.nonBlankOrNull()
}

fun SingBoxOptions.DNSRule_DefaultOptions.checkEmpty(): Boolean {
    if (rule_set?.isNotEmpty() == true) return false
    if (domain?.isNotEmpty() == true) return false
    if (domain_suffix?.isNotEmpty() == true) return false
    if (domain_regex?.isNotEmpty() == true) return false
    if (domain_keyword?.isNotEmpty() == true) return false
    if (user_id?.isNotEmpty() == true) return false
    return true
}

fun generateRuleSet(ruleSetString: List<String>, ruleSet: MutableList<RuleSet>) {
    ruleSetString.forEach {
        if (it.startsWith("geoip:") || it.startsWith("geosite:")) {
            ruleSet.add(RuleSet().apply {
                type = "local"
                tag = it
                format = "binary"
                path = it
            })
        }
    }
}

fun SingBoxOptions.Rule_DefaultOptions.makeSingBoxRule(list: List<String>, isIP: Boolean) {
    // the domain and IP stages run as two separate calls on one rule; keep the
    // rule_set entries already collected instead of dropping the other stage's
    if (rule_set == null) rule_set = mutableListOf<String>()
    if (isIP) {
        ip_cidr = mutableListOf<String>()
        list.forEach {
            if (it.startsWith("geoip:")) {
                if (it == "geoip:private") {
                    ip_is_private = true
                } else {
                    rule_set.plusAssign(it)
                }
            } else {
                ip_cidr.plusAssign(it)
            }
        }
    } else {
        val lists = DomainRuleLists(list)
        rule_set.addAll(lists.ruleSet)
        domain = lists.domain
        domain_suffix = lists.domainSuffix
        domain_regex = lists.domainRegex
        domain_keyword = lists.domainKeyword
    }
    ip_cidr = ip_cidr.nonBlankOrNull()
    rule_set = rule_set.nonBlankOrNull()
    domain = domain.nonBlankOrNull()
    domain_suffix = domain_suffix.nonBlankOrNull()
    domain_regex = domain_regex.nonBlankOrNull()
    domain_keyword = domain_keyword.nonBlankOrNull()
}

fun SingBoxOptions.Rule_DefaultOptions.checkEmpty(): Boolean {
    if (ip_cidr?.isNotEmpty() == true) return false
    if (ip_is_private == true) return false
    if (domain?.isNotEmpty() == true) return false
    if (rule_set?.isNotEmpty() == true) return false
    if (domain_suffix?.isNotEmpty() == true) return false
    if (domain_regex?.isNotEmpty() == true) return false
    if (domain_keyword?.isNotEmpty() == true) return false
    if (user_id?.isNotEmpty() == true) return false
    //
    if (port?.isNotEmpty() == true) return false
    if (port_range?.isNotEmpty() == true) return false
    if (source_port?.isNotEmpty() == true) return false
    if (source_port_range?.isNotEmpty() == true) return false
    if (source_ip_cidr?.isNotEmpty() == true) return false
    if (network?.isNotEmpty() == true) return false
    if (protocol?.isNotEmpty() == true) return false
    //
    if (!_hack_custom_config.isNullOrBlank()) return false
    return true
}
