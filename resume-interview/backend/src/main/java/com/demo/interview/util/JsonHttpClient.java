package com.demo.interview.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;

/**
 * 基于 Spring RestClient 的 JSON POST 客户端，统一外部 HTTP 直调：
 * Content-Type / Authorization / 超时 / 非 2xx 错误体 / BOM 处理，避免各客户端手写 HTTP。
 */
@Component
public class JsonHttpClient {

    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 300_000;

    private final RestClient client;
    private final ObjectMapper json;

    public JsonHttpClient(ObjectMapper json) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        this.client = RestClient.builder().requestFactory(factory).build();
        this.json = json;
    }

    /** POST JSON，返回解析后的 JsonNode；非 2xx 抛 IOException（含响应体前 300 字） */
    public JsonNode postJson(String url, String bearerToken, Object body) throws IOException {
        try {
            String text = client.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + bearerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
            return json.readTree(stripBom(text));
        } catch (RestClientResponseException e) {
            throw new IOException("HTTP " + e.getStatusCode().value()
                    + " body=" + truncate(e.getResponseBodyAsString()), e);
        }
    }

    private static String stripBom(String s) {
        return s != null && s.startsWith("\uFEFF") ? s.substring(1) : s;
    }

    private static String truncate(String s) {
        return s == null ? "null" : s.substring(0, Math.min(300, s.length()));
    }
}