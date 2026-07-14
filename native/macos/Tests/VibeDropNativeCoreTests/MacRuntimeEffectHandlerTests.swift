import Foundation
import XCTest
import VibeDropMacRuntime
import VibeDropMacServer
import VibeDropMacStorage
import VibeDropNativeCore

final class MacRuntimeEffectHandlerTests: XCTestCase {
    func testTypeEnterUsesInputControllerAndRecordsHistory() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("vibedrop-runtime-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let database = try MacHistoryDatabase(url: directory.appendingPathComponent("history.sqlite"))
        let input = RecordingInputController()
        let configuration = MacServerConfiguration(
            serverId: "desktop_runtime_test",
            pin: "1234",
            hostname: "runtime-test.local",
            ip: "127.0.0.1",
            port: 9001
        )
        let runtime = MacRuntimeEffectHandler(
            configuration: configuration,
            historyDatabase: database,
            inputController: input,
            idGenerator: { "fixed-id" },
            now: { Date(timeIntervalSince1970: 1_774_800_000) }
        )
        let peer = ConnectedPeer(
            sessionId: 1,
            deviceId: "client_runtime_test",
            baseDeviceId: "client_runtime_test",
            deviceName: "一加 Ace 5",
            canReceiveFiles: true,
            receivesClipboard: false,
            deviceRole: "primary"
        )

        let outbound = runtime.handle(.typeTextAndEnter("测试文本", peer: peer))

        XCTAssertEqual(input.events, [.type("测试文本"), .enter])
        XCTAssertEqual(outbound, [.status(MacServerStatusEnvelope(status: "ok"))])
        let recent = try database.fetchRecent(limit: 1)
        XCTAssertEqual(recent.first?.id, "native-mac:fixed-id")
        XCTAssertEqual(recent.first?.text, "测试文本")
        XCTAssertEqual(recent.first?.status, "success")
        XCTAssertEqual(recent.first?.saveTarget, "type_enter")
        XCTAssertEqual(recent.first?.sender?.displayName, "一加 Ace 5")
        XCTAssertEqual(recent.first?.receiver?.deviceId, "desktop_runtime_test")
    }

    func testInputFailureReturnsErrorAndRecordsFailedHistory() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("vibedrop-runtime-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let database = try MacHistoryDatabase(url: directory.appendingPathComponent("history.sqlite"))
        let input = RecordingInputController(error: MacInputError.accessibilityPermissionMissing)
        let runtime = MacRuntimeEffectHandler(
            configuration: MacServerConfiguration(
                serverId: "desktop_runtime_test",
                pin: "1234",
                hostname: "runtime-test.local",
                ip: "127.0.0.1"
            ),
            historyDatabase: database,
            inputController: input,
            idGenerator: { "failed-id" },
            now: { Date(timeIntervalSince1970: 1_774_800_000) }
        )
        let peer = ConnectedPeer(
            sessionId: 1,
            deviceId: "client_runtime_test",
            baseDeviceId: "client_runtime_test",
            deviceName: "一加 Ace 5",
            canReceiveFiles: true,
            receivesClipboard: false,
            deviceRole: "primary"
        )

        let outbound = runtime.handle(.typeText("测试文本", peer: peer))

        guard case let .status(envelope) = outbound.first else {
            return XCTFail("missing status")
        }
        XCTAssertEqual(envelope.status, "error")
        XCTAssertEqual(envelope.error, "缺少 macOS 辅助功能权限，无法模拟键盘输入")
        XCTAssertEqual(try database.fetchRecent(limit: 1).first?.status, "failed")
    }

    func testImageClipboardSavesImageWritesClipboardAndRecordsHistory() throws {
        let fixture = try RuntimeFixture()
        defer { fixture.cleanup() }
        let clipboard = RecordingImageClipboard()
        let runtime = MacRuntimeEffectHandler(
            configuration: fixture.configuration,
            historyDatabase: fixture.database,
            inputController: RecordingInputController(),
            imageClipboard: clipboard,
            contentStore: fixture.contentStore,
            idGenerator: { "image-id" },
            now: { Date(timeIntervalSince1970: 1_774_800_000) }
        )

        let outbound = runtime.handle(
            .imageClipboard(
                VibeDropMessage(
                    action: .imageClipboard,
                    fileName: "demo.png",
                    mimeType: "image/png",
                    imageBase64: RuntimeFixture.onePixelPNGBase64
                ),
                peer: fixture.peer
            )
        )

        XCTAssertEqual(outbound, [.status(MacServerStatusEnvelope(status: "ok"))])
        XCTAssertEqual(clipboard.images.count, 1)
        let recent = try fixture.database.fetchRecent(limit: 1)
        XCTAssertEqual(recent.first?.kind, "image")
        XCTAssertEqual(recent.first?.text, "[图片] demo.png")
        XCTAssertEqual(recent.first?.items.first?.fileName, "demo.png")
        XCTAssertTrue(FileManager.default.fileExists(atPath: recent.first?.items.first?.savedPath ?? ""))
    }

