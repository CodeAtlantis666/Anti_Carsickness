# Anti_Carsickness
一个受同学的 iOS Vehicle Motion Cues（iOS 车辆运动提示）启发的轻量级 Android 实现，希望让安卓手机用户同样获得防晕车的能力

## 功能简介

- 通过运动传感器（`加速度计` + `陀螺仪`）检测可能的车内使用场景。
- 在屏幕边缘显示动画圆点。
- 圆点会根据感知到的加速度方向和速度近似值进行移动。
- 支持在其他应用上方显示全局悬浮窗（非触摸式，边缘提示低遮挡）。
- 支持通过 Android 快速设置磁贴（`Motion Cues`）一键开关。
- 包含类似 iOS 的选项：
  - `Off / Automatic / On`（关闭 / 自动 / 开启）
  - `Regular / Dynamic`（常规 / 动态）
  - `Normal / Larger dots`（正常 / 更大圆点）
  - `Normal / More dots`（正常 / 更多圆点）

## 构建

1. 安装 Android SDK 和命令行工具。
2. 设置环境变量：
   - `ANDROID_HOME` (或 `ANDROID_SDK_ROOT`)
   - `JAVA_HOME` (JDK 17+)
3. 如有需要，生成/恢复 Gradle wrapper，然后构建：

```powershell
./gradlew.bat clean :app:assembleRelease
```

或使用提供的脚本（推荐）：

```powershell
./scripts/build_apk.ps1 -Release -BuildAab -SignReleaseApk
```

## ABI 打包

`app/build.gradle.kts` 已配置为输出 ABI 拆分包和通用 APK：

- `armeabi-v7a`
- `arm64-v8a`
- `x86`
- `x86_64`
- `universal`

构建输出路径（release）：

- `app/build/outputs/apk/release/`
- 签名的 APK 名称：
  - `app-arm64-v8a-release.apk`
  - `app-armeabi-v7a-release.apk`
  - `app-x86-release.apk`
  - `app-x86_64-release.apk`
  - `app-universal-release.apk`

如需应用商店风格的自动架构交付，请构建 AAB：

```powershell
./gradlew.bat :app:bundleRelease
```

输出：

- `app/build/outputs/bundle/release/app-release.aab`

应用商店会从 AAB 自动安装正确的 ABI 拆分包。

## 注意事项

仅依赖传感器的车辆检测是启发式的。`VehicleMotionEngine.kt` 中的阈值可根据设备型号进行调整。

## 全局悬浮窗使用说明

1. 打开应用并授予悬浮窗权限。
2. 点击 `启动全局悬浮窗`。
3. 下拉快速设置，编辑磁贴，添加 `车辆运动提示`。
4. 使用该磁贴即可在其他应用中即时启动/停止悬浮窗。
