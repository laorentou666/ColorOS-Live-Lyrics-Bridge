# 在线歌词数据源

基线为 `76ea78a942a7544415f5b29648bae0e354ba68c4`，包含
`2e253f6c0ddac7f6dc650490baa77173b26094cd` 的播放器状态重建修复。

## 来源与移植范围

参考本地 SPlayer-Next `47d74cf1a87cc0f526c25110bb0cb924f6268f1d`：

| Bridge 来源 | 参考文件 | 支持内容 |
| --- | --- | --- |
| 网易云 | `electron/main/apis/common/lyric/netease.ts`、`netease/modules/{search,lyric_new}.ts`、`netease/core/{request,crypto}.ts` | eapi 搜索与歌词请求；YRC、LRC、配套翻译及音译 |
| QQ 音乐 | `common/lyric/qqmusic.ts`、`qqmusic/modules/{search,lyric}.ts`、`qqmusic/core/{request,qrc,tripledes}.ts` | 签名搜索；QRC 专用 Triple DES、zlib/raw/gzip 解码；LRC 回退、翻译及音译 |
| 酷狗 | `common/lyric/kugou.ts`、`kugou/modules/{search,lyric}.ts`、`kugou/core/krc.ts` | mobilecdn 搜索及 songsearch 回退；hash + 歌名 + 时长查询；KRC XOR/zlib、LRC、内嵌 language 翻译及音译 |
| AMLL TTML | `common/lyric/ttml.ts`、`src/services/lyric/resolve.ts` | 官方 `api.amll.dev`；网易歌曲 ID、QQ mid/数字 ID；TTML 内嵌与头部翻译 |

格式语义同时参考 SPlayer-Next 依赖的 `lyric-kit@0.6.1` 中
`src/parse/{lrc,yrc,qrc,krc,ttml}.ts`。自定义插件、用户流媒体服务器和本地文件由其各自配置决定；本次接入上述四个内置在线来源。

`online/` 下注明 SPDX 的文件属于上述 AGPL-3.0 来源的 Kotlin 移植；原始版权归
SPlayer-Next 和 lyric-kit 作者所有。QRC 算法继承 SPlayer-Next 对
LDDC `core/decryptor/tripledes.py` 的来源说明。许可证全文见
[licenses/AGPL-3.0.txt](licenses/AGPL-3.0.txt)，分发这些移植代码及其组合衍生作品时适用 AGPL-3.0 的相应源码条款。

## 请求与选择

曲目标识包括媒体 key、歌曲 ID、歌名、歌手、专辑及毫秒时长。平台歌词与 AMLL TTML 并行请求，每份有效结果完成后立即参与选择与展示。网易云包 `com.netease.cloudmusic` 的 `MediaMetadata.METADATA_KEY_MEDIA_ID` 为正整数时，按该 ID 同时直取网易云歌词和 AMLL TTML，QQ 音乐与酷狗搜索同时进行。缺少有效 ID 的网易云及其他播放器同时搜索三个平台，AMLL 两个查询分支复用匹配出的 ID，并与平台歌词请求并行。AMLL 命中后替换当前平台歌词，缺失、失败或内容无效时继续使用已取得的歌词。每次连接和读取超时为 8 秒，响应及解压内容各限制为 2 MB；只保留当前曲目的结果。切歌取消连接，代次检查覆盖 A → B → A 及已排入主线程的旧回调。

9.0.40 的 MediaSession 发布数字歌曲 ID；当前 7.2.22 未发布此字段，继续使用搜索。网易云搜索将响应中的原始歌名及歌名附带 `alias` 的显示形式分别评分，保留分数最高的形式；歌手与时长过滤继续生效。这与 SPlayer 按平台 ID 直取、分开保存歌名和别名的输入逻辑保持一致，避免长别名使基础歌名的长度比低于匹配阈值。

本 fork 维护网易云 7.2.22、9.0.40 的兼容性。其他播放器保留上游 Provider、显示能力与原有搜索流程，在线匹配尽力提供，获取失败时沿用原歌词管线；其他平台的专属适配问题暂不纳入维护范围。

