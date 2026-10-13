package com.demo.interview.resume;

import com.demo.interview.record.RecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * 简历解析的异步执行：接口先落 PROCESSING 记录并立即返回，重活（抽文本 + LLM 结构化）在此后台线程完成，
 * 完成后置 DONE 并回填画像，失败置 FAILED。前端靠个人主页轮询感知结果。
 */
@Slf4j
@Service
public class ResumeParseService {

    private final ResumeTextExtractor textExtractor;
    private final ResumeSkillExtractor skillExtractor;
    private final RecordRepository recordRepository;
    private final ObjectMapper json;

    public ResumeParseService(ResumeTextExtractor textExtractor, ResumeSkillExtractor skillExtractor,
                              RecordRepository recordRepository, ObjectMapper json) {
        this.textExtractor = textExtractor;
        this.skillExtractor = skillExtractor;
        this.recordRepository = recordRepository;
        this.json = json;
    }

    @Async("llmExecutor")
    public void process(Long recordId, byte[] bytes, String filename, String majorDomain, String role) {
        try {
            ResumeTextExtractor.ExtractedText extracted = textExtractor.extract(bytes, filename);
            recordRepository.markResumeExtracted(recordId, extracted.sourceType(), extracted.text().length());
            ResumeProfile profile = skillExtractor.extract(extracted.text());
            log.info("简历解析完成 recordId={} sourceType={} 技能点={} 方向={}/{}", recordId, extracted.sourceType(),
                    profile.skills() == null ? 0 : profile.skills().size(), majorDomain, role);
            recordRepository.markResumeDone(recordId, json.writeValueAsString(profile));
        } catch (IOException e) {
            log.warn("简历文件解析失败 recordId={}: {}", recordId, e.getMessage());
            recordRepository.markResumeFailed(recordId, "文件解析失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("简历结构化抽取失败 recordId={}", recordId, e);
            recordRepository.markResumeFailed(recordId,
                    isTimeout(e) ? "AI 解析超时，请稍后重试" : "解析失败: " + e.getMessage());
        }
    }

    /** 判断异常链是否为超时（网络/LLM 超时） */
    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.SocketTimeoutException || t instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null && msg.toLowerCase().contains("timeout")) {
                return true;
            }
        }
        return false;
    }
}