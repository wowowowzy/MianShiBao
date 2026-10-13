package com.demo.interview.resume;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 智谱 OCR 配置，对应 application.properties 中 zhipu.* 段。
 * key 来自根目录 .env（ZHIPU_API_KEY），不入库。
 */
@ConfigurationProperties(prefix = "zhipu")
public record ZhipuOcrProperties(String apiKey, String ocrModel, String baseUrl) {
}