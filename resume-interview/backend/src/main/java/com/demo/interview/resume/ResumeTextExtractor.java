package com.demo.interview.resume;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 简历文件 → 纯文本。分流策略：
 * - PDF：PDFBox 提取文字；若提取为空判定为扫描件（PDF 转图 OCR 属后续迭代）
 * - docx/doc：Apache POI 提取段落文本
 * - png/jpg/txt：图片走智谱 OCR，txt 直读
 */
@Slf4j
@Component
public class ResumeTextExtractor {

    private final ZhipuOcrClient ocrClient;

    public ResumeTextExtractor(ZhipuOcrClient ocrClient) {
        this.ocrClient = ocrClient;
    }

    public record ExtractedText(String text, String sourceType) {
    }

    /** MultipartFile 便捷入口（同步接口用） */
    public ExtractedText extract(MultipartFile file) throws IOException {
        return extract(file.getBytes(), file.getOriginalFilename());
    }

    /** 从字节 + 原文件名提取文本（异步处理时文件流已关闭，用字节副本） */
    public ExtractedText extract(byte[] bytes, String originalFilename) throws IOException {
        String name = originalFilename == null ? "" : originalFilename.toLowerCase();

        if (name.endsWith(".pdf")) {
            return new ExtractedText(extractPdf(bytes), "PDF");
        }
        if (name.endsWith(".docx") || name.endsWith(".doc")) {
            return new ExtractedText(extractWord(bytes), "Word");
        }
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            String mime = name.endsWith(".png") ? "image/png" : "image/jpeg";
            String base64 = Base64.getEncoder().encodeToString(bytes);
            return new ExtractedText(ocrClient.recognize(base64, mime), "OCR");
        }
        if (name.endsWith(".txt") || name.endsWith(".md")) {
            return new ExtractedText(new String(bytes, StandardCharsets.UTF_8), "TXT");
        }
        throw new IOException("不支持的文件类型：" + name + "（支持 PDF/Word/图片/TXT）");
    }

    private String extractPdf(byte[] bytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(doc);
            if (text == null || text.isBlank()) {
                throw new IOException("PDF 未提取到文字，疑似扫描件。当前版本请转成图片上传（扫描版 PDF 逐页转图 OCR 为后续迭代项）");
            }
            return text;
        }
    }

    private String extractWord(byte[] bytes) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new java.io.ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            doc.getParagraphs().forEach(p -> {
                if (!p.getText().isBlank()) {
                    sb.append(p.getText()).append("\n");
                }
            });
            for (var table : doc.getTables()) {
                table.getRows().forEach(row -> {
                    row.getTableCells().forEach(cell -> sb.append(cell.getText().trim()).append(" | "));
                    sb.append("\n");
                });
            }
            return sb.toString();
        }
    }

    /**
     * 校验文件内容与扩展名是否匹配（防伪造格式 / 错误文件）。不匹配抛 IllegalArgumentException。
     */
    public void validateContent(byte[] bytes, String originalFilename) {
        String name = originalFilename == null ? "" : originalFilename.toLowerCase();
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文件为空");
        }
        if (name.endsWith(".pdf")) {
            if (!startsWith(bytes, "%PDF")) {
                throw new IllegalArgumentException("文件内容不是 PDF，请检查文件格式");
            }
        } else if (name.endsWith(".docx") || name.endsWith(".doc")) {
            boolean zipOrOle = startsWith(bytes, "PK")
                    || startsWith(bytes, new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0});
            if (!zipOrOle) {
                throw new IllegalArgumentException("文件内容不是 Word 文档，请检查文件格式");
            }
        } else if (name.endsWith(".png")) {
            boolean png = bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50
                    && bytes[2] == 0x4E && bytes[3] == 0x47;
            if (!png) {
                throw new IllegalArgumentException("文件内容不是 PNG 图片，请检查文件格式");
            }
        } else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            boolean jpg = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
                    && (bytes[2] & 0xFF) == 0xFF;
            if (!jpg) {
                throw new IllegalArgumentException("文件内容不是 JPG 图片，请检查文件格式");
            }
        } else if (name.endsWith(".txt") || name.endsWith(".md")) {
            // 纯文本无固定魔数，放行
        } else {
            throw new IllegalArgumentException("不支持的文件类型（支持 PDF/Word/图片/TXT）");
        }
    }

    private static boolean startsWith(byte[] data, String prefix) {
        return startsWith(data, prefix.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}