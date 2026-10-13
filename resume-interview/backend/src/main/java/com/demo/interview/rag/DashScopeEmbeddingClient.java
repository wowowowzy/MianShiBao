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
 * 阿里百炼 DashScope embedding 客户端（HTTP 直调，不走 Spring AI）。
 * 端点：{baseUrl}/text-embedding，请求体 input 需 {texts:[...]} 包装；
 * 响应 output.embeddings[].embedding（按 text_index 对齐入参顺序，默认 1024 维）。
 */
@Slf4j
@Component
public class DashScopeEmbeddingClient {

    private final JsonHttpClient http;
    private final String apiKey;
    private final String model;
    private final String endpoint;

    public DashScopeEmbeddingClient(DashScopeProperties props, JsonHttpClient http) {
        this.http = http;
        this.apiKey = props.apiKey();
        this.model = props.embeddingModel();
        String base = props.baseUrl().replaceAll("/+$", "");
        this.endpoint = base.endsWith("/text-embedding") ? base : base + "/text-embedding";
    }

    /** 批量向量化，返回与入参一一对应的向量；失败抛 IOException 由上层降级 */
    public List<double[]> embed(List<String> texts) throws IOException {
        JsonNode root = http.postJson(endpoint, apiKey, Map.of("model", model, "input", Map.of("texts", texts)));
        List<double[]> out = new ArrayList<>();
        for (JsonNode n : root.path("output").path("embeddings")) {
            JsonNode vec = n.path("embedding");
            double[] d = new double[vec.size()];
            for (int i = 0; i < vec.size(); i++) {
                d[i] = vec.get(i).asDouble();
            }
            out.add(d);
        }
        if (out.size() != texts.size()) {
            log.warn("DashScope 返回向量数 {} 与入参 {} 不一致", out.size(), texts.size());
        }
        return out;
    }
}