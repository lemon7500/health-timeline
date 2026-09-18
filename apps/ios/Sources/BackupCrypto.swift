import CryptoKit
import Foundation
import Security

enum BackupCryptoError: LocalizedError {
    case invalidPassword, invalidContainer, unsupportedVersion, derivationFailed
    var errorDescription: String? {
        switch self {
        case .invalidPassword: return "密码错误或备份文件已损坏"
        case .invalidContainer: return "不是有效的病程日历备份"
        case .unsupportedVersion: return "不支持的备份容器版本"
        case .derivationFailed: return "无法生成备份加密密钥"
        }
    }
}

enum BackupCrypto {
    private static let magic = Data("HTBACKUP".utf8)
    private static let version: UInt32 = 1
    private static let iterations: UInt32 = 210_000

    static func encrypt(_ plaintext: Data, password: String) throws -> Data {
        guard password.count >= 8 else { throw StoreValidationError.message("备份密码至少需要 8 位") }
        let salt = random(count: 16)
        let nonceData = random(count: 12)
        let key = try derive(password: password, salt: salt)
        let nonce = try AES.GCM.Nonce(data: nonceData)
        let sealed = try AES.GCM.seal(plaintext, using: key, nonce: nonce)
        var result = Data()
        result.append(magic)
        result.appendBigEndian(version)
        result.appendBigEndian(UInt32(salt.count))
        result.append(salt)
        result.appendBigEndian(UInt32(nonceData.count))
        result.append(nonceData)
        result.append(sealed.ciphertext)
        result.append(sealed.tag)
        return result
    }

    static func decrypt(_ container: Data, password: String) throws -> Data {
        var reader = BinaryReader(data: container)
        guard try reader.read(count: magic.count) == magic else { throw BackupCryptoError.invalidContainer }
        guard try reader.readUInt32() == version else { throw BackupCryptoError.unsupportedVersion }
        let saltCount = Int(try reader.readUInt32())
        guard (8...64).contains(saltCount) else { throw BackupCryptoError.invalidContainer }
        let salt = try reader.read(count: saltCount)
        let nonceCount = Int(try reader.readUInt32())
        guard (12...32).contains(nonceCount) else { throw BackupCryptoError.invalidContainer }
        let nonceData = try reader.read(count: nonceCount)
        let encrypted = try reader.remaining()
        guard encrypted.count >= 16 else { throw BackupCryptoError.invalidContainer }
        do {
            let key = try derive(password: password, salt: salt)
            let tag = Data(encrypted.suffix(16))
            let ciphertext = Data(encrypted.dropLast(16))
            let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonceData), ciphertext: ciphertext, tag: tag)
            return try AES.GCM.open(box, using: key)
        } catch let error as BackupCryptoError {
            throw error
        } catch {
            throw BackupCryptoError.invalidPassword
        }
    }

    private static func derive(password: String, salt: Data) throws -> SymmetricKey {
        let passwordBytes = Array(password.utf8)
        var derived = [UInt8](repeating: 0, count: 32)
        let derivedCount = derived.count
        let status = passwordBytes.withUnsafeBytes { passwordBuffer in
            salt.withUnsafeBytes { saltBuffer in
                derived.withUnsafeMutableBytes { output in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        passwordBuffer.bindMemory(to: Int8.self).baseAddress,
                        passwordBytes.count,
                        saltBuffer.bindMemory(to: UInt8.self).baseAddress,
                        salt.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                        iterations,
                        output.bindMemory(to: UInt8.self).baseAddress,
                        derivedCount
                    )
                }
            }
        }
        guard status == kCCSuccess else { throw BackupCryptoError.derivationFailed }
        return SymmetricKey(data: Data(derived))
    }

    private static func random(count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        let status = bytes.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, count, $0.baseAddress!) }
        precondition(status == errSecSuccess)
        return Data(bytes)
    }
}

private struct BinaryReader {
    let data: Data
    var offset = 0

    mutating func read(count: Int) throws -> Data {
        guard count >= 0, offset <= data.count - count else { throw BackupCryptoError.invalidContainer }
        defer { offset += count }
        return data.subdata(in: offset..<(offset + count))
    }

    mutating func readUInt32() throws -> UInt32 {
        let bytes = try read(count: 4)
        return bytes.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
    }

    mutating func remaining() throws -> Data { try read(count: data.count - offset) }
}

private extension Data {
    mutating func appendBigEndian(_ value: UInt32) {
        append(UInt8((value >> 24) & 0xff))
        append(UInt8((value >> 16) & 0xff))
        append(UInt8((value >> 8) & 0xff))
        append(UInt8(value & 0xff))
    }
}