    func testIncomingFileChunksSaveToInboxAndReturnSavedAck() throws {
        let fixture = try RuntimeFixture()
        defer { fixture.cleanup() }
        let runtime = MacRuntimeEffectHandler(
            configuration: fixture.configuration,
            historyDatabase: fixture.database,
            inputController: RecordingInputController(),
            imageClipboard: RecordingImageClipboard(),
            contentStore: fixture.contentStore,
            idGenerator: { "file-id" },
            now: { Date(timeIntervalSince1970: 1_774_800_000) }
        )
        let bytes = Data("hello native mac".utf8)
        let base64 = bytes.base64EncodedString()

        XCTAssertEqual(
            runtime.handle(
                .incomingFileStart(
                    VibeDropMessage(
                        action: .incomingFileStart,
                        fileName: "note.txt",
                        mimeType: "text/plain",
                        transferId: "transfer-1",
                        sizeBytes: Int64(bytes.count)
                    ),
                    peer: fixture.peer
                )
            ),
            [.status(MacServerStatusEnvelope(status: "ok"))]
        )
        XCTAssertEqual(
            runtime.handle(
                .incomingFileChunk(
                    VibeDropMessage(
                        action: .incomingFileChunk,
                        transferId: "transfer-1",
                        chunkBase64: base64
                    ),
                    peer: fixture.peer
                )
            ),
            []
        )
        let complete = runtime.handle(
            .incomingFileComplete(
                VibeDropMessage(
                    action: .incomingFileComplete,
                    transferId: "transfer-1"
                ),
                peer: fixture.peer
            )
        )

        guard case let .rawJSON(payload) = complete.first else {
            return XCTFail("missing saved ack")
        }
        XCTAssertEqual(payload["action"], "incoming_file_saved")
        XCTAssertEqual(payload["transfer_id"], "transfer-1")
        let savedPath = try XCTUnwrap(payload["saved_path"])
        XCTAssertEqual(try Data(contentsOf: URL(fileURLWithPath: savedPath)), bytes)
        let recent = try fixture.database.fetchRecent(limit: 1)
        XCTAssertEqual(recent.first?.kind, "file")
        XCTAssertEqual(recent.first?.text, "[文件] note.txt")
        XCTAssertEqual(recent.first?.items.first?.sizeBytes, Int64(bytes.count))
    }

    func testIncomingFileErrorCancelsPartialIncomingFile() throws {
        let fixture = try RuntimeFixture()
        defer { fixture.cleanup() }
        let runtime = MacRuntimeEffectHandler(
            configuration: fixture.configuration,
            historyDatabase: fixture.database,
            inputController: RecordingInputController(),
            imageClipboard: RecordingImageClipboard(),
            contentStore: fixture.contentStore
        )
        let transferId = "transfer-cancel"
        let bytes = Data("partial data".utf8)

        XCTAssertEqual(
            runtime.handle(
                .incomingFileStart(
                    VibeDropMessage(
                        action: .incomingFileStart,
                        fileName: "cancel.txt",
                        mimeType: "text/plain",
                        transferId: transferId,
                        sizeBytes: Int64(bytes.count)
                    ),
                    peer: fixture.peer
                )
            ),
            [.status(MacServerStatusEnvelope(status: "ok"))]
        )
        XCTAssertEqual(
            runtime.handle(
                .incomingFileChunk(
                    VibeDropMessage(
                        action: .incomingFileChunk,
                        transferId: transferId,
                        chunkBase64: bytes.base64EncodedString()
                    ),
                    peer: fixture.peer
                )
            ),
            []
        )
        let partURL = fixture.directory
            .appendingPathComponent("incoming", isDirectory: true)
            .appendingPathComponent("\(transferId).part")
        XCTAssertTrue(FileManager.default.fileExists(atPath: partURL.path))

        let outbound = runtime.handle(
            .incomingFileError(
                transferId: transferId,
                error: "Android 发送文件失败",
                peer: fixture.peer
            )
        )

        XCTAssertEqual(outbound, [])
        XCTAssertFalse(FileManager.default.fileExists(atPath: partURL.path))
        let recent = try fixture.database.fetchRecent(limit: 1)
        XCTAssertEqual(recent.first?.status, "failed")
        XCTAssertEqual(recent.first?.text, "[文件] cancel.txt")
        XCTAssertEqual(recent.first?.sender?.displayName, "一加 Ace 5")
    }

