import XCTest
@testable import VibeDropNativeCore

final class VibeDropMessageTests: XCTestCase {
    func testDecodeAuthFixtureShape() throws {
        let json = """
        {
          "action": "auth",
          "pin": "1234",
          "device_id": "client_android_demo",
          "base_device_id": "client_android_demo",
          "device_name": "一加 Ace 5",
          "can_receive_files": true,
          "receives_clipboard": false,
          "device_role": "primary"
        }
        """

        let message = try JSONDecoder().decode(VibeDropMessage.self, from: Data(json.utf8))

        XCTAssertEqual(message.action, .auth)
        XCTAssertEqual(message.pin, "1234")
        XCTAssertEqual(message.deviceId, "client_android_demo")
        XCTAssertEqual(message.deviceName, "一加 Ace 5")
        XCTAssertEqual(message.canReceiveFiles, true)
        XCTAssertEqual(message.receivesClipboard, false)
        XCTAssertEqual(message.deviceRole, "primary")
    }

    func testEncodeTypeEnterUsesV1SnakeCaseAction() throws {
        let message = VibeDropMessage(
            action: .typeEnter,
            text: "测试文本"
        )

        let data = try JSONEncoder().encode(message)
        let object = try JSONSerialization.jsonObject(with: data) as? [String: Any]

        XCTAssertEqual(object?["action"] as? String, "type_enter")
        XCTAssertEqual(object?["text"] as? String, "测试文本")
    }

    func testConnectionSnapshotCanSendOnlyWhenConnected() {
        XCTAssertTrue(ConnectionSnapshot(status: .connected).canSend)
        XCTAssertFalse(ConnectionSnapshot(status: .connecting).canSend)
        XCTAssertFalse(ConnectionSnapshot(status: .failed).canSend)
    }
}
