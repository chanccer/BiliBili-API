package com.chanccer.bilicrawler;

import kong.unirest.Unirest;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        int exit;
        try { exit = run(args); }
        catch (Exception e) { System.err.println("Error: " + e.getClass().getSimpleName() + ": " + e.getMessage()); exit = 1; }
        System.exit(exit);
    }

    static int run(String[] args) throws Exception {
        if (args.length == 0 || Arrays.asList(args).contains("--help")) {
            System.out.println("""
                    Bili UP Signature Crawler (uses abcLiyew/BiliBili-API)
                    crawl [--db data/up-signatures.sqlite] [--output data/up-signatures.csv]
                      [--target 1000] [--interval-ms 3000] [--ranking-ids 0,1,...]
                      [--keywords-file keywords.txt] [--popular-pages 100] [--search-pages 50]
                      [--retry-failed]
                    export [--db data/up-signatures.sqlite] [--output data/up-signatures.csv]
                    Target counts all distinct saved creators, including previous runs.
                    Public video authors only; full-site coverage is not guaranteed.
                    Exit codes: 0 target reached/exported; 2 incomplete/paused; 1 error.
                    """);
            return 0;
        }
        String command = args[0];
        if (!command.equals("crawl") && !command.equals("export")) throw new IllegalArgumentException("Expected crawl or export");
        Map<String, String> options = options(args);
        Path database = Path.of(options.getOrDefault("--db", "data/up-signatures.sqlite")).toAbsolutePath().normalize();
        Path output = Path.of(options.getOrDefault("--output", "data/up-signatures.csv")).toAbsolutePath().normalize();
        if (database.equals(output) || (Files.exists(database) && Files.exists(output) && Files.isSameFile(database, output))) {
            throw new IllegalArgumentException("Database and CSV paths must differ");
        }
        if (command.equals("export") && !Files.exists(database)) throw new IllegalArgumentException("Database does not exist");
        long target = positive(options, "--target", 1000);
        long interval = positive(options, "--interval-ms", 3000);
        List<Api.Source> sources = sources(options);
        Files.createDirectories(database.getParent());
        var stopped = new AtomicBoolean(false);
        var finished = new CountDownLatch(1);
        Thread worker = Thread.currentThread();
        Thread hook = new Thread(() -> {
            stopped.set(true); worker.interrupt();
            try { finished.await(45, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "save-crawler-progress");
        Runtime.getRuntime().addShutdownHook(hook);
        try (FileChannel channel = FileChannel.open(Path.of(database + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("Database is already in use by another crawler");
            try (Store store = new Store(database)) {
                if (command.equals("export")) {
                    System.out.println("Exported " + store.export(output) + " rows to " + output);
                    return 0;
                }
                store.configure(sources);
                if (options.containsKey("--retry-failed")) store.retryFailed();
                Crawler.Outcome outcome;
                try {
                    outcome = new Crawler(store, new BiliApi(interval), Thread::sleep, stopped::get).run(target);
                } catch (InterruptedException e) {
                    Thread.interrupted();
                    outcome = new Crawler.Outcome("Interrupted; progress saved", false);
                } finally {
                    // Clear interrupt status before SQLite and CSV I/O during graceful shutdown.
                    Thread.interrupted();
                    long rows = store.export(output);
                    System.out.println("Exported " + rows + " rows to " + output);
                    System.out.println("Database " + database + " " + store.stats());
                }
                System.out.println(outcome.reason());
                return outcome.targetReached() ? 0 : 2;
            }
        } finally {
            Unirest.shutDown(); finished.countDown();
            try { Runtime.getRuntime().removeShutdownHook(hook); }
            catch (IllegalStateException ignored) { /* JVM shutdown is already underway. */ }
        }
    }

    static Map<String, String> options(String[] args) {
        List<String> valued = List.of("--db", "--output", "--target", "--interval-ms", "--ranking-ids", "--keywords-file", "--popular-pages", "--search-pages");
        Map<String, String> result = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String key = args[i];
            if (result.containsKey(key)) throw new IllegalArgumentException("Duplicate option " + key);
            if (key.equals("--retry-failed")) result.put(key, "true");
            else if (valued.contains(key) && i + 1 < args.length && !args[i + 1].startsWith("--")) result.put(key, args[++i]);
            else throw new IllegalArgumentException("Unknown option or missing value: " + key);
        }
        return result;
    }

    static List<Api.Source> sources(Map<String, String> options) throws Exception {
        var sources = new ArrayList<Api.Source>();
        String rankingIds = options.getOrDefault("--ranking-ids", "0,1,3,4,5,36,119,129,155,160,181,188,202,211,217,223,234");
        for (String value : rankingIds.split(",")) {
            if (value.isBlank()) continue;
            int rid = Integer.parseInt(value.trim());
            if (rid < 0) throw new IllegalArgumentException("Ranking IDs must be nonnegative");
            sources.add(new Api.Source("ranking:" + rid, "ranking", Integer.toString(rid), 1));
        }
        sources.add(new Api.Source("popular", "popular", "", Math.toIntExact(positive(options, "--popular-pages", 100))));
        List<String> keywords = options.containsKey("--keywords-file") ? Files.readAllLines(Path.of(options.get("--keywords-file")))
                : List.of("\u52a8\u753b", "\u97f3\u4e50", "\u821e\u8e48", "\u6e38\u620f", "\u79d1\u6280", "\u6570\u7801", "\u7f16\u7a0b", "\u77e5\u8bc6",
                          "\u6c7d\u8f66", "\u8fd0\u52a8", "\u7f8e\u98df", "\u65c5\u884c", "\u751f\u6d3b", "\u52a8\u7269", "\u65f6\u5c1a", "\u5f71\u89c6");
        int searchPages = Math.toIntExact(positive(options, "--search-pages", 50));
        for (String keyword : keywords.stream().map(String::trim).filter(k -> !k.isEmpty() && !k.startsWith("#")).distinct().toList()) {
            sources.add(new Api.Source("search:" + keyword, "search", keyword, searchPages));
        }
        return sources.stream().distinct().toList();
    }

    private static long positive(Map<String, String> options, String key, long defaultValue) {
        long value = Long.parseLong(options.getOrDefault(key, Long.toString(defaultValue)));
        if (value <= 0) throw new IllegalArgumentException(key + " must be positive");
        return value;
    }
}
