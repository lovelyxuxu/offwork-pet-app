# 搭子提醒 · 安卓接收端

最简安卓 App：加入已有集会，收到电脑发送的信号后弹通知并震动。支持默认的安全、警戒、危险和电脑端的自定义提醒。手机不发送警报、不创建集会、不提供聊天或桌宠。

## 安装和使用

安装包：`outputs/OffworkPet-Receiver-v0.1.7.apk`，Android 8.0 及以上。

线上下载：[Android APK](http://106.54.27.8/offwork-pet/android/OffworkPet-Receiver-v0.1.7.apk) · [官网下载页](http://106.54.27.8/download#android)。

1. 在电脑端创建或打开集会，找到 8 位集会码。
2. 打开“搭子提醒”，填写集会码，点“开始接收”，允许系统通知，并在系统提示时允许后台运行。
3. 点“测试通知”，确认横幅和震动能正常出现。
4. 切到微信、浏览器等其他应用，在电脑端发出提醒，检查手机是否通知并震动。

接收期间有一条静默的“搭子提醒”状态通知。可以退出 App 页面继续接收；在页面或状态通知中点“停止接收”即可断开。停止接收保留集会成员身份，下次使用无需重新填写集会码。

默认提醒只震动、不响铃；“通知设置”打开系统设置。横幅、震动和声音最终按系统通知设置及勿扰模式执行。接收状态会显示连接、加入或重连情况，不把断线显示成正在接收。

这是用户主动开启的后台实时连接方案，符合本次确认的“允许静默接收状态”。没有接入厂商推送，也没有隐藏后台服务的系统提示。屏幕开启、使用其他 App 时是首要验收场景；系统强制停止、限制后台、重启或长期锁屏省电后不承诺实时送达。重新打开 App 可以恢复此前开启的接收。

## 范围与数据

- 直接复用现有 `http://106.54.27.8` 的 `/health`、`/v2/devices` 和 `/v2/connect`。没有修改或部署桌宠服务端。
- 手机作为一个独立成员加入集会，计入集会人数。默认昵称为“手机”。
- 自动重连使用原设备身份，不重复创建设备或重放提醒。无效集会码和失效身份会明确提示。
- 按服务端时间和单调时钟丢弃过期提醒；过滤错误集会及乱序的旧信号；连续有效信号逐条通知，不做重复合并。通知到期自动消失，旧“危险”信号不会覆盖较新的“安全”。
- 设备令牌经 Android Keystore AES-GCM 加密后保存在应用私有存储，禁用云备份和设备迁移；不写令牌日志。仅保存集会码、开关、连接状态及最近一次信号摘要，不保存聊天内容。
- 安装包是开发签名的首个预览版，未做应用商店发布。保持同一个开发签名即可覆盖升级。

## 本地构建

需要 JDK 17、Android SDK 35 / Build Tools 35.0.0。项目使用 Gradle 8.11.1、Android Gradle Plugin 8.9.2 和原生 Java 界面，无需 Python/Qt 运行时。

已有 Android SDK 时，可用 Android Studio 打开本目录，或设置 `local.properties` / `ANDROID_HOME` 后执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

没有 SDK 时，项目提供 Windows 本地工具安装脚本。脚本从官方来源下载并核对校验和，SDK 与 Gradle 安装在忽略提交的 `.tools` 下，不改系统环境变量：

```powershell
python tools/bootstrap.py
.\build.ps1
```

`build.ps1` 执行 APK 构建、单元测试和 Android Lint，并将可安装 APK 复制到 `outputs`。

## 本地真实协议联调

使用临时数据库和本地服务，将 APK 相同的 Java 接收逻辑接到桌宠 Python 服务。验证注册、鉴权、入会以及三种信号（含自定义名称和颜色）。不连接线上集会。

```powershell
.\build.ps1
python tools/integration_relay.py --relay-project ..\offwork-pet
```

结果写入 `outputs/integration-relay.json`。这是协议与传输联调，不等同于安卓设备的通知、震动和后台省电验收。

Debug 构建额外允许通过 Activity 的 `test_endpoint` Intent 参数连接 `127.0.0.1`、`localhost`、`10.0.2.2` 的本机测试服务。正式构建不接受该参数，普通用户界面没有服务地址输入项。

连接异常时，点首页右上角状态标签打开“连接诊断”，可复制最近的连接记录。诊断只存本机，不记录消息内容或令牌。

## 源码与许可

这里是手机接收端源码。搭子后端和桌面端在 [offwork-pet](https://github.com/lovelyxuxu/offwork-pet)，Android 下载见 [GitHub Releases](https://github.com/lovelyxuxu/offwork-pet-app/releases)。项目自有代码采用 [MIT](LICENSE)，第三方依赖保留其发行许可。安装包使用开发签名。
