import AppKit
import Foundation
import SwiftUI
import VibeDropMacServer
import VibeDropNativeCore

struct MacContentView: View {
    @EnvironmentObject private var model: MacNativeAppModel
    @State private var selectedTab = "overview"

    var body: some View {
        VStack(spacing: 0) {
            header
            Picker("", selection: $selectedTab) {
                Text("概览").tag("overview")
                Text("历史").tag("history")
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 24)
            .padding(.bottom, 16)

            if !model.isAccessibilityTrusted {
                PermissionView()
                    .padding(.horizontal, 24)
                    .padding(.bottom, 24)
            } else if selectedTab == "overview" {
                OverviewView()
            } else {
                HistoryView()
            }
        }
        .frame(minWidth: 860, minHeight: 680)
        .background(Color(red: 0.94, green: 0.97, blue: 1.0))
        .task { model.startIfNeeded() }
    }

    private var header: some View {
        HStack(spacing: 14) {
            VibeDropBrandMark()
                .frame(width: 42, height: 42)
            VStack(alignment: .leading, spacing: 2) {
                Text("VibeDrop")
                    .font(.system(size: 34, weight: .black))
                Text(model.serviceError ?? "\(model.serviceStatus) · \(model.addressText)")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(model.serviceError == nil ? .secondary : .red)
            }
            Spacer()
            StatusPill(text: model.serviceStatus, isOnline: model.serviceError == nil)
            Button("复制地址") { model.copyAddress() }
                .buttonStyle(.borderedProminent)
            Button("复制 PIN") { model.copyPin() }
                .buttonStyle(.bordered)
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 22)
    }
}

private struct VibeDropBrandMark: View {
    private let image: NSImage? = {
        guard let url = Bundle.module.url(forResource: "VibeDropMark", withExtension: "png") else {
            return nil
        }
        return NSImage(contentsOf: url)
    }()

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 12)
                .fill(Color(red: 0.31, green: 0.25, blue: 0.43))
            if let image {
                Image(nsImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Image(systemName: "drop.fill")
                    .font(.system(size: 21))
                    .foregroundColor(.white)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

private struct PermissionView: View {
    @EnvironmentObject private var model: MacNativeAppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("允许 VibeDrop 代表你输入文字")
                .font(.system(size: 28, weight: .bold))
            Text("手机发来的文字要进入当前光标位置，macOS 必须给当前 App 辅助功能权限。服务可以先启动，但没有这个权限时只会记录失败历史，不会模拟键盘。")
                .font(.system(size: 15))
                .foregroundStyle(.secondary)
            HStack {
                Button("打开辅助功能设置") { model.openAccessibilitySettings() }
                    .buttonStyle(.borderedProminent)
                Button("重新检查") { model.requestAccessibilityPermission() }
                    .buttonStyle(.bordered)
            }
        }
        .padding(28)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.white.opacity(0.9), in: RoundedRectangle(cornerRadius: 22))
        .overlay(RoundedRectangle(cornerRadius: 22).stroke(Color.black.opacity(0.08)))
    }
}

private struct OverviewView: View {
    @EnvironmentObject private var model: MacNativeAppModel

    var body: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 18), GridItem(.flexible(), spacing: 18)], spacing: 18) {
                ConnectionCard()
                ConnectedClientsCard()
                PairRequestsCard()
                DropSendCard()
                    .gridCellColumns(2)
                DiagnosticCard()
                    .gridCellColumns(2)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
        }
    }
}

private struct ConnectionCard: View {
    @EnvironmentObject private var model: MacNativeAppModel

    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: 16) {
                Text("高级连接信息").font(.system(size: 20, weight: .bold))
                InfoRow(label: "主机名", value: model.configuration?.hostname ?? "加载中")
                InfoRow(label: "地址", value: model.addressText)
                InfoRow(label: "PIN", value: model.pinText)
                Divider()
                Toggle("开机自动启动", isOn: Binding(
                    get: { model.launchAtLoginEnabled },
                    set: { model.setLaunchAtLoginEnabled($0) }
                ))
                .toggleStyle(.switch)
                HStack {
                    Text("登录项状态：\(model.launchAtLoginStatus)")
                        .font(.system(size: 13))
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button("系统登录项") { model.openLoginItemsSettings() }
                        .buttonStyle(.bordered)
                }
                Text("手机端优先用“附近电脑 + 验证码配对”，手动地址只作为排障兜底。")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
            }
        }
    }
}

