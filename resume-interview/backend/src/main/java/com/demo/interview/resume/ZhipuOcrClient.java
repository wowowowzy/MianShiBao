package com.demo.interview.resume;

import com.demo.interview.util.JsonHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * 智谱 GLM-OCR 直调客户端（不走 Spring AI，智谱无官方 starter）。
 * 曲线：POST {baseUrl}/chat/completions，模型为 glm-ocr，图片 base64 内联。
 * HTTP 走 {@link JsonHttpClient}。
 */
@Slf4j
@Component
public class ZhipuOcrClient {

    private final JsonHttpClient http;
    private final String apiKey;
    private final String model;
    private final String chatCompletionsUrl;

    public ZhipuOcrClient(ZhipuOcrProperties props, JsonHttpClient http) {
        this.http = http;
        this.apiKey = props.apiKey();
        this.model = props.ocrModel();
        String base = props.baseUrl() == null ? "" : props.baseUrl().replaceAll("/+$", "");
        this.chatCompletionsUrl = base + "/chat/completions";
    }

    /**
     * 识别图片中的文字，返回纯文本（尽量保留排版换行）。
     */
    public String recognize(String imageBase64, String mimeType) throws IOException {
        String dataUrl = "data:" + mimeType + ";base64," + imageBase64;
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", new Object[]{Map.of(
                        "role", "user",
                        "content", new Object[]{
                                Map.of("type", "image_url",
                                        "image_url", Map.of("url", dataUrl)),
                                Map.of("type", "text",
                                        "text", "请识别图片中的所有文字，完整输出，尽量保留原始排版与换行，不要额外解释。")
                        })}
        );
        JsonNode root = http.postJson(chatCompletionsUrl, apiKey, body);
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        log.info("OCR done, text length={}", content.length());
        return content;
    }
}