    func testIncomingBatchFilesAreAggregatedIntoOneHistoryEntry() throws {
        let fixture = try RuntimeFixture()
        defer { fixture.cleanup() }
        let runtime = MacRuntimeEffectHandler(
            configuration: fixture.configuration,
            historyDatabase: fixture.database,
            inputController: RecordingInputController(),
            imageClipboard: RecordingImageClipboard(),
            contentStore: fixture.contentStore,
            idGenerator: { "unused-file-id" },
            now: { Date(timeIntervalSince1970: 1_774_800_000) }
        )

        let firstBytes = Data("first batch item".utf8)
        let secondBytes = Data("second batch item".utf8)
        let files: [(transferId: String, fileName: String, bytes: Data)] = [
            ("transfer-a", "a.txt", firstBytes),
            ("transfer-b", "b.txt", secondBytes)
        ]

        for (index, file) in files.enumerated() {
            XCTAssertEqual(
                runtime.handle(
                    .incomingFileStart(
                        VibeDropMessage(
                            action: .incomingFileStart,
                            fileName: file.fileName,
                            mimeType: "text/plain",
                            transferId: file.transferId,
                            sizeBytes: Int64(file.bytes.count),
                            saveTarget: "desktop_inbox",
                            historySessionId: "android-batch-1",
                            historyItemIndex: index,
                            historyItemCount: files.count
                        ),
                        peer: fixture.peer
                    )
                ),
                [.status(MacServerStatusEnvelope(status: "ok"))]
            )
            XCTAssertEqual(
                runtime.handle(
                    .incomingFileChunk(
                        VibeDropMessage(
                            action: .incomingFileChunk,
                            transferId: file.transferId,
                            chunkBase64: file.bytes.base64EncodedString()
                        ),
                        peer: fixture.peer
                    )
                ),
                []
            )
            let complete = runtime.handle(
                .incomingFileComplete(
                    VibeDropMessage(
                        action: .incomingFileComplete,
                        transferId: file.transferId
                    ),
                    peer: fixture.peer
                )
            )
            guard case let .rawJSON(payload) = complete.first else {
                return XCTFail("missing saved ack")
            }
            XCTAssertEqual(payload["action"], "incoming_file_saved")
            XCTAssertEqual(payload["transfer_id"], file.transferId)
        }

        let recent = try fixture.database.fetchRecent(limit: 10)
        XCTAssertEqual(recent.count, 1)
        let entry = try XCTUnwrap(recent.first)
        XCTAssertEqual(entry.id, "native-mac-inbound:android-batch-1")
        XCTAssertEqual(entry.direction, "mobile_to_desktop")
        XCTAssertEqual(entry.kind, "file")
        XCTAssertEqual(entry.status, "success")
        XCTAssertEqual(entry.text, "[文件] 2 项")
        XCTAssertEqual(entry.sessionId, "android-batch-1")
        XCTAssertEqual(entry.itemCount, 2)
        XCTAssertEqual(entry.saveTarget, "desktop_inbox")
        XCTAssertEqual(entry.items.map(\.fileName), ["a.txt", "b.txt"])
        XCTAssertEqual(entry.items.map(\.status), ["success", "success"])
        XCTAssertEqual(try Data(contentsOf: URL(fileURLWithPath: entry.items[0].savedPath ?? "")), firstBytes)
        XCTAssertEqual(try Data(contentsOf: URL(fileURLWithPath: entry.items[1].savedPath ?? "")), secondBytes)
    }