private struct ConnectedClientsCard: View {
    @EnvironmentObject private var model: MacNativeAppModel

    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Text("当前已连接设备").font(.system(size: 20, weight: .bold))
                    Spacer()
                    CountBadge(count: model.connectedClients.count)
                }
                if model.connectedClients.isEmpty {
                    EmptyHint("手机连接成功后会出现在这里。")
                } else {
                    ForEach(model.connectedClients, id: \.peer.sessionId) { item in
                        Button {
                            model.selectedSessionId = item.peer.sessionId
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(item.peer.deviceName).font(.system(size: 15, weight: .bold))
                                    Text(item.peer.canReceiveFiles ? "支持接收文件" : "只支持文字/剪贴板")
                                        .font(.system(size: 12))
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                if model.selectedSessionId == item.peer.sessionId {
                                    Image(systemName: "checkmark.circle.fill").foregroundStyle(.blue)
                                }
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .padding(12)
                        .background(Color(red: 0.96, green: 0.98, blue: 1.0), in: RoundedRectangle(cornerRadius: 12))
                    }
                }
            }
        }
    }
}

private struct PairRequestsCard: View {
    @EnvironmentObject private var model: MacNativeAppModel

    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Text("待确认配对").font(.system(size: 20, weight: .bold))
                    Spacer()
                    CountBadge(count: model.pendingPairRequests.count)
                }
                if model.pendingPairRequests.isEmpty {
                    EmptyHint("手机端发起配对后会显示验证码。")
                } else {
                    ForEach(model.pendingPairRequests, id: \.requestId) { request in
                        VStack(alignment: .leading, spacing: 10) {
                            Text(request.clientName).font(.system(size: 15, weight: .bold))
                            Text(request.code)
                                .font(.system(size: 30, weight: .black, design: .monospaced))
                            HStack {
                                Button("拒绝") { model.rejectPairRequest(request) }
                                    .buttonStyle(.bordered)
                                Button("同意") { model.approvePairRequest(request) }
                                    .buttonStyle(.borderedProminent)
                            }
                        }
                        .padding(12)
                        .background(Color(red: 0.96, green: 0.98, blue: 1.0), in: RoundedRectangle(cornerRadius: 12))
                    }
                }
            }
        }
    }
}

private struct DropSendCard: View {
    @EnvironmentObject private var model: MacNativeAppModel
    @State private var isTargeted = false

    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("发送到手机").font(.system(size: 22, weight: .bold))
                        Text(model.selectedFilePeer.map { "目标：\($0.deviceName)" } ?? "当前没有可接收文件的手机")
                            .font(.system(size: 13))
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("选择文件") { chooseFiles() }
                        .buttonStyle(.bordered)
                }
                RoundedRectangle(cornerRadius: 20)
                    .fill(isTargeted ? Color.blue.opacity(0.12) : Color(red: 0.95, green: 0.98, blue: 1.0))
                    .frame(height: 150)
                    .overlay(
                        VStack(spacing: 10) {
                            Image(systemName: "square.and.arrow.up")
                                .font(.system(size: 34, weight: .semibold))
                                .foregroundStyle(.blue)
                            Text("拖入文件发送到手机")
                                .font(.system(size: 18, weight: .bold))
                            Text("支持普通文件、文件夹和多文件发送；图片/视频在手机端进入相册，其它内容进入下载目录。")
                                .font(.system(size: 13))
                                .foregroundStyle(.secondary)
                        }
                    )
                    .onDrop(of: MacDropFileLoader.typeIdentifiers, isTargeted: $isTargeted) { providers in
                        MacDropFileLoader.load(from: providers) { result in
                            switch result {
                            case let .success(payload):
                                model.sendFiles(payload.urls, cleanupDirectories: payload.cleanupDirectories)
                            case let .failure(error):
                                model.reportFileDropError(error.localizedDescription)
                            }
                        }
                        return true
                    }
                ForEach(model.transferItems) { item in
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(item.fileName).font(.system(size: 14, weight: .bold))
                                Text(item.detail).font(.system(size: 12)).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(statusText(item.status))
                                .font(.system(size: 12, weight: .bold))
                                .foregroundStyle(item.status == "success" ? .green : item.status == "failed" ? .red : .blue)
                        }
                        if item.totalBytes > 0 {
                            HStack(spacing: 10) {
                                ProgressView(value: min(1, max(0, item.progress)))
                                    .progressViewStyle(.linear)
                                Text("\(Int((min(1, max(0, item.progress)) * 100).rounded()))%")
                                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                                    .foregroundStyle(.secondary)
                                    .frame(width: 38, alignment: .trailing)
                            }
                        }
                    }
                    .padding(12)
                    .background(Color.white.opacity(0.7), in: RoundedRectangle(cornerRadius: 12))
                }
            }
        }
    }

    private func chooseFiles() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseFiles = true
        panel.canChooseDirectories = true
        panel.canCreateDirectories = false
        panel.title = "选择要发送到手机的文件"
        if panel.runModal() == .OK {
            model.sendFiles(panel.urls)
        }
    }

    private func statusText(_ status: String) -> String {
        switch status {
        case "success": return "已发送"
        case "failed": return "失败"
        default: return "发送中"
        }
    }
}

