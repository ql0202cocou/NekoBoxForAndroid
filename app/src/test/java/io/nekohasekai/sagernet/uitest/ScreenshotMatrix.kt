package io.nekohasekai.sagernet.uitest

import io.nekohasekai.sagernet.utils.Theme

/**
 * 截图矩阵：哪些画面、什么档位要有基线，全部在这一张表里。基线文件名由它生成
 * （`<画面>/<主题>-<亮暗>_w<宽度>_f<字号>.png`），清单测试（ScreenshotManifestTest）据此要求
 * `app/src/test/screenshots/` 里恰好是这些文件：缺一张、多一张都失败。
 * 纯数据，不依赖 Android，所以清单测试在所有平台上都跑。
 */
enum class Screen(val dir: String) {
    // 主界面四页
    CONFIG_LIST("config-list"),
    GROUPS("groups"),
    ROUTES("routes"),
    SETTINGS_HUB("settings-hub"),

    // 设置里的独立内容页，以及网络工具的测试表单
    ABOUT("about"),
    TOOLS_NETWORK("tools-network"),
    TOOLS_BACKUP("tools-backup"),
    STUN("stun"),
    LOGS("logs"),

    // 配置页「已连接」态（服务在测试里不会连上，用例直接调 MainActivity.stateChanged）
    CONFIG_CONNECTED("config-connected"),

    // 设置的七个分类页，截全长
    SETTINGS_SERVICE("settings-service"),
    SETTINGS_INTERFACE("settings-interface"),
    SETTINGS_NOTIFICATION("settings-notification"),
    SETTINGS_ROUTE("settings-route"),
    SETTINGS_DNS("settings-dns"),
    SETTINGS_INBOUND("settings-inbound"),
    SETTINGS_ADVANCED("settings-advanced"),

    // 节点编辑器（长表单与短表单），截全长
    EDITOR_VMESS("editor-vmess"),
    EDITOR_SHADOWSOCKS("editor-shadowsocks"),

    // 常用对话框
    DIALOG_DELETE_CONFIRM("dialog-delete-confirm"),
    DIALOG_UNSAVED_CHANGES("dialog-unsaved-changes"),
    DIALOG_THEME_PICKER("dialog-theme-picker"),
    DIALOG_LIST_CHOICE("dialog-list-choice"),
    DIALOG_TEXT_INPUT("dialog-text-input"),

    // 编辑器里带行内校验的文本对话框，输入不合法的状态（端口 70000：错误行 + 「确定」禁用）
    DIALOG_INLINE_ERROR("dialog-inline-error"),
}

/** 主题：[id] 进文件名；[appTheme] 是写进 DataStore.appTheme 的编号（选色网格里的全部 21 种颜色） */
enum class ThemeVariant(val id: String, val appTheme: Int) {
    PINK("pink", Theme.PINK_SSR),
    RED("red", Theme.RED),
    PINK_MATERIAL("pink_material", Theme.PINK),
    PURPLE("purple", Theme.PURPLE),
    DEEP_PURPLE("deep_purple", Theme.DEEP_PURPLE),
    INDIGO("indigo", Theme.INDIGO),
    BLUE("blue", Theme.BLUE),
    LIGHT_BLUE("light_blue", Theme.LIGHT_BLUE),
    CYAN("cyan", Theme.CYAN),
    TEAL("teal", Theme.TEAL),
    GREEN("green", Theme.GREEN),
    LIGHT_GREEN("light_green", Theme.LIGHT_GREEN),
    LIME("lime", Theme.LIME),
    YELLOW("yellow", Theme.YELLOW),
    AMBER("amber", Theme.AMBER),
    ORANGE("orange", Theme.ORANGE),
    DEEP_ORANGE("deep_orange", Theme.DEEP_ORANGE),
    BROWN("brown", Theme.BROWN),
    GREY("grey", Theme.GREY),
    BLUE_GREY("blue_grey", Theme.BLUE_GREY),
    BLACK("black", Theme.BLACK),
}