    func testOutboundFileTransferSendsChunksWaitsForAckAndRecordsHistory() throws {
        let fixture = try RuntimeFixture()
        defer { fixture.cleanup() }
        let fileURL = fixture.directory.appendingPathComponent("outbound.txt")
        let bytes = Data("hello android inbox".utf8)
        try bytes.write(to: fileURL)
        let registry = MacOutboundTransferRegistry()
        let sender = RecordingMacServerSender { payload in
            guard payload["action"] as? String == "incoming_file_complete",
                  let transferId = payload["transfer_id"] as? String else {
                return
            }
            registry.resolveSaved(transferId: transferId, savedPath: "content://vibedrop/outbound.txt")
        }
        let ids = RecordingIDGenerator()
        let progress = RecordingTransferProgress()
        let service = MacOutboundFileTransferService(
            sender: sender,
            transferRegistry: registry,
            historyDatabase: fixture.database,
            configuration: fixture.configuration,
            chunkSize: 8,
            ackTimeoutSeconds: 1,
            idGenerator: { ids.next() },
            now: { Date(timeIntervalSince1970: 1_774_800_000) }
        )

        let report = try service.sendFile(
            at: fileURL,
            to: fixture.peer,
            progressHandler: { progress.append($0) }
        )

        XCTAssertEqual(report.status, "success")
        XCTAssertEqual(report.transferId, "native-mac-id-1")
        XCTAssertEqual(report.chunksSent, 3)
        XCTAssertEqual(sender.payloads.map { $0["action"] as? String }, [
            "incoming_history_session_start",
            "incoming_file_start",
            "incoming_file_chunk",
            "incoming_file_chunk",
            "incoming_file_chunk",
            "incoming_file_complete"
        ])
        XCTAssertEqual(sender.payloads.first?["text"] as? String, "[文件] outbound.txt")
        XCTAssertEqual(sender.payloads.dropFirst().first?["size_bytes"] as? Int, bytes.count)
        XCTAssertEqual(sender.payloads.dropFirst().first?["history_session_id"] as? String, "native-mac-id-1")
        let joinedChunks = try sender.payloads
            .filter { $0["action"] as? String == "incoming_file_chunk" }
            .map { try XCTUnwrap($0["chunk_base64"] as? String) }
            .reduce(Data()) { partial, value in
                var data = partial
                data.append(Data(base64Encoded: value) ?? Data())
                return data
            }
        XCTAssertEqual(joinedChunks, bytes)
        let recent = try fixture.database.fetchRecent(limit: 1)
        XCTAssertEqual(recent.first?.direction, "desktop_to_mobile")
        XCTAssertEqual(recent.first?.status, "success")
        XCTAssertEqual(recent.first?.saveTarget, "download")
        XCTAssertEqual(recent.first?.items.first?.savedPath, "content://vibedrop/outbound.txt")
        let progressEvents = progress.snapshot()
        XCTAssertEqual(progressEvents.first?.stage, .preparing)
        XCTAssertEqual(progressEvents.filter { $0.stage == .sending }.map(\.sentBytes), [
            8,
            16,
            Int64(bytes.count)
        ])
        XCTAssertEqual(progressEvents.first(where: { $0.stage == .waitingForReceiver })?.sentBytes, Int64(bytes.count))
        XCTAssertEqual(progressEvents.last?.stage, .success)
        XCTAssertEqual(progressEvents.last?.fraction, 1)
    }

    func testOutboundDirectoryAndMultipleFilesAreArchivedBeforeTransfer() throws {
        let fixture = try RuntimeFixture()
        defer { fixture.cleanup() }
        let first = fixture.directory.appendingPathComponent("alpha.txt")
        try Data("alpha".utf8).write(to: first)
        let folder = fixture.directory.appendingPathComponent("folder", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try Data("nested".utf8).write(to: folder.appendingPathComponent("nested.txt"))
        let registry = MacOutboundTransferRegistry()
        let sender = RecordingMacServerSender { payload in
            guard payload["action"] as? String == "incoming_file_complete",
                  let transferId = payload["transfer_id"] as? String else {
                return
            }
            registry.resolveSaved(transferId: transferId, savedPath: "content://vibedrop/archive.zip")
        }
        let ids = RecordingIDGenerator()
        let service = MacOutboundFileTransferService(
            sender: sender,
            transferRegistry: registry,
            historyDatabase: fixture.database,
            configuration: fixture.configuration,
            chunkSize: 1024,
            ackTimeoutSeconds: 1,
            idGenerator: { ids.next() },
            now: { Date(timeIntervalSince1970: 1_774_800_000) }
        )

        let reports = try service.sendURLs([first, folder], to: fixture.peer)

        XCTAssertEqual(reports.count, 1)
        XCTAssertEqual(reports.first?.status, "success")
        XCTAssertEqual(sender.payloads.first?["action"] as? String, "incoming_history_session_start")
        XCTAssertEqual(sender.payloads.first?["text"] as? String, "[文件] 2 项")
        let start = try XCTUnwrap(sender.payloads.first(where: { $0["action"] as? String == "incoming_file_start" }))
        XCTAssertEqual(start["mime_type"] as? String, "application/zip")
        XCTAssertEqual(start["is_archive"] as? Bool, true)
        XCTAssertEqual(start["history_item_count"] as? Int, 2)
        XCTAssertEqual((start["file_name"] as? String)?.hasSuffix(".zip"), true)
        let archivedBytes = sender.payloads
            .filter { $0["action"] as? String == "incoming_file_chunk" }
            .compactMap { $0["chunk_base64"] as? String }
            .reduce(Data()) { partial, value in
                var data = partial
                data.append(Data(base64Encoded: value) ?? Data())
                return data
            }
        XCTAssertTrue(archivedBytes.starts(with: Data([0x50, 0x4B])))
        let recent = try fixture.database.fetchRecent(limit: 1)
        XCTAssertEqual(recent.first?.direction, "desktop_to_mobile")
        XCTAssertEqual(recent.first?.text, "[文件] 2 项")
        XCTAssertEqual(recent.first?.itemCount, 2)
        XCTAssertEqual(recent.first?.items.first?.mimeType, "application/zip")
    }
}

private final class RecordingInputController: MacInputControlling, @unchecked Sendable {
    enum Event: Equatable {
        case type(String)
        case enter
    }

