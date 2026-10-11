package com.chanccer.bilicrawler;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.http.HttpPolicy;
import com.esdllm.bilibiliApi.http.RateLimiter;
import com.esdllm.bilibiliApi.sign.WbiSigner;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;

import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

final class BiliApi implements Api {
    private static final String SPI = "https://api.bilibili.com/x/frontend/finger/spi";
    private String imgKey;
    private String subKey;
    private LocalDate keyDay;
    private boolean initialized;

    BiliApi(long intervalMs) {
        HttpPolicy.setMinRequestIntervalMs(intervalMs);
        HttpPolicy.setMaxAttempts(1);
        HttpPolicy.setRotateOnRiskControl(false);
        HttpPolicy.setRotateOnEmptyFeed(false);
        HttpPolicy.setMaxRotations(0);
        if (HttpPolicy.getUserAgent() == null || HttpPolicy.getUserAgent().isBlank()) {
            HttpPolicy.setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        }
        try {
            // Initialize the upstream HTTP configuration before the raw bootstrap request.
            MethodHandles.lookup().ensureInitialized(BilibiliHttp.class);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot initialize upstream HTTP client", e);
        }
    }

    // Bootstrap explicitly so a risk response from the fingerprint endpoint cannot be swallowed.
    private void initialize() throws Failure {
        if (initialized) return;
        if (!HttpPolicy.cookieProvidesDeviceId()) {
            RateLimiter.acquire();
            JSONObject data = request(() -> Unirest.get(BilibiliHttp.rewriteForTest(SPI))
                    .header("User-Agent", HttpPolicy.getUserAgent())
                    .header("Referer", BilibiliEndpoint.referer)
                    .connectTimeout(HttpPolicy.getConnectTimeoutMs())
                    .socketTimeout(HttpPolicy.getSocketTimeoutMs()).asString());
            String b3 = data.getString("b_3");
            String b4 = data.getString("b_4");
            if (b3 == null || b3.isBlank() || b4 == null || b4.isBlank()) {
                throw permanent("Fingerprint response missing device identifiers");
            }
            String existing = HttpPolicy.getCookie();
            HttpPolicy.setCookie((existing == null || existing.isBlank() ? "" : existing + "; ")
                    + "buvid3=" + b3 + "; buvid4=" + b4);
        }
        initialized = true;
    }

