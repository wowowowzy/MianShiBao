package com.demo.interview.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 业务 JSON 用的 Jackson 2 ObjectMapper 单例。
 * Boot 4 默认集成 Jackson 3（tools.jackson.*），故这里显式提供 Jackson 2 实例，供业务代码注入复用，
 * 避免各处 new ObjectMapper() 造成的实例与配置分散。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper businessObjectMapper() {
        return new ObjectMapper();
    }
}