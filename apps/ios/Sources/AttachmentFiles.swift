import CryptoKit
import Foundation
import UniformTypeIdentifiers

enum AttachmentError: LocalizedError {
    case unsupported, tooLarge, cannotRead
    var errorDescription: String? {
        switch self { case .unsupported: return "只支持图片或 PDF"; case .tooLarge: return "单个附件不能超过 100 MB"; case .cannotRead: return "无法读取所选文件" }
    }
}

enum AttachmentFiles {
    static func importFile(recordId: UUID, source: URL) throws -> Attachment {
        let accessing = source.startAccessingSecurityScopedResource()
        defer { if accessing { source.stopAccessingSecurityScopedResource() } }
        let values = try source.resourceValues(forKeys: [.fileSizeKey, .contentTypeKey, .nameKey])
        guard let size = values.fileSize, size >= 0, size <= 100 * 1024 * 1024 else { throw AttachmentError.tooLarge }
        let type = values.contentType
        let kind: String
        if type?.conforms(to: .pdf) == true { kind = "PDF" }
        else if type?.conforms(to: .image) == true { kind = "IMAGE" }
        else { throw AttachmentError.unsupported }
        let id = UUID()
        let directory = try root().appendingPathComponent(recordId.uuidString, isDirectory: true).ensuringDirectory()
        let destination = directory.appendingPathComponent(id.uuidString)
        let temporary = directory.appendingPathComponent(".\(id.uuidString).tmp")
        do {
            try FileManager.default.copyItem(at: source, to: temporary)
            let (copiedSize, digest) = try sha256(of: temporary)
            guard copiedSize == Int64(size) else { throw AttachmentError.cannotRead }
            try FileManager.default.moveItem(at: temporary, to: destination)
            try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: destination.path)
            return Attachment(id: id, recordId: recordId, kind: kind, displayName: values.name ?? source.lastPathComponent,
                              mimeType: type?.preferredMIMEType ?? "application/octet-stream",
                              relativePath: "\(recordId.uuidString)/\(id.uuidString)", sizeBytes: Int64(size), sha256: digest)
        } catch {
            try? FileManager.default.removeItem(at: temporary)
            try? FileManager.default.removeItem(at: destination)
            throw error
        }
    }

    static func url(_ attachment: Attachment) throws -> URL { try root().appendingPathComponent(attachment.relativePath) }
    static func delete(_ attachment: Attachment) throws { try FileManager.default.removeItem(at: url(attachment)) }

    private static func root() throws -> URL {
        let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        return base.appendingPathComponent("HealthTimeline/attachments", isDirectory: true)
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
            guard total <= 100 * 1024 * 1024 else { throw AttachmentError.tooLarge }
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
