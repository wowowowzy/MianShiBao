package com.demo.interview.rag;

import com.demo.interview.util.JsonHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 阿里 MaaS rerank 客户端（qwen3.7-text-rerank，cross-encoder 相关度精排）。
 * HTTP 走 {@link JsonHttpClient}，解析 response JSON（output.results[]）。
 */
@Slf4j
@Component
public class RankClient {

    private final JsonHttpClient http;
    private final String apiKey;
    private final String model;
    private final String endpoint;

    public RankClient(RerankProperties props, JsonHttpClient http) {
        this.http = http;
        this.apiKey = props.apiKey();
        this.model = props.model();
        String base = props.baseUrl() == null ? "" : props.baseUrl().replaceAll("/+$", "");
        this.endpoint = base.endsWith("/text-rerank") ? base : base + "/text-rerank";
    }

    /** 对 query 与候选 documents 精排，返回按相关度降序结果（index0 最相关）；失败抛 IOException 上层降级 */
    public List<Ranked> rerank(String query, List<String> documents, int topN) throws IOException {
        Map<String, Object> body = Map.of(
                "model", model,
                "input", Map.of("query", query, "documents", documents),
                "parameters", Map.of("top_n", topN, "return_documents", true));
        JsonNode root = http.postJson(endpoint, apiKey, body);
        List<Ranked> out = new ArrayList<>();
        for (JsonNode r : root.path("output").path("results")) {
            out.add(new Ranked(
                    r.path("index").asInt(-1),
                    r.path("document").path("text").asText(""),
                    r.path("relevance_score").asDouble(0)));
        }
        log.info("rerank 完成：候选 {} 道 → 返回 {} 道", documents.size(), out.size());
        return out;
    }

    /** 精排结果：index 对应 documents 原始下标，score 为相关度（越大越相关） */
    public record Ranked(int index, String text, double score) {
    }
}