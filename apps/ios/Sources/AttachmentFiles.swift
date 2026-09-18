import CryptoKit
import Foundation
import UniformTypeIdentifiers

enum AttachmentError: LocalizedError {
    case unsupported, tooLarge, cannotRead, unsafePath
    var errorDescription: String? {
        switch self {
        case .unsupported: return "只支持图片或 PDF"
        case .tooLarge: return "单个附件不能超过 100 MB"
        case .cannotRead: return "无法读取所选文件"
        case .unsafePath: return "附件路径不安全，操作已取消"
        }
    }
}

struct AttachmentDeleteToken: Equatable {
    let attachmentId: UUID
    let original: URL
    let staged: URL
}

enum AttachmentFiles {
    static let maxBytes: Int64 = 100 * 1024 * 1024

    static func importFile(recordId: UUID, source: URL) throws -> Attachment {
        let accessing = source.startAccessingSecurityScopedResource()
        defer { if accessing { source.stopAccessingSecurityScopedResource() } }
        let values = try source.resourceValues(forKeys: [.fileSizeKey, .contentTypeKey, .nameKey])
        guard let size = values.fileSize, size >= 0, Int64(size) <= maxBytes else { throw AttachmentError.tooLarge }
        let type = values.contentType
        let kind: String
        if type?.conforms(to: .pdf) == true { kind = "PDF" }
        else if type?.conforms(to: .image) == true { kind = "IMAGE" }
        else { throw AttachmentError.unsupported }

        let id = UUID()
        let directory = try root().appendingPathComponent(recordId.uuidString, isDirectory: true).ensuringDirectory()
        let destination = directory.appendingPathComponent(id.uuidString, isDirectory: false)
        let temporary = directory.appendingPathComponent(".\(id.uuidString).tmp", isDirectory: false)
        do {
            try FileManager.default.copyItem(at: source, to: temporary)
            let (copiedSize, digest) = try sha256(of: temporary)
            guard copiedSize == Int64(size) else { throw AttachmentError.cannotRead }
            try atomicallyMove(temporary, to: destination)
            try protect(destination)
            return Attachment(
                id: id,
                recordId: recordId,
                kind: kind,
                displayName: values.name ?? source.lastPathComponent,
                mimeType: type?.preferredMIMEType ?? "application/octet-stream",
                relativePath: "\(recordId.uuidString)/\(id.uuidString)",
                sizeBytes: Int64(size),
                sha256: digest
            )
        } catch {
            try? FileManager.default.removeItem(at: temporary)
            try? FileManager.default.removeItem(at: destination)
            throw error
        }
    }

    static func url(_ attachment: Attachment) throws -> URL {
        try safeURL(relativePath: attachment.relativePath, inside: root())
    }

    static func stageDelete(_ attachment: Attachment) throws -> AttachmentDeleteToken? {
        let original = try url(attachment)
        guard FileManager.default.fileExists(atPath: original.path) else { return nil }
        let directory = try trashRoot().appendingPathComponent(attachment.id.uuidString, isDirectory: true).ensuringDirectory()
        let staged = directory.appendingPathComponent("payload.deleted", isDirectory: false)
        if FileManager.default.fileExists(atPath: staged.path) { throw AttachmentError.cannotRead }
        try atomicallyMove(original, to: staged)
        return AttachmentDeleteToken(attachmentId: attachment.id, original: original, staged: staged)
    }

    static func rollbackDelete(_ token: AttachmentDeleteToken) throws {
        guard FileManager.default.fileExists(atPath: token.staged.path) else { return }
        try FileManager.default.createDirectory(at: token.original.deletingLastPathComponent(), withIntermediateDirectories: true)
        if FileManager.default.fileExists(atPath: token.original.path) { throw AttachmentError.unsafePath }
        try atomicallyMove(token.staged, to: token.original)
        try? FileManager.default.removeItem(at: token.staged.deletingLastPathComponent())
    }

    static func commitDelete(_ token: AttachmentDeleteToken) throws {
        if FileManager.default.fileExists(atPath: token.staged.path) { try FileManager.default.removeItem(at: token.staged) }
        try? FileManager.default.removeItem(at: token.staged.deletingLastPathComponent())
    }

    static func deleteImmediately(_ attachment: Attachment) throws {
        let file = try url(attachment)
        if FileManager.default.fileExists(atPath: file.path) { try FileManager.default.removeItem(at: file) }
    }