    var events: [Event] = []
    let error: Error?

    init(error: Error? = nil) {
        self.error = error
    }

    var isAccessibilityTrusted: Bool {
        error == nil
    }

    func typeText(_ text: String) throws {
        if let error { throw error }
        events.append(.type(text))
    }

    func pressEnter() throws {
        if let error { throw error }
        events.append(.enter)
    }
}

private final class RecordingImageClipboard: MacImageClipboardControlling, @unchecked Sendable {
    var images: [Data] = []

    func writeImage(data: Data, mimeType: String?) throws {
        images.append(data)
    }
}

private final class RecordingMacServerSender: MacServerMessageSending, @unchecked Sendable {
    var payloads: [[String: Any]] = []
    private let onPayload: ([String: Any]) -> Void

    init(onPayload: @escaping ([String: Any]) -> Void = { _ in }) {
        self.onPayload = onPayload
    }

    func sendJSONData(_ data: Data, to sessionId: UInt64) throws {
        let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
        payloads.append(object)
        onPayload(object)
    }
}

private final class RecordingIDGenerator: @unchecked Sendable {
    private let lock = NSLock()
    private var value = 0

    func next() -> String {
        lock.lock()
        defer { lock.unlock() }
        value += 1
        return "id-\(value)"
    }
}

private final class RecordingTransferProgress: @unchecked Sendable {
    private let lock = NSLock()
    private var events: [MacOutboundFileTransferProgress] = []

    func append(_ event: MacOutboundFileTransferProgress) {
        lock.lock()
        defer { lock.unlock() }
        events.append(event)
    }

    func snapshot() -> [MacOutboundFileTransferProgress] {
        lock.lock()
        defer { lock.unlock() }
        return events
    }
}

private struct RuntimeFixture {
    static let onePixelPNGBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="

    let directory: URL
    let database: MacHistoryDatabase
    let contentStore: MacReceivedContentStore
    let configuration: MacServerConfiguration
    let peer: ConnectedPeer

    init() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("vibedrop-runtime-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        database = try MacHistoryDatabase(url: directory.appendingPathComponent("history.sqlite"))
        contentStore = try MacReceivedContentStore(
            receivedImagesDirectory: directory.appendingPathComponent("received-images", isDirectory: true),
            desktopInboxDirectory: directory.appendingPathComponent("inbox", isDirectory: true),
            incomingTransfersDirectory: directory.appendingPathComponent("incoming", isDirectory: true)
        )
        configuration = MacServerConfiguration(
            serverId: "desktop_runtime_test",
            pin: "1234",
            hostname: "runtime-test.local",
            ip: "127.0.0.1",
            port: 9001
        )
        peer = ConnectedPeer(
            sessionId: 1,
            deviceId: "client_runtime_test",
            baseDeviceId: "client_runtime_test",
            deviceName: "一加 Ace 5",
            canReceiveFiles: true,
            receivesClipboard: false,
            deviceRole: "primary"
        )
    }

    func cleanup() {
        try? FileManager.default.removeItem(at: directory)
    }
}
