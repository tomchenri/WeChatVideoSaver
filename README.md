# 微信视频保存器 (WeChatVideoSaver)

一个安卓原生 App，用于扫描手机上微信存储的短视频文件，并一键保存到手机相册。

## 功能

- 自动扫描微信视频目录（支持多个可能路径）
- 通过 MediaStore 查询微信视频
- 列表展示视频文件名、大小、日期
- 单击保存单个视频到相册
- 长按进入多选模式，批量保存
- 保存到 `Movies/WeChatSaved/` 目录
- 支持 Android 7.0 ~ 14+

## 项目结构

```
WeChatVideoSaver/
├── build.gradle.kts              # 顶层构建文件
├── settings.gradle.kts          # 项目设置
├── gradle.properties            # Gradle 配置
├── app/
│   ├── build.gradle.kts         # App 模块构建配置
│   ├── proguard-rules.pro       # 代码混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml  # 清单文件
│       ├── java/com/wbconv/wechatvideosaver/
│       │   ├── data/
│       │   │   └── VideoItem.kt          # 数据模型
│       │   ├── ui/
│       │   │   ├── MainActivity.kt        # 主界面
│       │   │   ├── VideoAdapter.kt        # 列表适配器
│       │   │   └── FolderPickerActivity.kt
│       │   └── util/
│       │       └── WeChatScanner.kt      # 核心：扫描+保存逻辑
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml      # 主界面布局
│           │   └── item_video.xml         # 列表项布局
│           ├── values/
│           │   ├── colors.xml
│           │   ├── strings.xml
│           │   └── themes.xml
│           ├── drawable/
│           │   ├── ic_launcher_background.xml
│           │   └── ic_launcher_foreground.xml
│           └── mipmap-anydpi-v26/
│               ├── ic_launcher.xml
│               └── ic_launcher_round.xml
```

## 如何编译

### 方法一：Android Studio（推荐）

1. 在电脑上安装 [Android Studio](https://developer.android.com/studio)（最新版即可）
2. 把 `WeChatVideoSaver` 整个文件夹拷到电脑上
3. 打开 Android Studio → `File → Open` → 选择该文件夹
4. 等待 Gradle 同步完成（首次会自动下载依赖，需要联网）
5. 连接手机（开启 USB 调试）或创建模拟器
6. 点击绿色三角 ▶ Run 按钮，自动编译并安装到手机
7. 或者 `Build → Build APK(s)` 生成 APK 文件，拷到手机安装

### 方法二：命令行编译（需要 JDK 17 + Android SDK）

```bash
# 设置环境变量（示例）
export ANDROID_HOME=/path/to/Android/Sdk
export JAVA_HOME=/path/to/jdk-17

# 进入项目目录
cd WeChatVideoSaver

# 生成 Gradle Wrapper（如果没有 gradlew）
gradle wrapper

# 编译 Debug APK
./gradlew assembleDebug

# 生成的 APK 在：
# app/build/outputs/apk/debug/app-debug.apk
```

### 方法三：用在线编译服务

如果不想装 Android Studio，可以把项目上传到 [GitHub](https://github.com)，然后用以下在线服务编译：
- [GitHub Actions](https://github.com/features/actions)（配置 CI）
- [Bitrise](https://bitrise.io)
- [Codemagic](https://codemagic.io)

## 安装到手机

1. 把编译好的 `app-debug.apk` 拷到手机
2. 手机上点击安装（需要开启「允许安装未知来源应用」）
3. 安装完成后打开 App
4. 授予存储权限
5. 自动扫描微信视频

## 使用方法

1. **自动扫描**：打开 App 自动扫描微信视频
2. **手动扫描**：点击右下角搜索按钮重新扫描
3. **保存单个**：点击列表项 → 确认保存
4. **批量保存**：长按进入多选 → 勾选多个 → 点击「保存选中」
5. **保存位置**：手机相册 → `Movies/WeChatSaved/`

## 注意事项

### Android 11+ 的 /Android/data/ 限制

从 Android 11 开始，系统限制了第三方应用访问 `/Android/data/` 目录。如果微信视频存放在 `/sdcard/Android/data/com.tencent.mm/MicroMsg/` 下，可能无法直接扫描到。

**解决办法**：
1. App 也通过 MediaStore 查询（已实现），部分视频可通过此途径获取
2. 在手机自带的「文件管理器」中，手动进入 `/Android/data/com.tencent.mm/MicroMsg/` → 找到视频 → 分享/复制出来
3. OPPO/vivo 手机自带的文件管理器通常有权限访问此目录

### 微信视频路径说明

微信视频可能存储在以下路径（因微信版本和手机品牌而异）：

| 路径 | 说明 |
|------|------|
| `/sdcard/tencent/MicroMsg/<hash>/video/` | 老版本微信 |
| `/sdcard/Android/data/com.tencent.mm/MicroMsg/<hash>/video/` | 新版本微信 |
| `/sdcard/Tencent/MicroMsg/<hash>/video/` | 大小写变体 |

其中 `<hash>` 是 32 位用户标识。

## 技术参数

- **最低 SDK**：Android 7.0 (API 24)
- **目标 SDK**：Android 14 (API 34)
- **语言**：Kotlin
- **构建工具**：Gradle 8.5 + AGP 8.2.0
- **依赖库**：AndroidX, Material Components, RecyclerView, Coroutines

## 后续可扩展功能

- [ ] 用 SAF（Storage Access Framework）让用户手动选择微信目录
- [ ] 视频缩略图预览
- [ ] 视频时长显示
- [ ] 按日期/大小排序
- [ ] 搜索功能