private struct DiagnosticCard: View {
    @EnvironmentObject private var model: MacNativeAppModel

    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("诊断日志").font(.system(size: 22, weight: .bold))
                        Text("记录服务启动、配对、连接数量变化和文件发送事件；不记录正文、剪贴板内容和文件路径。")
                            .font(.system(size: 13))
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("刷新") { model.refreshDiagnostics() }
                        .buttonStyle(.bordered)
                    Button("导出诊断") { model.exportDiagnostics() }
                        .buttonStyle(.borderedProminent)
                }
                if let path = model.diagnosticExportPath {
                    Text("最近导出：\((path as NSString).lastPathComponent)")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                if model.diagnosticEvents.isEmpty {
                    EmptyHint("暂无诊断事件。")
                } else {
                    VStack(spacing: 8) {
                        ForEach(model.diagnosticEvents.prefix(8)) { event in
                            HStack(alignment: .top, spacing: 12) {
                                Image(systemName: diagnosticIcon(scope: event.scope))
                                    .font(.system(size: 14, weight: .bold))
                                    .foregroundStyle(.blue)
                                    .frame(width: 18)
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(event.label)
                                        .font(.system(size: 12, weight: .bold, design: .monospaced))
                                        .foregroundStyle(.primary)
                                    Text(event.detailText)
                                        .font(.system(size: 12))
                                        .foregroundStyle(.secondary)
                                        .lineLimit(2)
                                }
                                Spacer()
                            }
                            .padding(10)
                            .background(Color(red: 0.96, green: 0.98, blue: 1.0), in: RoundedRectangle(cornerRadius: 12))
                        }
                    }
                }
            }
        }
    }

    private func diagnosticIcon(scope: String) -> String {
        switch scope {
        case "service": return "antenna.radiowaves.left.and.right"
        case "clients": return "iphone.radiowaves.left.and.right"
        case "pair": return "person.badge.key"
        case "transfer": return "arrow.up.doc"
        case "permission": return "hand.raised"
        case "login-item": return "power"
        default: return "waveform.path.ecg"
        }
    }
}

private struct HistoryView: View {
    @EnvironmentObject private var model: MacNativeAppModel
    @State private var query = ""
    @State private var timeFilter = "all"
    @State private var kindFilter = "all"
    @State private var statusFilter = "all"
    @State private var senderFilter = "all"
    @State private var receiverFilter = "all"
    @State private var hourFilter = "all"
    @State private var customStartDate = ""
    @State private var customEndDate = ""
    @State private var customStartTime = ""
    @State private var customEndTime = ""
    @State private var heatmapWindowOffset = 0
    @State private var heatmapSelection: MacHeatmapSelection?
    @State private var previewItem: HistoryItem?

    private var senderFilters: [HistoryEndpointFilter] {
        buildHistoryEndpointFilters(entries: model.recentHistory, endpoint: .sender)
    }

    private var receiverFilters: [HistoryEndpointFilter] {
        buildHistoryEndpointFilters(entries: model.recentHistory, endpoint: .receiver)
    }

    private var baseFilteredEntries: [HistoryEntry] {
        let now = Date()
        return model.recentHistory.filter { entry in
            guard matchesHistoryDateFilter(entry.timestamp, filter: timeFilter, now: now, startDate: customStartDate, endDate: customEndDate),
                  matchesHistoryHourFilter(entry.timestamp, filter: hourFilter, startTime: customStartTime, endTime: customEndTime) else {
                return false
            }
            if kindFilter != "all" &&
                entry.kind != kindFilter &&
                !entry.items.contains(where: { $0.kind == kindFilter }) {
                return false
            }
            if statusFilter != "all" &&
                entry.status != statusFilter &&
                !entry.items.contains(where: { $0.status == statusFilter }) {
                return false
            }
            if senderFilter != "all" && !entry.matchesSenderFilter(senderFilter) {
                return false
            }
            if receiverFilter != "all" && !entry.matchesReceiverFilter(receiverFilter) {
                return false
            }
            let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { return true }
            let haystack = [
                entry.text,
                entry.kind,
                kindLabel(entry.kind),
                entry.status,
                statusLabel(entry.status),
                entry.sender?.deviceId,
                entry.sender?.displayName,
                entry.receiver?.deviceId,
                entry.receiver?.displayName,
                entry.items.map {
                    [
                        $0.kind,
                        kindLabel($0.kind),
                        $0.fileName,
                        $0.mimeType,
                        $0.status,
                        statusLabel($0.status ?? ""),
                        $0.localPath,
                        $0.savedPath
                    ].compactMap { $0 }.joined(separator: " ")
                }.joined(separator: " ")
            ].compactMap { $0 }.joined(separator: " ").lowercased()
            return haystack.contains(trimmed.lowercased())
        }
    }

