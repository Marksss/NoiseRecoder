# 测试与复现

## 本次验证环境

- 执行日期：2026-10-22（Asia/Shanghai）。
- 真机：Pixel 2，Android 11 / API 30，USB 充电。
- 本机可用工具：Android SDK 34、Build Tools 33.0.0、Android Studio 自带 JBR 21、Kotlin 2.1.10、JUnit 4.13.2。
- 工程正式构建版本：AGP 8.9.2、Gradle 8.11.1、Kotlin 2.1.20、JDK 17、compile/target SDK 35。
- Gradle 下载超时，尚未验证标准 Gradle 构建与 Android Lint。测试 APK 通过本地 AAPT2 / D8 / zipalign / apksigner 打包并验证签名；旧 D8 不识别新 Kotlin 元数据，会在日志中输出相关提示，APK 不进行压缩混淆。
- 测试设备的系统日期与主机不同；没有修改设备时间。文件名和 CSV 如实使用设备时间，实际取证前应校准手机日期和时区。

## 已通过

| 验证项 | 结果 |
| --- | --- |
| 全部 Kotlin 主源码编译 | 通过 API 34 编译检查 |
| 11 项 JUnit 单元测试 | 全部通过 |
| 时段外不保存 | 实际麦克风采集，数据库无新增录音 |
| 高阈值抑制 | 实际麦克风有信号，无新增录音 |
| 锁屏后台与连续触发 | 连续录音超过 65 秒，仍处于 recording 状态 |
| 停止与原始 WAV | 首轮文件 71.2 秒，RIFF 数据长度与文件长度一致 |
| 声级记录 | 数据库保存录音峰值，触发 CSV 包含对应文件名 |
| ZIP 与 SHA-256 | 导出 CSV 和原始音频，哈希一致，原件保留 |
| 系统文件选择器 | 实际保存 ZIP 至下载目录，拉取后再次验证 SHA-256 |
| Provider 权限 | 允许只读打开，拒绝 rw 写入 |

单元测试独立验证：普通与跨午夜窗口的包含/排除边界、循环缓冲顺序、精确 5 秒容量、RMS 数学结果、重复触发重置 60 秒期限、WAV 头与 PCM 原样保存、意外中断头部修复。

真机连续触发测试使用阈值 0 / 偏移 160 保证稳定触发；阈值抑制使用阈值 140 / 偏移 -20。它们仅用于自动测试，不能作为真实声级校准数据。测试结束恢复测试前设置，并停止服务；设备上保留测试录音。

## 1.1 报告与删除验证

- 现有 v1 数据库成功迁移到 v2，原有录音保留，旧录音未补造声级样本。
- 真实麦克风采集的约 71 秒持续超限被记录为一个事件，保存约每秒一个声级摘要。
- 已知信号序列分成两个超限事件，持续时间分别 1,200 / 300 ms，总监听 4,000 ms、总超限 1,500 ms；数学测试样本会清理，不混入实际监听时段。
- 单元测试总计 **11 项**，新增能量平均、缺样本、CFB 小附件和超过 7 MB 的 DIFAT 大附件完整性。
- 真机集成检查总计 **11 项**，新增实际监听时段和声级、分段事件、DOCX/HTML、临时预览及 Range、安全删除。
- 同一模型输出 DOCX 与 HTML，包含相同次数、声级、时间、录音文件名。独立 HTML 中 Base64 解码后的音频与原始 WAV 完全一致。
- 本机 Windows 桌面 Word 以只读方式成功打开样例，识别一个 `Package` 音频附件，导出两页 PDF 并逐页视觉检查。这里验证附件结构和识别，不宣称所有 Word 阅读器均能播放。
- 手机 Chrome 实际打开回环地址报告，点击音频播放，媒体会话确认已播放约 11 秒后暂停。预览服务的 WAV Range 请求返回 206；错误访问令牌返回 404。
- 删除仅针对本轮新建测试 WAV：文件消失、列表隐藏、数据库标记删除，原有录音和监听统计不变；已有报告副本仍保留其音频，新生成报告明确显示已删除且不带该音频。
- 验证正常录音中的文件不能删除；界面另提供永久删除确认、批量勾选和独立清理报告副本。

