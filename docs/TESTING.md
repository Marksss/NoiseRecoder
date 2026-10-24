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

## USB 报告获取脚本

`scripts/pull-reports.ps1` 已在 Windows PowerShell 5.1、设备 `FA7951A00460` 上实测：指定设备获取最新报告、自动选择 USB 设备获取全部报告（当前设备有一份已完成报告）、含空格的输出目录。获取的 Word、HTML、图表、音频等全部 9 个文件与手机逐文件 SHA-256 一致，脚本自动验证其中 2 段音频的报告清单哈希。无效设备号返回非零退出码。导出目录被 `.gitignore` 排除，手机文件保持不变。

多份报告排序和多设备选择错误提示已有实现，本轮未额外构造多报告或连接多台真机；PowerShell 7 未单独运行。该脚本不依赖 Android 构建，因此本轮未重新编译 APK。

## 1.3 两种值守方式验证

2026-10-24 在设备 `FA7951A00460`（Android 11）安装 1.3.0，本地编译和 **14 项 JUnit 测试通过**。新增测试覆盖立即模式忽略预约时间、每日模式跨午夜及结束边界，以及旧启动方式兼容。

`DutyModeInstrumentation` 专项检查共 **5 项 PASS、无 FAIL**：

- 从首页“立即开启值守”启动，预约时段外实际采集并触发 WAV，锁屏继续运行，手动停止后保存为 `user_stop`。
- 从首页“预约每日值守”启动，开始前不采集、不产生 WAV 或监听时段。
- 锁屏等待设备实际时间 11:30 到点，自动开启麦克风并触发录音。
- 11:31 时段结束后自动释放麦克风，WAV 和监听时段均以 `window_end` 收尾，文件头长度正确；预约服务继续等待次日。
- 手动停止取消预约，麦克风和服务均停止。

专项测试将新建的录音和声级时段清理，恢复测试前参数，原有录音、报告不变。它使用测试阈值 0 / 偏移 160 确保触发，不用于真实声级判断。每日次日重复和跨午夜分支由单元测试覆盖，本轮未等待到第二天；Android 14–16 后台预约行为仍需真机验证。本轮使用离线备用 APK 构建，未重新运行完整旧版录音/报告集成套件或标准 Gradle 构建。

先停止设备已有的值守，再运行专项检查（测试期间会锁屏，最长约两分钟）：

```powershell
adb -s $deviceSerial shell am instrument -w com.example.noiserecorder.test/com.example.noiserecorder.DutyModeInstrumentation
adb -s $deviceSerial shell run-as com.example.noiserecorder cat files/duty-mode-test.txt
```

## 1.4 预约勾选与时段删除验证

- 首页将每日预约改为一个可勾选控件，勾选启用、取消勾选取消预约；立即开启/停止使用独立按钮，并与预约互斥。
- 在 Android 11 测试设备上重新运行值守专项检查，5 项通过：勾选后正常待命、锁屏到点开始、时段结束仍保持勾选、取消勾选停止服务并清除勾选；原设置和原始录音保持不变。测试时间段为 11:57—11:58。
- 记录页按实际监听时段分组，保留单文件和批量音频删除，新增整时段永久删除及确认框，没有录音的时段也可删除。
- `RecordDeletionInstrumentation` 在设备 `FA7951A00460` 上 **5 项 PASS、无 FAIL**，验证单文件删除保留统计，整段清理包含已删录音索引在内的全部关联表和音频，其他时段及副本不受影响，删除标记中断恢复，活动时段/录音保护，以及确认框取消与确认。
- 删除测试仅创建和删除自己的样本；结束时断言原有录音名称集合和监听时段 ID 集合均保持不变。没有删除用户已有录音或报告。
- 最终源码编译和 **14 项 JUnit 测试通过**，本地备用 APK 已安装。未重复运行完整旧版报告集成套件或标准 Gradle 构建，Android 14–16 仍待真机验证。

删除专项检查命令（先停止值守或取消预约）：

```powershell
adb -s $deviceSerial shell am instrument -w com.example.noiserecorder.test/com.example.noiserecorder.RecordDeletionInstrumentation
adb -s $deviceSerial shell run-as com.example.noiserecorder cat files/record-deletion-test.txt
```

### 1.4.1 默认收起

1.4.1 默认收起调整：本地编译安装后，在 `FA7951A00460` 新启动应用进入记录页，确认没有自动展开的录音，点击可展开、再次点击可收起。旧录音分组同样默认收起；本轮未新增或重复运行单元测试，未操作用户音频删除。

### 1.4.2 按监听时段选择报告

记录页时段卡片增加独立的“选择此时段生成报告”，顶部显示选择数量，支持最多 31 个时段；录音勾选明确标为仅用于删除。报告范围不依赖音频文件选择，旧版无时段录音的报告入口移至设置页。

在 `FA7951A00460` 安装 1.4.2 后运行扩展的 `RecordDeletionInstrumentation`，共 **6 项 PASS、无 FAIL**。新增检查创建两个独立测试时段，只勾选其中一个、不选择任何音频，实际生成 Word 和 HTML，验证两种报告均包含所选时段两份音频、排除另一个时段的音频。测试结束恢复原报告指针，清理新建测试报告和样本，原录音及监听时段集合保持不变。本轮完成本地 APK 编译和专项设备验证，未重复运行单元测试或标准 Gradle 构建。

### 1.4.3 音频逐条删除与报告清理入口

移除音频勾选框及“全选/删除选中”，保留回放和逐条删除。将报告清理入口放到记录页，命名“删除本地所有已生成报告”，确认框明确只清理应用内部报告副本，保留原始录音、统计和外部导出文件。