    private var filteredEntries: [HistoryEntry] {
        guard let heatmapSelection else { return baseFilteredEntries }
        let calendar = Calendar.current
        return baseFilteredEntries.filter { entry in
            calendar.startOfDay(for: entry.timestamp) == heatmapSelection.dayStart &&
                calendar.component(.hour, from: entry.timestamp) == heatmapSelection.hour
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Text("传输历史").font(.system(size: 24, weight: .bold))
                    Spacer()
                    Button("导出历史") { model.exportHistory() }
                        .buttonStyle(.bordered)
                    Button("刷新") { model.refresh() }
                        .buttonStyle(.bordered)
                }
                HStack(spacing: 12) {
                    TextField("搜索内容、设备或文件名", text: $query)
                        .textFieldStyle(.roundedBorder)
                    if !query.isEmpty {
                        Button("清空") {
                            query = ""
                        }
                        .buttonStyle(.bordered)
                    }
                }
                HStack(spacing: 12) {
                    Picker("", selection: $kindFilter) {
                        Text("全部类型").tag("all")
                        Text("文本").tag("text")
                        Text("媒体").tag("media")
                        Text("图片").tag("image")
                        Text("视频").tag("video")
                        Text("文件").tag("file")
                    }
                    .pickerStyle(.menu)
                    .frame(width: 150)
                    Picker("", selection: $statusFilter) {
                        Text("全部状态").tag("all")
                        Text("成功").tag("success")
                        Text("进行中").tag("pending")
                        Text("部分完成").tag("partial")
                        Text("失败").tag("failed")
                    }
                    .pickerStyle(.menu)
                    .frame(width: 150)
                    Picker("", selection: $senderFilter) {
                        ForEach(senderFilters, id: \.key) { filter in
                            Text(filter.label).tag(filter.key)
                        }
                    }
                    .pickerStyle(.menu)
                    .frame(width: 220)
                    Picker("", selection: $receiverFilter) {
                        ForEach(receiverFilters, id: \.key) { filter in
                            Text(filter.label).tag(filter.key)
                        }
                    }
                    .pickerStyle(.menu)
                    .frame(width: 220)
                    Spacer()
                }
                HStack(spacing: 12) {
                    Picker("", selection: $timeFilter) {
                        Text("全部时间").tag("all")
                        Text("今天").tag("today")
                        Text("近7天").tag("7d")
                        Text("近30天").tag("30d")
                        Text("自定义").tag("custom")
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 440)
                    Picker("", selection: $hourFilter) {
                        Text("全天").tag("all")
                        Text("上午").tag("morning")
                        Text("下午").tag("afternoon")
                        Text("晚上").tag("evening")
                        Text("凌晨").tag("night")
                        Text("自定义时段").tag("custom")
                    }
                    .pickerStyle(.menu)
                    .frame(width: 150)
                    if timeFilter == "custom" {
                        TextField("开始日期 YYYY-MM-DD", text: $customStartDate)
                            .textFieldStyle(.roundedBorder)
                            .frame(width: 170)
                        TextField("结束日期 YYYY-MM-DD", text: $customEndDate)
                            .textFieldStyle(.roundedBorder)
                            .frame(width: 170)
                    }
                    if hourFilter == "custom" {
                        TextField("开始时间 HH:mm", text: $customStartTime)
                            .textFieldStyle(.roundedBorder)
                            .frame(width: 140)
                        TextField("结束时间 HH:mm", text: $customEndTime)
                            .textFieldStyle(.roundedBorder)
                            .frame(width: 140)
                    }
                    if timeFilter == "custom" || hourFilter != "all" {
                        Button("清除自定义") {
                            timeFilter = "all"
                            hourFilter = "all"
                            customStartDate = ""
                            customEndDate = ""
                            customStartTime = ""
                            customEndTime = ""
                            heatmapSelection = nil
                            heatmapWindowOffset = 0
                        }
                        .buttonStyle(.bordered)
                    }
                    Spacer()
                }
                MacHistoryHeatmap(
                    entries: baseFilteredEntries,
                    windowOffset: heatmapWindowOffset,
                    selection: heatmapSelection,
                    onWindowOffsetChange: { heatmapWindowOffset = $0 },
                    onSelect: { heatmapSelection = $0 }
                )
                if filteredEntries.isEmpty {
                    EmptyHint(heatmapSelection == nil ? "等待接收或发送内容。" : "这个小时没有匹配记录。")
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    LazyVStack(alignment: .leading, spacing: 14) {
                        ForEach(filteredEntries, id: \.id) { entry in
                            HistoryRow(entry: entry, onPreviewItem: { previewItem = $0 })
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
        }
        .sheet(item: $previewItem) { item in
            MacHistoryMediaPreview(item: item)
        }
    }
}

private struct MacHeatmapSelection: Equatable {
    var dayStart: Date
    var hour: Int
}

private struct MacHistoryHeatmap: View {
    let entries: [HistoryEntry]
    let windowOffset: Int
    let selection: MacHeatmapSelection?
    let onWindowOffsetChange: (Int) -> Void
    let onSelect: (MacHeatmapSelection?) -> Void

    private let visibleDayCount = 7

    private var days: [Date] {
        let calendar = Calendar.current
        let anchor = entries.map(\.timestamp).max() ?? Date()
        let end = calendar.date(
            byAdding: .day,
            value: -max(0, windowOffset),
            to: calendar.startOfDay(for: anchor)
        ) ?? calendar.startOfDay(for: Date())
        return (0..<visibleDayCount).compactMap { offset in
            calendar.date(byAdding: .day, value: offset - (visibleDayCount - 1), to: end)
        }
    }

    private var counts: [String: Int] {
        let calendar = Calendar.current
        let visibleDays = Set(days.map { Int($0.timeIntervalSince1970) })
        return entries.reduce(into: [String: Int]()) { result, entry in
            let day = calendar.startOfDay(for: entry.timestamp)
            guard visibleDays.contains(Int(day.timeIntervalSince1970)) else { return }
            let hour = calendar.component(.hour, from: entry.timestamp)
            result[key(day: day, hour: hour), default: 0] += 1
        }
    }

    private var maxCount: Int {
        max(1, counts.values.max() ?? 1)
    }

    private var peak: (key: String, value: Int)? {
        counts.max { lhs, rhs in lhs.value < rhs.value }
    }

    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("接收热力图").font(.system(size: 18, weight: .bold))
                    Spacer()
                    Button("更早") {
                        onWindowOffsetChange(windowOffset + visibleDayCount)
                        onSelect(nil)
                    }
                    .buttonStyle(.bordered)
                    Button("更近") {
                        onWindowOffsetChange(max(0, windowOffset - visibleDayCount))
                        onSelect(nil)
                    }
                    .buttonStyle(.bordered)
                    .disabled(windowOffset <= 0)
                    if windowOffset > 0 || selection != nil {
                        Button("回到最近") {
                            onWindowOffsetChange(0)
                            onSelect(nil)
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    Text("当前筛选 \(entries.count) 条")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(.secondary)
                }
                HStack(spacing: 10) {
                    Tag("\(days.first?.formatted(.dateTime.month().day()) ?? "") - \(days.last?.formatted(.dateTime.month().day()) ?? "")")
                    Tag("峰值 \(peak.map { "\($0.value) 条" } ?? "无")")
                    if let selection {
                        Tag("\(selection.dayStart.formatted(.dateTime.month().day())) \(selection.hour):00")
                    }
                }
                HStack(alignment: .bottom, spacing: 9) {
                    ForEach(days, id: \.timeIntervalSince1970) { day in
                        VStack(spacing: 4) {
                            Text(day.formatted(.dateTime.weekday(.abbreviated)))
                                .font(.system(size: 11, weight: .bold))
                                .foregroundStyle(.secondary)
                            Text(day.formatted(.dateTime.month().day()))
                                .font(.system(size: 11, weight: .bold))
                            VStack(spacing: 3) {
                                ForEach(0..<24, id: \.self) { hour in
                                    let count = counts[key(day: day, hour: hour), default: 0]
                                    let isSelected = selection?.dayStart == day && selection?.hour == hour
                                    Button {
                                        let next = MacHeatmapSelection(dayStart: day, hour: hour)
                                        onSelect(selection == next ? nil : next)
                                    } label: {
                                        RoundedRectangle(cornerRadius: 3)
                                            .fill(color(count: count))
                                            .frame(width: 46, height: 7)
                                            .overlay(
                                                RoundedRectangle(cornerRadius: 3)
                                                    .stroke(isSelected ? Color.blue : Color.clear, lineWidth: 2)
                                            )
                                    }
                                    .buttonStyle(.plain)
                                    .help("\(day.formatted(.dateTime.month().day())) \(hour):00 · \(count) 条")
                                }
                            }
                        }
                    }
                }
                HStack {
                    Text("少").font(.system(size: 11, weight: .bold)).foregroundStyle(.secondary)
                    LinearGradient(
                        colors: [Color.white, Color(red: 0.36, green: 0.86, blue: 0.54), Color(red: 0.03, green: 0.07, blue: 0.05)],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                    .frame(height: 9)
                    .clipShape(Capsule())
                    Text("多").font(.system(size: 11, weight: .bold)).foregroundStyle(.secondary)
                }
            }
        }
    }

    private func key(day: Date, hour: Int) -> String {
        "\(Int(day.timeIntervalSince1970)):\(hour)"
    }

    private func color(count: Int) -> Color {
        guard count > 0 else { return Color(red: 0.95, green: 0.96, blue: 0.98) }
        let ratio = sqrt(min(1.0, Double(count) / Double(maxCount)))
        if ratio < 0.58 {
            return interpolateColor(
                from: (1.0, 1.0, 1.0),
                to: (0.36, 0.86, 0.54),
                t: ratio / 0.58
            )
        }
        return interpolateColor(
            from: (0.36, 0.86, 0.54),
            to: (0.03, 0.07, 0.05),
            t: (ratio - 0.58) / 0.42
        )
    }

    private func interpolateColor(
        from: (Double, Double, Double),
        to: (Double, Double, Double),
        t: Double
    ) -> Color {
        let clamped = min(1.0, max(0.0, t))
        return Color(
            red: from.0 + (to.0 - from.0) * clamped,
            green: from.1 + (to.1 - from.1) * clamped,
            blue: from.2 + (to.2 - from.2) * clamped
        )
    }
}

private struct HistoryRow: View {
    let entry: HistoryEntry
    var onPreviewItem: (HistoryItem) -> Void = { _ in }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 14) {
                VStack(alignment: .leading, spacing: 7) {
                    Text(entry.timestamp.formatted(date: .numeric, time: .standard))
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(.secondary)
                    Text(entry.text ?? entry.items.first?.fileName ?? entry.kind)
                        .font(.system(size: 15, weight: .bold))
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            Tag(kindLabel(entry.kind))
                            Tag(statusLabel(entry.status))
                            if let itemCount = entry.itemCount, itemCount > 1 { Tag("\(itemCount) 项") }
                            if let saveTarget = entry.saveTarget { Tag(saveTargetLabel(saveTarget)) }
                            if let sender = entry.sender?.displayName { Tag(sender) }
                            if let receiver = entry.receiver?.displayName { Tag(receiver) }
                        }
                    }
                }
                Spacer()
            }
            if !entry.items.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(entry.items.prefix(16), id: \.id) { item in
                            MacHistoryItemPreview(item: item, onPreview: { onPreviewItem(item) })
                        }
                        if entry.items.count > 16 {
                            ExtraItemsTile(count: entry.items.count - 16)
                        }
                    }
                }
            }
        }
        .padding(16)
        .background(.white.opacity(0.92), in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.black.opacity(0.06)))
    }
}

