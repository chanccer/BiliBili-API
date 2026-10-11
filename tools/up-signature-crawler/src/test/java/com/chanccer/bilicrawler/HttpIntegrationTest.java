package com.chanccer.bilicrawler;

import com.esdllm.bilibiliApi.http.AnonymousSession;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.http.HttpPolicy;
import com.esdllm.bilibiliApi.http.RateLimiter;
import com.esdllm.bilibiliApi.sign.WbiSigner;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

class HttpIntegrationTest {
    record Request(String path, String query, String referer, String cookie, long time) {}
    HttpServer server;
    final List<Request> requests = new ArrayList<>();
    String blockedPath;
    static final String KEY = "0123456789abcdef0123456789abcdef";

    @BeforeEach void start() throws Exception {
        HttpPolicy.reset(); HttpPolicy.clearCookie(); AnonymousSession.reset(); RateLimiter.reset();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(new Request(path, exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders().getFirst("Referer"),
                    exchange.getRequestHeaders().getFirst("Cookie"), System.nanoTime()));
            String body;
            if (path.equals(blockedPath)) body = "{\"code\":-352}";
            else if (path.endsWith("/spi")) body = "{\"code\":0,\"data\":{\"b_3\":\"fixture-three\",\"b_4\":\"fixture-four\"}}";
            else if (path.endsWith("/nav")) body = "{\"code\":-101,\"data\":{\"wbi_img\":{\"img_url\":\"https://i.test/" + KEY + ".png\",\"sub_url\":\"https://i.test/" + KEY + ".png\"}}}";
            else if (path.endsWith("/card")) body = "{\"code\":0,\"data\":{\"card\":{\"mid\":\"3000000000\",\"name\":\"name\",\"sign\":\"a\\nb\"},\"follower\":5,\"archive_count\":1}}";
            else if (path.endsWith("/type")) body = "{\"code\":0,\"data\":{\"numPages\":1,\"result\":[{\"bvid\":\"BVfixture\",\"mid\":3000000000}]}}";
            else body = "{\"code\":0,\"data\":{\"no_more\":true,\"list\":[{\"bvid\":\"BVfixture\",\"owner\":{\"mid\":3000000000}}]}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
    }

    BiliApi api() {
        BiliApi api = new BiliApi(35);
        BilibiliHttp.setTestBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return api;
    }

    @AfterEach void cleanup() {
        server.stop(0); BilibiliHttp.clearTestBaseUrl();
        HttpPolicy.reset(); HttpPolicy.clearCookie(); AnonymousSession.reset(); RateLimiter.reset();
    }

    @Test void bootstrapRankingPopularSearchAndCardUseCorrectCookiesSigningAndGlobalSpacing() throws Exception {
        BiliApi api = api();
        assertEquals(3000000000L, api.discover(new Api.Source("rank", "ranking", "1", 1), 1).authors().get(0).uid());
        assertTrue(api.discover(new Api.Source("popular", "popular", "", 2), 1).last());
        Api.Source search = new Api.Source("search:test", "search", "test", 2);
        assertTrue(api.discover(search, 1).last());
        api.discover(search, 1);
        assertEquals("a\nb", api.profile(3000000000L).sign());
        assertEquals(1, requests.stream().filter(r -> r.path().endsWith("/spi")).count());
        assertEquals(1, requests.stream().filter(r -> r.path().endsWith("/nav")).count());
        assertEquals("https://www.bilibili.com/v/popular/rank/all", requests.get(1).referer());
        for (int i = 1; i < requests.size(); i++) {
            assertTrue((requests.get(i).time() - requests.get(i - 1).time()) / 1_000_000 >= 20);
            assertTrue(requests.get(i).cookie().contains("buvid3=fixture-three"));
        }
        Request signed = requests.stream().filter(r -> r.path().endsWith("/type")).findFirst().orElseThrow();
        Map<String, String> params = new LinkedHashMap<>();
        for (String field : signed.query().split("&")) {
            String[] pair = field.split("=", 2);
            params.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        long timestamp = Long.parseLong(params.remove("wts"));
        String signature = params.remove("w_rid");
        assertTrue(WbiSigner.sign(params, KEY, KEY, timestamp).contains("w_rid=" + signature));
    }

    @Test void riskDuringFingerprintBootstrapMakesOnlyOneRequest() {
        blockedPath = "/x/frontend/finger/spi";
        BiliApi api = api();
        assertEquals(Api.Failure.Kind.RISK, assertThrows(Api.Failure.class,
                () -> api.discover(new Api.Source("rank", "ranking", "0", 1), 1)).kind);
        assertEquals(1, requests.size());
    }

    @Test void riskDuringNavPreventsSearchAndDoesNotTriggerUpstreamRetry() {
        blockedPath = "/x/web-interface/nav";
        BiliApi api = api();
        assertEquals(Api.Failure.Kind.RISK, assertThrows(Api.Failure.class,
                () -> api.discover(new Api.Source("search:test", "search", "test", 2), 1)).kind);
        assertEquals(2, requests.size());
        assertTrue(requests.stream().noneMatch(r -> r.path().endsWith("/type")));
    }
}
