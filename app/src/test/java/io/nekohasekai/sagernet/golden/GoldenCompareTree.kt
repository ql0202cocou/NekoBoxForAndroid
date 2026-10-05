package io.nekohasekai.sagernet.golden

import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

// 比较两棵采集产物目录树（格式 v2，v1 的布局相同）：
// <根>/manifest.json（只打印）、scenarios/<id>/input.json、scenarios/<id>/<mode>/{result.json, sing-box.json,
// ext-<n>.<pluginId>.<json|yaml>, export.txt}、address/corpus.json。
// 每个场景的每个模式单独编号占位符；格式之外的文件按字节比较并给出警告。

/** 比较范围。 */
enum class GoldenCompareScope(val label: String) {
    /** 全部产物。 */
    ALL("全部"),

    /**
     * 只比 sing-box 一侧：input.json（不含 formatVersion）、sing-box.json、export.txt 第 0 段、result.json（不含 external）。
     * 外核配置（ext-*、export.txt 第 1 段起、result.json 的 external）与带格式版本号的 address/corpus.json 不比较，
     * 用来证明外核一侧的改动（含产物格式升级）没有动到 sing-box 一侧。
     */
    SING_BOX("只比 sing-box 一侧"),
}

class GoldenTreeReport(
    val expectedRoot: File,
    val actualRoot: File,
    val manifest: List<String>,
    val diffs: List<GoldenDiff>,
    val warnings: List<String>,
    val scope: GoldenCompareScope = GoldenCompareScope.ALL,
) {
    val same get() = diffs.isEmpty()

    /** 可读的报告；每个文件最多列 [maxPerFile] 处差异并给出总数。 */
    fun render(maxPerFile: Int = 20): String {
        val sb = StringBuilder()
        sb.appendLine("配置结构比较")
        sb.appendLine("预期（基线）：${expectedRoot.path}")
        sb.appendLine("实际（新采集）：${actualRoot.path}")
        sb.appendLine("比较范围：${scope.label}")
        sb.appendLine("manifest：")
        manifest.forEach { sb.appendLine("  $it") }
        if (warnings.isNotEmpty()) {
            sb.appendLine("警告 ${warnings.size} 条：")
            warnings.forEach { sb.appendLine("  - $it") }
        }
        if (diffs.isNotEmpty()) {
            val byFile = diffs.groupBy { it.file }
            sb.appendLine("差异 ${diffs.size} 处，涉及 ${byFile.size} 个文件：")
            for ((file, list) in byFile) {
                sb.appendLine("  $file（${list.size} 处）")
                for (d in list.take(maxPerFile)) {
                    sb.appendLine(if (d.path.isEmpty()) "    ${d.message}" else "    ${d.path}：${d.message}")
                }
                if (list.size > maxPerFile) sb.appendLine("    …另有 ${list.size - maxPerFile} 处未列出")
            }
        }
        sb.appendLine(if (same) "结论：一致" else "结论：不一致（${diffs.size} 处差异）")
        return sb.toString()
    }
}

object GoldenCompareTree {

    private const val SIDE_E = "预期"
    private const val SIDE_A = "实际"
    private val extName = Regex("ext-(0|[1-9][0-9]*)\\.[^/]+\\.(json|yaml)")

    fun compare(expectedRoot: File, actualRoot: File, scope: GoldenCompareScope = GoldenCompareScope.ALL): GoldenTreeReport =
        Run(expectedRoot, actualRoot, scope).run()

    private class Run(val e: File, val a: File, val scope: GoldenCompareScope) {
        val singBoxOnly = scope == GoldenCompareScope.SING_BOX
        val diffs = ArrayList<GoldenDiff>()
        val warnings = ArrayList<String>()
        val handled = HashSet<String>()
        lateinit var eFiles: Set<String>
        lateinit var aFiles: Set<String>

