# 夜间声证（NoiseRecorder）

Kotlin 原生 Android 工程，最低 Android 11（API 30），compile/target SDK 35。无需账号，应用不申请联网权限，声音和数据库仅保存在手机本地。

## 功能

- 每日默认 **02:30–06:00** 监听，可设置时间，支持跨午夜；结束时间不包含在监听窗口内。
- 默认 **45 dB** 触发阈值，可设置灵敏度和校准偏移。实时显示 dBFS 与估算声级。
- 内存循环缓冲保留触发前最多 **5 秒** PCM。刚进入监听时段时，预录音会从零逐步积累。
- 达到阈值后录制 **60 秒**；每个超阈值的 100 ms 音频帧都会把结束时间延至该帧之后 60 秒。所以首次触发后的文件通常约 65 秒（5 秒预录 + 60 秒正文）。
- 连续噪声期间自动延长，每 30 分钟无间断分文件，避免单文件过大；监听窗口结束或手动停止时立即收尾，即使不足 60 秒。
- 时间戳文件名，例如 `noise_20261023_023015_123_a1b2c3d4.wav`，按手机当地时间命名；CSV 使用 UTC ISO-8601 时间，保留毫秒。
- 录音索引保存开始触发时间、完整录音期间的最高 100 ms RMS 声级、时长、状态、触发阈值、校准偏移。触发事件另存时间、即时声级、文件名；连续超阈值时每秒最多保存一条事件，不影响逐帧延时。
- 保留 16 kHz / 单声道 / 16-bit little-endian PCM WAV 原始音频，无转码、降噪或事后剪辑。点击录音可调用系统播放器回放。
- 使用系统文件选择器导出 CSV 或 ZIP 证据包，ZIP 含原始 WAV、两份 CSV 和 SHA-256 清单。导出不会移动或删除原件。
- 麦克风前台服务 + 常驻通知 + PARTIAL_WAKE_LOCK，支持锁屏、充电整夜运行。通知提供停止按钮。
- 低存储停止并收尾；读取错误、系统静音麦克风时明确停止；进程意外退出后下次启动修复 WAV 文件头，并标记 `interrupted`。

## 构建与运行

1. 用 Android Studio 打开本目录。设置 Gradle JDK 为 **17 或更高的兼容 JDK**（推荐 Studio 自带 JBR）。工程使用 AGP 8.9.2、Kotlin 2.1.20、Gradle 8.11.1。
2. 在 SDK Manager 安装 Android SDK Platform 35、Build Tools 35.0.0。
3. 让 Studio 生成 `local.properties`，或手动指定 `sdk.dir`，例如 `sdk.dir=D:/Programer/AndroidSDK`。
4. 运行 `app`，允许麦克风和通知权限。Windows 命令行构建：

   ```powershell
   .\gradlew.bat testDebugUnitTest assembleDebug
   ```

   macOS / Linux：`sh gradlew testDebugUnitTest assembleDebug`。

5. APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`。

仓库包含 Wrapper 启动脚本与 JAR；第一次构建需要联网下载 Gradle 和构建依赖。

## 夜间使用

保存监听时间和阈值，点击“开启每日值守”，再锁屏。建议连接电源，并在“电池优化设置”中允许本应用后台运行；部分厂商还需在应用管理中允许自启动/后台活动。设置在**下次开启值守**时生效，修改后应停止再开启。

Android 对从后台新建麦克风前台服务有限制，Android 14+ 也不允许普通应用在开机广播中直接启动此类服务。本工程从用户可见的 Activity 启动服务，然后持续维持音频采集。因此：

- **需要用户先开启值守**，不提供重启后静默启动麦克风。
- 值守期间包括监听时段之外，系统仍会显示麦克风占用；时段外采集的声音立即丢弃，不存入预录缓冲或文件。
- 服务保持运行时，之后每天自动进入监听窗口；重启、强行停止、权限撤回、系统终止或音频错误后需要用户重新开启。
- 锁屏运行依赖系统保留服务。前台服务和唤醒锁不能保证抵抗所有厂商杀进程策略，需要在目标手机实测。
- 默认音频源为 `UNPROCESSED`；设备不支持时系统可能回退到默认采集路径，硬件、自动增益、手机壳与摆放位置仍会影响结果。

平台依据：[麦克风前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone)、[后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)。构建版本依据：[AGP 8.9 兼容要求](https://developer.android.com/build/releases/agp-8-9-0-release-notes)。

## 声级校准

未经校准的手机 PCM 只能给出相对于数字满量程的 dBFS，不能直接给出真实 dB SPL。工程使用：

`估算 dB SPL = 20 × log10(RMS / 32768) + 校准偏移`

默认偏移 90 dB 是占位值，不代表已校准。用稳定声源，将手机麦克风与参考声级计尽量靠近；开启值守，点击“用参考声级校准”，输入同一时刻参考读数，计算偏移后保存，再停止并重新开启。建议在接近目标阈值的多个声级核对误差，改变手机、位置或音频路径后重新校准。

这里的“峰值”指所有 100 ms RMS 声级中的最大值，**不是瞬时声压峰值**。未实现 A/C 频率计权或 IEC 时间计权，不能把结果视为专业 dBA 测量。SHA-256 用于验证导出的文件字节，不能单独证明采集时间或法律真实性。

## 数据与导出

- 原始音频：应用私有目录 `files/recordings/`。
- SQLite：`evidence.db`，含 `recordings` 和 `triggers` 表。
- ZIP：`audio/*.wav`、`recordings.csv`、`triggers.csv`、`SHA256SUMS.txt`、说明文件。
- “仅导出声级记录 CSV”输出 `triggers`，对应录音的整体峰值在 ZIP 的 `recordings.csv` 中。
- 为保证导出文件一致，导出前必须停止值守；导出过程中不能开启值守。CSV 使用 UTF-8 BOM，便于 Excel 打开中文。
- 文件访问仅通过系统导出目录或给播放器的单文件只读 URI 授权。无公开写入 Provider。
- 应用不自动清理录音。WAV 每分钟约 1.92 MB，整段连续 3.5 小时约 403 MB。剩余空间低于 32 MB 时不再开始新文件，值守中每 5 秒检查，低于 16 MB 时停止。请定期导出并管理手机存储。
- 卸载或清除应用数据会删除应用内原件，提前导出。导出失败时目标位置可能留有不完整文件，应重新导出。
- `complete` 正常结束；`window_end` 时段结束；`user_stop` 手动停止；`segment` 连续录音分段；`error` 运行异常；`interrupted` 意外中断后修复；`missing` 数据库对应文件缺失。

## 验证情况

已在本机通过全部 Kotlin 主源码编译检查，并通过 **7 项 JUnit 测试**：普通/跨午夜时间边界、预录音顺序与容量、RMS、连续触发延时、WAV 文件头/原始 PCM、意外中断文件头修复。离线校验脚本见 `scripts/verify-local.ps1`。

已使用 Pixel 2（Android 11 / API 30）完成真实麦克风集成测试，覆盖时段外不落盘、阈值抑制、锁屏持续录音超过 65 秒、连续触发延长、WAV 收尾、CSV、ZIP 导出、SHA-256 一致性、Provider 只读保护。另通过系统文件选择器实际保存 ZIP 到下载目录并校验哈希。

当前机器仅有 Android API 34 的 SDK，完整 Gradle 构建所需的 Gradle 8.11.1 下载超时。因此尚未验证标准 `assembleDebug` / Android Lint。真机测试包使用本地 Kotlin 2.1.10、SDK 34 和 Build Tools 33 离线打包，为兼容旧 D8 使用 JVM 1.8 字节码；正式 Gradle 工程配置仍为 Kotlin 2.1.20、JVM 17、SDK 35。运行时调用兼容 API 30；Android 13+ 通知权限按版本分支请求，清单额外声明 Android 14+ 麦克风前台服务权限。

测试步骤、范围与限制见 [docs/TESTING.md](docs/TESTING.md)。设备自动测试源代码在 `app/src/androidTest`。请只在专用测试设备运行，它会占用真实麦克风约 90 秒、锁屏再唤醒设备，并保留测试录音和缓存导出包。

交付后应在目标 Android 11、14、15/16 设备验证：锁屏跨时段、跨午夜、5 秒预录、重复触发延长、时段结束收尾、参考声级校准、充电整夜运行、麦克风竞争、权限撤回、低存储、进程终止后修复以及 ZIP 原件哈希。尤其需要在实际使用的手机上完成一整夜测试。
