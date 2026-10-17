<div align="center">
  <img src="https://raw.githubusercontent.com/shilapi/xcertplay/refs/heads/master/asset/xcertplay_small.png" width="180" height="180" alt="xcertplay icon" />
<h1><strong><font size="6">xcertplay</font></strong></h1>
  <a href="README.md">English</a> | <a href="README.zh-CN.md">中文</a>
  <p>xcertplay 是面向 Android 车机的 CarPlay 接收端项目。支持通过 CH341 I2C 桥接到 MFi 芯片，亦可通过板载 I2C 控制器直连，支持 CarPlay 有线和无线连接。</p>
</div>

## Features

- 面向 Android 和 Android Automotive OS 的 CarPlay 主机应用。
- 支持 CH341 桥接 MFI 芯片、原生 `/dev/i2c-N` 设备连接的 MFI 芯片、本地证书/私钥文件和 Remote MFI 认证（API 见下）。
- 支持 CarPlay 有线或无线连接。
- 实验性 CarPlay Ultra 互操作：包含加固后的多路 RCS、UI Sync、CAF/车辆数据基础，以及与网页输出解耦的仪表屏声明；证据不足的私有能力不会宣告。
- 支持语音、导航、音乐多通道音频输出并 mapping 至 Android 的对应通道。
- 支持动态 Activity resize ，并自动重新握手至新的分辨率。
- 支持主屏和仪表的独立网页输出（浏览器直接解码 H.264/H.265，主屏支持触控和音频）。
- 支持车机位置回传。
- 支持 Android 9 (API 28) 。

## 使用方法

1. 通过蓝牙将 iPhone 与车机配对。
2. CarPlay 视频流尚未启动时，点击右下角的设置按钮；也可以用三指向下滑动打开设置页面。
3. 确认所有设置均已按需配置。
   如需启用另一种手势，打开 `More gestures to Settings page`：单指从屏幕左侧 1/8 区域的上 1/4 开始，沿左侧下滑，在下 1/4 区域抬起。
4. 滑动到底部，选择 `Save & Reconnect`。
5. 按照你选择的方式连接 MFi 芯片。
6. 等待连接完成，然后开始使用。

## 网页显示

1. 在设置的 `Web displays` 中开启 `Main display` 和/或 `Instrument display`，填写分辨率，点击 `Save & Reconnect`。
2. 浏览器设备与 Android 接入同一局域网，打开设置里显示的地址（`https://<IP>:8080/main` 或 `/cluster`，带访问 key，可复制）。
3. 证书为自签名，每个浏览器首次打开会提示不安全，选择继续访问即可。
4. 主屏网页：支持双指以内触控；打开后手机音频转到网页播放，点一下屏幕即可出声（浏览器限制）。麦克风仍使用 Android。
5. 仪表网页仅显示，需要 iPhone 提供第二屏视频流。

建议使用较新的 Chrome / Edge / Safari；浏览器不支持 H.265 时，在设置中关闭 `HEVC (H.265)`。

## CarPlay Ultra

设置中可独立声明仪表屏。实验性 UI Sync 编解码、逐显示器路由和呈现桥已经实现并测试，
但在集成方提供获授权的 CarKit 车辆身份及匹配主题配置前，应用不会向手机宣告 UI Sync。
普通 type-111 视频、本地预览、PIP 和自定义仪表网页不依赖这些配置。

项目已经包含严格的 profile/主题解析、精确绑定会话的 MFi receipt 接口、V2 多输入/
多输出 graph、带解码器确认的 Surface 退休、方向明确的受限 UI Sync 语义、逐会话
Android 资源协调器，以及兼容 API 28 的 EGL14/GLES2 external-OES 后端。逻辑坐标映射
与诊断主题 scene 不读取 Android density。这些只是默认禁用的集成基础，不代表完整或
可启用的 Ultra。新增的 compiled-runtime manifest 会把已验证 profile 与 UI Sync 的
schema/binding/主题程序、renderer 实现、全部 display/output/ingress，以及 CAF 的
schema、decoder 和 handler 身份精确绑定；激活协调器会直接创建并持有最终
`AirPlayUltraRuntime`，因此 raw UI Sync/CAF provisioning 不能通过 ownership transfer
逃逸 AA05/profile 撤销。项目也已加入有证据支撑的 APAP packet、ChaCha20-Poly1305、
TCP 长度和 APTransport control 的严格独立 codec，以及只接收已授权注入 stream/key 的
有界入站运行时；在 live listener、SETUP/control 事务、decoder、时钟化音频 sink 与逐会话
readiness 完成前仍不会宣告 `mainBuffered`。AA05 授权生命周期已接入产品 Host，但默认 provider 与车辆身份源不可用；仍缺获授权的完整语义/主题工厂、UI Sync renderer/输出 Host 接线、真实跨屏原子
批量 binder、固定 OEM 信任验证器和获授权的 profile 编译器。产品 profile provider 保持
`Disabled`；Vehicle State Protocol、UI Sync、主题传输、UpdateData、LoggingData 等
尚未确认或依赖私有授权的能力不会宣告。
实现范围与证据矩阵见 [CarPlay Ultra 文档](docs/carplay-ultra.md)。

