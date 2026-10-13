package com.demo.interview.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 阿里百炼 embedding 配置，对应 application.properties 中 dashscope.* 段。
 * key 来自根目录 .env（DASHSCOPE_API_KEY），不入库。
 */
@ConfigurationProperties(prefix = "dashscope")
public record DashScopeProperties(
        String apiKey,
        String embeddingModel,
        String baseUrl
) {
}