private struct MacHistoryItemPreview: View {
    let item: HistoryItem
    let onPreview: () -> Void

    private var image: NSImage? {
        if let thumbnail = item.thumbnailDataUrl.flatMap(decodeDataURLImage) {
            return thumbnail
        }
        guard item.kind == "image",
              let path = item.savedPath ?? item.localPath,
              FileManager.default.fileExists(atPath: path) else {
            return nil
        }
        return NSImage(contentsOf: URL(fileURLWithPath: path))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack {
                RoundedRectangle(cornerRadius: 10)
                    .fill(itemKindColor(item.kind))
                if let image {
                    Image(nsImage: image)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .frame(width: 92, height: 58)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                } else {
                    Text(itemShortLabel(item))
                        .font(.system(size: 13, weight: .black))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .padding(.horizontal, 6)
                }
            }
            .frame(width: 92, height: 58)
            Text(item.fileName ?? kindLabel(item.kind))
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(.primary)
                .lineLimit(1)
            Text(statusLabel(item.status ?? ""))
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(item.status == "failed" ? .red : .secondary)
                .lineLimit(1)
        }
        .padding(7)
        .frame(width: 108, alignment: .leading)
        .background(Color(red: 0.95, green: 0.97, blue: 1.0), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(statusBorderColor(item.status)))
        .contentShape(Rectangle())
        .onTapGesture(perform: onPreview)
    }
}

