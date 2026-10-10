import Foundation
import Security

private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

enum StorageError: LocalizedError {
    case keychain(OSStatus), database(String), corruptData
    var errorDescription: String? {
        switch self {
        case .keychain(let status): return "无法访问安全密钥（\(status)）"
        case .database(let message): return "本地数据库错误：\(message)"
        case .corruptData: return "本地数据未通过完整性检查"
        }
    }
}

protocol HealthStateDatabase {
    func load() throws -> HealthState
    func save(_ state: HealthState) throws
}

final class SecureDatabase: HealthStateDatabase {
    private var db: OpaquePointer?
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(directory: URL? = nil, encryptionKey: Data? = nil) throws {
        encoder.dateEncodingStrategy = .iso8601
        decoder.dateDecodingStrategy = .iso8601
        let root = try directory ?? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("HealthTimeline", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        let url = root.appendingPathComponent("health_timeline.db")
        guard sqlite3_open_v2(url.path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else {
            throw StorageError.database(lastError)
        }
        let key = try encryptionKey ?? KeychainKey.loadOrCreate()
        guard key.count == 32 else { throw StorageError.corruptData }
        let result = key.withUnsafeBytes { sqlite3_key(db, $0.baseAddress, Int32($0.count)) }
        guard result == SQLITE_OK else { throw StorageError.database("无法启用数据库加密") }
        try execute("PRAGMA cipher_memory_security = ON")
        try execute("PRAGMA journal_mode = WAL")
        try execute("PRAGMA synchronous = FULL")
        try execute("CREATE TABLE IF NOT EXISTS app_state(id INTEGER PRIMARY KEY CHECK(id=1), payload BLOB NOT NULL, updated_at TEXT NOT NULL)")
        // Retained inside the encrypted DB, never exported as plaintext.
        try execute("CREATE TABLE IF NOT EXISTS calendar_date_migration_original(id INTEGER PRIMARY KEY CHECK(id=1), payload BLOB NOT NULL, interpreted_time_zone TEXT NOT NULL)")
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, "PRAGMA quick_check", -1, &statement, nil) == SQLITE_OK else { throw StorageError.database(lastError) }
        defer { sqlite3_finalize(statement) }
        guard sqlite3_step(statement) == SQLITE_ROW,
              let checkResult = sqlite3_column_text(statement, 0),
              String(cString: UnsafeRawPointer(checkResult).assumingMemoryBound(to: CChar.self)) == "ok" else {
            throw StorageError.corruptData
        }
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
    }

    deinit { sqlite3_close(db) }

    func load() throws -> HealthState {
        guard let data = try rawPayload() else { return HealthState() }
        return try decoder.decode(HealthState.self, from: data)
    }

    private func rawPayload(table: String = "app_state") throws -> Data? {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, "SELECT payload FROM \(table) WHERE id=1", -1, &statement, nil) == SQLITE_OK else { throw StorageError.database(lastError) }
        defer { sqlite3_finalize(statement) }
        let result = sqlite3_step(statement)
        if result == SQLITE_DONE { return nil }
        guard result == SQLITE_ROW else { throw StorageError.database(lastError) }
        let count = Int(sqlite3_column_bytes(statement, 0))
        guard let bytes = sqlite3_column_blob(statement, 0), count > 0 else { throw StorageError.corruptData }
        return Data(bytes: bytes, count: count)
    }

