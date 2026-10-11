package com.chanccer.bilicrawler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MainTest {
    @TempDir Path dir;

    @Test void validatesArgumentsBeforeCreatingDatabaseOrMakingRequests() {
        assertThrows(IllegalArgumentException.class, () -> Main.options(new String[]{"crawl", "--unknown", "x"}));
        assertThrows(IllegalArgumentException.class, () -> Main.options(new String[]{"crawl", "--target"}));
        assertThrows(IllegalArgumentException.class, () -> Main.options(new String[]{"crawl", "--target", "1", "--target", "2"}));
        assertThrows(IllegalArgumentException.class, () -> Main.sources(Map.of("--popular-pages", "0")));
        assertThrows(IllegalArgumentException.class, () -> Main.run(new String[]{"export", "--db", dir.resolve("missing.sqlite").toString()}));
        assertFalse(Files.exists(dir.resolve("missing.sqlite")));
        assertThrows(IllegalArgumentException.class, () -> Main.run(new String[]{"crawl", "--db", dir.resolve("db.sqlite").toString(),
                "--output", dir.resolve("unused/../db.sqlite").toString()}));
    }

    @Test void defaultSourcesCoverRankingsAndSixteenTopicsAndKeywordFileIsSupported() throws Exception {
        var defaults = Main.sources(Map.of());
        assertEquals(16, defaults.stream().filter(s -> s.kind().equals("search")).count());
        assertEquals(17, defaults.stream().filter(s -> s.kind().equals("ranking")).count());
        Path keywords = dir.resolve("keywords.txt");
        Files.writeString(keywords, "# comment\n\ncustom\ncustom\n");
        var custom = Main.sources(Map.of("--ranking-ids", "", "--keywords-file", keywords.toString(), "--search-pages", "70"));
        assertEquals(2, custom.size());
        assertEquals(70, custom.get(1).maxPages());
    }
}
