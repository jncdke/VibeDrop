import importlib.util
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "scripts" / "sync-home-vault.py"
SPEC = importlib.util.spec_from_file_location("sync_home_vault", MODULE_PATH)
sync_home_vault = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(sync_home_vault)


class HomeVaultDeviceRolesTests(unittest.TestCase):
    def test_explicit_native_sender_receiver_fields_win_over_legacy_shape(self):
        entry = {
            "direction": "mobile_to_desktop",
            "clientId": "legacy-client",
            "clientName": "Legacy Client",
            "senderDeviceId": "native-android:pixel-demo",
            "senderBaseDeviceId": "native-android:pixel-demo",
            "senderName": "Pixel Demo",
            "senderRole": "primary",
            "receiverDeviceId": "desktop-studio-demo",
            "receiverBaseDeviceId": "desktop-studio-demo",
            "receiverName": "Studio Mac",
            "receiverHost": "studio-mac.local",
            "receiverRole": "desktop",
        }

        roles = sync_home_vault.derive_device_roles(entry, "android-inbox:demo")

        self.assertEqual(roles["sender_id"], "native-android:pixel-demo")
        self.assertEqual(roles["sender_name"], "Pixel Demo")
        self.assertEqual(roles["receiver_id"], "desktop-studio-demo")
        self.assertEqual(roles["receiver_name"], "Studio Mac")
        self.assertEqual(roles["receiver_host"], "studio-mac.local")

    def test_base_device_id_participates_in_canonical_sender_merge(self):
        entry = {
            "senderDeviceId": "client_mmr92alsud0nu7:clipboard:123",
            "senderBaseDeviceId": "client_mmr92alsud0nu7",
            "senderName": "一加 Ace 5 剪贴板",
            "receiverDeviceId": "desktop-demo",
            "receiverName": "Test Mac",
        }

        roles = sync_home_vault.derive_device_roles(entry, "android-inbox:demo")

        self.assertEqual(roles["sender_id"], "client_mmr92alsud0nu7")
        self.assertEqual(roles["sender_name"], "一加 Ace 5")

    def test_partial_explicit_receiver_keeps_legacy_sender(self):
        entry = {
            "direction": "mobile_to_desktop",
            "clientId": "client_demo",
            "clientName": "Android Demo",
            "receiverDeviceId": "desktop_demo",
            "receiverName": "Mac Demo",
            "receiverHost": "mac-demo.local",
        }

        roles = sync_home_vault.derive_device_roles(entry, "android-inbox:demo")

        self.assertEqual(roles["sender_id"], "client_demo")
        self.assertEqual(roles["sender_name"], "Android Demo")
        self.assertEqual(roles["receiver_id"], "desktop_demo")
        self.assertEqual(roles["receiver_name"], "Mac Demo")
        self.assertEqual(roles["receiver_host"], "mac-demo.local")

    def test_legacy_mobile_to_desktop_keeps_client_as_sender(self):
        entry = {
            "direction": "mobile_to_desktop",
            "clientId": "client_demo",
            "clientName": "Android Demo",
            "targetServerId": "desktop_demo",
            "targetDeviceName": "mac-demo.local",
        }

        roles = sync_home_vault.derive_device_roles(entry, "vault:legacy")

        self.assertEqual(roles["sender_id"], "client_demo")
        self.assertEqual(roles["sender_name"], "Android Demo")
        self.assertEqual(roles["receiver_server_id"], "desktop_demo")
        self.assertEqual(roles["receiver_name"], "mac-demo.local")

    def test_legacy_desktop_to_mobile_flips_sender_and_receiver(self):
        entry = {
            "direction": "desktop_to_mobile",
            "clientId": "client_demo",
            "clientName": "Android Demo",
            "targetServerId": "desktop_demo",
            "targetDeviceName": "mac-demo.local",
        }

        roles = sync_home_vault.derive_device_roles(entry, "vault:legacy")

        self.assertEqual(roles["sender_id"], "desktop_demo")
        self.assertEqual(roles["sender_name"], "mac-demo.local")
        self.assertEqual(roles["receiver_id"], "client_demo")
        self.assertEqual(roles["receiver_name"], "Android Demo")


if __name__ == "__main__":
    unittest.main()
