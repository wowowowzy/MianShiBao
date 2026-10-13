package com.demo.interview.resume;

import com.demo.interview.auth.CurrentUser;
import com.demo.interview.record.RecordRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 简历解析入口（异步）。
 * POST /api/resume/parse   上传简历 → 同步校验文件格式 → 落 PROCESSING 并返回 202 {recordId, status}，
 *                          抽文本 + LLM 结构化在后台完成；结果到个人主页轮询。
 * POST /api/resume/ocr     仅图片识别文字（调试用）。
 */
@Slf4j
@RestController
@RequestMapping("/api/resume")
public class ResumeParseController {

    private final ResumeParseService parseService;
    private final ResumeTextExtractor textExtractor;
    private final RecordRepository recordRepository;

    public ResumeParseController(ResumeParseService parseService, ResumeTextExtractor textExtractor,
                                 RecordRepository recordRepository) {
        this.parseService = parseService;
        this.textExtractor = textExtractor;
        this.recordRepository = recordRepository;
    }

    @PostMapping("/parse")
    public ResponseEntity<?> parse(@RequestParam("file") MultipartFile file,
                                   @RequestParam(required = false) String majorDomain,
                                   @RequestParam(required = false) String role,
                                   HttpServletRequest request) {
        Long userId = CurrentUser.id(request);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(new ErrorMsg("文件读取失败: " + e.getMessage()));
        }
        // 同步校验文件格式，格式不对立即返回，不入队
        try {
            textExtractor.validateContent(bytes, file.getOriginalFilename());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorMsg(e.getMessage()));
        }
        Long recordId = recordRepository.insertProcessingResumeRecord(userId);
        try {
            parseService.process(recordId, bytes, file.getOriginalFilename(), majorDomain, role);
        } catch (TaskRejectedException e) {
            recordRepository.markResumeFailed(recordId, "系统繁忙，请稍后重试");
            return ResponseEntity.status(503).body(new ErrorMsg("系统繁忙，请稍后重试"));
        }
        return ResponseEntity.accepted().body(new ParseResult("PROCESSING", 0, null, recordId));
    }

    @PostMapping("/ocr")
    public ResponseEntity<?> ocr(@RequestParam("file") MultipartFile file) throws IOException {
        ResumeTextExtractor.ExtractedText extracted = textExtractor.extract(file);
        if (!"OCR".equals(extracted.sourceType())) {
            return ResponseEntity.badRequest().body(new ErrorMsg("仅支持图片文件调用 OCR 接口"));
        }
        return ResponseEntity.ok(new OcrResult(extracted.text()));
    }

    record ParseResult(String sourceType, int textLength, ResumeProfile profile, Long recordId) {
    }

    record OcrResult(String text) {
    }

    record ErrorMsg(String message) {
    }
}