package com.healthtimeline.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FamilyMemberDao {
    @Query("SELECT * FROM family_members ORDER BY archived, id") fun observeAll(): Flow<List<FamilyMemberEntity>>
    @Query("SELECT * FROM family_members ORDER BY id") suspend fun all(): List<FamilyMemberEntity>
    @Query("SELECT * FROM family_members WHERE id = :id") suspend fun byId(id: Long): FamilyMemberEntity?
    @Query("SELECT * FROM family_members WHERE uuid = :uuid") suspend fun byUuid(uuid: String): FamilyMemberEntity?
    @Query("SELECT COUNT(*) FROM family_members WHERE archived = 0") suspend fun activeCount(): Int
    @Query(
        "SELECT (SELECT COUNT(*) FROM conditions WHERE memberId = :id) + " +
            "(SELECT COUNT(*) FROM clinical_records WHERE memberId = :id) + " +
            "(SELECT COUNT(*) FROM follow_up_schedules WHERE memberId = :id) + " +
            "(SELECT COUNT(*) FROM medications WHERE memberId = :id)"
    )
    suspend fun referenceCount(id: Long): Int
    @Insert suspend fun insert(value: FamilyMemberEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(values: List<FamilyMemberEntity>)
    @Update suspend fun update(value: FamilyMemberEntity)
    @Query("DELETE FROM family_members WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("DELETE FROM family_members") suspend fun clear()
}

@Dao
interface ConditionDao {
    @Query("SELECT * FROM conditions ORDER BY archived, name") fun observeAll(): Flow<List<ConditionEntity>>
    @Query("SELECT * FROM conditions WHERE memberId = :memberId ORDER BY archived, name") fun observeForMember(memberId: Long): Flow<List<ConditionEntity>>
    @Query("SELECT * FROM conditions WHERE id = :id") suspend fun byId(id: Long): ConditionEntity?
    @Query("SELECT * FROM conditions ORDER BY id") suspend fun all(): List<ConditionEntity>
    @Insert suspend fun insert(value: ConditionEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(values: List<ConditionEntity>)
    @Update suspend fun update(value: ConditionEntity)
    @Query("UPDATE conditions SET archived = 1 WHERE id = :id") suspend fun archive(id: Long)
    @Query("DELETE FROM conditions") suspend fun clear()
}

@Dao
interface ClinicalRecordDao {
    @Query("SELECT * FROM clinical_records WHERE deletedAt IS NULL ORDER BY recordDate DESC, dayOrder ASC, id ASC") fun observeAll(): Flow<List<ClinicalRecordEntity>>
    @Query("SELECT * FROM clinical_records WHERE memberId = :memberId AND deletedAt IS NULL ORDER BY recordDate DESC, dayOrder ASC, id ASC") fun observeForMember(memberId: Long): Flow<List<ClinicalRecordEntity>>
    @Query("SELECT * FROM clinical_records WHERE memberId = :memberId AND deletedAt IS NOT NULL ORDER BY deletedAt DESC, id DESC") fun observeDeletedForMember(memberId: Long): Flow<List<ClinicalRecordEntity>>
    @Query("SELECT * FROM clinical_records WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff ORDER BY deletedAt, id") suspend fun deletedBefore(cutoff: String): List<ClinicalRecordEntity>
    @Query("SELECT * FROM clinical_records WHERE id = :id") suspend fun byId(id: Long): ClinicalRecordEntity?
    @Query("SELECT * FROM clinical_records ORDER BY id") suspend fun all(): List<ClinicalRecordEntity>
    @Insert suspend fun insert(value: ClinicalRecordEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(values: List<ClinicalRecordEntity>)
    @Update suspend fun update(value: ClinicalRecordEntity)
    @Query("UPDATE clinical_records SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :id AND deletedAt IS NULL") suspend fun moveToTrash(id: Long, deletedAt: String): Int
    @Query("UPDATE clinical_records SET deletedAt = NULL, updatedAt = :updatedAt WHERE id = :id AND deletedAt IS NOT NULL") suspend fun restore(id: Long, updatedAt: String): Int
    @Query("DELETE FROM clinical_records WHERE id = :id AND deletedAt IS NOT NULL") suspend fun hardDelete(id: Long): Int
    @Query("SELECT COALESCE(MAX(dayOrder), -1) FROM clinical_records WHERE memberId = :memberId AND recordDate = :recordDate AND deletedAt IS NULL")
    suspend fun maxDayOrder(memberId: Long, recordDate: String): Long
    @Query("DELETE FROM clinical_records") suspend fun clear()
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE deletedAt IS NULL ORDER BY createdAt") fun observeAll(): Flow<List<AttachmentEntity>>
    @Query("SELECT a.* FROM attachments a INNER JOIN clinical_records r ON r.id = a.recordId WHERE r.memberId = :memberId AND r.deletedAt IS NULL AND a.deletedAt IS NULL ORDER BY a.createdAt")
    fun observeForMember(memberId: Long): Flow<List<AttachmentEntity>>
    @Query("SELECT * FROM attachments WHERE recordId = :recordId AND deletedAt IS NULL ORDER BY createdAt") fun observeForRecord(recordId: Long): Flow<List<AttachmentEntity>>
    @Query("SELECT a.* FROM attachments a INNER JOIN clinical_records r ON r.id = a.recordId WHERE r.memberId = :memberId AND r.deletedAt IS NULL AND a.deletedAt IS NOT NULL ORDER BY a.deletedAt DESC, a.id DESC")
    fun observeDeletedForMember(memberId: Long): Flow<List<AttachmentEntity>>
    @Query("SELECT a.* FROM attachments a INNER JOIN clinical_records r ON r.id = a.recordId WHERE r.deletedAt IS NULL AND a.deletedAt IS NOT NULL AND a.deletedAt <= :cutoff ORDER BY a.deletedAt, a.id")
    suspend fun individuallyDeletedBefore(cutoff: String): List<AttachmentEntity>
    @Query("SELECT * FROM attachments WHERE recordId = :recordId ORDER BY id") suspend fun forRecord(recordId: Long): List<AttachmentEntity>
    @Query("SELECT * FROM attachments WHERE id = :id LIMIT 1") suspend fun byId(id: Long): AttachmentEntity?
    @Query("SELECT * FROM attachments WHERE uuid = :uuid LIMIT 1") suspend fun byUuid(uuid: String): AttachmentEntity?
    @Query("SELECT * FROM attachments ORDER BY id") suspend fun all(): List<AttachmentEntity>
    @Insert suspend fun insert(value: AttachmentEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(values: List<AttachmentEntity>)
    @Query("UPDATE attachments SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :id AND deletedAt IS NULL") suspend fun moveToTrash(id: Long, deletedAt: String): Int
    @Query("UPDATE attachments SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE recordId = :recordId AND deletedAt IS NULL") suspend fun moveActiveForRecordToTrash(recordId: Long, deletedAt: String): Int
    @Query("UPDATE attachments SET deletedAt = NULL, updatedAt = :updatedAt WHERE id = :id AND deletedAt IS NOT NULL") suspend fun restore(id: Long, updatedAt: String): Int
    @Query("UPDATE attachments SET deletedAt = NULL, updatedAt = :updatedAt WHERE recordId = :recordId AND deletedAt = :recordDeletedAt") suspend fun restoreWithRecord(recordId: Long, recordDeletedAt: String, updatedAt: String): Int
    @Query("DELETE FROM attachments WHERE id = :id") suspend fun deleteById(id: Long): Int
    @Query("DELETE FROM attachments") suspend fun clear()
}

@Dao
interface FollowUpDao {
    @Query("SELECT * FROM follow_up_schedules WHERE deletedAt IS NULL ORDER BY enabled DESC, nextDueDate") fun observeSchedules(): Flow<List<FollowUpScheduleEntity>>
    @Query("SELECT * FROM follow_up_schedules WHERE memberId = :memberId AND deletedAt IS NULL ORDER BY enabled DESC, nextDueDate") fun observeSchedulesForMember(memberId: Long): Flow<List<FollowUpScheduleEntity>>
    @Query("SELECT * FROM follow_up_schedules WHERE memberId = :memberId AND deletedAt IS NOT NULL ORDER BY deletedAt DESC, id DESC") fun observeDeletedForMember(memberId: Long): Flow<List<FollowUpScheduleEntity>>
    @Query("SELECT * FROM follow_up_schedules WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff ORDER BY deletedAt, id") suspend fun deletedBefore(cutoff: String): List<FollowUpScheduleEntity>
    @Query("SELECT s.* FROM follow_up_schedules s INNER JOIN family_members m ON m.id = s.memberId WHERE s.enabled = 1 AND s.deletedAt IS NULL AND m.archived = 0 ORDER BY s.nextDueDate")
    suspend fun enabledSchedules(): List<FollowUpScheduleEntity>
    @Query("SELECT * FROM follow_up_schedules WHERE id = :id") suspend fun scheduleById(id: Long): FollowUpScheduleEntity?
    @Query("SELECT * FROM follow_up_schedules ORDER BY id") suspend fun allSchedules(): List<FollowUpScheduleEntity>
    @Insert suspend fun insertSchedule(value: FollowUpScheduleEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSchedules(values: List<FollowUpScheduleEntity>)
    @Update suspend fun updateSchedule(value: FollowUpScheduleEntity)
    @Query("UPDATE follow_up_schedules SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :id AND deletedAt IS NULL") suspend fun moveToTrash(id: Long, deletedAt: String): Int
    @Query("UPDATE follow_up_schedules SET deletedAt = NULL, updatedAt = :updatedAt WHERE id = :id AND deletedAt IS NOT NULL") suspend fun restore(id: Long, updatedAt: String): Int
    @Query("DELETE FROM follow_up_schedules WHERE id = :id AND deletedAt IS NOT NULL") suspend fun hardDelete(id: Long): Int

    @Query("SELECT * FROM follow_up_occurrences ORDER BY dueDate DESC") fun observeOccurrences(): Flow<List<FollowUpOccurrenceEntity>>
    @Query("SELECT o.* FROM follow_up_occurrences o INNER JOIN follow_up_schedules s ON s.id = o.scheduleId WHERE s.memberId = :memberId AND s.deletedAt IS NULL ORDER BY o.dueDate DESC")
    fun observeOccurrencesForMember(memberId: Long): Flow<List<FollowUpOccurrenceEntity>>
    @Query("SELECT * FROM follow_up_occurrences WHERE id = :id") suspend fun occurrenceById(id: Long): FollowUpOccurrenceEntity?
    @Query("SELECT * FROM follow_up_occurrences ORDER BY id") suspend fun allOccurrences(): List<FollowUpOccurrenceEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertOccurrence(value: FollowUpOccurrenceEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertOccurrences(values: List<FollowUpOccurrenceEntity>)
    @Query("UPDATE follow_up_occurrences SET status = :status, completedAt = :completedAt WHERE id = :id AND status = 'PENDING'")
    suspend fun markOccurrence(id: Long, status: String, completedAt: String): Int
    @Query("DELETE FROM follow_up_occurrences") suspend fun clearOccurrences()
    @Query("DELETE FROM follow_up_schedules") suspend fun clearSchedules()
}

@Dao
interface MedicationDao {
    @Query("SELECT * FROM medications WHERE deletedAt IS NULL ORDER BY archived, startDate DESC") fun observeMedications(): Flow<List<MedicationEntity>>
    @Query("SELECT * FROM medications WHERE memberId = :memberId AND deletedAt IS NULL ORDER BY archived, startDate DESC") fun observeMedicationsForMember(memberId: Long): Flow<List<MedicationEntity>>
    @Query("SELECT * FROM medications WHERE memberId = :memberId AND deletedAt IS NOT NULL ORDER BY deletedAt DESC, id DESC") fun observeDeletedForMember(memberId: Long): Flow<List<MedicationEntity>>
    @Query("SELECT * FROM medications WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff ORDER BY deletedAt, id") suspend fun deletedBefore(cutoff: String): List<MedicationEntity>
    @Query("SELECT * FROM medications WHERE id = :id") suspend fun medicationById(id: Long): MedicationEntity?
    @Query("SELECT * FROM medications ORDER BY id") suspend fun allMedications(): List<MedicationEntity>
    @Insert suspend fun insertMedication(value: MedicationEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMedications(values: List<MedicationEntity>)
    @Update suspend fun updateMedication(value: MedicationEntity)
    @Query("UPDATE medications SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :id AND deletedAt IS NULL") suspend fun moveToTrash(id: Long, deletedAt: String): Int
    @Query("UPDATE medications SET deletedAt = NULL, updatedAt = :updatedAt WHERE id = :id AND deletedAt IS NOT NULL") suspend fun restoreDeleted(id: Long, updatedAt: String): Int
    @Query("DELETE FROM medications WHERE id = :id AND deletedAt IS NOT NULL") suspend fun hardDelete(id: Long): Int
    @Query("UPDATE medications SET archivedPreviousEndDate = endDate, archived = 1, endDate = :endDate, endedAt = :endedAt, updatedAt = :endedAt WHERE id = :id AND archived = 0")
    suspend fun archiveMedication(id: Long, endDate: String, endedAt: String): Int
    @Query("UPDATE medications SET archived = 0, endDate = archivedPreviousEndDate, archivedPreviousEndDate = NULL, endedAt = NULL, updatedAt = :updatedAt WHERE id = :id AND archived = 1")
    suspend fun restoreMedication(id: Long, updatedAt: String): Int

    @Query("SELECT * FROM medication_schedules ORDER BY localTime") fun observeSchedules(): Flow<List<MedicationScheduleEntity>>
    @Query("SELECT s.* FROM medication_schedules s INNER JOIN medications m ON m.id = s.medicationId WHERE m.memberId = :memberId AND m.deletedAt IS NULL ORDER BY s.localTime")
    fun observeSchedulesForMember(memberId: Long): Flow<List<MedicationScheduleEntity>>
    @Query("SELECT s.* FROM medication_schedules s INNER JOIN medications m ON m.id = s.medicationId INNER JOIN family_members f ON f.id = m.memberId WHERE s.enabled = 1 AND (s.effectiveTo IS NULL OR s.effectiveTo >= :onDate) AND m.archived = 0 AND m.deletedAt IS NULL AND f.archived = 0 ORDER BY s.localTime")
    suspend fun enabledSchedules(onDate: String): List<MedicationScheduleEntity>
    @Query("SELECT s.* FROM medication_schedules s INNER JOIN medications m ON m.id = s.medicationId WHERE m.memberId = :memberId AND m.deletedAt IS NULL AND s.effectiveFrom <= :endDate AND (s.effectiveTo IS NULL OR s.effectiveTo >= :startDate) ORDER BY s.effectiveFrom, s.localTime")
    fun observeSchedulesForMemberBetween(memberId: Long, startDate: String, endDate: String): Flow<List<MedicationScheduleEntity>>
    @Query("SELECT * FROM medication_schedules WHERE id = :id") suspend fun scheduleById(id: Long): MedicationScheduleEntity?
    @Query("SELECT * FROM medication_schedules WHERE medicationId = :medicationId ORDER BY id") suspend fun schedulesForMedication(medicationId: Long): List<MedicationScheduleEntity>
    @Query("SELECT * FROM medication_schedules ORDER BY id") suspend fun allSchedules(): List<MedicationScheduleEntity>
    @Insert suspend fun insertSchedule(value: MedicationScheduleEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSchedules(values: List<MedicationScheduleEntity>)
    @Update suspend fun updateSchedule(value: MedicationScheduleEntity)
    @Query("DELETE FROM medication_schedules WHERE id = :id") suspend fun deleteScheduleById(id: Long): Int
    @Query("DELETE FROM medication_schedules WHERE medicationId = :medicationId") suspend fun deleteSchedulesForMedication(medicationId: Long)

    @Query("SELECT * FROM medication_logs ORDER BY scheduledAt DESC") fun observeLogs(): Flow<List<MedicationLogEntity>>
    @Query("SELECT l.* FROM medication_logs l INNER JOIN medications m ON m.id = l.medicationId WHERE m.memberId = :memberId AND m.deletedAt IS NULL ORDER BY l.scheduledAt DESC")
    fun observeLogsForMember(memberId: Long): Flow<List<MedicationLogEntity>>
    @Query("SELECT l.* FROM medication_logs l INNER JOIN medications m ON m.id = l.medicationId WHERE m.memberId = :memberId AND m.deletedAt IS NULL AND l.scheduledAt >= :startAt AND l.scheduledAt < :endAt ORDER BY l.scheduledAt")
    fun observeLogsForMemberBetween(memberId: Long, startAt: String, endAt: String): Flow<List<MedicationLogEntity>>
    @Query("SELECT * FROM medication_logs WHERE id = :id") suspend fun logById(id: Long): MedicationLogEntity?
    @Query("SELECT COUNT(*) FROM medication_logs WHERE medicationId = :medicationId AND scheduledAt >= :startAt AND scheduledAt < :endAt")
    suspend fun logCountBetween(medicationId: Long, startAt: String, endAt: String): Int
    @Query("SELECT * FROM medication_logs ORDER BY id") suspend fun allLogs(): List<MedicationLogEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertLog(value: MedicationLogEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLogs(values: List<MedicationLogEntity>)
    @Update suspend fun updateLog(value: MedicationLogEntity): Int
    @Query("DELETE FROM medication_logs") suspend fun clearLogs()
    @Query("DELETE FROM medication_schedules") suspend fun clearSchedules()
    @Query("DELETE FROM medications") suspend fun clearMedications()
}