    static func reconcileTrash(liveAttachments: [Attachment]) throws {
        let trash = try trashRoot()
        guard FileManager.default.fileExists(atPath: trash.path) else { return }
        let liveById = Dictionary(uniqueKeysWithValues: liveAttachments.map { ($0.id, $0) })
        let directories = try FileManager.default.contentsOfDirectory(at: trash, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles])
        for directory in directories {
            guard let id = UUID(uuidString: directory.lastPathComponent) else { try? FileManager.default.removeItem(at: directory); continue }
            let staged = directory.appendingPathComponent("payload.deleted")
            if let live = liveById[id], FileManager.default.fileExists(atPath: staged.path) {
                let original = try url(live)
                try FileManager.default.createDirectory(at: original.deletingLastPathComponent(), withIntermediateDirectories: true)
                if !FileManager.default.fileExists(atPath: original.path) { try atomicallyMove(staged, to: original) }
                try? FileManager.default.removeItem(at: directory)
            } else {
                try? FileManager.default.removeItem(at: directory)
            }
        }
    }

    static func reconcileRestoreDirectories(liveAttachments: [Attachment]) throws {
        let restore = try restoreRoot()
        let livePaths = Set(liveAttachments.map(\.relativePath))
        let batches = try FileManager.default.contentsOfDirectory(at: restore, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles])
        for batch in batches {
            guard (try? batch.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true else {
                try? FileManager.default.removeItem(at: batch)
                continue
            }
            let enumerator = FileManager.default.enumerator(at: batch, includingPropertiesForKeys: [.isRegularFileKey], options: [.skipsHiddenFiles])
            var containsLiveFile = false
            while let file = enumerator?.nextObject() as? URL {
                guard (try? file.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true else { continue }
                let relative = relativePath(of: file, inside: try root())
                if let relative, livePaths.contains(relative) { containsLiveFile = true }
            }
            if !containsLiveFile { try? FileManager.default.removeItem(at: batch) }
        }
    }

    static func restoredPath(recordId: UUID, attachmentId: UUID, batchId: UUID) -> String {
        "restored/\(batchId.uuidString)/\(recordId.uuidString)/\(attachmentId.uuidString)"
    }

    static func installVerified(bytes: Data, attachment: Attachment) throws {
        try verify(bytes: bytes, expectedSize: attachment.sizeBytes, expectedHash: attachment.sha256)
        let destination = try url(attachment)
        try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        let temporary = destination.appendingPathExtension("tmp-\(UUID().uuidString)")
        do {
            try bytes.write(to: temporary, options: [.atomic])
            try atomicallyMove(temporary, to: destination)
            try protect(destination)
        } catch {
            try? FileManager.default.removeItem(at: temporary)
            throw error
        }
    }

    static func verify(_ attachment: Attachment) throws {
        let file = try url(attachment)
        guard FileManager.default.fileExists(atPath: file.path) else { throw AttachmentError.cannotRead }
        let result = try sha256(of: file)
        guard result.0 == attachment.sizeBytes, result.1 == attachment.sha256 else { throw AttachmentError.cannotRead }
    }

    static func verify(bytes: Data, expectedSize: Int64, expectedHash: String) throws {
        guard Int64(bytes.count) == expectedSize, bytes.count <= Int(maxBytes) else { throw AttachmentError.tooLarge }
        let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        guard digest == expectedHash else { throw AttachmentError.cannotRead }
    }

    private static func root() throws -> URL {
        let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        return try base.appendingPathComponent("HealthTimeline/attachments", isDirectory: true).ensuringDirectory()
    }

    private static func trashRoot() throws -> URL {
        let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        return try base.appendingPathComponent("HealthTimeline/attachment-trash", isDirectory: true).ensuringDirectory()
    }

    private static func restoreRoot() throws -> URL {
        try root().appendingPathComponent("restored", isDirectory: true).ensuringDirectory()
    }

    private static func relativePath(of file: URL, inside base: URL) -> String? {
        let rootPath = base.standardizedFileURL.path + "/"
        let filePath = file.standardizedFileURL.path
        guard filePath.hasPrefix(rootPath) else { return nil }
        return String(filePath.dropFirst(rootPath.count))
    }

    private static func safeURL(relativePath: String, inside base: URL) throws -> URL {
        guard !relativePath.isEmpty, !relativePath.hasPrefix("/"), !relativePath.contains(".."), !relativePath.contains("\\") else { throw AttachmentError.unsafePath }
        let standardizedBase = base.standardizedFileURL
        let candidate = standardizedBase.appendingPathComponent(relativePath).standardizedFileURL
        guard candidate.path.hasPrefix(standardizedBase.path + "/") else { throw AttachmentError.unsafePath }
        return candidate
    }

    private static func atomicallyMove(_ source: URL, to destination: URL) throws {
        try FileManager.default.moveItem(at: source, to: destination)
    }

    private static func protect(_ file: URL) throws {
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: file.path)
    }

    private static func sha256(of url: URL) throws -> (Int64, String) {
        guard let stream = InputStream(url: url) else { throw AttachmentError.cannotRead }
        stream.open()
        defer { stream.close() }
        var hasher = SHA256()
        var total: Int64 = 0
        var buffer = [UInt8](repeating: 0, count: 64 * 1024)
        while true {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count < 0 { throw stream.streamError ?? AttachmentError.cannotRead }
            if count == 0 { break }
            total += Int64(count)
            guard total <= maxBytes else { throw AttachmentError.tooLarge }
            hasher.update(data: Data(buffer[0..<count]))
        }
        return (total, hasher.finalize().map { String(format: "%02x", $0) }.joined())
    }
}

private extension URL {
    func ensuringDirectory() throws -> URL {
        try FileManager.default.createDirectory(at: self, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        return self
    }
}
