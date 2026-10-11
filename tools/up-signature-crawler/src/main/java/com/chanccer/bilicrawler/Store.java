package com.chanccer.bilicrawler;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

final class Store implements AutoCloseable {
    record Cursor(Api.Source source, int page) {}
    record Stats(long discovered, long saved, long emptySignatures, long failed) {}
    private final Connection db;

    Store(Path file) throws Exception {
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (var stmt = db.createStatement()) {
            stmt.execute("PRAGMA busy_timeout=5000");
            // Rollback journaling avoids cross-OS shared-memory WAL issues on Docker bind mounts.
            stmt.execute("PRAGMA journal_mode=DELETE");
            stmt.execute("PRAGMA foreign_keys=ON");
            try (var rs = stmt.executeQuery("PRAGMA user_version")) {
                rs.next();
                if (rs.getInt(1) > 1) throw new SQLException("Unsupported database schema version");
            }
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS creators (
                      uid INTEGER PRIMARY KEY, name TEXT NOT NULL, sign TEXT NOT NULL,
                      face TEXT, followers INTEGER, archive_count INTEGER,
                      space_url TEXT NOT NULL, discovered_at TEXT NOT NULL, collected_at TEXT NOT NULL)
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS tasks (
                      uid INTEGER PRIMARY KEY, status TEXT NOT NULL DEFAULT 'pending',
                      discovered_at TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT)
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS creator_sources (
                      uid INTEGER NOT NULL REFERENCES tasks(uid), source TEXT NOT NULL,
                      PRIMARY KEY(uid,source))
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS discovery (
                      source TEXT PRIMARY KEY, kind TEXT NOT NULL, value TEXT NOT NULL,
                      max_pages INTEGER NOT NULL, priority INTEGER NOT NULL,
                      next_page INTEGER NOT NULL DEFAULT 1, exhausted INTEGER NOT NULL DEFAULT 0,
                      last_error TEXT)
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS discovery_pages (
                      source TEXT NOT NULL REFERENCES discovery(source), fingerprint TEXT NOT NULL,
                      PRIMARY KEY(source,fingerprint))
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS tasks_status_uid ON tasks(status,uid)");
            stmt.execute("PRAGMA user_version=1");
        } catch (Exception e) {
            db.close();
            throw e;
        }
    }

    void configure(List<Api.Source> sources) throws SQLException {
        // Old sources remain as provenance, but are inactive unless supplied for this run.
        transaction(() -> {
            try (var stmt = db.createStatement()) { stmt.executeUpdate("UPDATE discovery SET priority=-1"); }
            try (var stmt = db.prepareStatement("""
                    INSERT INTO discovery(source,kind,value,max_pages,priority) VALUES(?,?,?,?,?)
                    ON CONFLICT(source) DO UPDATE SET max_pages=excluded.max_pages, priority=excluded.priority
                    """)) {
                for (int i = 0; i < sources.size(); i++) {
                    Api.Source source = sources.get(i);
                    stmt.setString(1, source.key()); stmt.setString(2, source.kind()); stmt.setString(3, source.value());
                    stmt.setInt(4, source.maxPages()); stmt.setInt(5, i); stmt.addBatch();
                }
                stmt.executeBatch();
            }
        });
    }

