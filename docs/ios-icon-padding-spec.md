# iOS 图标留白调整规格

## 1. 问题

iPhone 桌面上的 VibeDrop 图标主体偏小，水滴周围紫色留白偏大。当前 iOS 图标复用了 `mobile_launcher_source`，该源图会先把完整图标缩进到 `82.4%` 再输出；这个策略适合部分移动端启动器，但不适合 iOS 桌面图标。

## 2. 原因

iOS App Icon 的平台规则是：开发者提供一张完整方形位图，系统在展示时统一套用圆角遮罩。也就是说，iOS 不需要我们在位图内部额外预留太多“外框安全区”。

Android 启动器和 adaptive icon 的展示逻辑更复杂，不同厂商可能会裁切、缩放或加形状容器；macOS 图标也有自己的透明画布和 Dock/Launchpad 展示缩放。所以三端不能只用同一个内缩比例。

## 3. 目标

1. 不改变安卓当前认可过的图标比例。
2. 不改变 macOS 当前认可过的图标比例。
3. 只放大 iOS AppIcon 的水滴主体，减少 iPhone 桌面上的紫色留白。
4. 继续同步写入 Xcode 真正打包使用的 `mobile/src-tauri/gen/apple/Assets.xcassets/AppIcon.appiconset`。

## 4. 方案

iOS 使用独立的 `ios_launcher_source`，不再复用 `mobile_launcher_source`。`ios_launcher_source` 直接使用母版 `source`，让 iOS 系统自己负责最终圆角遮罩。

预期主体占比从当前约 `52%` 提升到约 `63%`，视觉上会更接近你现在认可的 macOS / Android 观感，但不会影响它们已有资源。

## 5. 验收标准

1. `AppIcon-60x60@3x.png` 的白色主体宽度占比约为 `0.63`。
2. 安卓 `android-launcher-source.png` 主体比例不因本次 iOS 调整而变化。
3. 重新安装到 iPhone 后，桌面图标水滴更大、紫色边距更少。
