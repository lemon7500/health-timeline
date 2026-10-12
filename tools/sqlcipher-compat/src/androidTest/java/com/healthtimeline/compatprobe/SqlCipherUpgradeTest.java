package com.healthtimeline.compatprobe;

import android.content.Context;
import android.database.Cursor;

import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import net.zetetic.database.sqlcipher.SQLiteDatabase;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SqlCipherUpgradeTest {
    @Test public void closedDatabase_survivesRealBinaryReplacement() throws Exception { verify("closed", false); }
    @Test public void committedWal_survivesKilledOldProcessAndBinaryReplacement() throws Exception { verify("wal", true); }

    private void verify(String name, boolean requireWal) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("candidate", BuildConfig.FLAVOR);
        System.loadLibrary("sqlcipher");
        assertTrue(new File(context.getFilesDir(), "seed-ready.json").isFile());
        assertEquals(ProbeData.PUBLIC_PASSWORD, context.getSharedPreferences("synthetic-key", Context.MODE_PRIVATE).getString("public-test-key", null));
        assertEquals(java.util.UUID.nameUUIDFromBytes("family_members:2".getBytes(StandardCharsets.UTF_8)).toString(),
            context.getSharedPreferences("synthetic-key", Context.MODE_PRIVATE).getString("member-uuid", null));
        File file = context.getDatabasePath(name + ".db");
        File walFile = new File(file.getPath() + "-wal");
        assertTrue(file.isFile());
        if (requireWal) assertTrue("Old committed WAL must exist before candidate opens", walFile.length() > 32);
        else {
            String expectedHash;
            try (FileInputStream input = new FileInputStream(new File(context.getFilesDir(), "closed.sha256"))) {
                expectedHash = ProbeData.readText(input);
            }
            assertEquals("Installing new binary must not alter closed DB bytes", expectedHash, ProbeData.hash(file));
        }
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] header = new byte[16];
            assertEquals(16, input.read(header));
            assertFalse("Fixture must be encrypted, not a plain SQLite migration fixture",
                new String(header, StandardCharsets.US_ASCII).startsWith("SQLite format 3"));
        }
        wrongKeyMustNotMutate(file, walFile);
        JSONObject expected;
        try (FileInputStream input = new FileInputStream(new File(context.getFilesDir(), name + ".snapshot.json"))) {
            expected = new JSONObject(ProbeData.readText(input));
        }
        assertTrue(expected.getString("sourceEngine").startsWith("4.18.0"));
        try (SupportSQLiteOpenHelper helper = ProbeData.helper(context, name + ".db")) {
            helper.setWriteAheadLoggingEnabled(true);
            SupportSQLiteDatabase db = helper.getWritableDatabase();
            assertTrue(ProbeData.scalar(db, "PRAGMA cipher_version").startsWith("4.19.0"));
            assertEquals("8", ProbeData.scalar(db, "PRAGMA user_version"));
            assertEquals("4096", ProbeData.scalar(db, "PRAGMA cipher_page_size"));
            assertEquals("256000", ProbeData.scalar(db, "PRAGMA kdf_iter"));
            assertEquals("HMAC_SHA512", ProbeData.scalar(db, "PRAGMA cipher_hmac_algorithm"));
            assertEquals("PBKDF2_HMAC_SHA512", ProbeData.scalar(db, "PRAGMA cipher_kdf_algorithm"));
            assertEquals("wal", ProbeData.scalar(db, "PRAGMA journal_mode"));
            assertEquals("2", ProbeData.scalar(db, "PRAGMA synchronous"));
            assertEquivalent(expected.getJSONObject("schema"), ProbeData.snapshot(context, db).getJSONObject("schema"));
            JSONObject current = ProbeData.snapshot(context, db);
            int[] counts = {2, 2, 3, 2, 2, 2, 3, 3, 4};
            assertEquals(9, current.getJSONObject("tables").length());
            for (int i = 0; i < ProbeData.TABLES.length; i++) {
                assertEquals("Synthetic coverage for " + ProbeData.TABLES[i], counts[i],
                    current.getJSONObject("tables").getJSONObject(ProbeData.TABLES[i]).getJSONArray("rows").length());
            }
            assertEquals(expected.getString("schemaSha256"), current.getString("schemaSha256"));
            assertEquivalent(expected.getJSONObject("tables"), current.getJSONObject("tables"));
            assertEquivalent(expected.getJSONObject("files"), current.getJSONObject("files"));
            assertIntegrity(db);
            db.beginTransaction();
            try {
                db.execSQL("UPDATE clinical_records SET notes = notes || ? WHERE id = 1", new Object[]{"\n候选加密库写入并重新打开验证"});
                db.execSQL("UPDATE medications SET instructions = ? WHERE id = 1", new Object[]{"候选加密库写入的虚构用药说明"});
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        try (SupportSQLiteOpenHelper reopened = ProbeData.helper(context, name + ".db")) {
            reopened.setWriteAheadLoggingEnabled(true);
            SupportSQLiteDatabase db = reopened.getWritableDatabase();
            assertTrue(ProbeData.scalar(db, "SELECT notes FROM clinical_records WHERE id = 1").endsWith("候选加密库写入并重新打开验证"));
            assertEquals("候选加密库写入的虚构用药说明", ProbeData.scalar(db, "SELECT instructions FROM medications WHERE id = 1"));
            assertIntegrity(db);
        }
    }

    /** Read-only/no-op corruption handler is deliberate: wrong-key probe cannot delete original fixtures. */
    private void wrongKeyMustNotMutate(File file, File wal) throws Exception {
        String before = ProbeData.hash(file);
        String walBefore = wal.isFile() ? ProbeData.hash(wal) : null;
        boolean rejected = false;
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getAbsolutePath(),
                "intentionally-wrong-public-test-key".getBytes(StandardCharsets.UTF_8), null,
                SQLiteDatabase.OPEN_READONLY, (database, failure) -> { }, null)) {
            try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM sqlite_master", (String[]) null)) { cursor.moveToFirst(); }
        } catch (android.database.sqlite.SQLiteException expected) { rejected = true; }
        assertTrue("Wrong key must be rejected", rejected);
        assertEquals(before, ProbeData.hash(file));
        if (walBefore != null) assertEquals(walBefore, ProbeData.hash(wal));
    }

    private void assertIntegrity(SupportSQLiteDatabase db) {
        assertEquals("ok", ProbeData.scalar(db, "PRAGMA quick_check"));
        try (Cursor cursor = db.query("PRAGMA foreign_key_check")) { assertEquals(0, cursor.getCount()); }
        try (Cursor cursor = db.query("PRAGMA cipher_integrity_check")) {
            // SQLCipher reports no rows on success; versions returning "ok" are also accepted.
            while (cursor.moveToNext()) assertEquals("ok", cursor.getString(0));
        }
    }

    private void assertEquivalent(JSONObject expected, JSONObject current) throws Exception {
        assertEquals(expected.length(), current.length());
        Iterator<String> keys = expected.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            assertTrue("Missing " + key, current.has(key));
            Object a = expected.get(key), b = current.get(key);
            if (a instanceof JSONObject) assertEquivalent((JSONObject) a, (JSONObject) b);
            else assertEquals("Value mismatch at " + key, a.toString(), b.toString());
        }
    }
}
