所有更改默认同时需要在 iOS 和 Android 生效，且行为需一致。

复盘记录：

- 共享 UI 与平台原生 UI 可能同时存在并分别作为实际入口。修改 `shared/src/commonMain` 后，必须确认 Android 的 `app` 和 iOS 的 `iosApp` 是否各自还有同功能实现，并分别检查、编译和安装验证，不能仅凭共享层代码推断两个平台都会生效。
- Compose 中使用 `Box` 叠加阴影时，`matchParentSize()` 子项不参与父容器尺寸测量。父容器必须至少有一个正常参与测量的内容子项（例如固定高度的 `Row`），否则胶囊、按钮等控件可能宽度变为 0 而完全消失。
- iOS 真机构建使用命令行时，工程若未配置 `DEVELOPMENT_TEAM` 会在签名阶段失败。应从本机 provisioning profile 或 Xcode 已配置的团队读取 Team ID，并通过本次 `xcodebuild` 的 `DEVELOPMENT_TEAM=<Team ID>` 参数构建；不要把个人本机签名配置写入仓库。
- 安装完成后，必须用实际连接设备核对包名/Bundle ID 和安装结果；Android 与 iOS 的 application identifier 不一定相同，不能只看构建成功。
- 搜索文件或代码时，默认始终排除缓存目录、第三方源码目录、构建目录及其他生成文件目录，避免被无用信息淹没；除非明确知道自己需要搜索这些目录。

真机安装流程：

- Android：先用 `adb devices -l` 确认目标 serial，再执行 `./gradlew :app:assembleDebug --no-daemon` 和 `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`。安装后必须执行 `adb -s <serial> shell pm path com.highsockscapital.sunshine`，并用 `dumpsys package` 核对版本信息。多设备环境禁止使用不带 `-s` 的 `adb install`。
- iOS 设备发现优先使用 `xcrun devicectl list devices`；`xctrace list devices` 可能把通过 CoreDevice 可用的设备显示为 Offline。用 `xcrun devicectl device info details --device <CoreDevice ID>` 获取实际 UDID、系统版本和 Developer Mode 状态。
- Xcode 16 及后续版本的 provisioning profile 可能位于 `~/Library/Developer/Xcode/UserData/Provisioning Profiles/`，不能只检查旧的 `~/Library/MobileDevice/Provisioning Profiles/`。用 `security cms -D -i <profile> | plutil -p -` 核对 Team ID、application identifier、有效期和 `ProvisionedDevices`；证书名称括号中的字符串不是 Team ID，Team ID 应读取 profile 的 `TeamIdentifier` 或 Xcode 的 `IDEProvisioningTeams`。
- iOS Debug 真机构建使用自动签名：`xcodebuild -project iosApp/Sunshine.xcodeproj -scheme Sunshine -configuration Debug -sdk iphoneos -destination 'id=<CoreDevice ID>' -derivedDataPath "$HOME/Library/Developer/Xcode/DerivedData/Sunshine-device" DEVELOPMENT_TEAM=<Team ID> CODE_SIGN_STYLE=Automatic CODE_SIGN_IDENTITY='Apple Development' build`。Xcode 管理型 profile 不得与 `CODE_SIGN_STYLE=Manual` 混用。Team ID 只通过本次命令传入，不写入项目配置。
- 不要对同一个 `derivedDataPath` 并发启动多个 `xcodebuild`，否则会触发 `build.db` locked。等待现有构建退出后再继续。
- iOS 构建成功后执行 `xcrun devicectl device install app --device <CoreDevice ID> <DerivedData>/Build/Products/Debug-iphoneos/Sunshine.app`，再执行 `xcrun devicectl device info apps --device <CoreDevice ID> --bundle-id com.highsockscapital.sunshine`，核对 Bundle ID、版本和 build number。