显示物理尺寸会参与 iPhone 侧的界面缩放。实验分支曾把默认基准从“宽度”迁移到“最长
边”但没有换算毫米值，可能导致竖屏 CarPlay 图标和文字明显变大；当前版本会自动修复
该测试偏好，并默认用横竖屏一致的“最短边”校准。这不是 Android 全局 DPI 修改。

## 当前进度

普通 CarPlay 已可运行👍；这不表示默认禁用的 Ultra 已具备产品可用性。普通路径已在车机/
手机平台测试，如果出现适配问题欢迎 issue（请附上
`/sdcard/Android/data/com.shilapi.xcertplay/files/logs/xcertplay.log`）。

转接板：[CH341-to-MFI](https://github.com/shilapi/ch341-to-mfi-chip)

## 本地 MFI 文件

在 `MFI certificate & signing target` 中选择 `Local files`，然后通过两个 `Choose`
按钮使用 Android 系统文件选择器选择证书和私钥。当前支持 DER PKCS#7 证书（`.p7b`）
及与之匹配的、未加密 DER PKCS#8 私钥（`.pk8`）。应用会在开始连接手机前校验两者是否
匹配，并在 MFI 重连时重新读取文件。

建议把私钥放在受保护的位置。应用不会把证书或私钥复制到偏好设置，只会保存 Android
授予的持久读取权限和文档 URI。

## 工程结构

| 路径 | 用途 |
| --- | --- |
| `common/` | 两个目标共用的 CarPlay 宿主界面、设置、持久化和应用资源。 |
| `mobile/` | 使用共享 CarPlay 主机界面的 Android 应用。 |
| `automotive/` | 使用共享主机界面并支持高级音频通道映射的 Android Automotive OS 应用。 |
| `shared/` | Car App Library 代码，以及 CH341、I2C、MFi、iPhone、iAP2、NCM、VPN、AirPlay 和媒体实现。 |

## Remote MFI 功能

Remote MFi 客户端把远程服务当作一块 MFi 芯片远程调用，抑或是采用 BAA 认证，通过远程进行认证免去了本地连接 MFI 芯片进行认证的流程。

### 端点

| Method | Path | 用途 | Request body | Success response | 失败 response |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/mfi/certificate` | 获取 MFI 芯片版本、证书类型和证书内容，客户端首次调用后缓存 | 无 | 证书 JSON | `{"detail":"..."}` |
| `POST` | `/mfi/sign` | 对 challenge 签名 | `{"challenge":"...","requestId":"..."}` | `{"signature":"..."}` | `{"detail":"..."}` |
| `POST` | `/mfi/reset` | 请求重置远程 MFI 芯片 | `{}` | `{"detail":""}` | `{"detail":"..."}` |

（可选）采用标准 Bearer Authentication 进行验证。

**当前仅测试了 BAA Authentication**

## 环境要求

- 启动 Gradle 需要可正常运行的 JDK 17 或更高版本；daemon 使用 `JAVA_HOME`（或 `PATH` 中的 Java）选定的 JDK，CI 单独指定 JDK。
- Android SDK Platform 37。
- Android 9（API 28）或更高版本。
  在 Android 9 上不可用 Wi-Fi P2P 5 GHz 模式，应用会改用 LocalOnlyHotspot。
- Android NDK `28.2.13676358`。
- 硬件验证需要支持 USB Host/OTG 的 Android 设备以及 MFi 硬件。

## 构建

在 Windows PowerShell 中：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest :common:lintDebug :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug
```

在 macOS 或 Linux 中：

```bash
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :common:lintDebug :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug
```

构建未签名 release APK：

```powershell
.\gradlew.bat :mobile:assembleRelease :automotive:assembleRelease
```

## 致谢

感谢 [LIVI](https://github.com/f-io/LIVI) 项目为本项目提供了重要参考。
感谢 [showcase](https://github.com/amineross/showcase) 项目为本项目的 BAA 认证提供重要参考。

## 许可证

本项目采用 [GNU General Public License v3.0](LICENSE) 许可。
