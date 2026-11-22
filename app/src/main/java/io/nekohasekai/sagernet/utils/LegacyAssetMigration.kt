package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.ktx.copyToLimited
import java.io.File
import java.io.IOException

// 只限制冷启动时自动迁移的旧外部文件；更大的自定义资源仍可在资源页手工导入。
internal const val LEGACY_ASSET_MAX_BYTES = 256L * 1024 * 1024
private const val LIMIT_MESSAGE = "legacy asset exceeds automatic migration limit"
private const val TEMP_PREFIX = ".legacy-"
private const val TEMP_SUFFIX = ".tmp"

// 早于此时间未再写入的临时文件视为被杀进程遗留；另一个进程正在写的临时文件修改时间是新的，不会被误删。
internal const val LEGACY_TEMP_STALE_MS = 10 * 60 * 1000L

/** 删除进程在复制途中被杀留下的临时文件；每次尝试都换新名字，不清理就会逐次累积。 */
internal fun cleanupStaleLegacyAssetTemps(dir: File, now: Long = System.currentTimeMillis()) {
    dir.listFiles { file ->
        file.isFile && file.name.startsWith(TEMP_PREFIX) && file.name.endsWith(TEMP_SUFFIX) &&
            now - file.lastModified() >= LEGACY_TEMP_STALE_MS
    }?.forEach { it.delete() }
}

/** 临时文件完整写入后再发布；复制或发布失败保留旧源文件与原目标，并移除半写文件。 */
internal fun migrateLegacyAsset(source: File, target: File, maxBytes: Long = LEGACY_ASSET_MAX_BYTES) {
    require(maxBytes >= 0)
    if (source.length() > maxBytes) throw IOException(LIMIT_MESSAGE)
    val tmp = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, target.parentFile)
    try {
        source.inputStream().use { input ->
            tmp.outputStream().use { input.copyToLimited(it, maxBytes, LIMIT_MESSAGE) }
        }
        if (target.isDirectory || !tmp.renameTo(target)) throw IOException("cannot publish migrated asset")
        // 源文件的删除失败允许下次重试，不能在未搬完时写迁移完成标记。
        if (!source.delete() && source.exists()) throw IOException("cannot remove migrated source")
    } finally {
        tmp.delete()
    }
}