        fun run(): GoldenTreeReport {
            val manifest = listOf("$SIDE_E：${manifestLine(e)}", "$SIDE_A：${manifestLine(a)}")
            if (!e.isDirectory || !a.isDirectory) {
                if (!e.isDirectory) diffs.add(GoldenDiff(e.path, "", "预期目录不存在"))
                if (!a.isDirectory) diffs.add(GoldenDiff(a.path, "", "实际目录不存在"))
                return GoldenTreeReport(e, a, manifest, diffs, warnings, scope)
            }
            eFiles = listFiles(e)
            aFiles = listFiles(a)
            // manifest.json 只打印；根目录的 README.md 是基线归档里的说明文档，不参与比较
            handled.add("manifest.json")
            handled.add("README.md")

            val ids = (subdirs(e, "scenarios") + subdirs(a, "scenarios")).toSortedSet()
            if (ids.isEmpty()) diffs.add(GoldenDiff("scenarios/", "", "两侧都没有任何场景"))
            for (id in ids) compareScenario("scenarios/$id")

            if (singBoxOnly) handled.add("address/corpus.json") else compareFile("address/corpus.json")

            for (rel in (eFiles + aFiles - handled).sorted()) {
                val inE = rel in eFiles
                val inA = rel in aFiles
                when {
                    !inA -> diffs.add(GoldenDiff(rel, "", "格式之外的文件，只在预期一侧存在"))
                    !inE -> diffs.add(GoldenDiff(rel, "", "格式之外的文件，只在实际一侧存在"))
                    else -> {
                        warnings.add("$rel 不在产物格式内，按字节比较")
                        if (!File(e, rel).readBytes().contentEquals(File(a, rel).readBytes())) {
                            diffs.add(GoldenDiff(rel, "", "格式之外的文件，两侧字节不同"))
                        }
                    }
                }
            }
            return GoldenTreeReport(e, a, manifest, diffs, warnings, scope)
        }

        fun compareScenario(rel: String) {
            if (!onBothSides(rel)) return
            // 只比 sing-box 一侧时两侧可能是不同格式版本的产物
            compareFile("$rel/input.json", if (singBoxOnly) setOf("formatVersion") else emptySet())
            for (mode in (subdirs(e, rel) + subdirs(a, rel)).toSortedSet()) {
                val modeRel = "$rel/$mode"
                if (onBothSides(modeRel)) compareMode(modeRel)
            }
        }

        // 目录只在一侧时记一处差异，并把其下文件都算作已处理
        fun onBothSides(rel: String): Boolean {
            val inE = File(e, rel).isDirectory
            val inA = File(a, rel).isDirectory
            if (inE && inA) return true
            diffs.add(GoldenDiff("$rel/", "", if (inE) "整个目录只在预期一侧存在" else "整个目录只在实际一侧存在"))
            (eFiles + aFiles).filterTo(handled) { it.startsWith("$rel/") }
            return false
        }

        // 不做动态值替换的严格比较（input.json、address/corpus.json）；ignoredKeys 是不比较的顶层键
        fun compareFile(rel: String, ignoredKeys: Set<String> = emptySet()) {
            handled.add(rel)
            val inE = rel in eFiles
            val inA = rel in aFiles
            when {
                !inE && !inA -> diffs.add(GoldenDiff(rel, "", "两侧都缺少这个文件"))
                !inA -> diffs.add(GoldenDiff(rel, "", "只在预期一侧存在"))
                !inE -> diffs.add(GoldenDiff(rel, "", "只在实际一侧存在"))
                else -> {
                    val r = GoldenCompare.compareDocumentSets(
                        listOf(withoutKeys(document(e, rel, rel, GoldenFormat.JSON), ignoredKeys)), GoldenDynamic.NONE,
                        listOf(withoutKeys(document(a, rel, rel, GoldenFormat.JSON), ignoredKeys)), GoldenDynamic.NONE,
                    )
                    diffs.addAll(r.diffs)
                    warnings.addAll(r.warnings)
                }
            }
        }

        fun compareMode(rel: String) {
            val (eDocs, eDyn) = modeDocuments(e, rel, SIDE_E)
            val (aDocs, aDyn) = modeDocuments(a, rel, SIDE_A)
            if (eDocs.none { it.name == "result.json" } && aDocs.none { it.name == "result.json" }) {
                diffs.add(GoldenDiff("$rel/result.json", "", "两侧都缺少 result.json"))
            }
            // 只比 sing-box 一侧时外核一侧的动态值（测速控制端口、CA 路径、secret）本来就不出现，不给警告
            val r = GoldenCompare.compareDocumentSets(eDocs, eDyn, aDocs, aDyn, "$rel/", reportUnused = !singBoxOnly)
            diffs.addAll(r.diffs)
            warnings.addAll(r.warnings)
        }

