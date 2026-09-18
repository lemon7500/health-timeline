import Foundation
import SwiftUI
import UniformTypeIdentifiers
import ZIPFoundation

extension UTType {
    static let healthTimelineBackup = UTType(exportedAs: "com.healthtimeline.backup", conformingTo: .data)
}

struct HealthTimelineBackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.healthTimelineBackup, .data] }
    var data: Data

    init(data: Data = Data()) { self.data = data }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else {
            throw StoreValidationError.message("无法读取备份文件")
        }
        self.data = data
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

struct PreparedPortableBackup {
    let snapshot: PortableSnapshot
    let importedState: HealthState
    let attachmentBytes: [UUID: Data]
    let preview: BackupPreview
    let previewStateLocal: HealthState
}

enum PortableBackupService {
    private static let maximumManifestBytes = 10_000_000
    private static let maximumRows = 100_000
    // CryptoKit AES-GCM is one-shot. Keep the preview build below a memory-safe bound until the streaming adapter is completed.
    private static let maximumContainerBytes = 128 * 1024 * 1024
    private static let maximumAttachmentPayloadBytes: Int64 = 120 * 1024 * 1024

    static func export(state: HealthState, password: String) throws -> Data {
        let totalAttachmentBytes = try state.attachments.reduce(Int64(0)) { try addingWithoutOverflow($0, $1.sizeBytes) }
        guard totalAttachmentBytes <= maximumAttachmentPayloadBytes else {
            throw StoreValidationError.message("iOS 开发预览版单次备份总附件暂不能超过 120 MB")
        }
        let snapshot = try PortableBackupMapper.export(state)
        let root = try temporaryDirectory(prefix: "health-export")
        defer { try? FileManager.default.removeItem(at: root) }
        let manifestURL = root.appendingPathComponent("manifest.json")
        let zipURL = root.appendingPathComponent("payload.zip")
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        let manifest = try encoder.encode(snapshot)
        guard manifest.count <= maximumManifestBytes else { throw StoreValidationError.message("备份清单过大") }
        try manifest.write(to: manifestURL, options: [.atomic])
        let archive = try Archive(url: zipURL, accessMode: .create)
        try archive.addEntry(with: "manifest.json", fileURL: manifestURL, compressionMethod: .deflate)
        for item in snapshot.attachments {
            guard let local = state.attachments.first(where: { $0.id == item.uuid }) else {
                throw StoreValidationError.message("附件索引缺失：\(item.displayName)")
            }
            let source = try AttachmentFiles.url(local)
            try AttachmentFiles.verify(local)
            try archive.addEntry(with: item.archivePath, fileURL: source, compressionMethod: .deflate)
        }
        return try BackupCrypto.encrypt(Data(contentsOf: zipURL), password: password)
    }

    static func prepareImport(data: Data, password: String, local: HealthState, targetMember: FamilyMember?) throws -> PreparedPortableBackup {
        guard data.count <= maximumContainerBytes else { throw StoreValidationError.message("iOS 开发预览版暂不能导入超过 128 MB 的备份") }
        let root = try temporaryDirectory(prefix: "health-import")
        defer { try? FileManager.default.removeItem(at: root) }
        let zipURL = root.appendingPathComponent("payload.zip")
        try BackupCrypto.decrypt(data, password: password).write(to: zipURL, options: [.atomic])
        let archive = try Archive(url: zipURL, accessMode: .read)
        guard let manifestEntry = archive["manifest.json"], manifestEntry.uncompressedSize > 0,
              manifestEntry.uncompressedSize <= UInt32(maximumManifestBytes) else {
            throw StoreValidationError.message("备份缺少清单或清单大小异常")
        }
        let manifestURL = root.appendingPathComponent("manifest-decoded.json")
        try archive.extract(manifestEntry, to: manifestURL)
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let raw = try decoder.decode(PortableSnapshot.self, from: Data(contentsOf: manifestURL))
        let snapshot = try PortableBackupMapper.normalized(raw, targetMember: targetMember)
        try validateRowLimits(snapshot)
        let expectedEntries = Set(["manifest.json"] + snapshot.attachments.map(\.archivePath))
        let actualEntries = Set(archive.map(\.path))
        guard actualEntries == expectedEntries else { throw StoreValidationError.message("备份包含未声明文件或缺少附件") }
        let importedPaths = Dictionary(uniqueKeysWithValues: snapshot.attachments.map {
            ($0.uuid, "\($0.recordUuid.uuidString)/\($0.uuid.uuidString)")
        })
        let importedState = try PortableBackupMapper.toState(snapshot, attachmentPaths: importedPaths)
        var attachmentBytes: [UUID: Data] = [:]
        var total: Int64 = 0
        for item in snapshot.attachments {
            guard item.archivePath == "files/\(item.uuid.uuidString.lowercased())",
                  let entry = archive[item.archivePath], entry.uncompressedSize == UInt32(item.sizeBytes) else {
                throw StoreValidationError.message("附件缺失或大小异常：\(item.displayName)")
            }
            total = try addingWithoutOverflow(total, item.sizeBytes)
            let destination = root.appendingPathComponent(item.uuid.uuidString)
            try archive.extract(entry, to: destination)
            let bytes = try Data(contentsOf: destination, options: [.mappedIfSafe])
            try AttachmentFiles.verify(bytes: bytes, expectedSize: item.sizeBytes, expectedHash: item.sha256)
            attachmentBytes[item.uuid] = bytes
        }
        let available = try root.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage ?? 0
        guard available == 0 || available > total + 20 * 1024 * 1024 else { throw StoreValidationError.message("设备存储空间不足") }
        return PreparedPortableBackup(
            snapshot: snapshot,
            importedState: importedState,
            attachmentBytes: attachmentBytes,
            preview: preview(local: local, imported: importedState),
            previewStateLocal: local
        )
    }