    Cursor nextSource() throws SQLException {
        try (var stmt = db.createStatement(); var rs = stmt.executeQuery("""
                SELECT source,kind,value,max_pages,next_page FROM discovery
                WHERE priority>=0 AND exhausted=0 AND next_page<=max_pages
                ORDER BY next_page,priority LIMIT 1
                """)) {
            return rs.next() ? new Cursor(new Api.Source(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)), rs.getInt(5)) : null;
        }
    }

    void discovered(Cursor cursor, Api.Page page) throws SQLException {
        transaction(() -> {
            boolean repeated;
            try (var stmt = db.prepareStatement("SELECT 1 FROM discovery_pages WHERE source=? AND fingerprint=?")) {
                stmt.setString(1, cursor.source().key()); stmt.setString(2, page.fingerprint());
                try (var rs = stmt.executeQuery()) { repeated = rs.next(); }
            }
            try (var task = db.prepareStatement("INSERT OR IGNORE INTO tasks(uid,discovered_at) VALUES(?,?)");
                 var provenance = db.prepareStatement("INSERT OR IGNORE INTO creator_sources(uid,source) VALUES(?,?)")) {
                String now = Instant.now().toString();
                for (var author : page.authors()) {
                    if (author.uid() <= 0) continue;
                    task.setLong(1, author.uid()); task.setString(2, now); task.addBatch();
                    provenance.setLong(1, author.uid()); provenance.setString(2, author.source()); provenance.addBatch();
                }
                task.executeBatch(); provenance.executeBatch();
            }
            try (var stmt = db.prepareStatement("INSERT OR IGNORE INTO discovery_pages(source,fingerprint) VALUES(?,?)")) {
                stmt.setString(1, cursor.source().key()); stmt.setString(2, page.fingerprint()); stmt.executeUpdate();
            }
            try (var stmt = db.prepareStatement("UPDATE discovery SET next_page=?, exhausted=?,last_error=NULL WHERE source=?")) {
                stmt.setInt(1, cursor.page() + 1); stmt.setInt(2, page.last() || page.authors().isEmpty() || repeated ? 1 : 0);
                stmt.setString(3, cursor.source().key()); stmt.executeUpdate();
            }
        });
    }

    Long nextUid() throws SQLException {
        try (var stmt = db.createStatement(); var rs = stmt.executeQuery("SELECT uid FROM tasks WHERE status='pending' ORDER BY discovered_at,uid LIMIT 1")) {
            return rs.next() ? rs.getLong(1) : null;
        }
    }

    void save(Api.Profile profile) throws SQLException {
        transaction(() -> {
            try (var stmt = db.prepareStatement("""
                    INSERT INTO creators(uid,name,sign,face,followers,archive_count,space_url,discovered_at,collected_at)
                    SELECT ?,?,?,?,?,?,?,discovered_at,? FROM tasks WHERE uid=?
                    ON CONFLICT(uid) DO UPDATE SET name=excluded.name,sign=excluded.sign,face=excluded.face,
                      followers=excluded.followers,archive_count=excluded.archive_count,collected_at=excluded.collected_at
                    """)) {
                stmt.setLong(1, profile.uid()); stmt.setString(2, profile.name()); stmt.setString(3, profile.sign());
                stmt.setString(4, profile.face()); stmt.setObject(5, profile.followers()); stmt.setObject(6, profile.archiveCount());
                stmt.setString(7, "https://space.bilibili.com/" + profile.uid()); stmt.setString(8, profile.collectedAt());
                stmt.setLong(9, profile.uid());
                if (stmt.executeUpdate() != 1) throw new SQLException("Cannot save a profile without a discovery task");
            }
            try (var stmt = db.prepareStatement("UPDATE tasks SET status='done',last_error=NULL WHERE uid=?")) {
                stmt.setLong(1, profile.uid()); stmt.executeUpdate();
            }
        });
    }

    void attempted(long uid) throws SQLException {
        try (var stmt = db.prepareStatement("UPDATE tasks SET attempts=attempts+1 WHERE uid=?")) {
            stmt.setLong(1, uid); stmt.executeUpdate();
        }
    }

    void profileError(long uid, String error, boolean terminal) throws SQLException {
        try (var stmt = db.prepareStatement("UPDATE tasks SET status=?,last_error=? WHERE uid=?")) {
            stmt.setString(1, terminal ? "failed" : "pending"); stmt.setString(2, error); stmt.setLong(3, uid); stmt.executeUpdate();
        }
    }

    void sourceError(Cursor cursor, String error, boolean terminal) throws SQLException {
        try (var stmt = db.prepareStatement("UPDATE discovery SET exhausted=?,last_error=? WHERE source=?")) {
            stmt.setInt(1, terminal ? 1 : 0); stmt.setString(2, error); stmt.setString(3, cursor.source().key()); stmt.executeUpdate();
        }
    }

    void retryFailed() throws SQLException {
        transaction(() -> {
            try (var stmt = db.createStatement()) {
                stmt.executeUpdate("UPDATE tasks SET status='pending' WHERE status='failed'");
                stmt.executeUpdate("UPDATE discovery SET exhausted=0 WHERE last_error IS NOT NULL");
            }
        });
    }

    Stats stats() throws SQLException {
        try (var stmt = db.createStatement(); var rs = stmt.executeQuery("""
                SELECT (SELECT COUNT(*) FROM tasks), (SELECT COUNT(*) FROM creators),
                  (SELECT COUNT(*) FROM creators WHERE sign=''), (SELECT COUNT(*) FROM tasks WHERE status='failed')
                """)) {
            rs.next(); return new Stats(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4));
        }
    }

    long export(Path output) throws Exception {
        Path destination = output.toAbsolutePath();
        Files.createDirectories(destination.getParent());
        Path temp = Files.createTempFile(destination.getParent(), ".up-signatures-", ".tmp");
        long rows = 0;
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                writer.write('\ufeff');
                try (CSVPrinter csv = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(
                        "uid", "name", "sign", "face", "followers", "archive_count", "space_url", "sources", "discovered_at", "collected_at").get());
                     var stmt = db.createStatement(); var rs = stmt.executeQuery("""
                             SELECT c.*, (SELECT GROUP_CONCAT(source,';') FROM
                               (SELECT source FROM creator_sources WHERE uid=c.uid ORDER BY source)) AS sources
                             FROM creators c ORDER BY uid
                             """)) {
                    while (rs.next()) {
                        csv.printRecord(rs.getLong("uid"), rs.getString("name"), rs.getString("sign"), rs.getString("face"),
                                nullableLong(rs, "followers"), nullableLong(rs, "archive_count"), rs.getString("space_url"),
                                rs.getString("sources"), rs.getString("discovered_at"), rs.getString("collected_at"));
                        rows++;
                    }
                }
            }
            try { Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING); }
            return rows;
        } finally { Files.deleteIfExists(temp); }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column); return rs.wasNull() ? null : value;
    }

    @FunctionalInterface private interface Work { void run() throws SQLException; }
    private void transaction(Work work) throws SQLException {
        db.setAutoCommit(false);
        try { work.run(); db.commit(); }
        catch (SQLException | RuntimeException e) { db.rollback(); throw e; }
        finally { db.setAutoCommit(true); }
    }

    @Override public void close() throws SQLException { db.close(); }
}
