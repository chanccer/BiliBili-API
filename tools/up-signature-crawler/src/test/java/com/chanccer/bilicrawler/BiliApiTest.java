package com.chanccer.bilicrawler;

import com.alibaba.fastjson.JSON;
import com.esdllm.bilibiliApi.http.HttpPolicy;
import com.esdllm.bilibiliApi.http.RateLimiter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BiliApiTest {
    @Test void preservesEmptyAndMultilineSignaturesAndRejectsMissingOrMismatchingCardFields() throws Exception {
        for (String sign : new String[]{"", "\u4e2d\u6587\n\ud83d\ude80"}) {
            var card = new com.alibaba.fastjson.JSONObject();
            card.put("mid", "3000000000"); card.put("name", "name"); card.put("sign", sign);
            var data = new com.alibaba.fastjson.JSONObject(); data.put("card", card);
            assertEquals(sign, BiliApi.parseProfile(data, 3000000000L).sign());
        }
        for (String body : new String[]{"{}", "{\"card\":[]}", "{\"card\":{\"mid\":7,\"name\":\"n\"}}",
                "{\"card\":{\"mid\":8,\"name\":\"n\",\"sign\":\"s\"}}", "{\"card\":{\"mid\":\"invalid\",\"name\":\"n\",\"sign\":\"s\"}}"}) {
            assertEquals(Api.Failure.Kind.PERMANENT,
                    assertThrows(Api.Failure.class, () -> BiliApi.parseProfile(JSON.parseObject(body), 7)).kind);
        }
    }
    @Test void detectsHttpAndBusinessRiskBeforeParsingData() {
        for (int status : new int[]{412, 429}) {
            var failure = assertThrows(Api.Failure.class, () -> BiliApi.response(status, "<html>blocked</html>"));
            assertEquals(Api.Failure.Kind.RISK, failure.kind);
        }
        for (int code : new int[]{-352, -509, -412, 412, 429}) {
            var failure = assertThrows(Api.Failure.class, () -> BiliApi.response(200, "{\"code\":" + code + "}"));
            assertEquals(Api.Failure.Kind.RISK, failure.kind);
        }
    }

    @Test void serverFaultIsRetryableButMalformedSuccessIsPermanent() {
        assertEquals(Api.Failure.Kind.TRANSIENT, assertThrows(Api.Failure.class, () -> BiliApi.response(503, "")).kind);
        for (String body : new String[]{"<html>", "null", "{}", "{\"code\":null}", "{\"code\":0}", "{\"code\":0,\"data\":[]}"}) {
            assertEquals(Api.Failure.Kind.PERMANENT, assertThrows(Api.Failure.class, () -> BiliApi.response(200, body)).kind);
        }
    }

    @Test void anonymousNavKeysAreAcceptedDespiteNotLoggedInCode() throws Exception {
        assertNotNull(BiliApi.response(200, "{\"code\":-101,\"data\":{\"wbi_img\":{}}}"));
        assertThrows(Api.Failure.class, () -> BiliApi.response(200, "{\"code\":-101,\"data\":{}}"));
    }

    @Test void actualGlobalLimiterSpacesRequestsAndConfigurationDisablesHiddenRetries() {
        new BiliApi(45);
        assertEquals(1, HttpPolicy.getMaxAttempts());
        assertFalse(HttpPolicy.isRotateOnRiskControl());
        assertEquals(0, HttpPolicy.getMaxRotations());
        RateLimiter.reset();
        RateLimiter.acquire();
        long before = System.nanoTime();
        RateLimiter.acquire();
        assertTrue((System.nanoTime() - before) / 1_000_000 >= 35);
        HttpPolicy.reset(); RateLimiter.reset();
    }
}