private struct MacHistoryMediaPreview: View {
    @Environment(\.dismiss) private var dismiss
    let item: HistoryItem

    private var image: NSImage? {
        if let image = item.thumbnailDataUrl.flatMap(decodeDataURLImage) {
            return image
        }
        guard item.kind == "image",
              let path = historyItemOpenPath(item),
              FileManager.default.fileExists(atPath: path) else {
            return nil
        }
        return NSImage(contentsOf: URL(fileURLWithPath: path))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(item.fileName ?? kindLabel(item.kind))
                        .font(.system(size: 20, weight: .bold))
                    Text(kindLabel(item.kind))
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button("关闭") { dismiss() }
                    .buttonStyle(.bordered)
            }
            ZStack {
                RoundedRectangle(cornerRadius: 16)
                    .fill(Color(red: 0.94, green: 0.97, blue: 1.0))
                if let image {
                    Image(nsImage: image)
                        .resizable()
                        .scaledToFit()
                        .padding(12)
                } else {
                    VStack(spacing: 10) {
                        Image(systemName: item.kind == "video" ? "play.rectangle.fill" : "doc.fill")
                            .font(.system(size: 44))
                            .foregroundStyle(.secondary)
                        Text("这个项目没有可内嵌预览，仍可用系统默认应用打开。")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .frame(minWidth: 560, minHeight: 360)
            HStack {
                if let path = historyItemOpenPath(item), FileManager.default.fileExists(atPath: path) {
                    Button("系统打开") {
                        NSWorkspace.shared.open(URL(fileURLWithPath: path))
                    }
                    .buttonStyle(.borderedProminent)
                    Text((path as NSString).lastPathComponent)
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                } else {
                    Text("没有保留可打开的本地路径。")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }
        }
        .padding(22)
    }
}

private struct ExtraItemsTile: View {
    let count: Int

    var body: some View {
        Text("+\(count)")
            .font(.system(size: 16, weight: .black))
            .foregroundStyle(.secondary)
            .frame(width: 78, height: 96)
            .background(Color(red: 0.9, green: 0.94, blue: 1.0), in: RoundedRectangle(cornerRadius: 14))
    }
}

private enum HistoryEndpoint {
    case sender
    case receiver
}

private struct HistoryEndpointFilter: Equatable {
    var key: String
    var label: String
}

private func buildHistoryEndpointFilters(entries: [HistoryEntry], endpoint: HistoryEndpoint) -> [HistoryEndpointFilter] {
    var counts: [String: (label: String, count: Int)] = [:]
    for entry in entries {
        let participant = endpoint == .sender ? entry.sender : entry.receiver
        guard let participant else { continue }
        let label = participant.displayName.isEmpty ? participant.deviceId : participant.displayName
        let key = historyParticipantKey(id: participant.deviceId, fallback: label)
        let current = counts[key]
        counts[key] = (label: label, count: (current?.count ?? 0) + 1)
    }
    let allLabel = switch endpoint {
    case .sender: "全部发送端"
    case .receiver: "全部接收端"
    }
    return [HistoryEndpointFilter(key: "all", label: "\(allLabel) (\(entries.count))")] +
        counts
            .sorted { left, right in
                if left.value.count == right.value.count {
                    return left.value.label.localizedStandardCompare(right.value.label) == .orderedAscending
                }
                return left.value.count > right.value.count
            }
            .prefix(16)
            .map { key, value in
                HistoryEndpointFilter(key: key, label: "\(value.label) (\(value.count))")
            }
}

private extension HistoryEntry {
    func matchesSenderFilter(_ key: String) -> Bool {
        let senderLabel = sender?.displayName.isEmpty == false ? sender?.displayName : sender?.deviceId
        return historyParticipantKey(id: sender?.deviceId, fallback: senderLabel ?? "") == key
    }

    func matchesReceiverFilter(_ key: String) -> Bool {
        let receiverLabel = receiver?.displayName.isEmpty == false ? receiver?.displayName : receiver?.deviceId
        return historyParticipantKey(id: receiver?.deviceId, fallback: receiverLabel ?? "") == key
    }
}

private func historyParticipantKey(id: String?, fallback: String) -> String {
    (id?.isEmpty == false ? id! : fallback)
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .lowercased()
}

private func matchesHistoryDateFilter(
    _ date: Date,
    filter: String,
    now: Date,
    startDate: String,
    endDate: String
) -> Bool {
    let calendar = Calendar.current
    let todayStart = calendar.startOfDay(for: now)
    switch filter {
    case "today":
        return calendar.isDateInToday(date)
    case "7d":
        return date >= (calendar.date(byAdding: .day, value: -6, to: todayStart) ?? todayStart)
    case "30d":
        return date >= (calendar.date(byAdding: .day, value: -29, to: todayStart) ?? todayStart)
    case "custom":
        let entryKey = historyDateKey(date)
        let start = normalizedHistoryDateInput(startDate)
        let end = normalizedHistoryDateInput(endDate)
        if !startDate.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && start == nil { return false }
        if !endDate.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && end == nil { return false }
        if let start, entryKey < start { return false }
        if let end, entryKey > end { return false }
        return true
    default:
        return true
    }
}

private func matchesHistoryHourFilter(
    _ date: Date,
    filter: String,
    startTime: String,
    endTime: String
) -> Bool {
    let minute = Calendar.current.component(.hour, from: date) * 60 + Calendar.current.component(.minute, from: date)
    let range: ClosedRange<Int>
    switch filter {
    case "morning":
        range = 6 * 60...(11 * 60 + 59)
    case "afternoon":
        range = 12 * 60...(17 * 60 + 59)
    case "evening":
        range = 18 * 60...(23 * 60 + 59)
    case "night":
        range = 0...(5 * 60 + 59)
    case "custom":
        let trimmedStart = startTime.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedEnd = endTime.trimmingCharacters(in: .whitespacesAndNewlines)
        let start = trimmedStart.isEmpty ? 0 : parseHistoryClockMinutes(trimmedStart)
        let end = trimmedEnd.isEmpty ? (23 * 60 + 59) : parseHistoryClockMinutes(trimmedEnd)
        guard let start, let end, start <= end else { return false }
        range = start...end
    default:
        return true
    }
    return range.contains(minute)
}

private func historyDateKey(_ date: Date) -> String {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "zh_CN")
    formatter.calendar = Calendar(identifier: .gregorian)
    formatter.dateFormat = "yyyy-MM-dd"
    return formatter.string(from: date)
}

private func normalizedHistoryDateInput(_ value: String) -> String? {
    let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { return nil }
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "zh_CN")
    formatter.calendar = Calendar(identifier: .gregorian)
    formatter.dateFormat = "yyyy-MM-dd"
    formatter.isLenient = false
    guard let date = formatter.date(from: trimmed), formatter.string(from: date) == trimmed else {
        return nil
    }
    return trimmed
}

