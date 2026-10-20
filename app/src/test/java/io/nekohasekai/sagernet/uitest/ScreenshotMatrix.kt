package io.nekohasekai.sagernet.uitest

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
}

/** 主题：[id] 进文件名；颜色由 DataStore.appTheme 决定，见用例 */
enum class ThemeVariant(val id: String) {
    PINK("pink"),
}

/** 一张截图的档位。[widthDp] 是屏幕宽度（dp），[fontScale] 是系统字号缩放 */
data class Shot(
    val screen: Screen,
    val theme: ThemeVariant = ThemeVariant.PINK,
    val dark: Boolean = false,
    val widthDp: Int = 411,
    val fontScale: Float = 1.0f,
) {
    /** 相对基线目录的路径，如 `config-list/pink-light_w411_f1.0.png` */
    val path: String
        get() = "${screen.dir}/${theme.id}-${if (dark) "dark" else "light"}_w${widthDp}_f${fontScale}.png"
}

object ScreenshotMatrix {

    /** 默认档（粉色、亮色、411dp、字号 1.0）：每个画面一张 */
    val defaultTier: List<Shot> = Screen.entries.map { Shot(it) }

    val shots: List<Shot> = defaultTier

    val paths: Set<String> = shots.map { it.path }.toSet()

    init {
        // 档位重复会让两个用例写同一个文件
        require(paths.size == shots.size) { "截图矩阵里有重复的档位" }
    }
}
