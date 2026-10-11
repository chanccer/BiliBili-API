package com.chanccer.bilicrawler;

import java.util.List;

interface Api {
    record Author(long uid, String source) {}
    record Page(List<Author> authors, String fingerprint, boolean last) {}
    record Profile(long uid, String name, String sign, String face, Long followers,
                   Long archiveCount, String collectedAt) {
        public Profile {
            if (uid <= 0 || name == null || sign == null) {
                throw new IllegalArgumentException("Incomplete profile; signature must be present (empty is valid)");
            }
        }
    }
    record Source(String key, String kind, String value, int maxPages) {}

    Page discover(Source source, int page) throws Failure;
    Profile profile(long uid) throws Failure;

    final class Failure extends Exception {
        enum Kind { RISK, TRANSIENT, PERMANENT }
        final Kind kind;
        final int code;

        Failure(Kind kind, int code, String message) {
            super(message);
            this.kind = kind;
            this.code = code;
        }
    }
}
