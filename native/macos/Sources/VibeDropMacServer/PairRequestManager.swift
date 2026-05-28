import Foundation

public struct PairRequestInfo: Codable, Equatable, Sendable {
    public var requestId: String
    public var clientId: String
    public var clientName: String
    public var code: String
    public var requestedAt: Date

    enum CodingKeys: String, CodingKey {
        case requestId = "request_id"
        case clientId = "client_id"
        case clientName = "client_name"
        case code
        case requestedAt = "requested_at"
    }
}

public struct PairRequestAccepted: Codable, Equatable, Sendable {
    public var requestId: String
    public var code: String
    public var hostname: String
    public var expiresInSeconds: Int

    enum CodingKeys: String, CodingKey {
        case requestId = "request_id"
        case code
        case hostname
        case expiresInSeconds = "expires_in_secs"
    }
}

public struct PairRequestStatusResponse: Codable, Equatable, Sendable {
    public var status: String
    public var requestId: String
    public var serverId: String?
    public var hostname: String?
    public var ip: String?
    public var port: Int?
    public var pin: String?
    public var error: String?

    enum CodingKeys: String, CodingKey {
        case status
        case requestId = "request_id"
        case serverId = "server_id"
        case hostname
        case ip
        case port
        case pin
        case error
    }
}

public final class PairRequestManager: @unchecked Sendable {
    public enum PairError: Error, Equatable {
        case missingClientIdentity
        case unknownRequest
    }

    private enum Status: Equatable {
        case pending
        case approved
        case rejected
    }

    private struct Entry {
        var info: PairRequestInfo
        var status: Status
    }

    private var entries: [String: Entry] = [:]
    private var sequence: UInt64 = 1
    private let ttlSeconds: Int

    public init(ttlSeconds: Int = 180) {
        self.ttlSeconds = ttlSeconds
    }

    public func requestPairing(
        clientId: String,
        clientName: String,
        configuration: MacServerConfiguration,
        now: Date = Date()
    ) throws -> PairRequestAccepted {
        let normalizedClientId = clientId.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedClientName = clientName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedClientId.isEmpty, !normalizedClientName.isEmpty else {
            throw PairError.missingClientIdentity
        }

        prune(now: now)
        if let existing = entries.values.first(where: {
            $0.info.clientId == normalizedClientId && $0.status == .pending
        }) {
            return PairRequestAccepted(
                requestId: existing.info.requestId,
                code: existing.info.code,
                hostname: configuration.hostname,
                expiresInSeconds: ttlSeconds
            )
        }

        let currentSequence = sequence
        sequence += 1
        let millis = UInt64((now.timeIntervalSince1970 * 1000.0).rounded())
        let requestId = "pair-\(currentSequence)-\(millis % 100000)"
        let code = String(format: "%06d", Int((millis ^ (currentSequence * 7_919)) % 1_000_000))
        let info = PairRequestInfo(
            requestId: requestId,
            clientId: normalizedClientId,
            clientName: normalizedClientName,
            code: code,
            requestedAt: now
        )
        entries[requestId] = Entry(info: info, status: .pending)
        return PairRequestAccepted(
            requestId: requestId,
            code: code,
            hostname: configuration.hostname,
            expiresInSeconds: ttlSeconds
        )
    }

    public func pendingRequests(now: Date = Date()) -> [PairRequestInfo] {
        prune(now: now)
        return entries.values
            .filter { $0.status == .pending }
            .map(\.info)
            .sorted { $0.requestedAt < $1.requestedAt }
    }

    public func approve(_ requestId: String, now: Date = Date()) throws {
        try setStatus(requestId, status: .approved, now: now)
    }

    public func reject(_ requestId: String, now: Date = Date()) throws {
        try setStatus(requestId, status: .rejected, now: now)
    }

    public func status(
        requestId: String,
        configuration: MacServerConfiguration,
        advertisedIP: String? = nil,
        now: Date = Date()
    ) -> PairRequestStatusResponse {
        prune(now: now)
        guard let entry = entries[requestId] else {
            return PairRequestStatusResponse(
                status: "expired",
                requestId: requestId,
                serverId: nil,
                hostname: nil,
                ip: nil,
                port: nil,
                pin: nil,
                error: "配对请求已过期，请重新发起"
            )
        }

        switch entry.status {
        case .pending:
            return PairRequestStatusResponse(
                status: "pending",
                requestId: requestId,
                serverId: nil,
                hostname: nil,
                ip: nil,
                port: nil,
                pin: nil,
                error: nil
            )
        case .approved:
            return PairRequestStatusResponse(
                status: "approved",
                requestId: requestId,
                serverId: configuration.serverId,
                hostname: configuration.hostname,
                ip: advertisedIP ?? configuration.ip,
                port: configuration.port,
                pin: configuration.pin,
                error: nil
            )
        case .rejected:
            return PairRequestStatusResponse(
                status: "rejected",
                requestId: requestId,
                serverId: nil,
                hostname: nil,
                ip: nil,
                port: nil,
                pin: nil,
                error: "桌面端拒绝了配对请求"
            )
        }
    }

    private func setStatus(_ requestId: String, status: Status, now: Date) throws {
        prune(now: now)
        guard var entry = entries[requestId] else {
            throw PairError.unknownRequest
        }
        entry.status = status
        entries[requestId] = entry
    }

    private func prune(now: Date) {
        entries = entries.filter { _, entry in
            now.timeIntervalSince(entry.info.requestedAt) <= TimeInterval(ttlSeconds)
        }
    }
}
