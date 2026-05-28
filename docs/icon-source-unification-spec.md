# 图标源统一替换规格

## 1. 背景

当前仓库的图标生成链路已经统一到了 `scripts/generate-app-icons.py`，但默认源图一度被错误切到了黑底白图 `状态栏图标.png`，在更早之前又长期优先使用金属质感版本 `图标_v2.png`。这会导致：

- Android 启动器图标可能生成错误的黑底版本，或者继续生成旧的金属版。
- macOS Dock / 应用 bundle 图标可能生成错误的黑底版本，或者继续生成旧的金属版。
- 只要重新跑部署脚本，之前手动换掉的图标又会被覆盖回错误版本。

用户期望的正式 App Icon 版本是仓库里已有的紫底简化版图标语义：

- 根目录 `图标.jpg`

该图标满足：

- 保留原来的紫色底
- 白色水滴与波纹符号
- 无金属高光质感
- 图形占比比金属版更克制，视觉上更小

## 2. 目标

统一所有 App Icon 相关输出，默认改用紫底简化版这条视觉线，并通过无损母版覆盖：

- `mobile/src/icon*.png`
- `desktop/src/icon.png`
- `desktop/static/icon*.png`
- `mobile/src-tauri/icons/*`
- `desktop/src-tauri/icons/*`
- Android `mipmap-*`
- macOS `icon.icns`
- Windows / iOS 派生图标

桌面端 app icon 还需要额外满足一条约束：

- 不附加人为的外阴影

原因是：

- macOS 已经会把图标放在自己的系统语境里显示。
- 额外的阴影会让图标像被悬浮起来，和原本简洁版本不一致。
- 用户当前明确不需要这个效果。

## 3. 例外项

桌面端菜单栏 / 托盘图标不跟 App Icon 共用同一张源图。

原因：

- 托盘图标在 macOS 中以模板图标方式显示：`icon_as_template(true)`。
- 它需要白色透明底的小尺寸单色资产，而不是紫底 App Icon。
- 托盘图标需要继续作为独立模板资产维护。
- 根目录 `状态栏图标.png` 也更适合作为单色辅助参考，而不是作为 App Icon 默认源图。

因此这里的原则是：

- 托盘图标继续独立于 App Icon 生成
- 具体尺寸和覆盖率优化另见 `docs/macos-tray-icon-sizing-spec.md`

## 4. 默认源图策略

默认查找顺序改为：

1. 环境变量 `APP_ICON_SOURCE`
2. 根目录 `图标.png`（由 `图标.jpg + 状态栏图标.png` 自动合成的无损母版）
3. 根目录 `图标_v2.png`
4. 根目录 `状态栏图标.png`

这样既能保证部署默认使用正确的紫底版，也能避免继续直接从低分辨率 JPEG 导出。更完整的清晰度策略见 `docs/icon-sharpness-spec.md`。

## 5. 文档与脚本同步

以下文案要同步修正：

- 部署脚本帮助信息不能再写“从 `图标.jpg` 生成”
- README 不能继续把黑底 `状态栏图标.png` 或金属版描述成默认正式源图
- 需要明确说明：`图标.jpg` 是默认正式源图，金属版和黑底图都只是兼容 / 辅助用途

## 6. 验证

实施后需要验证：

1. `python3 scripts/generate-app-icons.py` 成功生成全量图标
2. `./scripts/deploy-desktop.sh --no-open` 成功重装桌面端
3. `./scripts/deploy-android.sh --device <serial>` 成功安装到真机
4. 手机桌面图标与 macOS Dock 图标均变为紫底白图标简化版，而不是黑底或金属版
5. macOS Dock / Launchpad 中的桌面端 app icon 不再出现额外外阴影