匹配规则沿用 `common/lyric/utils.ts`：歌名全等或子串长度比至少 0.34；已知歌手须命中；已知时长差最多 20 秒。歌名全等/子串分别加 10/4 分，歌手全等/包含加 5/2 分，专辑全等加 2 分，时长差在 5 秒内加 3 分。缺少歌手时，子串歌名匹配须有接近的时长佐证。

有效 AMLL 结果始终优先，包括逐行 TTML；AMLL 先返回时可直接展示，后续平台结果继续保留为候选。其他结果按实际时间轴质量、匹配分数、用户来源顺序排序：逐字 YRC/QRC/KRC 优先于逐行歌词。多个 AMLL 结果之间也按时间轴质量与匹配分数选择。来源顺序与请求完成先后独立。顺序保存在现有 `lockscreen_lyrics` 偏好中的 `online_lyric_source_order`，沿用受权限保护的设置广播同步到 SystemUI；已取得的结果可即时重新选择。

## 锁屏岛数据边界

`OnlineLyricRuntime` 在元数据入口加入在线 `lyricInfo`，完成后通过既有元数据刷新方法重入。获取期间及全部来源失败时使用原管线；有效在线结果优先于后续同曲目的原始歌词更新。原始封面、曲目信息及现有内容清理流程继续沿用。

TTML 对唱作为主歌词按行起始时间排序。背景和声单独解析，再与全部主唱区间检查交集，仅保留处于主唱空隙的和声。内嵌及头部 `translation/text for`、`transliteration` 分别提取翻译与音译，使用对应主行时间；翻译轨优先显示翻译，音译作为其后备。正文排除翻译、音译和 ruby 注音。

逐字行输出绝对时间戳，修正倒退的时间，保留末尾结束标签；普通 LRC 保留现有逐行模式，末尾时间标记会使既有渲染器将它识别为逐字行，因此此类行采用原有 LRC 表达。锁屏岛渲染、Recycler、AOD 及原有状态修复代码保持基线内容。

## 本地验证

构建入口：工作区 `android_tools/build-bridge.ps1`。脚本显式设置
`ANDROID_HOME`、`ANDROID_SDK_ROOT`、`GRADLE_USER_HOME`、`ANDROID_USER_HOME`、
`JAVA_HOME`、Java 用户目录、临时目录和项目缓存到 `android_tools` 内，并使用其 JDK 21。

```powershell
& ..\android_tools\build-bridge.ps1 :app:assembleDebug
& ..\android_tools\build-bridge.ps1 :app:testDebugUnitTest --tests '*OnlineLyric*Test' --tests '*PlayerMediaLyricRepairTest' --tests '*NativeLyricModelAssemblerTest' --tests '*LyricsCoreAdapterTest' --tests '*SystemUiLyricPublicationGateTest'
```

测试覆盖格式解析、TTML 背景与对唱及翻译、时间顺序、上游 QRC/签名固定样本、并行请求、错误候选过滤、实时排序、失败回退、旧请求失效、原解析器兼容及两个保留提交的修复行为。真实 HTTP 抽查取得 QQ QRC、酷狗 KRC、AMLL TTML 与网易云 LRC；服务可用性随网络与曲目变化。

2026-10-31 验证：`:app:assembleDebug` 成功；上述回归组共 85 项，79 项通过，6 项既有外部歌词文件用例通过 `Assume` 跳过。`git diff --check` 通过。产物为 `app/build/outputs/apk/debug/app-debug.apk`。

同日发布前完整验证：`:app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug` 成功；共 904 项测试，898 项通过，6 项按既有条件跳过。ColorOS 自定义 `lyricInfo` 字段的调用使用定点 `SuppressLint` 注解，保留现有运行逻辑。发布 APK 使用本地 Debug 签名，SHA-256 为 `d8d00e7b2aba81672bfb149ec91d0e59c07157f52fcf4b62c43ed7c9f6b1573f`。