        // 按约定的遍历次序给出本模式的文档：sing-box.json、ext-0、ext-1……、export.txt 各段，最后 result.json。
        // 只比 sing-box 一侧时外核配置只记为已处理，export.txt 只取第 0 段
        fun modeDocuments(root: File, rel: String, side: String): Pair<List<GoldenDocument>, GoldenDynamic> {
            val names = File(root, rel).listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()
            val docs = ArrayList<GoldenDocument>()
            if ("sing-box.json" in names) {
                docs.add(document(root, "$rel/sing-box.json", "sing-box.json", GoldenFormat.JSON_OBJECT))
            }
            names.mapNotNull { name -> extName.matchEntire(name)?.let { Triple(it.groupValues[1].toInt(), name, it.groupValues[2]) } }
                .sortedWith(compareBy({ it.first }, { it.second }))
                .forEach { (_, name, ext) ->
                    if (singBoxOnly) {
                        handled.add("$rel/$name")
                        return@forEach
                    }
                    val format = if (ext == "json") GoldenFormat.JSON else GoldenFormat.YAML
                    docs.add(document(root, "$rel/$name", name, format))
                }
            if ("export.txt" in names) {
                val segments = exportSegments(root, "$rel/export.txt")
                docs.addAll(if (singBoxOnly) segments.take(1) else segments)
            }
            var dynamic = GoldenDynamic.NONE
            if ("result.json" in names) {
                val (doc, dyn) = resultDocument(root, "$rel/result.json", side)
                docs.add(doc)
                dynamic = dyn
            }
            docs.forEach { handled.add("$rel/${it.name.substringBefore('#')}") }
            return docs to dynamic
        }

        // exportConfig() 的原文：sing-box 配置后接各外核配置，段间以 "\n\n" 分隔
        fun exportSegments(root: File, rel: String): List<GoldenDocument> {
            val text = try {
                readUtf8(File(root, rel))
            } catch (ex: GoldenParseException) {
                return listOf(GoldenDocument.ofError("export.txt", ex.message!!))
            }
            return text.split("\n\n").mapIndexed { i, segment ->
                val name = "export.txt#$i"
                when {
                    segment.isBlank() -> GoldenDocument.ofError(name, "第 $i 段为空")
                    i == 0 -> GoldenDocument(name, segment, GoldenFormat.JSON_OBJECT)
                    else -> GoldenDocument(name, segment, GoldenFormat.JSON_OR_YAML)
                }
            }
        }

        // 取出 dynamic 供占位符使用；dynamic 本身只比较各类的个数
        fun resultDocument(root: File, rel: String, side: String): Pair<GoldenDocument, GoldenDynamic> {
            val value = try {
                GoldenParser.parseJson(readUtf8(File(root, rel)))
            } catch (ex: GoldenParseException) {
                return GoldenDocument.ofError("result.json", ex.message!!) to GoldenDynamic.NONE
            }
            fun bad(message: String) = diffs.add(GoldenDiff(rel, "$.dynamic", "${side}一侧 $message"))
            if (value !is GoldenValue.Obj) {
                return GoldenDocument.ofError("result.json", "顶层不是 JSON 对象") to GoldenDynamic.NONE
            }
            val dyn = value.fields["dynamic"]
            if (dyn !is GoldenValue.Obj) {
                bad(if (dyn == null) "缺少 dynamic" else "dynamic 不是对象")
                return GoldenDocument.ofValue("result.json", value) to GoldenDynamic.NONE
            }
            fun items(key: String): List<GoldenValue> = when (val v = dyn.fields[key]) {
                is GoldenValue.Arr -> v.items
                null -> emptyList<GoldenValue>().also { bad("dynamic.$key 缺失") }
                else -> emptyList<GoldenValue>().also { bad("dynamic.$key 不是数组") }
            }

            val longRange = BigInteger.valueOf(Long.MIN_VALUE)..BigInteger.valueOf(Long.MAX_VALUE)
            val ports = items("ports").mapIndexedNotNull { i, v ->
                if (v is GoldenValue.Integer && v.value in longRange) v.value.toLong()
                else null.also { bad("dynamic.ports[$i] 不是整数：${GoldenCompare.render(v)}") }
            }

            fun strings(key: String) = items(key).mapIndexedNotNull { i, v ->
                val parts = (v as? GoldenValue.Str)?.parts
                when {
                    parts == null -> null.also { bad("dynamic.$key[$i] 不是字符串：${GoldenCompare.render(v)}") }
                    else -> parts.joinToString("") { (it as StrPart.Text).text }
                }
            }

            val dynamic = GoldenDynamic(ports, strings("paths"), strings("secrets"))
            val counts = GoldenValue.Obj(
                linkedMapOf(
                    "ports" to GoldenValue.Integer(BigInteger.valueOf(dynamic.ports.size.toLong())),
                    "paths" to GoldenValue.Integer(BigInteger.valueOf(dynamic.paths.size.toLong())),
                    "secrets" to GoldenValue.Integer(BigInteger.valueOf(dynamic.secrets.size.toLong())),
                )
            )
            val replaced = GoldenValue.Obj(LinkedHashMap(value.fields).apply {
                put("dynamic", counts)
                if (singBoxOnly) remove("external")
            })
            return GoldenDocument.ofValue("result.json", replaced) to dynamic
        }
    }

