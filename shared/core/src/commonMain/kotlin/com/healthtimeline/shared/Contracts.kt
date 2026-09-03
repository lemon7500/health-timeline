package com.healthtimeline.shared

enum class MergeChoice { KEEP_LOCAL, USE_IMPORTED }

data class MergeConflict(
    val entityType: String,
    val uuid: String,
    val localUpdatedAt: String,
    val importedUpdatedAt: String,
    val importedIsNewer: Boolean
)

data class BackupImportPreview(
    val additions: Int,
    val updates: Int,
    val duplicates: Int,
    val conflicts: List<MergeConflict>,
    val legacyReplacementOnly: Boolean = false
)

interface PlatformStore {
    suspend fun snapshot(sourcePlatform: String): PortableSnapshot
    suspend fun applyMergedSnapshot(snapshot: PortableSnapshot)
}

interface PlatformAttachmentStore {
    suspend fun stage(attachment: PortableAttachment, bytes: ByteArray)
    suspend fun verify(attachment: PortableAttachment): Boolean
    suspend fun commitStaged()
    suspend fun discardStaged()
}

interface ReminderScheduler {
    suspend fun rebuild(snapshot: PortableSnapshot)
    suspend fun cancelAll()
}

interface SecureKeyStore {
    suspend fun getOrCreateDatabaseKey(): ByteArray
}
