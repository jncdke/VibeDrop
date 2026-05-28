# iOS 免费 Personal Team 真机签名规格

## 1. 目标

在不购买 Apple Developer Program 的前提下，先让 VibeDrop iOS 版能够：

1. 在你自己的 iPhone 上签名安装
2. 通过 Xcode / Tauri 本地运行与调试
3. 不把个人 Apple Team ID 硬编码进仓库

## 2. 当前环境结论

本机调查结果：

1. `cargo tauri info` 显示 `iOS -> Developer Teams: None`
2. `xcrun devicectl list devices` 显示当前没有连接的 iPhone
3. 钥匙串里存在一个可用签名身份，但这不等于 Xcode 已经能选到可用 Team

结论：

- 当前机器还没有进入“可直接拿 Personal Team 真机跑”的状态
- 外部前置条件仍然有两个：
  1. 在 Xcode 中登录 Apple ID，生成/可见 `Personal Team`
  2. 把 iPhone 通过 USB 连到这台 Mac，并在设备侧完成信任与开发者模式

## 3. 设计原则

### 3.1 Team ID 不写死进仓库

`Personal Team` 是用户私有配置，不应直接写入仓库里的正式配置文件，否则会带来三个问题：

1. 这台机器可用，换一台机器就失效
2. 其他开发者拉代码后会拿到错误的 Team ID
3. 免费账号与付费账号、个人账号与公司账号容易互相污染

所以第一阶段统一采用：

- 运行时环境变量：`APPLE_DEVELOPMENT_TEAM`
- 或脚本参数：`--team <TEAM_ID>`

这和 Tauri 官方支持的覆盖机制一致。

### 3.2 先用 `run`，不先纠缠复杂分发

当前前端是静态文件 `frontendDist = ../src`，所以第一阶段真机调试优先使用：

- `cargo tauri ios run`

而不是先做 IPA 导出、TestFlight 或 App Store 分发。

这符合当前目标：先把 App 装到你自己的 iPhone 上跑起来。

## 4. 最短可执行路径

### Step 1. 用户操作

用户需要先完成：

1. 打开 Xcode
2. 进入 `Xcode -> Settings -> Accounts`
3. 登录自己的 Apple ID
4. 把 iPhone 连接到 Mac
5. 在 iPhone 上点“信任这台电脑”
6. 如果系统提示，打开开发者模式

### Step 2. 本地运行参数

本项目统一使用：

- `APPLE_DEVELOPMENT_TEAM=<TEAM_ID>`

作为 Tauri iOS 运行时的 Team 注入方式。

### Step 3. 启动方式

脚本化入口：

- `./scripts/deploy-ios.sh --team <TEAM_ID>`

如果要先手动看 Xcode 工程：

- `./scripts/deploy-ios.sh --open-xcode`

## 5. 脚本职责

`scripts/deploy-ios.sh` 需要承担这些职责：

1. 校验 `cargo` / `xcodebuild` / `xcrun`
2. 如无 iOS 工程则自动执行 `cargo tauri ios init --ci`
3. 校验是否提供 Team ID
4. 打印当前连接的 iPhone 列表，便于确认设备名
5. 用 `APPLE_DEVELOPMENT_TEAM=<TEAM_ID>` 调起 `cargo tauri ios run`
6. 支持只打开 Xcode 工程，不强制立即运行

## 6. 验收标准

以下条件成立即可认为这轮签名准备完成：

1. 仓库有固定的 iOS 运行脚本入口
2. Team ID 不被提交进通用项目配置
3. 用户只需要登录 Xcode 账号并连接 iPhone，就能继续跑下一步

## 7. 边界

免费 `Personal Team` 适合“自己设备上的开发调试”，不等于正式分发方案。后续如果要：

1. 长期稳定安装
2. 多设备批量分发
3. TestFlight
4. App Store

则应升级到付费 Apple Developer Program。
