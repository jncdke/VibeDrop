# iOS App 图标同步规格

## 1. 问题

iOS 真机上的 VibeDrop 仍显示默认图标，原因不是签名或安装问题，而是图标资源链路断在 Apple 工程这一层。

当前脚本 `scripts/generate-app-icons.py` 已经生成：

- `mobile/src-tauri/icons/ios/*.png`

但 Xcode 真正打包读取的是：

- `mobile/src-tauri/gen/apple/Assets.xcassets/AppIcon.appiconset/*.png`

这两个目录没有同步，所以 Tauri 初始化 Apple 工程后，`Assets.xcassets` 里的 `AppIcon` 仍可能保留默认图标。

## 2. 目标

让 iOS 版 App 图标和 Android 手机端图标保持同一套品牌语义：

1. 紫色底
2. 白色水滴符号
3. 使用手机端认可过的内部留白比例
4. 每次重新生成图标或重新生成 iOS 工程后，都能稳定覆盖 Xcode 使用的 `AppIcon.appiconset`

## 3. 方案

把 Apple 工程的 `AppIcon.appiconset` 纳入统一图标生成脚本。

生成脚本需要同时输出：

1. 长期源资产：`mobile/src-tauri/icons/ios/*.png`
2. Xcode 实际打包资产：`mobile/src-tauri/gen/apple/Assets.xcassets/AppIcon.appiconset/*.png`

并保留 `Contents.json` 不动，因为它定义了 Xcode 资产目录的 idiom、size、scale 和 filename 映射。

## 4. 图标几何

iOS 图标不应使用桌面端 `macOS app icon` 的透明外画布，也不应使用托盘模板图标。

iOS 应使用和 Android 手机端一致的 `mobile_launcher_source` 几何基础，也就是从正式母版图按手机端比例内缩后生成的紫底图标。这样 Android 和 iOS 在手机桌面上看到的是同一套视觉语义，而不是 iOS 自己跑成默认图标或桌面端透明容器图标。

## 5. 验收标准

1. `mobile/src-tauri/gen/apple/Assets.xcassets/AppIcon.appiconset/AppIcon-60x60@3x.png` 与 `mobile/src-tauri/icons/ios/AppIcon-60x60@3x.png` 内容一致。
2. 重新运行 `python3 scripts/generate-app-icons.py` 后，Apple 工程里的 AppIcon 会被覆盖。
3. 重新安装到 iPhone 后，桌面图标显示为 VibeDrop 紫底白色水滴图标。
