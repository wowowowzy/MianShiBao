package com.demo.interview.question;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多方向技能图谱：
 * - Java 后端域（8 大领域节点）——出题的主体标签
 * - 软件测试域（TEST_SKILLS）——软件测试岗位的语义对齐节点
 * 简历技能名落在此图谱内才能稳定命中对应题库标签；未命中用 RAG 语义兜底。
 * 目前为常量维护，后续可改为数据库/JSON 配置。
 */
public final class SkillGraph {

    private SkillGraph() {
    }

    /** 领域 -> 该领域的标准技能节点名（题库标签就是 SkillItem.name） */
    public static final Map<String, List<String>> DOMAIN_SKILLS;

    /** 软件测试方向的技能节点（测试岗位出题 / 语义对齐用） */
    public static final List<String> TEST_SKILLS = List.of(
            "接口测试", "自动化测试", "性能测试", "JMeter", "Selenium", "Appium", "Pytest",
            "单元测试", "集成测试", "回归测试", "功能测试", "冒烟测试", "测试用例设计", "缺陷管理",
            "压力测试", "兼容性测试", "安全测试", "接口自动化测试", "APP测试", "性能监控",
            "测试框架", "Mock测试", "代码覆盖率", "Jenkins", "Allure", "测试报告",
            "Web自动化测试", "Locust", "数据库测试", "移动端测试", "异常场景测试", "持续集成");

    /** 计网 / 操作系统 / 数据结构 / 算法方向的技能节点（补充题语义对齐用） */
    public static final List<String> NETWORK_SKILLS = List.of(
            "TCP/IP", "TCP", "UDP", "HTTP", "HTTPS", "DNS", "Socket", "网络分层", "三次握手",
            "操作系统", "进程与线程", "内存管理", "页面置换", "死锁",
            "数据结构", "数组", "链表", "栈", "队列", "哈希表", "二叉树", "二叉搜索树", "图", "堆",
            "算法", "排序", "二分查找", "动态规划", "贪心", "回溯", "分治");

    static {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("JVM 与并发", List.of("JVM", "运行时数据区", "GC 调优", "类加载机制", "并发编程", "JUC", "线程池", "锁"));
        m.put("Java 基础", List.of("Java", "集合框架", "泛型", "反射", "Stream", "设计模式"));
        m.put("Spring 生态", List.of("Spring Boot", "Spring Cloud", "IOC", "AOP", "Spring Security", "MyBatis", "事务管理"));
        m.put("数据库", List.of("MySQL", "索引原理", "SQL 优化", "事务隔离级别", "MVCC", "数据库锁"));
        m.put("缓存", List.of("Redis", "缓存一致性", "缓存穿透", "分布式锁"));
        m.put("消息与异步", List.of("Kafka", "消息可靠性", "削峰", "重试与幂等"));
        m.put("分布式与微服务", List.of("分布式事务", "分布式ID", "服务注册发现", "Nacos", "Sentinel", "网关", "分布式事务"));
        m.put("网络与运维", List.of("HTTP", "TCP/IP", "HTTPS", "Linux", "Docker", "Kubernetes", "Elasticsearch"));
        DOMAIN_SKILLS = m;
    }

    public static boolean isValid(String skill) {
        return match(skill) != null;
    }

    /** 全部技能节点（Java 后端 + 软件测试 + 计网/OS/算法） */
    public static List<String> allSkills() {
        Set<String> set = new LinkedHashSet<>();
        DOMAIN_SKILLS.values().forEach(set::addAll);
        set.addAll(TEST_SKILLS);
        set.addAll(NETWORK_SKILLS);
        return new ArrayList<>(set);
    }

    public static List<String> allGroups() {
        return new ArrayList<>(DOMAIN_SKILLS.keySet());
    }

    /**
     * 把简历中的技能名对齐到图谱节点：先精确匹配，再双向包含匹配。
     * 匹配范围为 Java 后端节点 + 软件测试节点；返回匹配到的节点名；匹配不到返回 null。
     */
    public static String match(String resumeSkill) {
        if (resumeSkill == null || resumeSkill.isBlank()) {
            return null;
        }
        String s = resumeSkill.trim().toLowerCase();
        return matchIn(s, allSkills());
    }

    private static String matchIn(String s, List<String> nodes) {
        for (String node : nodes) {
            if (node.toLowerCase().equals(s)) {
                return node;
            }
        }
        for (String node : nodes) {
            String ns = node.toLowerCase();
            if (s.contains(ns) || ns.contains(s)) {
                return node;
            }
        }
        return null;
    }
}