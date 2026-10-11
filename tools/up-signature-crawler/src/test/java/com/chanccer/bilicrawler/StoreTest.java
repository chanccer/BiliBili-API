package com.chanccer.bilicrawler;

import org.apache.commons.csv.CSVFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StoreTest {
    @TempDir Path dir;
    final Api.Source source = new Api.Source("popular", "popular", "", 2);

    @Test void usesRollbackJournalForCrossPlatformBindMountCompatibility() throws Exception {
        Path file = dir.resolve("profiles.sqlite");
        try (Store store = new Store(file);
             var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var stmt = connection.createStatement(); var rows = stmt.executeQuery("PRAGMA journal_mode")) {
            assertTrue(rows.next());
            assertEquals("delete", rows.getString(1));
            discover(store, 7);
            store.save(profile(7, "sign"));
            assertFalse(Files.exists(Path.of(file + "-wal")));
            assertFalse(Files.exists(Path.of(file + "-shm")));
        }
    }

    void discover(Store store, long... ids) throws Exception {
        store.configure(List.of(source));
        var authors = java.util.Arrays.stream(ids).mapToObj(uid -> new Api.Author(uid, source.key())).toList();
        store.discovered(new Store.Cursor(source, 1), new Api.Page(authors, "page1", false));
    }

    Api.Profile profile(long uid, String sign) {
        return new Api.Profile(uid, "\u6635\u79f0", sign, null, null, 1L, "2026-10-11T00:00:00Z");
    }

    @Test void deduplicatesAcrossPagesAndSourcesAndResumesAfterReopening() throws Exception {
        Path file = dir.resolve("profiles.sqlite");
        try (Store store = new Store(file)) {
            discover(store, 3000000000L, 3000000000L, 4);
            Api.Source other = new Api.Source("ranking:1", "ranking", "1", 1);
            store.configure(List.of(source, other));
            store.discovered(new Store.Cursor(other, 1), new Api.Page(List.of(new Api.Author(3000000000L, other.key())), "rank", true));
            assertEquals(2, store.stats().discovered());
            store.save(profile(4, ""));
        }
        try (Store store = new Store(file)) {
            assertEquals(3000000000L, store.nextUid());
            assertEquals(2, store.nextSource().page());
            assertEquals(1, store.stats().saved());
            assertEquals(1, store.stats().emptySignatures());
            Path csv = dir.resolve("out.csv");
            store.save(profile(3000000000L, "sign"));
            store.export(csv);
            assertTrue(Files.readString(csv).contains("popular;ranking:1"));
        }
    }

    @Test void latestSignatureOnlyAndCsvRoundTripsUnicodeQuotesAndMultilineText() throws Exception {
        String sign = "\u4e2d\u6587,\"quoted\"\r\n\ud83d\ude80\nline";
        Path file = dir.resolve("profiles.sqlite");
        try (Store store = new Store(file)) {
            discover(store, 7);
            store.save(profile(7, "old"));
            store.save(profile(7, sign));
            Path csv = dir.resolve("out.csv");
            assertEquals(1, store.export(csv));
            byte[] bytes = Files.readAllBytes(csv);
            assertArrayEquals(new byte[]{(byte)0xef, (byte)0xbb, (byte)0xbf}, java.util.Arrays.copyOf(bytes, 3));
            String text = new String(bytes, StandardCharsets.UTF_8).substring(1);
            try (var parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(new StringReader(text))) {
                var rows = parser.getRecords();
                assertEquals(1, rows.size());
                assertEquals(sign, rows.get(0).get("sign"));
                assertEquals("", rows.get(0).get("followers"));
            }
            assertEquals(1, store.stats().saved());
            assertThrows(IllegalArgumentException.class, () -> profile(7, null));
            assertEquals(1, store.stats().saved());
        }
    }

    @Test void transactionRollsBackProfileWhenTaskCompletionFails() throws Exception {
        Path file = dir.resolve("profiles.sqlite");
        try (Store store = new Store(file)) {
            discover(store, 7);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file); var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER reject_completion BEFORE UPDATE OF status ON tasks WHEN NEW.status='done' BEGIN SELECT RAISE(ABORT,'test failure'); END");
            }
            assertThrows(SQLException.class, () -> store.save(profile(7, "sign")));
            assertEquals(0, store.stats().saved());
            assertEquals(7L, store.nextUid());
        }
    }

    @Test void stopsRepeatedPageEvenWhenNonconsecutive() throws Exception {
        try (Store store = new Store(dir.resolve("profiles.sqlite"))) {
            Api.Source pages = new Api.Source("popular", "popular", "", 10);
            store.configure(List.of(pages));
            for (int page = 1; page <= 3; page++) {
                store.discovered(new Store.Cursor(pages, page), new Api.Page(List.of(new Api.Author(7, "popular")), page == 2 ? "different" : "repeated", false));
            }
            assertNull(store.nextSource());
        }
    }

    @Test void increasingPageLimitResumesButEmptyPageStaysExhausted() throws Exception {
        try (Store store = new Store(dir.resolve("profiles.sqlite"))) {
            Api.Source one = new Api.Source("popular", "popular", "", 1);
            store.configure(List.of(one));
            store.discovered(new Store.Cursor(one, 1), new Api.Page(List.of(new Api.Author(7, "popular")), "first", false));
            assertNull(store.nextSource());
            store.configure(List.of(source));
            assertEquals(2, store.nextSource().page());
            store.discovered(store.nextSource(), new Api.Page(List.of(), "", true));
            store.configure(List.of(new Api.Source("popular", "popular", "", 100)));
            assertNull(store.nextSource());
        }
    }

    @Test void retryFailedKeepsExistingSignatureAndReactivatesTask() throws Exception {
        try (Store store = new Store(dir.resolve("profiles.sqlite"))) {
            discover(store, 7);
            store.save(profile(7, "preserved"));
            store.profileError(7, "missing field", true);
            assertEquals(1, store.stats().saved());
            assertNull(store.nextUid());
            store.retryFailed();
            assertEquals(7L, store.nextUid());
            assertEquals(0, store.stats().failed());
        }
    }

    @Test void deactivatesSourcesNotPresentInCurrentConfiguration() throws Exception {
        try (Store store = new Store(dir.resolve("profiles.sqlite"))) {
            store.configure(List.of(source));
            store.configure(List.of(new Api.Source("search:new", "search", "new", 5)));
            assertEquals("search:new", store.nextSource().source().key());
        }
    }
}