private func parseHistoryClockMinutes(_ value: String) -> Int? {
    let parts = value.split(separator: ":")
    guard parts.count == 2,
          parts[0].count == 2,
          parts[1].count == 2,
          let hour = Int(parts[0]),
          let minute = Int(parts[1]),
          (0...23).contains(hour),
          (0...59).contains(minute) else {
        return nil
    }
    return hour * 60 + minute
}

private func kindLabel(_ kind: String) -> String {
    switch kind {
    case "text": return "文本"
    case "image": return "图片"
    case "video": return "视频"
    case "media": return "媒体"
    case "file": return "文件"
    default: return kind
    }
}

private func statusLabel(_ status: String) -> String {
    switch status {
    case "success": return "成功"
    case "failed": return "失败"
    case "partial": return "部分完成"
    case "pending": return "进行中"
    default: return status.isEmpty ? "未知" : status
    }
}

private func saveTargetLabel(_ target: String) -> String {
    switch target {
    case "type": return "输入"
    case "type_enter": return "输入并回车"
    case "clipboard": return "剪贴板"
    case "inbox", "download": return "收件箱"
    case "gallery-image": return "相册"
    case "gallery-video": return "视频库"
    default: return target
    }
}

private func itemShortLabel(_ item: HistoryItem) -> String {
    if let fileName = item.fileName {
        let ext = (fileName as NSString).pathExtension.uppercased()
        if !ext.isEmpty && ext.count <= 5 {
            return ext
        }
    }
    switch item.kind {
    case "image": return "IMG"
    case "video": return "VID"
    case "media": return "MEDIA"
    case "file": return "FILE"
    default: return kindLabel(item.kind)
    }
}

