package com.chanccer.bilicrawler;

import java.sql.SQLException;
import java.util.function.BooleanSupplier;

final class Crawler {
    @FunctionalInterface interface Sleeper { void sleep(long milliseconds) throws InterruptedException; }
    @FunctionalInterface interface Call<T> { T run() throws Api.Failure, SQLException; }
    record Outcome(String reason, boolean targetReached) {}
    private final Store store;
    private final Api api;
    private final Sleeper sleeper;
    private final BooleanSupplier stop;

    Crawler(Store store, Api api, Sleeper sleeper, BooleanSupplier stop) {
        this.store = store; this.api = api; this.sleeper = sleeper; this.stop = stop;
    }

    Outcome run(long target) throws Exception {
        while (!stop.getAsBoolean()) {
            if (store.stats().saved() >= target) return new Outcome("Target reached", true);
            Long uid = store.nextUid();
            if (uid != null) {
                try {
                    Api.Profile profile = retry(() -> {
                        store.attempted(uid);
                        return api.profile(uid);
                    });
                    store.save(profile);
                    Store.Stats stats = store.stats();
                    if (stats.saved() % 10 == 0 || stats.saved() == target) System.out.println("Progress " + stats);
                } catch (Api.Failure failure) {
                    boolean risk = failure.kind == Api.Failure.Kind.RISK;
                    store.profileError(uid, failure.getMessage(), !risk);
                    if (risk) return new Outcome("Paused on risk response: " + failure.getMessage(), false);
                    System.err.println("Profile failed uid=" + uid + ": " + failure.getMessage());
                }
                continue;
            }
            Store.Cursor cursor = store.nextSource();
            if (cursor == null) return new Outcome("Sources exhausted before reaching target", false);
            try {
                Api.Page page = retry(() -> api.discover(cursor.source(), cursor.page()));
                store.discovered(cursor, page);
                System.out.println("Discovered " + cursor.source().key() + " page=" + cursor.page() + " authors=" + page.authors().size());
            } catch (Api.Failure failure) {
                boolean risk = failure.kind == Api.Failure.Kind.RISK;
                store.sourceError(cursor, failure.getMessage(), !risk);
                if (risk) return new Outcome("Paused on risk response: " + failure.getMessage(), false);
                System.err.println("Source skipped " + cursor.source().key() + ": " + failure.getMessage());
            }
        }
        return new Outcome("Stopped; progress saved", false);
    }

    private <T> T retry(Call<T> call) throws Api.Failure, SQLException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            if (stop.getAsBoolean()) throw new InterruptedException("Stop requested");
            try { return call.run(); }
            catch (Api.Failure failure) {
                if (failure.kind != Api.Failure.Kind.TRANSIENT || attempt == 2) throw failure;
                sleeper.sleep(attempt == 0 ? 10_000 : 30_000);
            }
        }
    }
}
