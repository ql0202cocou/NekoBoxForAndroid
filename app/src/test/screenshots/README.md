# UI 截图基线

`app/src/test/java/io/nekohasekai/sagernet/uitest/` 里的 Robolectric 用例（SDK 35）把页面渲染成 PNG，
与本目录的基线逐像素比较。文件名由 `ScreenshotMatrix.kt` 的一张表生成：
`<画面>/<主题>-<亮暗>_w<宽度dp>_f<字号>.png`，画面与档位以那张表为准。

## 规则

- **只在录制平台比较**：macOS arm64。原生绘图的字体光栅与抗锯齿跨系统不保证一致，其它平台（含 release CI）
  只渲染、打印「跳过比较」，渲染崩溃照样失败；行为用例在所有平台都跑。
- **阈值 0 个像素**。同一平台两次渲染逐字节一致，没有理由放宽；以后要放宽，必须在本文件按 golden 基线
  `RELAXATIONS` 的格式写明原因，不能改代码里的阈值了事。
- **缺基线 = 失败**，绝不自动生成。本目录里有矩阵之外的文件（或矩阵里的文件缺失）= `ScreenshotManifestTest`
  失败，该测试是纯 JVM，所有平台都跑。唯一允许的非图片文件是本 README。
- 比较失败时，实际图与差异图（变化的像素标红）在 `app/build/outputs/screenshots/{actual,diff}/`。

## 重录只能显式点名

```bash
./gradlew app:testOssDebugUnitTest -Pscreenshots.record='^config-list/'
```

`-Pscreenshots.record=<正则>` 对基线相对路径做 find，只重写名字匹配的截图，其余照常比较；没有「默认全部重录」。
重录只在录制平台上生效，其它平台会报错。

## 审查流程

1. 跑测试，逐张看差异图，确认每处变化都是有意的；
2. 只按正则重录这些截图；
3. `git diff --stat` 应恰好是这些 PNG；
4. 与引起变化的代码放进同一个提交。

U5 的整体换肤同样逐张看，不许为了让测试变绿直接重录。

## 环境约定

en-US、时区 UTC、动画时长缩放 0、固定的夹具时间戳、文档保留地址（192.0.2.x、example.com）与虚构凭据；
密度 xhdpi（411dp 宽 = 822 px）。设备与夹具见 `UiTestBase.kt`、`UiFixtures.kt`。