    // 去掉顶层对象里不比较的键；解析失败或不是对象时原样返回，由比较报告解析错误
    private fun withoutKeys(doc: GoldenDocument, keys: Set<String>): GoldenDocument {
        if (keys.isEmpty()) return doc
        val value = try {
            doc.parsed()
        } catch (ex: GoldenParseException) {
            return doc
        }
        if (value !is GoldenValue.Obj) return doc
        return GoldenDocument.ofValue(doc.name, GoldenValue.Obj(value.fields - keys))
    }

    private fun document(root: File, rel: String, name: String, format: GoldenFormat): GoldenDocument = try {
        GoldenDocument(name, readUtf8(File(root, rel)), format)
    } catch (ex: GoldenParseException) {
        GoldenDocument.ofError(name, ex.message!!)
    }

    private fun readUtf8(file: File): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(file.readBytes()))
            .toString()
    } catch (ex: CharacterCodingException) {
        throw GoldenParseException("不是有效的 UTF-8")
    }

    private fun listFiles(root: File): Set<String> =
        root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toSet()

    private fun subdirs(root: File, rel: String): List<String> =
        File(root, rel).listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()

    // 打印采集元数据：采集所基于的提交与工作区状态、应用版本、三个核心的版本、系统、采集时间。
    // 字段缺失就不打印；文件解析不了或一个认识的字段都没有时，原样打印
    private fun manifestLine(root: File): String {
        val file = File(root, "manifest.json")
        if (!file.isFile) return "（没有 manifest.json）"
        val text = try {
            readUtf8(file)
        } catch (ex: GoldenParseException) {
            return "（manifest.json ${ex.message}）"
        }
        val value = try {
            GoldenParser.parseJson(text)
        } catch (ex: GoldenParseException) {
            return "（manifest.json 解析失败：${ex.message}）原文：${text.trim()}"
        }
        val parts = (value as? GoldenValue.Obj)?.let { manifestParts(it) }.orEmpty()
        return if (parts.isEmpty()) text.trim() else parts.joinToString("，")
    }

    private fun valueAt(obj: GoldenValue.Obj, vararg path: String): GoldenValue? {
        var cur: GoldenValue = obj
        for (key in path) cur = (cur as? GoldenValue.Obj)?.fields?.get(key) ?: return null
        return cur
    }

    private fun textAt(obj: GoldenValue.Obj, vararg path: String): String? = (valueAt(obj, *path) as? GoldenValue.Str)
        ?.parts?.joinToString("") { (it as? StrPart.Text)?.text ?: "" }?.takeIf { it.isNotEmpty() }

    private fun intAt(obj: GoldenValue.Obj, vararg path: String): BigInteger? =
        (valueAt(obj, *path) as? GoldenValue.Integer)?.value

    private fun manifestParts(m: GoldenValue.Obj): List<String> {
        fun text(vararg path: String) = textAt(m, *path)
        fun int(vararg path: String) = intAt(m, *path)

        val parts = ArrayList<String>()
        text("commit")?.let { parts.add("提交 $it") }
        dirtyText(m)?.let { parts.add(it) }
        text("app", "versionName")?.let { parts.add("应用 $it") }
        // 旧格式里 cores.<核心> 下是对象，核心版本在其 version 字段
        for ((key, label) in listOf("sing-box" to "sing-box", "xray" to "Xray", "mihomo" to "mihomo")) {
            text("cores", key, "version")?.let { parts.add("$label $it") }
        }
        val api = int("system", "sdkInt")
        val release = text("system", "release")
        when {
            api != null && release != null -> parts.add("系统 API $api（Android $release）")
            api != null -> parts.add("系统 API $api")
            release != null -> parts.add("系统 Android $release")
        }
        text("collectedAt")?.let { parts.add("采集于 $it") }
        return parts
    }

    // 工作区状态：新格式的 dirty 对象带总数与「代码改动」数；旧格式只有路径数组 dirtyPaths
    private fun dirtyText(m: GoldenValue.Obj): String? {
        val count = intAt(m, "dirty", "count")
        if (count != null) {
            if (count.signum() == 0) return "工作区干净"
            val code = intAt(m, "dirty", "codeCount")
            return "工作区有 $count 处改动" + if (code == null) "" else "，其中可能影响配置输出的代码 $code 处"
        }
        val old = (m.fields["dirtyPaths"] as? GoldenValue.Arr)?.items?.size ?: return null
        return if (old == 0) "工作区干净" else "工作区有 $old 处改动（旧格式，未区分代码改动）"
    }
}
