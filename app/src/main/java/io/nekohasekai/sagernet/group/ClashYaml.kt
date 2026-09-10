package io.nekohasekai.sagernet.group

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.NodeId
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver

fun clashYaml(): Yaml {
    // Preserve credential text (0123, true, dates, etc.). Field parsers own
    // numeric/boolean conversion. Keep null and merge tags and all load limits.
    val loader = LoaderOptions().apply { codePointLimit = 32 * 1024 * 1024 }
    val dumper = DumperOptions()
    val resolver = object : Resolver() {
        override fun resolve(kind: NodeId, value: String?, implicit: Boolean): Tag {
            val tag = super.resolve(kind, value, implicit)
            return if (kind == NodeId.scalar && (tag == Tag.INT || tag == Tag.FLOAT || tag == Tag.BOOL || tag == Tag.TIMESTAMP)) {
                Tag.STR
            } else tag
        }
    }
    return Yaml(SafeConstructor(loader), Representer(dumper), dumper, loader, resolver)
}

// Retain YAML 1.1 boolean spellings, but only in fields that require booleans.
fun Any?.clashBoolean(): Boolean = when (this?.toString()?.lowercase()) {
    "true", "yes", "on" -> true
    else -> false
}
