# Debug 签名 Profile 台架路径

此路径只存在于 Debug 构建；用户自己的 P-256 签名是**本地台架准入**，不是 Apple、OEM 或 MFi Profile 授权。真正的 AA05 收据仍须由当前 MFi provider 在连接中产生。CAF v1 编译只读属性；CAF v2 仅支持内存合成写入／控制。只有另行签入下述静态 UI Sync 目录、开启第二个独立选项并具备真实 DDP 物理输出时，才会尝试 UI Sync；它仍不是完整 Ultra 仪表实现。`source=synthetic` 的数值仅供协议测试，**不是车辆状态**。

设置中的独立 DDP 面板探针可只声明当前真实、可见且尺寸匹配的 MAIN Surface；旧式 type-111 预览不会被冒充为物理仪表。该探针不属于签名 CAF Profile，也不使 UI Sync 就绪；Surface 退役会撤销对应会话。切换设置后仍需在停车／台架上重新连接，且须分别检查 iPhone 的 SETUP 请求、实际接受、`/info` 和后续通道。

准备一个通过 `UltraProfilePackageInspector` 的 ZIP。除检查器要求的根文件和 `CarPlayPhone.assets/{package.json,theme.json,Info.plist}` 外，增加 `CarPlayPhone.assets/xcertplay-bench-caf-v1.json`。它必须声明 `format=xcertplay-debug-caf-v1`、`source=androidCar` 或 `synthetic`、`pluginID`、`protocolVersion=1.0`、`pluginConfig`、`configTree`、覆盖全部只读 CAF characteristic IID 的 `bindings` 和四个不同的有符号 `errors`。两个 DATA 优先级 0/1 都须存在。`androidCar` 的每条 binding 包含准确的 Android `propertyId` 与 `areaId`；`synthetic` 还须为每条 binding 显式提供 `syntheticValues`，且不能声称这些是实车读数。可参照 [可运行的签名 ZIP/CAF 测试样例](../common/src/testDebug/java/com/shilapi/xcertplay/bench/BenchUltraProfileDeploymentTest.kt)。所有这些字节均计入检查器的 canonical `contentDigest`，由后续签名覆盖。

可用仓库内的生成器制作固定的 CAF-only synthetic 样例（只含合成数值，没有 UI Sync，也不包含私钥／签名；输出路径必须是新文件）：

```sh
python3 scripts/create_debug_bench_profile.py /private/tmp/xcertplay-synthetic-caf-v1-profile.zip
python3 -m unittest discover -s scripts -p 'test_create_debug_bench_profile.py'
```

生成器默认使用单插件 ID `0`：iOS 27 会按 `pluginCount` 从 `0` 枚举，而旧样例 ID `7` 虽能通过本地签名，却会让手机的首个 `configRequest(0)` 找不到本地注册。`--plugin-id` 仅供显式路由诊断；每次改变 ZIP 都要重新导出请求并签名。生成器输出 SHA-256 便于核对传输，不能代替导入界面的当前 MFi 绑定签名，也不代表 Apple/OEM/MFi 授权。实机已验证 ID `0` 能解码请求并回复配置；尚未观察到后续订阅或读数，也不能据此宣称进入 Ultra UI。

仅在明确停车／台架、且所有观察者知道读数为假的情况下，可用 `--vehicle-motion-probe` 生成另一份 ZIP：它把合成数值放到 iOS 27 已知的 VehicleMotion → DisplayedSpeed → SpeedKMH/SpeedMPH 类型上，以观察手机是否进一步订阅。其速度值固定为 21.5/13.4，**不是车速，不能在行驶车辆上启用或作为仪表使用**。此选项需要另签名，默认 Profile 不使用这些速度类型。

一次获准的停车／台架试跑中，这一变体通过 AA05 本地验证，手机请求插件 `0`，App 回复 288 字节配置；约一分钟内未观察到 `register/read`，DATA 优先级 0 通道在 30 秒内没有首个认证帧。试跑后已关闭 CAF bench，把 Active Profile 换回未签名的普通测试 ZIP，并重连恢复普通 CarPlay；固定假速度不再活动。该结果不能证明手机接受了速度类型或显示了假速度。