private func itemKindColor(_ kind: String) -> Color {
    switch kind {
    case "image": return Color(red: 0.09, green: 0.55, blue: 0.97)
    case "video": return Color(red: 0.55, green: 0.36, blue: 0.96)
    case "media": return Color(red: 0.06, green: 0.47, blue: 0.43)
    case "file": return Color(red: 0.28, green: 0.32, blue: 0.40)
    default: return Color.secondary
    }
}

private func statusBorderColor(_ status: String?) -> Color {
    switch status {
    case "success": return Color.green.opacity(0.28)
    case "failed": return Color.red.opacity(0.32)
    case "partial": return Color.orange.opacity(0.32)
    default: return Color.black.opacity(0.06)
    }
}

private func historyItemOpenPath(_ item: HistoryItem) -> String? {
    [item.localPath, item.savedPath]
        .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
        .first { !$0.isEmpty }
}

private func decodeDataURLImage(_ value: String) -> NSImage? {
    let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
    let base64: String
    if let range = trimmed.range(of: "base64,") {
        base64 = String(trimmed[range.upperBound...])
    } else {
        base64 = trimmed
    }
    guard let data = Data(base64Encoded: base64) else { return nil }
    return NSImage(data: data)
}

private struct Card<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        content
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .topLeading)
            .background(.white.opacity(0.92), in: RoundedRectangle(cornerRadius: 20))
            .overlay(RoundedRectangle(cornerRadius: 20).stroke(Color.black.opacity(0.08)))
    }
}

private struct InfoRow: View {
    let label: String
    let value: String?

    var body: some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(value ?? "加载中")
                .font(.system(size: 14, weight: .bold, design: .monospaced))
        }
    }
}

private struct StatusPill: View {
    let text: String
    let isOnline: Bool

    var body: some View {
        Text(text)
            .font(.system(size: 13, weight: .bold))
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(isOnline ? Color.green.opacity(0.13) : Color.red.opacity(0.12), in: Capsule())
            .foregroundStyle(isOnline ? .green : .red)
    }
}

private struct CountBadge: View {
    let count: Int

    var body: some View {
        Text("\(count)")
            .font(.system(size: 13, weight: .black))
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(Color(red: 0.9, green: 0.94, blue: 1.0), in: Capsule())
    }
}

private struct EmptyHint: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.system(size: 14, weight: .medium))
            .foregroundStyle(.secondary)
            .padding(14)
            .background(Color(red: 0.96, green: 0.98, blue: 1.0), in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct Tag: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.system(size: 11, weight: .bold))
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .background(Color(red: 0.9, green: 0.94, blue: 1.0), in: Capsule())
            .foregroundStyle(.secondary)
    }
}
