package com.demo.interview.config;

import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * Spring AI（DeepSeek）HTTP 客户端超时配置：连接 30s、读取 600s（LLM 长回答较慢，超时要设长，避免被掐断）。
 * 重试由 application.properties 的 spring.ai.retry.* 控制。
 */
@Configuration
public class AiHttpConfig {

    @Bean
    public RestClientCustomizer aiRestClientCustomizer() {
        return builder -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(30_000);
            factory.setReadTimeout(600_000);
            builder.requestFactory(factory);
        };
    }
}