在 Mac 上生成**仅供本次台架**的外部私钥和 DER 公钥；私钥不要导入 Android App：

```sh
openssl ecparam -name prime256v1 -genkey -noout -out bench-key.pem
openssl pkey -in bench-key.pem -pubout -outform DER -out bench-public.der
```

安装 Debug APK 后，在停车／台架状态下打开 App 的仪表屏／Ultra 设置，点“DEBUG CAF-only Profile bench”进入导入界面。Activity 是 Debug-only 且 `exported=false`；外部 App 和 `adb shell am start` 不能直接打开它。Release 不显示这个按钮。

在界面中用系统文件选择器选 ZIP、公钥，填写固定车辆 ID，按“Stage”，并勾选独立的 Debug bench opt-in。App 的正常 CarPlay Ultra 开关也要打开。首次重新启动 Controller 并连接 iPhone 后，当前 MFi 证书的**类型和身份摘要**会被写进该代 Profile 的签名请求；此时尚无签名，功能保持关闭。回到导入界面，使用“Export current MFi-bound signing request”保存二进制文件，在 Mac 上签名：

```sh
openssl dgst -sha256 -sign bench-key.pem -out bench-signature.der xcertplay-bench-signing-request.bin
```

在界面中导入 DER 签名，再断开并重新启动 Controller／重连 iPhone。导入过程不接受外部 Intent 中的文件路径或 URI；所有文件都必须由用户在 SAF 选择器里确认。每次准入会重新检查 ZIP、签名、当前 MFi 身份及 Store 中的 canonical Profile。更换 Profile、CAF schema／映射、车辆 ID、pin 或 MFi provider 证书后，旧签名不再有效，须重新导出请求并签名。取消勾选只影响**下一次 Controller 启动**；若要立刻停止当前 CAF，请先断开并关闭当前 Controller。`source=androidCar` 要求 Android Car service 可开始绑定；普通手机若只做协议台架，应使用明确签入 Profile 的 `source=synthetic`。

即便本地准入成功，iPhone 是否接受这些非 OEM 资产、是否开启协议通道仍需要独立实机日志证明。Release AAR 不含 bench provider 或导入 Activity，仍为 `Disabled/Unavailable`。

## 额外的签名静态 UI Sync 台架

可在同一个已签名 ZIP 中加入 `CarPlayPhone.assets/xcertplay-bench-uisync-v1.json`，或加入支持本地画布的 `xcertplay-bench-uisync-v2.json`；两版互斥。导入界面还须单独勾选“signed physical UI Sync bench”（CAF 台架总开关也须启用）。没有目录时仍走原 CAF-only 路径；有目录但选项未开、签名/目录无效、DDP 面板或物理 Surface 不满足条件时，本次 Profile 准入失败，不会悄悄降级成 CAF-only。

若仅想对现有 MAIN 物理输出试跑静态 UI Sync，可另外生成一份完整独立的 ZIP：

```sh
python3 scripts/create_debug_bench_profile.py --main-uisync /private/tmp/xcertplay-synthetic-caf-main-uisync-profile.zip
```

此选项保留相同的合成只读 CAF，并增加单个 `centerConsole` 显示、精确的 MAIN ScreenID/拓扑 UUID、DDP plug-in UID `xcertplay-main`，以及覆盖 800×480 逻辑画布的 type-110 `InputStream` 视频槽（实际像素仍由 Host 校验、映射）；它不制造空白仪表画面，也不包含密钥。它与 CAF-only ZIP 的内容摘要不同，因此导入后必须重新导出当前 MFi 绑定请求并重新签名，再单独开启上述 UI Sync 选项。编译通过只是本地目录/主题校验；仍须实际 iPhone 会话、物理 MAIN Surface、DDP 与视频入口证明才能显示，不能据此宣称 Ultra 已被手机接受。

