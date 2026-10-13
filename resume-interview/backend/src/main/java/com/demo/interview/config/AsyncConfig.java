package com.demo.interview.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * LLM/耗时任务的线程池。QPS 低，本地线程池即可，无需 MQ。
 * - 核心/最大线程：4 / 8；队列 200
 * - 关闭时等待在途任务完成，避免重启把正在跑的 LLM 任务中断
 * - 拒绝策略：默认 Abort（提交方捕获 TaskRejectedException 后回错，避免阻塞请求线程）
 */
@Configuration
public class AsyncConfig {

    @Bean("llmExecutor")
    public ThreadPoolTaskExecutor llmExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(4);
        ex.setMaxPoolSize(8);
        ex.setQueueCapacity(200);
        ex.setKeepAliveSeconds(60);
        ex.setThreadNamePrefix("llm-");
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(60);
        ex.initialize();
        return ex;
    }
}