    static func merge(local: HealthState, imported: HealthState, useImported: Set<String> = []) throws -> HealthState {
        var result = local
        result.members = merge(local.members, imported.members, entity: "member", useImported: useImported)
        result.conditions = merge(local.conditions, imported.conditions, entity: "condition", useImported: useImported)
        result.records = merge(local.records, imported.records, entity: "record", useImported: useImported)
        result.attachments = merge(local.attachments, imported.attachments, entity: "attachment", useImported: useImported)
        result.followUps = merge(local.followUps, imported.followUps, entity: "followup", useImported: useImported)
        result.occurrences = merge(local.occurrences, imported.occurrences, entity: "occurrence", useImported: useImported)
        result.medications = merge(local.medications, imported.medications, entity: "medication", useImported: useImported)
        result.medicationSchedules = merge(local.medicationSchedules, imported.medicationSchedules, entity: "medicationSchedule", useImported: useImported)
        result.medicationLogs = merge(local.medicationLogs, imported.medicationLogs, entity: "medicationLog", useImported: useImported)
        result.schemaVersion = 4
        _ = result.normalizeLegacyData()
        _ = try PortableBackupMapper.export(result)
        return result
    }

    static func preview(local: HealthState, imported: HealthState) -> BackupPreview {
        var output = BackupPreview()
        count(local.members, imported.members, entity: "member", output: &output)
        count(local.conditions, imported.conditions, entity: "condition", output: &output)
        count(local.records, imported.records, entity: "record", output: &output)
        countAttachments(local.attachments, imported.attachments, output: &output)
        count(local.followUps, imported.followUps, entity: "followup", output: &output)
        count(local.occurrences, imported.occurrences, entity: "occurrence", output: &output)
        count(local.medications, imported.medications, entity: "medication", output: &output)
        count(local.medicationSchedules, imported.medicationSchedules, entity: "medicationSchedule", output: &output)
        count(local.medicationLogs, imported.medicationLogs, entity: "medicationLog", output: &output)
        return output
    }

    private static func merge<T: Identifiable>(_ local: [T], _ imported: [T], entity: String, useImported: Set<String>) -> [T] where T.ID == UUID {
        var result = local
        var indexes = Dictionary(uniqueKeysWithValues: result.indices.map { (result[$0].id, $0) })
        for value in imported {
            if let index = indexes[value.id] {
                if useImported.contains("\(entity):\(value.id.uuidString)") { result[index] = value }
            } else {
                indexes[value.id] = result.count
                result.append(value)
            }
        }
        return result
    }

    private static func count<T: Identifiable & Equatable>(_ local: [T], _ imported: [T], entity: String, output: inout BackupPreview) where T.ID == UUID {
        let localById = Dictionary(uniqueKeysWithValues: local.map { ($0.id, $0) })
        for value in imported {
            guard let existing = localById[value.id] else { output.additions += 1; continue }
            if existing == value { output.duplicates += 1 }
            else {
                output.updates += 1
                output.conflicts.append(BackupConflict(entity: entity, uuid: value.id, localUpdatedAt: "local", importedUpdatedAt: "imported"))
            }
        }
    }

    private static func countAttachments(_ local: [Attachment], _ imported: [Attachment], output: inout BackupPreview) {
        let localById = Dictionary(uniqueKeysWithValues: local.map { ($0.id, $0) })
        for value in imported {
            guard let existing = localById[value.id] else { output.additions += 1; continue }
            if existing.recordId == value.recordId && existing.kind == value.kind && existing.displayName == value.displayName
                && existing.mimeType == value.mimeType && existing.sizeBytes == value.sizeBytes && existing.sha256 == value.sha256 {
                output.duplicates += 1
            } else {
                output.updates += 1
                output.conflicts.append(BackupConflict(entity: "attachment", uuid: value.id, localUpdatedAt: "local", importedUpdatedAt: "imported"))
            }
        }
    }

    private static func validateRowLimits(_ value: PortableSnapshot) throws {
        let counts = [value.members.count, value.conditions.count, value.records.count, value.attachments.count,
                      value.followUps.count, value.occurrences.count, value.medications.count,
                      value.medicationSchedules.count, value.medicationLogs.count]
        guard counts.allSatisfy({ $0 <= maximumRows }) else { throw StoreValidationError.message("备份记录数量异常") }
    }

    private static func temporaryDirectory(prefix: String) throws -> URL {
        let value = FileManager.default.temporaryDirectory.appendingPathComponent("\(prefix)-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: value, withIntermediateDirectories: true)
        return value
    }

    private static func addingWithoutOverflow(_ lhs: Int64, _ rhs: Int64) throws -> Int64 {
        let (sum, overflow) = lhs.addingReportingOverflow(rhs)
        guard !overflow else { throw StoreValidationError.message("附件总大小异常") }
        return sum
    }
}
