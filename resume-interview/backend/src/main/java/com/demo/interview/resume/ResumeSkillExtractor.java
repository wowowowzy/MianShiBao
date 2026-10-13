package com.demo.interview.resume;

import com.demo.interview.util.JsonLoose;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * 调用 DeepSeek 将简历纯文本结构化抽取为 {@link ResumeProfile}。
 * 用 Spring AI 结构化输出引导 JSON，并复用 {@link JsonLoose} 做宽松解析兜底（LLM 可能夹杂解释文字）。
 */
@Slf4j
@Component
public class ResumeSkillExtractor {

    private final ChatModel chatModel;
    private final ObjectMapper json;

    public ResumeSkillExtractor(ChatModel chatModel, ObjectMapper json) {
        this.chatModel = chatModel;
        this.json = json;
    }

    public ResumeProfile extract(String resumeText) {
        String prompt = buildPrompt(resumeText);
        log.info("调用 LLM 抽取简历结构化信息, 简历文本长度={}", resumeText.length());
        String reply = chatModel.call(new Prompt(prompt)).getResult().getOutput().getText();
        try {
            ResumeProfile profile = JsonLoose.parse(json, reply, new TypeReference<>() {
            });
            return SkillNormalizer.normalize(profile);
        } catch (Exception e) {
            log.error("简历结构化解析失败, LLM 回复前 500 字: {}",
                    reply == null ? "null" : reply.substring(0, Math.min(500, reply.length())), e);
            throw new IllegalStateException("LLM 未返回可解析的结构化结果", e);
        }
    }

    private String buildPrompt(String resumeText) {
        return """
                你是一名资深技术面试官兼简历解析专家。请从下面的简历原文中提取结构化信息，只输出一个合法的 JSON 对象，不要输出任何其他文字。

                要求：
                1. name: 候选人姓名
                2. targetRole: 最匹配的技术岗位方向（本次只关注 Java 后端方向，若候选人力求后端请写："Java 后端工程师"）
                3. yearsExperience: 工作年限整数，应届填 0
                4. school / degree / major: 最高学历信息
                5. skills: 技能点列表。name 使用标准技术命名（如 JDK、JVM、Spring Boot、MyBatis、MySQL、Redis、Kafka、分布式事务、并发编程、设计模式、Linux、Docker、Kubernetes、Nacos、Elasticsearch、HTTP、算法）。level 只能是「精通/熟悉/了解」三者之一。keywords 为简历原文中支撑该技能判断的关键词（每个技能 2 到 5 个）
                6. projects: 项目经历列表。name 为项目名，role 为本人在此项目的角色，techStack 为用到的技术栈，description 概括项目业务背景与本人职责
                7. 简历未提到的字段填合理默认值：yearsExperience 填 0，list 填空数组

                简历原文：
                ----------------
                %s
                ----------------
                """.formatted(resumeText);
    }
}