    private JSONObject get(String url, String referer) throws Failure {
        initialize();
        return request(() -> BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept, referer));
    }

    @Override
    public Page discover(Source source, int page) throws Failure {
        JSONObject data;
        JSONArray videos;
        boolean last;
        if (source.kind().equals("ranking")) {
            data = get(BilibiliEndpoint.rankingUrl + "?rid=" + source.value() + "&type=all", BilibiliEndpoint.rankingReferer);
            videos = data.getJSONArray("list");
            last = true;
        } else if (source.kind().equals("popular")) {
            data = get(BilibiliEndpoint.popularUrl + "?ps=20&pn=" + page, BilibiliEndpoint.referer);
            videos = data.getJSONArray("list");
            last = data.getBooleanValue("no_more");
        } else {
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
            if (!today.equals(keyDay)) {
                JSONObject nav = get(BilibiliEndpoint.navUrl, BilibiliEndpoint.referer);
                JSONObject images = nav.getJSONObject("wbi_img");
                if (images == null) throw permanent("nav response missing WBI keys");
                try {
                    imgKey = imageKey(images.getString("img_url"));
                    subKey = imageKey(images.getString("sub_url"));
                } catch (RuntimeException e) {
                    throw permanent("nav response has invalid WBI key URLs");
                }
                keyDay = today;
            }
            Map<String, String> params = new LinkedHashMap<>();
            params.put("search_type", "video");
            params.put("keyword", source.value());
            params.put("page", Integer.toString(page));
            data = get(BilibiliEndpoint.searchTypeUrl + "?" + WbiSigner.sign(params, imgKey, subKey), BilibiliEndpoint.searchReferer);
            videos = data.getJSONArray("result");
            last = data.containsKey("numPages") && page >= data.getIntValue("numPages");
        }
        if (videos == null) throw permanent("Discovery response missing video list");
        var authors = new ArrayList<Author>();
        var fingerprint = new ArrayList<String>();
        for (int i = 0; i < videos.size(); i++) {
            JSONObject video = videos.getJSONObject(i);
            JSONObject owner = video.getJSONObject("owner");
            Long mid = owner == null ? video.getLong("mid") : owner.getLong("mid");
            if (mid != null && mid > 0) authors.add(new Author(mid, source.key()));
            String id = video.getString("bvid");
            if (id == null) id = video.getString("aid");
            if (id == null) throw permanent("Video result missing identity");
            fingerprint.add(id);
        }
        fingerprint.sort(String::compareTo);
        return new Page(authors, String.join(",", fingerprint), last || videos.isEmpty());
    }

    @Override
    public Profile profile(long uid) throws Failure {
        JSONObject data = get(BilibiliEndpoint.cardBaseUrl + uid, "https://space.bilibili.com/" + uid);
        return parseProfile(data, uid);
    }

    static Profile parseProfile(JSONObject data, long uid) throws Failure {
        try {
            JSONObject card = data.getJSONObject("card");
            if (card == null || card.getLongValue("mid") != uid || !(card.get("sign") instanceof String)
                    || !(card.get("name") instanceof String)) {
                throw permanent("Card response missing or mismatching UID, name or signature");
            }
            return new Profile(uid, card.getString("name"), card.getString("sign"), card.getString("face"),
                    data.getLong("follower"), data.getLong("archive_count"), Instant.now().toString());
        } catch (RuntimeException e) {
            throw permanent("Malformed card fields");
        }
    }

    private static String imageKey(String url) {
        String path = URI.create(url).getPath();
        String file = path.substring(path.lastIndexOf('/') + 1);
        return file.substring(0, file.lastIndexOf('.'));
    }

    static JSONObject response(int status, String body) throws Failure {
        if (status == 412 || status == 429) throw new Failure(Failure.Kind.RISK, status, "HTTP " + status);
        if (status == 408 || status == 425 || status >= 500) {
            throw new Failure(Failure.Kind.TRANSIENT, status, "HTTP " + status);
        }
        if (status < 200 || status >= 300) throw new Failure(Failure.Kind.PERMANENT, status, "HTTP " + status);
        JSONObject root;
        try {
            root = JSON.parseObject(body);
        } catch (RuntimeException e) {
            throw permanent("Response is not a JSON object");
        }
        if (root == null || !(root.get("code") instanceof Number)) throw permanent("Response missing numeric business code");
        int code = root.getIntValue("code");
        if (code == -352 || code == -509 || code == -412 || code == 412 || code == 429) {
            throw new Failure(Failure.Kind.RISK, code, "Business risk code " + code);
        }
        if (code != 0) {
            // Anonymous nav can return -101 while still supplying public signing keys.
            JSONObject data = root.get("data") instanceof JSONObject ? root.getJSONObject("data") : null;
            if (code == -101 && data != null && data.getJSONObject("wbi_img") != null) return data;
            throw new Failure(code == -500 || code == -503 ? Failure.Kind.TRANSIENT : Failure.Kind.PERMANENT,
                    code, "Business code " + code);
        }
        if (!(root.get("data") instanceof JSONObject)) throw permanent("Response missing data object");
        JSONObject data = root.getJSONObject("data");
        if (data == null) throw permanent("Response missing data");
        return data;
    }

    private static JSONObject request(Supplier<HttpResponse<String>> sender) throws Failure {
        HttpResponse<String> resp;
        try {
            resp = sender.get();
        } catch (RuntimeException e) {
            throw new Failure(Failure.Kind.TRANSIENT, 0, "Transport failure: " + e.getClass().getSimpleName());
        }
        return response(resp.getStatus(), resp.getBody());
    }

    private static Failure permanent(String message) {
        return new Failure(Failure.Kind.PERMANENT, 0, message);
    }
}
