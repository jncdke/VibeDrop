import Foundation

public enum VibeDropAction: String, Codable, CaseIterable, Sendable {
    case auth
    case ping
    case pong
    case clipboard
    case type
    case typeEnter = "type_enter"
    case enter
    case imageClipboard = "image_clipboard"
    case incomingHistorySessionStart = "incoming_history_session_start"
    case incomingFileStart = "incoming_file_start"
    case incomingFileChunk = "incoming_file_chunk"
    case incomingFileComplete = "incoming_file_complete"
    case incomingFileSaved = "incoming_file_saved"
    case incomingFileError = "incoming_file_error"
}
