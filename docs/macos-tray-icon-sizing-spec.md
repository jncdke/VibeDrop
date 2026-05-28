# macOS 菜单栏图标放大规格

## 1. 问题

当前 macOS 菜单栏显示的是托盘模板图标 `desktop/src-tauri/icons/tray-icon.png`，不是 Dock / Launchpad 那张 App Icon。

运行时代码已经是标准做法：

- `desktop/src-tauri/src/main.rs` 使用 `TrayIconBuilder`
- `icon_as_template(true)` 让 macOS 按模板图标渲染

因此问题不在运行时 API，而在模板 PNG 自身的几何比例。

现状调查：

- 当前托盘图标画布尺寸是 `44x44`
- 实际非透明内容包围盒约为 `34x33`
- 宽高覆盖率约为 `77% x 75%`

这会导致进入菜单栏后视觉体积偏小，和周围常见菜单栏图标相比不够明显。

## 2. 目标

本次只优化 macOS 菜单栏图标的视觉体积，目标如下：

1. 让菜单栏中的 VibeDrop 图标更接近周围应用的存在感
2. 保持单色透明底模板图标机制，不改成彩色图标
3. 不影响 Dock / Launchpad 的 App Icon
4. 不新增阴影、描边、发光等花哨效果

## 3. 设计原则

菜单栏图标和 App Icon 是两套不同资产，不能混用：

- App Icon 服务于 Dock / Launchpad / Finder
- 菜单栏图标服务于极小尺寸状态栏，必须是模板图标

因此这里不应该直接拿紫底 App Icon 缩小，也不应该关闭 `icon_as_template(true)`。

正确做法是：

- 继续使用单色模板源图 `状态栏图标.png`
- 先提取白色符号为透明底模板图
- 去掉外围无效留白
- 再按更高的内容覆盖率回贴到 `44x44` 画布

## 4. 比例策略

新的菜单栏模板图标采用以下策略：

- 源图：根目录 `状态栏图标.png`
- 输出画布：`44x44`
- 内容目标覆盖率：`0.90`

也就是：

- 先裁出真实符号边界
- 再让最长边占到 `44 * 0.90 ≈ 40` 像素
- 最后居中放回透明画布

这样会比原先明显更大，但仍保留约 2px 级别的边距，不会挤到菜单栏边缘。

## 5. 实施方案

把托盘模板图标并入 `scripts/generate-app-icons.py`：

1. 读取 `状态栏图标.png`
2. 用亮度生成 alpha，得到透明底白色模板图
3. 裁掉外围无效透明区
4. 按 `0.90` 内容比例渲染成 `44x44`
5. 输出到：
   - `desktop/src-tauri/icons/tray-icon.png`
   - `mobile/src-tauri/icons/tray-icon.png`

移动端那份虽然当前不是本次重点，但和桌面端保持同一生成机制，能避免后续资产漂移。

## 6. 不做的事

本次明确不做：

- 不改 `desktop/src-tauri/src/main.rs` 的托盘 API 逻辑
- 不关闭 `icon_as_template(true)`
- 不给菜单栏图标加阴影或背景板
- 不改手机桌面 App Icon
- 不改 Dock / Launchpad 图标比例

## 7. 验证

1. 运行 `python3 scripts/generate-app-icons.py`
2. 确认 `desktop/src-tauri/icons/tray-icon.png` 的透明边距变小
3. 运行 `./scripts/deploy-desktop.sh --no-open`
4. 在 macOS 菜单栏确认图标比当前更大、更清楚，但仍是标准模板图标风格