本地编译安装后，在 `FA7951A00460` 验证：展开音频后没有音频选择框，逐条删除按钮存在；清理报告按钮弹出确认框，选择取消后原报告目录集合不变。检查了展开列表截图。复用原报告删除逻辑，本轮未确认清空用户已有报告、未操作音频删除，未重复运行单元测试或完整集成套件。

## 1.5 记录页布局与导出入口

- “导出证据包”移至记录页，与预览、报告导出并排；设置页整个“数据管理”区域移除，包括独立 CSV 和旧版录音报告入口。
- 记录页使用简洁的浅色时段卡片，时段日期、起止时间、数量和中文状态分层显示；“选入报告 / 展开 / 删除时段”在同一行。录音默认收起。
- 展开录音后显示当地时间、时长、声级和文件名，使用分隔线以及独立的“播放 / 删除”操作；按钮点击区域最小 48 dp。文件名可长按选择复制。
- 本地备用构建成功，在 `FA7951A00460` 上覆盖安装 1.5.0。原有报告范围和删除专项检查 **6 项 PASS、无 FAIL**，只清理本轮测试样本及报告，保留已有数据。
- ADB 界面检查确认设置页不再出现数据管理入口；记录页证据导出可打开 ZIP 保存界面并取消；默认收起、展开后播放/删除按钮存在。已视觉检查设置页、记录页收起和展开截图，无文字遮挡或按钮重叠。
- 本轮没有重新运行单元测试、完整旧版集成套件或标准 Gradle 构建；未实际播放用户录音，也未通过界面删除已有记录。

## 1.7 统一样式、并发报告与预约恢复

- 首页、设置页与记录页共用白色描边卡片、按钮及文字样式。首页分组显示状态、预约和立即值守；设置采用并排时间/数值输入，禁用操作显示灰色。已检查首页及设置页上下部分截图，保存原参数后值保持不变。
- 本地备用构建成功，14 项 JUnit 测试通过；安装到 `FA7951A00460`（Android 11）。标准 Gradle / Lint、Android 14–16 尚未验证。
- `HistoricalReportInstrumentation` **5 项 PASS、无 FAIL**：真实麦克风采集并写入 WAV 时生成历史报告，检查当前时段不可选、当前文件继续增长、文件头未被修复、数据库仍为 monitoring / recording；Word 和 HTML 仅含历史音频，Word 导出也不停止监听。测试恢复原参数、原报告指针，清理自身音频和时段，原数据集合保持不变。
- `ReservationRecoveryInstrumentation` **5 项 PASS、无 FAIL**：模拟开机回调，覆盖时段前恢复定时等待及静默通知、打开应用直接待命且不弹窗；时段内需弹窗手动确认，确认后实际启用麦克风；取消预约后不恢复。测试使用无法触发录音的阈值，仅创建并清理自己的短监听时段。
- 完成一次最终规则下的真实重启，启动次数从 4 变为 5：预约 enabled 状态保留，系统边界提醒闹钟重新注册，开机未启动麦克风服务；系统通知记录确认等待通知已发布。随后打开应用，不弹恢复确认，直接进入每日预约待命，显示未采集声音。
- 测试前确认原设备只在预约等待，才临时取消预约升级；测试结束已恢复原每日预约 02:30—06:00、阈值 45、偏移 90。立即值守不做开机恢复。系统限制开机后台启用麦克风，因此未打开应用的恢复等待到点后通过通知提示用户开启；已从应用恢复的待命服务正常自动监听。
- Android 12+ 没有精确闹钟能力时使用系统允许的非精确定时提醒，可能延后；该提醒用于恢复尚未运行的服务，不替代已运行值守服务的时段检查。

先停止值守或取消预约，再运行新增专项测试：

```powershell
adb -s $deviceSerial shell am instrument -w com.example.noiserecorder.test/com.example.noiserecorder.HistoricalReportInstrumentation
adb -s $deviceSerial shell am instrument -w com.example.noiserecorder.test/com.example.noiserecorder.ReservationRecoveryInstrumentation
```

原始结果保存在应用的 `files/historical-report-test.txt` 和 `files/reservation-recovery-test.txt`。实际重启及页面检查另使用 ADB 验证，设备重启不在 Instrumentation 内自动执行。

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

`scripts/verify-local.ps1` 接收 `JavaHome`、`KotlinHome`、`AndroidJar`、`JUnitJar`、`HamcrestJar` 路径，编译源码并运行十四项测试。

`scripts/build-local-test-apk.ps1` 接收 `JavaHome`、`KotlinHome`、`SdkRoot`，默认使用 SDK 34 / Build Tools 33，生成：

- `.verification/noise-app-local-test.apk`
- `.verification/noise-instrumentation-local-test.apk`

替换标准流程中的 APK 路径即可使用同一 Instrumentation 运行。离线流程使用 JVM 1.8 字节码适配旧 D8，不替代正式 Gradle 验证。缓存、测试音频、APK、本地签名密钥均被 `.gitignore` 排除，不提交仓库。

## 尚需验证

Android 14–16 真机权限与前台服务行为、目标手机整夜充电与厂商电池策略、实际声源/参考声级计校准、真实声源触发时的 5 秒预录波形、自然停止超阈值后完整 60 秒尾录、时段结束的真机收尾、30 分钟连续分段、低存储、权限撤回与系统终止恢复。相关逻辑已有源码实现与部分单元覆盖，但本次没有完成所有这些场景的真实设备验证。
