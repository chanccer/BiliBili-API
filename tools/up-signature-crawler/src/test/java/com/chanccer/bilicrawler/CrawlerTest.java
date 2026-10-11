package com.chanccer.bilicrawler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CrawlerTest {
    @TempDir Path dir;
    final Api.Source source = new Api.Source("popular", "popular", "", 5);

    static class FakeApi implements Api {
        int discoveries;
        int profiles;
        int transientFailures;
        Failure.Kind failure;
        boolean discoveryRisk;

        @Override public Page discover(Source source, int page) throws Failure {
            discoveries++;
            if (discoveryRisk) throw new Failure(Failure.Kind.RISK, -352, "risk");
            return new Page(List.of(new Author(7, source.key()), new Author(8, source.key())), "ids", true);
        }
        @Override public Profile profile(long uid) throws Failure {
            profiles++;
            if (transientFailures-- > 0) throw new Failure(Failure.Kind.TRANSIENT, 503, "temporary");
            if (failure != null) throw new Failure(failure, -352, "failure");
            return new Profile(uid, "name", "", null, 0L, 1L, "2026-10-11T00:00:00Z");
        }
    }

    @Test void reachesTargetAndResumeDoesNotRequestSavedCreators() throws Exception {
        try (Store store = new Store(dir.resolve("db.sqlite"))) {
            store.configure(List.of(source));
            FakeApi api = new FakeApi();
            Crawler crawler = new Crawler(store, api, ms -> fail("Unexpected sleep"), () -> false);
            assertTrue(crawler.run(1).targetReached());
            assertEquals(1, api.profiles);
            assertTrue(crawler.run(1).targetReached());
            assertEquals(1, api.profiles);
            assertTrue(crawler.run(2).targetReached());
            assertEquals(2, api.profiles);
            assertEquals(1, api.discoveries);
        }
    }

    @Test void profileRiskStopsImmediatelyAndRetainsPendingTaskForResume() throws Exception {
        try (Store store = new Store(dir.resolve("db.sqlite"))) {
            store.configure(List.of(source));
            FakeApi api = new FakeApi(); api.failure = Api.Failure.Kind.RISK;
            assertFalse(new Crawler(store, api, ms -> fail("Risk must not retry"), () -> false).run(2).targetReached());
            assertEquals(1, api.profiles);
            assertNotNull(store.nextUid());
            assertEquals(0, store.stats().failed());
            api.failure = null;
            assertTrue(new Crawler(store, api, ms -> {}, () -> false).run(2).targetReached());
        }
    }

    @Test void discoveryRiskDoesNotAdvanceCursorOrRequestProfiles() throws Exception {
        try (Store store = new Store(dir.resolve("db.sqlite"))) {
            store.configure(List.of(source));
            FakeApi api = new FakeApi(); api.discoveryRisk = true;
            var result = new Crawler(store, api, ms -> fail("Risk must not retry"), () -> false).run(2);
            assertTrue(result.reason().contains("risk"));
            assertEquals(1, api.discoveries);
            assertEquals(0, api.profiles);
            assertEquals(1, store.nextSource().page());
        }
    }

    @Test void transientErrorsHaveExactlyThreeAttemptsWithTenAndThirtySecondBackoff() throws Exception {
        try (Store store = new Store(dir.resolve("db.sqlite"))) {
            store.configure(List.of(source));
            FakeApi api = new FakeApi(); api.transientFailures = 2;
            var delays = new ArrayList<Long>();
            assertTrue(new Crawler(store, api, delays::add, () -> false).run(1).targetReached());
            assertEquals(3, api.profiles);
            assertEquals(List.of(10_000L, 30_000L), delays);
        }
    }

    @Test void permanentFailureDoesNotRetryAndCannotCountTowardTarget() throws Exception {
        try (Store store = new Store(dir.resolve("db.sqlite"))) {
            store.configure(List.of(source));
            FakeApi api = new FakeApi(); api.failure = Api.Failure.Kind.PERMANENT;
            var outcome = new Crawler(store, api, ms -> fail("Permanent errors must not retry"), () -> false).run(2);
            assertFalse(outcome.targetReached());
            assertEquals(2, api.profiles);
            assertEquals(2, store.stats().failed());
            assertEquals(0, store.stats().saved());
            assertTrue(outcome.reason().contains("exhausted"));
        }
    }

    @Test void stopDuringBackoffLeavesTaskPending() throws Exception {
        try (Store store = new Store(dir.resolve("db.sqlite"))) {
            store.configure(List.of(source));
            FakeApi api = new FakeApi(); api.transientFailures = 10;
            AtomicBoolean stop = new AtomicBoolean();
            Crawler crawler = new Crawler(store, api, ms -> stop.set(true), stop::get);
            assertThrows(InterruptedException.class, () -> crawler.run(1));
            assertEquals(1, api.profiles);
            assertNotNull(store.nextUid());
        }
    }
}
