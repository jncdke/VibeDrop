# iOS 真机直接安装兜底规格

## 1. 背景

`cargo tauri ios run` 当前已经可以完成 iOS 真机构建，但在后续导出阶段偶发失败：

`Couldn't load -exportOptionsPlist ... no such file`

这个失败发生在 `** BUILD SUCCEEDED **` 之后，说明 `.app` 已经存在，只是 Tauri 封装的导出步骤没有拿到临时 plist。

## 2. 目标

部署脚本应该优先保持标准 Tauri 流程；如果标准流程失败，但本次构建已经产出新的真机 `.app`，脚本应自动用 Apple 官方命令行工具安装并启动应用。

## 3. 方案

1. 运行 `cargo tauri ios run` 前记录当前时间戳。
2. 如果标准流程失败，查找 `~/Library/Developer/Xcode/DerivedData` 下本次时间戳之后生成的 `VibeDrop.app`。
3. 找到后执行 `xcrun devicectl device install app` 安装到目标 iPhone。
4. 安装完成后执行 `xcrun devicectl device process launch` 启动 `com.vibedrop.mobile`。

## 4. 防误装

兜底安装只接受修改时间晚于本次部署开始时间的 `.app`。如果没有找到新产物，说明并非单纯导出失败，脚本应该保留失败状态，避免把旧包误装到手机。
