# 手机端启动图标留白修正规格

## 1. 问题

当前 Android 手机桌面图标虽然已经切回正确的紫底白色版本，但仍然显得偏挤。

问题不在颜色，而在几何关系：

- 桌面端 Dock 图标使用了单独的 macOS 渲染逻辑，会把图标放进更克制的图标容器里。
- Android 启动图标当前只是把整张紫底源图直接缩放后输出。
- 由于源图本身白色图形占比偏大，手机端最终看到的就是“白色图形离紫色边缘太近”，显得不高级。

## 2. 目标

手机端启动图标要达到的效果：

1. 保留当前紫底白色、无金属质感的视觉方向。
2. 增加紫色留白，让白色水滴和波纹离四边更远。
3. 不改变桌面端当前已经满意的比例。
4. 不影响托盘模板图标。

## 3. 机制设计

不能直接把桌面端 `render_macos_app_icon(...)` 的整张透明产物原样塞进 Android。

原因：

- macOS 图标是“透明外层 + 圆角图标容器”的桌面体系。
- Android 启动器会再套自己的遮罩和圆角，直接复用整张桌面产物容易出现额外透明边。

因此 Android 采用“复用桌面端内部比例，但不复用整张桌面透明外框”的方案。

### 3.1 先生成手机端专用基础源图

从紫底原图 `图标.jpg` 出发：

- 取原图角落的紫色作为背景色
- 新建同尺寸紫色画布
- 把整张原图按和桌面端一致的内容比例缩进去并居中贴回

因为原图本身就是完整紫底，所以这样做的本质是：

- 白色图形整体缩小
- 紫色底保持满铺
- 紫色留白自然变多

### 3.2 再生成 Android launcher 输出

手机端专用基础源图生成后，再继续走现有 Android 输出逻辑：

- `android-launcher-source.png`
- `mipmap-*` 下的 `ic_launcher.png`
- `ic_launcher_round.png`
- `ic_launcher_foreground.png`

## 4. 比例策略

手机端专用基础源图直接对齐桌面端当前生效的内容比例。

建议值：

- `MOBILE_LAUNCHER_SOURCE_SCALE = 0.824`

理由：

- 与当前桌面端图标容器使用同一内容缩放比例。
- 手机端会直接对齐你已经认可的 Mac 图标观感。
- 只统一“内部留白”，不直接搬运桌面端透明外框。

## 5. 实施范围

本次只调整手机端打包图标链路：

- `scripts/generate-app-icons.py`
- Android launcher 派生图
- `mobile/src-tauri/icons/*` 中与移动端包图标相关的 PNG 输出

不调整：

- 桌面端 Dock 图标比例
- `desktop/src-tauri/icons/tray-icon.png`
- 应用内网页顶部引用的 `/icon.png`

## 6. 验证

1. 运行 `python3 scripts/generate-app-icons.py`
2. 观察 `mobile/src-tauri/icons/android-launcher-source.png` 的白色图形占比是否更克制
3. 运行 `./scripts/deploy-android.sh --device <serial>`
4. 在手机桌面确认图标紫色留白更自然
