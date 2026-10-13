package com.demo.interview.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 阿里 MaaS rerank 配置，对应 application.properties 中 rerank.* 段。
 */
@ConfigurationProperties(prefix = "rerank")
public record RerankProperties(String baseUrl, String model, String apiKey) {
}