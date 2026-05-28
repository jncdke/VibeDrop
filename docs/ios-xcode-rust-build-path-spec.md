# iOS Xcode Rust 构建 PATH 修复规格

## 1. 问题

当前 iOS 工程的预构建脚本 `Build Rust Code` 直接写的是：

`cargo tauri ios xcode-script ...`

但 Xcode 的脚本执行环境不会继承用户交互 shell 的完整 `PATH`。实际从 Xcode GUI 构建活动日志可以看到，脚本阶段的 `PATH` 里没有：

- `~/.cargo/bin`
- `/opt/homebrew/bin`

因此脚本报错：

- `cargo: command not found`

这就是 `Command PhaseScriptExecution failed with a nonzero exit code` 的真实根因。

## 2. 目标

让 Xcode GUI 与命令行构建在执行 `Build Rust Code` 时都能稳定找到：

1. `cargo`
2. `rustup` 间接代理的 Rust toolchain
3. `xcodegen` / Homebrew 环境下的常用二进制

## 3. 方案

不直接把某个用户机器上的绝对 `cargo` 路径硬编码进 `project.pbxproj`，而是新增一个仓库脚本：

- `scripts/run-tauri-ios-xcode-script.sh`

脚本职责：

1. 先补齐常见构建 PATH
   - `$HOME/.cargo/bin`
   - `/opt/homebrew/bin`
   - `/opt/homebrew/sbin`
   - `/usr/local/bin`
2. 校验 `cargo` 是否存在
3. 再调用 `cargo tauri ios xcode-script "$@"`

## 4. 为什么不用直接写死绝对路径

如果直接把 `/Users/xxx/.cargo/bin/cargo` 写进工程，会有三个问题：

1. 换用户目录就失效
2. 换机器就失效
3. 不利于以后把项目给别人或在 CI 上跑

所以更专业的方案是：

- Xcode 工程调用仓库里的稳定脚本
- 仓库脚本负责适配用户本机环境

## 5. 落地点

需要同步修改两处：

1. `mobile/src-tauri/gen/apple/project.yml`
2. `mobile/src-tauri/gen/apple/vibedrop-mobile.xcodeproj/project.pbxproj`

因为当前 Xcode 工程已经生成出来了，修改 `project.yml` 只是保证未来重新生成时不回退；修改 `project.pbxproj` 或重新运行 `xcodegen generate` 才能让当前工程立即生效。

补充一个容易踩的几何问题：Xcode 这里的 `SRCROOT` 实际指向的是 `mobile/src-tauri/gen/apple`，所以从工程脚本回到仓库根目录不是 `../../../`，而是 `../../../../`。如果少回一层，`PhaseScriptExecution` 会从“找不到 cargo”变成“找不到包装脚本”，表面上看还是脚本阶段失败，但根因已经变成相对路径错误。

## 6. 验收标准

满足以下条件即可视为修复完成：

1. Xcode GUI 再次点击 Run 时，不再出现 `cargo: command not found`
2. `Build Rust Code` 可以进入真正的 Rust/Tauri 构建阶段
3. 命令行复现日志中不再出现 PATH 缺少 cargo 的报错
