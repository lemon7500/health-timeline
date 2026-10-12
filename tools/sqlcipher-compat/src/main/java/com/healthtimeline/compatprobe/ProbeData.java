package com.healthtimeline.compatprobe;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;

import net.zetetic.database.sqlcipher.SupportOpenHelperFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** All names, medical content, UUIDs and the password here are PUBLIC SYNTHETIC fixtures. */
public final class ProbeData {
    public static final String PUBLIC_PASSWORD = "public-synthetic-sqlcipher-compat-key-not-for-real-data";
    public static final String SCHEMA_ASSET = "com.healthtimeline.app.data.AppDatabase/8.json";
    public static final String[] TABLES = {
        "family_members", "conditions", "clinical_records", "attachments", "follow_up_schedules",
        "follow_up_occurrences", "medications", "medication_schedules", "medication_logs"
    };
    public static final String CREATED = "2026-07-09T01:00:00Z";
    public static final String UPDATED = "2026-10-12T01:00:00Z";
    public static final String DELETED = "2026-10-11T01:00:00Z";
    private ProbeData() { }

    public static JSONObject schema(Context context) throws Exception {
        try (InputStream input = context.getAssets().open(SCHEMA_ASSET)) {
            return new JSONObject(readText(input)).getJSONObject("database");
        }
    }