    func save(_ state: HealthState) throws {
        let data = try encoder.encode(state)
        try execute("BEGIN IMMEDIATE")
        do {
            if state.calendarDateEncodingVersion == 1, let original = try rawPayload() {
                guard let object = try JSONSerialization.jsonObject(with: original) as? [String: Any] else { throw StorageError.corruptData }
                if (object["calendarDateEncodingVersion"] as? Int ?? 0) == 0 {
                    var backup: OpaquePointer?
                    guard sqlite3_prepare_v2(db, "INSERT OR IGNORE INTO calendar_date_migration_original(id,payload,interpreted_time_zone) VALUES(1,?,?)", -1, &backup, nil) == SQLITE_OK else { throw StorageError.database(lastError) }
                    defer { sqlite3_finalize(backup) }
                    let blob = original.withUnsafeBytes { sqlite3_bind_blob(backup, 1, $0.baseAddress, Int32($0.count), SQLITE_TRANSIENT) }
                    let zone = HealthDate.localTimeZone.identifier.withCString { sqlite3_bind_text(backup, 2, $0, -1, SQLITE_TRANSIENT) }
                    guard blob == SQLITE_OK, zone == SQLITE_OK, sqlite3_step(backup) == SQLITE_DONE else { throw StorageError.database(lastError) }
                    #if DEBUG
                    if failAfterMigrationBackupForTesting { throw StorageError.database("测试迁移中断") }
                    #endif
                }
            }
            var statement: OpaquePointer?
            let sql = "INSERT INTO app_state(id,payload,updated_at) VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload,updated_at=excluded.updated_at"
            guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK else { throw StorageError.database(lastError) }
            defer { sqlite3_finalize(statement) }
            let blobResult = data.withUnsafeBytes {
                sqlite3_bind_blob(statement, 1, $0.baseAddress, Int32($0.count), SQLITE_TRANSIENT)
            }
            guard blobResult == SQLITE_OK else { throw StorageError.database(lastError) }
            let timestamp = ISO8601DateFormatter().string(from: Date())
            let textResult = timestamp.withCString {
                sqlite3_bind_text(statement, 2, $0, -1, SQLITE_TRANSIENT)
            }
            guard textResult == SQLITE_OK else { throw StorageError.database(lastError) }
            guard sqlite3_step(statement) == SQLITE_DONE else { throw StorageError.database(lastError) }
            try execute("COMMIT")
        } catch {
            try? execute("ROLLBACK")
            throw error
        }
    }

    #if DEBUG
    var failAfterMigrationBackupForTesting = false
    func retainedMigrationPayloadForTesting() throws -> Data? { try rawPayload(table: "calendar_date_migration_original") }
    func currentPayloadForTesting() throws -> Data? { try rawPayload() }
    func seedLegacyPayloadForTesting(_ data: Data) throws {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, "INSERT OR REPLACE INTO app_state(id,payload,updated_at) VALUES(1,?,'test')", -1, &statement, nil) == SQLITE_OK else { throw StorageError.database(lastError) }
        defer { sqlite3_finalize(statement) }
        let bound = data.withUnsafeBytes { sqlite3_bind_blob(statement, 1, $0.baseAddress, Int32($0.count), SQLITE_TRANSIENT) }
        guard bound == SQLITE_OK, sqlite3_step(statement) == SQLITE_DONE else { throw StorageError.database(lastError) }
    }
    #endif

    private func execute(_ sql: String) throws {
        if sqlite3_exec(db, sql, nil, nil, nil) != SQLITE_OK { throw StorageError.database(lastError) }
    }
    private var lastError: String { db.map { String(cString: sqlite3_errmsg($0)) } ?? "数据库未打开" }
}

private enum KeychainKey {
    private static let service = "com.healthtimeline.app.ios.database"
    private static let account = "sqlcipher-key-v1"
    static func loadOrCreate() throws -> Data {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecSuccess, let data = result as? Data { return data }
        guard status == errSecItemNotFound else { throw StorageError.keychain(status) }
        var bytes = Data(count: 32)
        let randomStatus = bytes.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        guard randomStatus == errSecSuccess else { throw StorageError.keychain(randomStatus) }
        let add: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                  kSecAttrAccount as String: account,
                                  kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, kSecValueData as String: bytes]
        let addStatus = SecItemAdd(add as CFDictionary, nil)
        guard addStatus == errSecSuccess else { throw StorageError.keychain(addStatus) }
        return bytes
    }
}
