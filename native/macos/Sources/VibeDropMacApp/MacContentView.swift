import AppKit
import SwiftUI
import UniformTypeIdentifiers
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
            RoundedRectangle(cornerRadius: 12)
                .fill(Color(red: 0.31, green: 0.25, blue: 0.43))
                .frame(width: 42, height: 42)
                .overlay(Image(systemName: "drop.fill").font(.system(size: 21)).foregroundColor(.white))
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
                        Text(model.selectedPeer.map { "目标：\($0.deviceName)" } ?? "当前没有已连接手机")
                            .font(.system(size: 13))
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
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
                            Text("首版原生壳已支持普通文件分片发送和手机保存回执；文件夹打包会继续补齐。")
                                .font(.system(size: 13))
                                .foregroundStyle(.secondary)
                        }
                    )
                    .onDrop(of: [UTType.fileURL.identifier], isTargeted: $isTargeted) { providers in
                        loadFileURLs(from: providers) { urls in
                            model.sendFiles(urls)
                        }
                        return true
                    }
                ForEach(model.transferItems) { item in
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
                    .padding(12)
                    .background(Color.white.opacity(0.7), in: RoundedRectangle(cornerRadius: 12))
                }
            }
        }
    }

    private func loadFileURLs(from providers: [NSItemProvider], completion: @escaping ([URL]) -> Void) {
        var urls: [URL] = []
        let group = DispatchGroup()
        for provider in providers {
            group.enter()
            provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, _ in
                defer { group.leave() }
                if let data = item as? Data,
                   let url = URL(dataRepresentation: data, relativeTo: nil) {
                    urls.append(url)
                } else if let url = item as? URL {
                    urls.append(url)
                }
            }
        }
        group.notify(queue: .main) {
            completion(urls)
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

private struct HistoryView: View {
    @EnvironmentObject private var model: MacNativeAppModel
    @State private var query = ""
    @State private var timeFilter = "all"
    @State private var kindFilter = "all"
    @State private var statusFilter = "all"
    @State private var participantFilter = "all"

    private var participantFilters: [HistoryParticipantFilter] {
        buildHistoryParticipantFilters(entries: model.recentHistory)
    }

    private var filteredEntries: [HistoryEntry] {
        let now = Date()
        return model.recentHistory.filter { entry in
            switch timeFilter {
            case "today":
                guard Calendar.current.isDateInToday(entry.timestamp) else { return false }
            case "7d":
                guard entry.timestamp >= Calendar.current.date(byAdding: .day, value: -7, to: now) ?? now else { return false }
            case "30d":
                guard entry.timestamp >= Calendar.current.date(byAdding: .day, value: -30, to: now) ?? now else { return false }
            default:
                break
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
            if participantFilter != "all" && !entry.matchesParticipantFilter(participantFilter) {
                return false
            }
            let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { return true }
            let haystack = [
                entry.text,
                entry.kind,
                entry.status,
                entry.sender?.displayName,
                entry.receiver?.displayName,
                entry.items.map {
                    [
                        $0.fileName,
                        $0.mimeType,
                        $0.status,
                        $0.localPath,
                        $0.savedPath,
                        kindLabel($0.kind)
                    ].compactMap { $0 }.joined(separator: " ")
                }.joined(separator: " ")
            ].compactMap { $0 }.joined(separator: " ").lowercased()
            return haystack.contains(trimmed.lowercased())
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Text("传输历史").font(.system(size: 24, weight: .bold))
                    Spacer()
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
                    Picker("", selection: $participantFilter) {
                        ForEach(participantFilters, id: \.key) { filter in
                            Text(filter.label).tag(filter.key)
                        }
                    }
                    .pickerStyle(.menu)
                    .frame(width: 220)
                    Picker("", selection: $timeFilter) {
                        Text("全部时间").tag("all")
                        Text("今天").tag("today")
                        Text("近7天").tag("7d")
                        Text("近30天").tag("30d")
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 360)
                }
                MacHistoryHeatmap(entries: filteredEntries)
                if filteredEntries.isEmpty {
                    EmptyHint("等待接收或发送内容。")
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    ForEach(filteredEntries, id: \.id) { entry in
                        HistoryRow(entry: entry)
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
        }
    }
}

private struct MacHistoryHeatmap: View {
    let entries: [HistoryEntry]

    private var days: [Date] {
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: Date())
        return (0..<7).compactMap { offset in
            calendar.date(byAdding: .day, value: offset - 6, to: today)
        }
    }

    private var counts: [String: Int] {
        let calendar = Calendar.current
        return entries.reduce(into: [String: Int]()) { result, entry in
            let day = calendar.startOfDay(for: entry.timestamp)
            let hour = calendar.component(.hour, from: entry.timestamp)
            result[key(day: day, hour: hour), default: 0] += 1
        }
    }

    private var maxCount: Int {
        max(1, counts.values.max() ?? 1)
    }

    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("接收热力图").font(.system(size: 18, weight: .bold))
                    Spacer()
                    Text("当前筛选 \(entries.count) 条")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(.secondary)
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
                                    RoundedRectangle(cornerRadius: 3)
                                        .fill(color(count: counts[key(day: day, hour: hour), default: 0]))
                                        .frame(width: 46, height: 7)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private func key(day: Date, hour: Int) -> String {
        "\(Int(day.timeIntervalSince1970)):\(hour)"
    }

    private func color(count: Int) -> Color {
        guard count > 0 else { return Color(red: 0.92, green: 0.95, blue: 0.97) }
        let ratio = min(1.0, Double(count) / Double(maxCount))
        if ratio < 0.45 {
            return Color(red: 0.86 - 0.18 * ratio, green: 0.98, blue: 0.9 - 0.2 * ratio)
        }
        if ratio < 0.82 {
            return Color(red: 0.42 - 0.28 * ratio, green: 0.86 - 0.34 * ratio, blue: 0.5 - 0.28 * ratio)
        }
        let dark = 0.14 * (1.0 - ratio)
        return Color(red: dark, green: 0.18 + dark, blue: dark)
    }
}

private struct HistoryRow: View {
    let entry: HistoryEntry

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
                            MacHistoryItemPreview(item: item)
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

private struct HistoryParticipantFilter: Equatable {
    var key: String
    var label: String
}

private func buildHistoryParticipantFilters(entries: [HistoryEntry]) -> [HistoryParticipantFilter] {
    var counts: [String: (label: String, count: Int)] = [:]
    for entry in entries {
        for participant in [entry.sender, entry.receiver] {
            guard let participant else { continue }
            let label = participant.displayName.isEmpty ? participant.deviceId : participant.displayName
            let key = historyParticipantKey(id: participant.deviceId, fallback: label)
            let current = counts[key]
            counts[key] = (label: label, count: (current?.count ?? 0) + 1)
        }
    }
    return [HistoryParticipantFilter(key: "all", label: "全部设备")] +
        counts
            .sorted { left, right in
                if left.value.count == right.value.count {
                    return left.value.label.localizedStandardCompare(right.value.label) == .orderedAscending
                }
                return left.value.count > right.value.count
            }
            .prefix(16)
            .map { key, value in
                HistoryParticipantFilter(key: key, label: "\(value.label) (\(value.count))")
            }
}

private extension HistoryEntry {
    func matchesParticipantFilter(_ key: String) -> Bool {
        let senderLabel = sender?.displayName.isEmpty == false ? sender?.displayName : sender?.deviceId
        let receiverLabel = receiver?.displayName.isEmpty == false ? receiver?.displayName : receiver?.deviceId
        return historyParticipantKey(id: sender?.deviceId, fallback: senderLabel ?? "") == key ||
            historyParticipantKey(id: receiver?.deviceId, fallback: receiverLabel ?? "") == key
    }
}

private func historyParticipantKey(id: String?, fallback: String) -> String {
    (id?.isEmpty == false ? id! : fallback)
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .lowercased()
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