    public static SupportSQLiteOpenHelper helper(Context context, String name) {
        return new SupportOpenHelperFactory(PUBLIC_PASSWORD.getBytes(StandardCharsets.UTF_8)).create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(new SupportSQLiteOpenHelper.Callback(8) {
                    @Override public void onConfigure(SupportSQLiteDatabase db) {
                        db.setForeignKeyConstraintsEnabled(true);
                    }
                    @Override public void onCreate(SupportSQLiteDatabase db) {
                        try {
                            JSONObject definition = schema(context);
                            JSONArray entities = definition.getJSONArray("entities");
                            for (int i = 0; i < entities.length(); i++) {
                                JSONObject entity = entities.getJSONObject(i);
                                String table = entity.getString("tableName");
                                db.execSQL(entity.getString("createSql").replace("${TABLE_NAME}", table));
                                JSONArray indices = entity.getJSONArray("indices");
                                for (int j = 0; j < indices.length(); j++) {
                                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("${TABLE_NAME}", table));
                                }
                            }
                            JSONArray setup = definition.getJSONArray("setupQueries");
                            for (int i = 0; i < setup.length(); i++) db.execSQL(setup.getString(i));
                        } catch (Exception failure) {
                            throw new IllegalStateException("Could not materialize the production v8 schema", failure);
                        }
                    }
                    @Override public void onUpgrade(SupportSQLiteDatabase db, int oldVersion, int newVersion) {
                        throw new IllegalStateException("Probe forbids schema migration: " + oldVersion + " -> " + newVersion);
                    }
                    @Override public void onOpen(SupportSQLiteDatabase db) {
                        db.execSQL("PRAGMA synchronous=FULL");
                    }
                }).build());
    }

    public static void seed(Context context, SupportSQLiteDatabase db, String marker) throws Exception {
        JSONObject schema = schema(context);
        Map<String, JSONObject> definitions = new LinkedHashMap<>();
        JSONArray entities = schema.getJSONArray("entities");
        for (int i = 0; i < entities.length(); i++) {
            JSONObject entity = entities.getJSONObject(i);
            definitions.put(entity.getString("tableName"), entity);
        }
        createAttachments(context);
        db.beginTransaction();
        try {
            insert(db, definitions, "family_members", 1, "name", "虚构甲", "nickname", "本人", "relationship", "本人");
            insert(db, definitions, "family_members", 2, "name", "虚构乙", "nickname", "母亲", "relationship", "母亲");
            insert(db, definitions, "conditions", 1, "memberId", 1, "name", "虚构鼻窦炎", "color", 4280391411L);
            insert(db, definitions, "conditions", 2, "memberId", 2, "name", "虚构乳腺复查", "color", 4283215696L);
            StringBuilder longNotes = new StringBuilder("原文记录：\n");
            for (int i = 0; i < 200; i++) longNotes.append("仅供兼容测试的虚构资料，不能作为医疗建议。编号：").append(i).append('。');
            insert(db, definitions, "clinical_records", 1, "conditionId", 1, "memberId", 1,
                "recordDate", "2026-07-09", "title", "虚构鼻窦炎复查 · " + marker, "stage", "CHECKUP",
                "symptoms", "虚构症状已缓解", "diagnosis", "虚构鼻窦炎", "treatment", "虚构治疗方案",
                "medicationNotes", "仅测试用药文字", "hospital", "虚构医院", "clinician", "虚构医生甲",
                "notes", longNotes.toString(), "dayOrder", 7);
            insert(db, definitions, "clinical_records", 2, "conditionId", 2, "memberId", 2,
                "recordDate", "2026-07-09", "title", "虚构乳腺定期复查", "stage", "AFTER_VISIT", "dayOrder", 8);
            insert(db, definitions, "clinical_records", 3, "conditionId", 1, "memberId", 1,
                "recordDate", "2026-07-08", "title", "虚构鼻窦炎回收站病历", "stage", "BEFORE_VISIT",
                "dayOrder", 3, "deletedAt", DELETED);
            File live = new File(context.getFilesDir(), "attachments/synthetic-live.png");
            File recycled = new File(context.getFilesDir(), "attachments/synthetic-recycled.pdf");
            insert(db, definitions, "attachments", 1, "recordId", 1, "kind", "IMAGE",
                "displayName", "虚构图片.png", "mimeType", "image/png",
                "relativePath", "attachments/synthetic-live.png", "sizeBytes", live.length(), "sha256", hash(live));
            insert(db, definitions, "attachments", 2, "recordId", 3, "kind", "PDF",
                "displayName", "虚构回收报告.pdf", "mimeType", "application/pdf",
                "relativePath", "attachments/synthetic-recycled.pdf", "sizeBytes", recycled.length(),
                "sha256", hash(recycled), "deletedAt", DELETED);
            insert(db, definitions, "follow_up_schedules", 1, "conditionId", 2, "memberId", 2,
                "title", "虚构乳腺每三个月复查", "recurrenceType", "EVERY_N_MONTHS", "interval", 3,
                "anchorDate", "2026-07-09", "anchorDayOfMonth", 9, "reminderTime", "08:35",
                "leadDays", 3, "nextDueDate", "2026-10-09", "enabled", 1);
            insert(db, definitions, "follow_up_schedules", 2, "conditionId", 1, "memberId", 1,
                "title", "虚构每周四复查", "recurrenceType", "EVERY_N_WEEKS", "interval", 1,
                "anchorDate", "2026-07-09", "anchorDayOfMonth", 9, "weekday", 4,
                "reminderTime", "09:05", "leadDays", 0, "nextDueDate", "2026-10-15", "enabled", 1);
            insert(db, definitions, "follow_up_occurrences", 1, "scheduleId", 1,
                "dueDate", "2026-07-09", "status", "DONE", "completedAt", "2026-07-09T10:00:00Z");
            insert(db, definitions, "follow_up_occurrences", 2, "scheduleId", 2,
                "dueDate", "2026-10-08", "status", "SKIPPED");
            insert(db, definitions, "medications", 1, "conditionId", 1, "memberId", 1,
                "name", "虚构药甲", "doseAmount", "2", "doseUnit", "片", "instructions", "虚构服用说明",
                "startDate", "2026-07-09", "mode", "SCHEDULED", "archived", 0);
            insert(db, definitions, "medications", 2, "conditionId", 2, "memberId", 2,
                "name", "虚构药乙（已结束）", "doseAmount", "5", "doseUnit", "毫升",
                "startDate", "2026-07-09", "endDate", "2026-10-10", "mode", "SCHEDULED",
                "archived", 1, "endedAt", "2026-10-10T12:35:00Z", "archivedPreviousEndDate", "2026-12-31");
            insert(db, definitions, "medications", 3, "conditionId", 1, "memberId", 1,
                "name", "虚构按需回收药", "doseAmount", "1", "doseUnit", "片",
                "startDate", "2026-07-09", "mode", "AS_NEEDED", "deletedAt", DELETED);
            insert(db, definitions, "medication_schedules", 1, "medicationId", 1,
                "localTime", "08:05", "enabled", 1, "effectiveFrom", "2026-07-09", "effectiveTo", "2026-08-31",
                "doseAmountSnapshot", "1", "doseUnitSnapshot", "片");
            insert(db, definitions, "medication_schedules", 2, "medicationId", 1,
                "localTime", "20:35", "enabled", 1, "effectiveFrom", "2026-09-01", "effectiveTo", null,
                "doseAmountSnapshot", "2", "doseUnitSnapshot", "片");
            insert(db, definitions, "medication_schedules", 3, "medicationId", 2,
                "localTime", "09:10", "enabled", 0, "effectiveFrom", "2026-07-09", "effectiveTo", "2026-10-10",
                "doseAmountSnapshot", "5", "doseUnitSnapshot", "毫升", "pausedByCourseEnd", 1);
            insert(db, definitions, "medication_logs", 1, "medicationId", 1, "scheduleId", 1,
                "scheduledAt", "2026-07-09T08:05:00", "actualAt", "2026-07-09T08:10:00", "status", "TAKEN",
                "doseAmountSnapshot", "1", "doseUnitSnapshot", "片");
            insert(db, definitions, "medication_logs", 2, "medicationId", 1, "scheduleId", 2,
                "scheduledAt", "2026-10-10T20:35:00", "status", "SKIPPED", "doseAmountSnapshot", "2", "doseUnitSnapshot", "片");
            insert(db, definitions, "medication_logs", 3, "medicationId", 2, "scheduleId", 3,
                "scheduledAt", "2026-10-10T09:10:00", "actualAt", "2026-10-10T09:15:00", "status", "TAKEN",
                "doseAmountSnapshot", "5", "doseUnitSnapshot", "毫升");
            insert(db, definitions, "medication_logs", 4, "medicationId", 3,
                "scheduledAt", "2026-10-09T10:05:00", "actualAt", "2026-10-09T10:05:00", "status", "TAKEN",
                "doseAmountSnapshot", "1", "doseUnitSnapshot", "片");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static void insert(SupportSQLiteDatabase db, Map<String, JSONObject> definitions, String table,
                               int id, Object... overrides) throws Exception {
        JSONArray fields = definitions.get(table).getJSONArray("fields");
        ContentValues values = new ContentValues();
        for (int i = 0; i < fields.length(); i++) {
            JSONObject field = fields.getJSONObject(i);
            String column = field.getString("columnName");
            if (!field.optBoolean("notNull")) values.putNull(column);
            else if (field.getString("affinity").equals("INTEGER")) values.put(column, 0L);
            else values.put(column, "");
        }
        values.put("id", id);
        values.put("uuid", java.util.UUID.nameUUIDFromBytes((table + ":" + id).getBytes(StandardCharsets.UTF_8)).toString());
        if (values.containsKey("createdAt")) values.put("createdAt", CREATED);
        if (values.containsKey("updatedAt")) values.put("updatedAt", UPDATED);
        for (int i = 0; i < overrides.length; i += 2) {
            String field = (String) overrides[i];
            Object value = overrides[i + 1];
            if (value == null) values.putNull(field);
            else if (value instanceof Number) values.put(field, ((Number) value).longValue());
            else values.put(field, value.toString());
        }
        long result = db.insert(table, android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, values);
        if (result != id) throw new IllegalStateException("Unexpected synthetic row id in " + table);
    }

    public static JSONObject snapshot(Context context, SupportSQLiteDatabase db) throws Exception {
        JSONObject result = new JSONObject();
        result.put("sourceEngine", scalar(db, "PRAGMA cipher_version"));
        result.put("schemaVersion", 8);
        try (InputStream input = context.getAssets().open(SCHEMA_ASSET)) {
            result.put("schemaSha256", hash(input));
        }
        result.put("schema", query(db,
            "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name"));
        JSONObject tables = new JSONObject();
        for (String table : TABLES) tables.put(table, query(db, "SELECT * FROM `" + table + "` ORDER BY id"));
        result.put("tables", tables);
        JSONObject files = new JSONObject();
        files.put("attachments/synthetic-live.png", hash(new File(context.getFilesDir(), "attachments/synthetic-live.png")));
        files.put("attachments/synthetic-recycled.pdf", hash(new File(context.getFilesDir(), "attachments/synthetic-recycled.pdf")));
        result.put("files", files);
        return result;
    }

    public static JSONObject query(SupportSQLiteDatabase db, String sql) throws Exception {
        JSONObject data = new JSONObject();
        JSONArray columns = new JSONArray();
        JSONArray rows = new JSONArray();
        try (Cursor cursor = db.query(sql)) {
            for (String name : cursor.getColumnNames()) columns.put(name);
            while (cursor.moveToNext()) {
                JSONArray row = new JSONArray();
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    row.put(cursor.isNull(i) ? JSONObject.NULL : cursor.getString(i));
                }
                rows.put(row);
            }
        }
        data.put("columns", columns);
        data.put("rows", rows);
        return data;
    }

    public static String scalar(SupportSQLiteDatabase db, String sql) {
        try (Cursor cursor = db.query(sql)) {
            if (!cursor.moveToFirst()) throw new IllegalStateException("No result: " + sql);
            return cursor.getString(0);
        }
    }

    public static String readText(InputStream input) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) out.write(buffer, 0, count);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    public static String hash(File file) throws Exception {
        try (InputStream input = new FileInputStream(file)) { return hash(input); }
    }

    public static String hash(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        StringBuilder text = new StringBuilder();
        for (byte b : digest.digest()) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return text.toString();
    }

    public static void write(File file, String value) throws Exception {
        write(file, value.getBytes(StandardCharsets.UTF_8));
    }

    public static void write(File file, byte[] value) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("Could not create fixture directory");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value);
            output.getFD().sync();
        }
    }

    private static void createAttachments(Context context) throws Exception {
        // Public one-pixel PNG; contains no patient or camera metadata.
        write(new File(context.getFilesDir(), "attachments/synthetic-live.png"), android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII=",
            android.util.Base64.DEFAULT));
        write(new File(context.getFilesDir(), "attachments/synthetic-recycled.pdf"), "%PDF-1.4\n% PUBLIC SYNTHETIC RECYCLED ATTACHMENT; not a clinical document\n%%EOF\n");
    }
}
