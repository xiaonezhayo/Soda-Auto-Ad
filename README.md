# 汽水广告助手 (SodaAdHelper)

基于 **Android 无障碍服务 + Google ML Kit 中文离线 OCR** 的独立安卓应用，用于自动领取汽水音乐「免费听歌时长」广告奖励。

## 核心特性
- **纯手机端独立运行**：最低支持 Android 11 (API 30+)，无需连接电脑。
- **双引擎识别**：无障碍控件树毫秒级识别为主；遇到自绘/WebView 广告页时，按需截取顶部区域调用 ML Kit 中文离线 OCR 兜底。
- **极简悬浮窗**：运行期间可随时拖动查看进度，一键暂停、继续或停止。
- **自动静音与恢复**：运行期间自动将媒体音量调为 0，暂停/停止/完成后自动恢复原音量。
- **保守安全模式**：跳出汽水音乐、单条广告超时或检测到人机验证/滑块时立即暂停并发送通知提醒人工处理。
- **内置规则微调与节点快照**：汽水音乐改版时可在 App 内直接查看节点快照并修改关键词，无需重新编译。

## 如何打包成 APK

### 方式一：用 Android Studio 本地打包（最直观）
1. 安装 [Android Studio](https://developer.android.com/studio)。
2. 打开本目录 `SodaAdHelper`，等待 Gradle 自动同步完成。
3. 顶部菜单选择 `Build -> Build Bundle(s) / APK(s) -> Build APK(s)`。
4. 生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，可通过 USB（`adb install app-debug.apk`）或传到手机直接安装。

### 方式二：GitHub Actions 云端自动打包（本机免装 Android SDK）
1. 将 `SodaAdHelper` 目录推送到一个 GitHub 仓库（私有仓库即可）。
2. 打开仓库的 **Actions -> Build Debug APK -> Run workflow**。
3. 等待约 2~3 分钟构建完成，在页面底部的 **Artifacts** 下载 `SodaAdHelper-debug-apk` 并安装到手机。

## 手机端首次使用步骤
1. 安装 APK 并打开「汽水广告助手」。
2. 在首页「权限检查」中依次开启：
   - **无障碍服务**（必需）
   - **悬浮窗权限**（推荐，用于随时暂停/停止）
   - **通知权限**（推荐，用于完成或异常暂停提醒）
3. 在「规则配置」中按需设置目标次数、最长运行时长和自动静音。
4. 点击「一键启动并打开汽水音乐」即可开始。
