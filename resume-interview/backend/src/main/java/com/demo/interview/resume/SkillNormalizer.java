package com.demo.interview.resume;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 技能名归一化：把 LLM 抽取出的技能名收敛到规范命名（同义、大小写、空格/标点变体）。
 * 目的：同一份简历多次解析时技能名保持一致，避免 "GC调优"/"GC 调优"、"SpringCloud"/"Spring Cloud" 这类漂移。
 * 注意：只能统一「命名」，无法消除「漏抽/多抽」；要彻底稳定需固定词表约束 + 更低温/seed。
 */
public final class SkillNormalizer {

    private static final Map<String, String> CANONICAL = new LinkedHashMap<>();

    private SkillNormalizer() {
    }

    private static void put(String canonical, String... aliases) {
        CANONICAL.put(key(canonical), canonical);
        for (String a : aliases) {
            CANONICAL.put(key(a), canonical);
        }
    }

    static {
        // Java / 后端
        put("Java");
        put("JVM", "jdk", "jre");
        put("JUC");
        put("GC 调优", "gc调优", "gc 调优", "gc调优", "垃圾回收", "gc");
        put("并发编程", "并发", "多线程");
        put("集合框架", "集合", "collection");
        put("线程池", "threadpool");
        put("锁", "java锁");
        put("运行时数据区");
        put("类加载机制");
        put("反射");
        put("泛型");
        put("Stream", "stream流");
        put("设计模式");
        put("Spring", "spring框架");
        put("Spring Boot", "springboot", "spring boot");
        put("Spring MVC", "springmvc", "spring mvc");
        put("Spring Cloud", "springcloud", "spring cloud");
        put("Spring Cloud Alibaba", "springcloudalibaba", "spring cloud alibaba");
        put("Spring Security", "springsecurity", "spring security");
        put("MyBatis", "mybatis", "mybatis-plus", "mybatisplus");
        put("IOC", "ioc", "控制反转");
        put("AOP");
        // 数据库 / 缓存 / MQ
        put("MySQL", "mysql", "sql");
        put("Redis", "redis");
        put("索引原理", "索引", "b+树", "b+ 树");
        put("SQL 优化", "sql优化", "sql 优化");
        put("事务管理");
        put("事务隔离级别", "事务隔离", "隔离级别");
        put("MVCC");
        put("数据库锁", "行锁", "表锁");
        put("缓存穿透");
        put("缓存一致性");
        put("NoSQL", "nosql");
        put("Kafka");
        put("RabbitMQ", "rabbitmq");
        put("RocketMQ", "rocketmq");
        put("消息队列", "mq");
        put("消息可靠性");
        put("削峰");
        put("重试与幂等", "幂等");
        // 分布式 / 微服务
        put("分布式事务");
        put("分布式一致性");
        put("分布式锁");
        put("分布式 ID", "分布式id", "分布式 id");
        put("分布式系统");
        put("分布式链路追踪", "链路追踪");
        put("服务注册发现", "服务注册与发现");
        put("负载均衡");
        put("网关");
        put("微服务");
        put("Nacos");
        put("Sentinel");
        put("Seata");
        put("Redisson");
        put("RPC");
        put("ZooKeeper", "zookeeper", "zk");
        put("Lua");
        put("Protobuf");
        put("CDN");
        // 网络 / OS / 算法
        put("HTTP");
        put("HTTPS");
        put("TCP/IP", "tcpip", "tcp/ip", "tcp");
        put("UDP");
        put("WebSocket", "websocket");
        put("DNS");
        put("OSI", "网络分层");
        put("计算机网络");
        put("操作系统");
        put("Linux");
        put("数据结构");
        put("算法");
        put("三次握手", "握手挥手");
        // DevOps
        put("Docker");
        put("Kubernetes", "k8s");
        put("Elasticsearch", "es");
        put("CI/CD", "ci/cd", "cicd", "持续集成");
        // 前端
        put("JavaScript", "js");
        put("TypeScript", "ts");
        put("Node.js", "nodejs", "node", "node.js");
        put("Vue", "vue", "vue.js", "vue3", "vue2");
        put("React", "react");
        put("HTML");
        put("CSS");
        put("Promise", "promise");
        put("事件循环", "event loop", "eventloop");
        put("虚拟 DOM", "vdom", "virtual dom");
        put("diff 算法", "diff", "diff算法");
        put("响应式原理", "响应式", "reactivity");
        put("性能优化");
        put("浏览器缓存");
        put("跨域", "cors");
        // AI 应用
        put("Python", "python");
        put("PyTorch", "pytorch", "torch");
        put("TensorFlow", "tensorflow");
        put("Transformer", "transformer");
        put("多模态", "multimodal");
        put("RAG", "rag", "检索增强");
        put("Agent", "agent", "智能体");
        put("多 Agent", "多agent", "multi-agent", "multiagent");
        put("LangChain", "langchain", "lang chain");
        put("LangGraph", "langgraph");
        put("LlamaIndex", "llamaindex", "llama index");
        put("MCP");
        put("Prompt 工程", "prompt工程", "prompt 工程", "prompt", "提示词工程");
        put("Function Calling", "function calling", "functioncall", "工具调用");
        put("向量数据库", "向量库");
        put("Embedding", "embedding", "向量化");
        put("微调", "fine-tuning", "finetune", "sft");
        put("大模型", "llm", "大语言模型");
        // 测试
        put("软件测试", "测试理论");
        put("自动化测试");
        put("接口测试");
        put("性能测试");
        put("Selenium");
        put("JMeter");
        put("Pytest");
        put("Appium");
    }

    /** 归一化技能名：命中词表取规范名，未命中保留原文（去首尾空白） */
    public static String canonical(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        String hit = CANONICAL.get(key(trimmed));
        return hit == null ? trimmed : hit;
    }

    /** 归一化并去重（保留首次出现的档位/关键词），保持原顺序 */
    public static ResumeProfile normalize(ResumeProfile profile) {
        if (profile == null || profile.skills() == null) {
            return profile;
        }
        Map<String, ResumeProfile.SkillItem> byName = new LinkedHashMap<>();
        for (ResumeProfile.SkillItem s : profile.skills()) {
            String name = canonical(s.name());
            if (name == null || name.isBlank()) {
                continue;
            }
            byName.putIfAbsent(name, new ResumeProfile.SkillItem(name, s.level(), s.keywords()));
        }
        return new ResumeProfile(profile.name(), profile.targetRole(), profile.yearsExperience(),
                profile.school(), profile.degree(), profile.major(),
                new ArrayList<>(byName.values()), profile.projects());
    }

    /** 归一化键：小写 + 去掉空白与常见标点 */
    private static String key(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\-_/.]", "");
    }
}