报告生成采用 Android Kotlin 原生 OOXML / CFB 写入，不依赖桌面 Word、Python 或云服务；Word 仅用于开发阶段验证。标准 `render_docx.py` 在本机因缺少 LibreOffice 无法执行，改用本机 Word 原生导出 PDF 后用随附 Poppler 渲染，未安装 LibreOffice。

可使用 `scripts/verify-word-report.ps1 -DocumentPath <DOCX> -OutputDirectory <DIR>` 进行可选的桌面 Word 只读检查；这要求本机安装 Word。

## 1.2 三页导航验证

在设备 `FA7951A00460`（Android 11）覆盖安装 1.2.0，使用 ADB 点击、UI 层级断言及截图核对：

- 顶部固定横向“首页 / 记录 / 设置”，当前页高亮；各页内容按使用频率分组。
- 首页显示已保存计划，开启按钮成功启动 `MonitorService`，停止按钮成功结束服务。
- 修改阈值草稿后切换页面，首页仍显示原保存阈值，返回设置页保留草稿。
- 录音勾选在切换页面后保留，测试未执行删除；原有录音保留。
- 记录页报告导出菜单包含 Word、独立 HTML、完整 ZIP 三种格式；设置页底部提供电池优化、证据包、CSV 和报告副本清理。
- 已检查三个页面截图。测试结束停止值守，清除未保存的测试草稿，停留首页，原有保存设置保持不变。

本轮为界面重组，通过本地备用脚本编译 APK 并安装验证；未重复执行 1.1 的完整录音、报告生成和 Word 检查，也未完成标准 Gradle 构建。Activity 重建的恢复逻辑已实现，当前真机检查覆盖页面切换，未单独模拟进程重建。

## 标准构建与测试

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
.\gradlew.bat assembleDebugAndroidTest
$deviceSerial = 'YOUR_DEVICE_SERIAL'
adb -s $deviceSerial install --no-incremental -r app/build/outputs/apk/debug/app-debug.apk
adb -s $deviceSerial install --no-incremental -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s $deviceSerial shell pm grant com.example.noiserecorder android.permission.RECORD_AUDIO
adb -s $deviceSerial shell am instrument -w com.example.noiserecorder.test/com.example.noiserecorder.SmokeInstrumentation
```

在 Android 13+ 上另行授予通知权限，或从界面启动时完成通知授权。测试前解锁设备。集成测试会自动锁屏、录音、导出并唤醒；原始报告在应用私有目录 `files/smoke-report.txt`。命令行返回码本身不足以判断 Instrumentation 成功，应确认十一项 `PASS` 且无 `FAIL`。

专用测试机可用下列命令读取报告：

```powershell
adb -s $deviceSerial shell run-as com.example.noiserecorder cat files/smoke-report.txt
```

## 离线备用流程

`scripts/verify-local.ps1` 接收 `JavaHome`、`KotlinHome`、`AndroidJar`、`JUnitJar`、`HamcrestJar` 路径，编译源码并运行十一项测试。

`scripts/build-local-test-apk.ps1` 接收 `JavaHome`、`KotlinHome`、`SdkRoot`，默认使用 SDK 34 / Build Tools 33，生成：

- `.verification/noise-app-local-test.apk`
- `.verification/noise-instrumentation-local-test.apk`

替换标准流程中的 APK 路径即可使用同一 Instrumentation 运行。离线流程使用 JVM 1.8 字节码适配旧 D8，不替代正式 Gradle 验证。缓存、测试音频、APK、本地签名密钥均被 `.gitignore` 排除，不提交仓库。

## 尚需验证

Android 14–16 真机权限与前台服务行为、目标手机整夜充电与厂商电池策略、实际声源/参考声级计校准、真实声源触发时的 5 秒预录波形、自然停止超阈值后完整 60 秒尾录、时段结束的真机收尾、30 分钟连续分段、低存储、权限撤回与系统终止恢复。相关逻辑已有源码实现与部分单元覆盖，但本次没有完成所有这些场景的真实设备验证。
