import Foundation

enum BackupSafetyStore {
    private static let maximumRetained = 3

    @discardableResult
    static func create(state: HealthState, password: String) throws -> URL {
        let payload = try PortableBackupService.export(state: state, password: password)
        let directory = try root()
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        let destination = directory.appendingPathComponent("before-replace-\(formatter.string(from: Date()))-\(UUID().uuidString).htbackup")
        try payload.write(to: destination, options: [.atomic])
        try FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
            ofItemAtPath: destination.path
        )
        try? prune(directory)
        return destination
    }

    static func latestData() throws -> Data {
        guard let latest = try sortedBackups(in: root()).first else {
            throw StoreValidationError.message("还没有自动安全备份")
        }
        return try Data(contentsOf: latest, options: [.mappedIfSafe])
    }

    private static func root() throws -> URL {
        let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let directory = base.appendingPathComponent("HealthTimeline/safety-backups", isDirectory: true)
        try FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        return directory
    }

    private static func prune(_ directory: URL) throws {
        for obsolete in try sortedBackups(in: directory).dropFirst(maximumRetained) {
            try FileManager.default.removeItem(at: obsolete)
        }
    }

    private static func sortedBackups(in directory: URL) throws -> [URL] {
        let files = try FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.creationDateKey],
            options: [.skipsHiddenFiles]
        ).filter { $0.pathExtension == "htbackup" }
        return files.sorted {
            let lhs = (try? $0.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? .distantPast
            let rhs = (try? $1.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? .distantPast
            return lhs > rhs
        }
    }
}
