import Foundation
import CryptoKit
import Security

enum BridgeCrypto {
    static func randomBytes(_ count: Int) -> Data {
        var bytes = Data(count: count)
        _ = bytes.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, count, $0.baseAddress!) }
        return bytes
    }

    static func randomNonceBase64Url(_ count: Int = 16) -> String {
        base64UrlEncode(randomBytes(count))
    }

    static func base64UrlDecode(_ str: String) -> Data? {
        var s = str.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let rem = s.count % 4
        if rem > 0 { s += String(repeating: "=", count: 4 - rem) }
        return Data(base64Encoded: s)
    }

    static func base64UrlEncode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Per-direction UDP audio subkeys (HKDF-SHA256, matches Android BleCrypto.deriveUdpKeys)
    static func deriveUdpKeys(master: Data) -> (c2s: Data, s2c: Data)? {
        guard master.count == 32 else { return nil }
        let ikm = SymmetricKey(data: master)
        let salt = Data("simbridge-udp-v4.2".utf8)
        func derive(_ info: String) -> Data {
            let k = HKDF<SHA256>.deriveKey(inputKeyMaterial: ikm, salt: salt, info: Data(info.utf8), outputByteCount: 32)
            return k.withUnsafeBytes { Data($0) }
        }
        return (derive("c2s"), derive("s2c"))
    }

    /// Wire format matching Android BleCrypto.kt: [12-byte IV][ciphertext][16-byte GCM tag]
    static func encrypt(keyData: Data, plaintext: Data) -> Data? {
        guard keyData.count == 32 else { return nil }
        do {
            let key = SymmetricKey(data: keyData)
            let nonce = AES.GCM.Nonce()
            let sealed = try AES.GCM.seal(plaintext, using: key, nonce: nonce)
            var out = Data()
            out.append(contentsOf: nonce)
            out.append(sealed.ciphertext)
            out.append(sealed.tag)
            return out
        } catch {
            return nil
        }
    }

    static func decrypt(keyData: Data, packet: Data) -> Data? {
        guard keyData.count == 32, packet.count > 28 else { return nil }
        do {
            let key = SymmetricKey(data: keyData)
            let sealed = try AES.GCM.SealedBox(combined: packet)
            return try AES.GCM.open(sealed, using: key)
        } catch {
            return nil
        }
    }
}

enum KeychainStore {
    private static let service = "com.simbridge.ios.keys"
    private static let keyAccount = "master_aes256_key"
    private static let svcAccount = "ble_service_uuid"

    static func savePairing(keyData: Data, serviceUUID: String) {
        save(data: keyData, account: keyAccount)
        save(data: Data(serviceUUID.utf8), account: svcAccount)
    }

    static func loadKey() -> Data? {
        load(account: keyAccount)
    }

    static func loadServiceUUID() -> String? {
        guard let d = load(account: svcAccount) else { return nil }
        return String(data: d, encoding: .utf8)
    }

    static func clear() {
        delete(account: keyAccount)
        delete(account: svcAccount)
    }

    private static func save(data: Data, account: String) {
        delete(account: account)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        SecItemAdd(query as CFDictionary, nil)
    }

    private static func load(account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var ref: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &ref) == errSecSuccess else { return nil }
        return ref as? Data
    }

    private static func delete(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)
    }
}