/** 一张截图的档位。[widthDp] 是屏幕宽度（dp），[fontScale] 是系统字号缩放 */
data class Shot(
    val screen: Screen,
    val theme: ThemeVariant = ThemeVariant.PINK,
    val dark: Boolean = false,
    val widthDp: Int = 411,
    val fontScale: Float = 1.0f,
) {
    /** 窗口高度（dp）：360dp 宽按常见的 360×800，其余 411×914 */
    val heightDp: Int
        get() = if (widthDp == 360) 800 else 914

    /** 相对基线目录的路径，如 `config-list/pink-light_w411_f1.0.png` */
    val path: String
        get() = "${screen.dir}/${theme.id}-${if (dark) "dark" else "light"}_w${widthDp}_f${fontScale}.png"

    // 参数化用例的名字
    override fun toString() = path
}

/**
 * 分层矩阵（139 张）。每个轴的每个取值都至少覆盖到；宽度 × 字号的四个组合在配置页和设置一级页上齐全。
 * - A 主题层：10 个画面 × 粉 / 灰 / 黑 × 亮 / 暗，411dp、字号 1.0（60 张）
 * - B 布局层：除 [defaultOnlyScreens] 外的 24 个画面，粉亮，360dp、字号 1.3（最挤的一档，24 张）
 * - C 补角：配置页与设置一级页，粉亮，360dp / 1.0 与 411dp / 1.3（4 张）
 * - D 默认层：不在 A 层的 15 个画面，粉亮，411dp / 1.0（15 张）
 * - E 完整色板：其余 18 种主题的配置页 × 亮 / 暗（36 张）
 */
object ScreenshotMatrix {

    /** A 层的画面：颜色最集中、换主题与夜间最容易出问题的页面和对话框 */
    val themeScreens: List<Screen> = listOf(
        Screen.CONFIG_LIST, Screen.CONFIG_CONNECTED, Screen.GROUPS, Screen.ROUTES,
        Screen.SETTINGS_HUB, Screen.EDITOR_VMESS, Screen.DIALOG_THEME_PICKER,
        Screen.ABOUT, Screen.TOOLS_NETWORK, Screen.TOOLS_BACKUP,
    )

    private val cornerScreens = listOf(Screen.CONFIG_LIST, Screen.SETTINGS_HUB)

    private val representativeThemes = listOf(ThemeVariant.PINK, ThemeVariant.GREY, ThemeVariant.BLACK)

    val themeLayer: List<Shot> = themeScreens.flatMap { screen ->
        representativeThemes.flatMap { theme -> listOf(false, true).map { dark -> Shot(screen, theme, dark) } }
    }

    /** 只在 D 层留一张的画面：新增的单点状态，不需要每个布局档位都有 */
    private val defaultOnlyScreens = setOf(Screen.DIALOG_INLINE_ERROR)

    val layoutLayer: List<Shot> =
        (Screen.entries - defaultOnlyScreens).map { Shot(it, widthDp = 360, fontScale = 1.3f) }

    val cornerLayer: List<Shot> = cornerScreens.flatMap {
        listOf(Shot(it, widthDp = 360), Shot(it, fontScale = 1.3f))
    }

    val defaultLayer: List<Shot> = (Screen.entries - themeScreens.toSet()).map { Shot(it) }

    val paletteLayer: List<Shot> = (ThemeVariant.entries - representativeThemes.toSet()).flatMap { theme ->
        listOf(false, true).map { dark -> Shot(Screen.CONFIG_LIST, theme, dark) }
    }

    val shots: List<Shot> = themeLayer + layoutLayer + cornerLayer + defaultLayer + paletteLayer

    val paths: Set<String> = shots.map { it.path }.toSet()

    init {
        // 档位重复会让两个用例写同一个文件
        require(paths.size == shots.size) { "截图矩阵里有重复的档位" }
    }
}