目录按精确的 Profile 显示索引声明 ScreenID、MAIN/CLUSTER 角色与实例、拓扑/输出/UI Sync ID、所有选定静态布局与视频槽入口。编译器会重新读取已验证的 `CarPlayPhone.assets/package.json`，逐项检查签名目录、可执行静态主题子集和固定 UUID 绑定的视频入口；[测试样例](../common/src/testDebug/java/com/shilapi/xcertplay/bench/BenchUiSyncProfileCompilerTest.kt)展示了完整字段。Host 还独立核对实际 `/info displayPanels` 的 plug-in UID、可见 MAIN Surface 或受独立信任的外部 CLUSTER Surface、像素尺寸与同一媒体 sink。渲染器使用签名逻辑尺寸和精确 Host 像素尺寸映射，不从 Android DPI 猜缩放。

v2 每个布局增加必填 `localCanvasSlots` 数组，每项仅含 `zoneIndex`、`componentIndex`、`slotIndex` 和全目录唯一的 `contentId`。它只能命名该布局当前选中的、矩形且零 inset 的 `Empty` Slot；尺寸、位置、层级从已验证 `package.json` 重新编译，不能由目录或绘制者覆盖。发行方再次读取签名目录，并把画布绑定纳入主题语义指纹和运行时 manifest；跨组件的正面积绘制重叠会被拒绝。完整 v2 结构与篡改用例见同一[测试样例](../common/src/testDebug/java/com/shilapi/xcertplay/bench/BenchUiSyncProfileCompilerTest.kt)。

当前可执行子集为纯色背景、矩形视频槽与 v2 签名 `Empty` 槽中的本地 Canvas。Scene 仅在精确 AA05 代际、同一物理 batch 与同一 renderer child 完成绑定后尝试提交；缺视频入口时等待其发布并用新 graph 重试。本地画布使用 Host 真实像素尺寸：有已注册画笔时首帧就由它绘制，Debug 台架无画笔时才使用明显的 “LOCAL CANVAS” 诊断图；切换布局时旧 Surface 要等新场景票据 `PRESENTED` 才退役。票据不是物理面板可见证明，也不是 UI Sync ACK。此路径仍未实现真实车辆仪表值绘制、OEM 主题资源下载或完整 CarPlay Ultra，也未做此次修改后的 iPhone 实测。不要在行驶中启用。

App 代码可通过 `CarPlayUltraCustomCanvas.registerPainter(contentId) { canvas, pixels -> ... }` 为签名 `contentId` 注册完整帧绘制，并在数据变化时调用 `requestDraw(contentId)`；两者不暴露 Surface。画笔在子会话的后台生产线程上执行，坐标单位是该槽的真实像素，不要按 Android DPI 再缩放。只有当前已选中的签名槽位、存活 AA05、同一物理 batch 与 renderer graph 都匹配时才接受重绘；注册本身不会开启 UI Sync。注册句柄需在不再使用时关闭，旧句柄不能删除后来替换的画笔。Debug 台架在没有自定义画笔时才画上述诊断帧；非台架路径缺少画笔会拒绝场景提交。

## 显式合成写入／控制 v2 台架

Debug 另支持与只读 v1 **互斥**的 `CarPlayPhone.assets/xcertplay-bench-caf-v2.json`。文件必须由同一 ZIP 内容签名覆盖，声明 `format=xcertplay-debug-caf-v2`、`source=synthetic` 和 `mutationPolicy.enabled=true`，并为每个可写 IID／控制项提供明确的 `writeBindings`、`controlBindings` 与允许值；每通道 `maxTransactionsPerChannel` 不得超过 64。完整字段及可运行样例见 [BenchUltraProfileDeploymentTest.kt](../common/src/testDebug/java/com/shilapi/xcertplay/bench/BenchUltraProfileDeploymentTest.kt)。

v2 只修改每次会话独立的合成内存仓库，绝不调用 Android Car 写后端。DATA/DATA2 的相同交易 ID 与内容会复用已保存响应而不重复执行；相同 ID 携带不同内容会被拒绝，撤销与重连隔离旧状态。启用 v2 仍需本地 P-256 签名、明确 opt-in 和当前 AA05 身份；它不是 OEM 授权，也不证明实车控制或 iPhone 已开启 Ultra。
