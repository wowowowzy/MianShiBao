package com.demo.interview.admin;

import com.demo.interview.auth.User;
import com.demo.interview.bigtech.BigtechQuestionImporter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 管理后台接口（整体经 {@code AdminInterceptor} 校验管理员身份）。
 * <pre>
 * GET  /api/admin/overview            大屏概览
 * GET  /api/admin/users               用户列表
 * GET  /api/admin/resumes?limit=100   简历解析记录（含画像）
 * POST /api/admin/users               代注册 {username,password,admin}
 * POST /api/admin/users/{id}/ban      封禁/解封 {value}
 * POST /api/admin/users/{id}/quota    调整今日剩余次数 {quota}
 * POST /api/admin/users/{id}/admin    设为/取消管理员 {value}
 * POST /api/admin/interview/import    常规题库导入 {filePath,source,maxQuestions}
 * POST /api/admin/bigtech/import      大厂题库导入 {filePath,company,maxQuestions}
 * </pre>
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final InterviewImporter interviewImporter;
    private final BigtechQuestionImporter bigtechImporter;

    public AdminController(AdminService adminService, InterviewImporter interviewImporter,
                           BigtechQuestionImporter bigtechImporter) {
        this.adminService = adminService;
        this.interviewImporter = interviewImporter;
        this.bigtechImporter = bigtechImporter;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return adminService.overview();
    }

    @GetMapping("/users")
    public Map<String, Object> users() {
        var list = adminService.users();
        return Map.of("count", list.size(), "items", list);
    }

    @GetMapping("/resumes")
    public Map<String, Object> resumes(@RequestParam(defaultValue = "100") int limit) {
        var list = adminService.resumes(limit);
        return Map.of("count", list.size(), "items", list);
    }

    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestBody CreateUserBody body) {
        User u = adminService.createUser(body.username(), body.password(),
                body.admin() != null && body.admin());
        return ResponseEntity.ok(Map.of("id", u.id(), "username", u.username(), "admin", u.admin()));
    }

    @PostMapping("/users/{id}/ban")
    public ResponseEntity<?> ban(@PathVariable long id, @RequestBody FlagBody body) {
        adminService.setBanned(id, body.value());
        return ResponseEntity.ok(Map.of("id", id, "banned", body.value()));
    }

    @PostMapping("/users/{id}/quota")
    public ResponseEntity<?> quota(@PathVariable long id, @RequestBody QuotaBody body) {
        int q = body.quota() == null ? 0 : body.quota();
        adminService.setQuota(id, q);
        return ResponseEntity.ok(Map.of("id", id, "quota", q));
    }

    @PostMapping("/users/{id}/admin")
    public ResponseEntity<?> setAdmin(@PathVariable long id, @RequestBody FlagBody body) {
        adminService.setAdmin(id, body.value());
        return ResponseEntity.ok(Map.of("id", id, "admin", body.value()));
    }

    /** 常规题库导入：读取本地题库文本 → LLM 清洗 → 去重入库 */
    @PostMapping("/interview/import")
    public ResponseEntity<?> importInterview(@RequestBody ImportBody body) {
        if (body.filePath() == null || body.filePath().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "filePath 不能为空"));
        }
        if (!Files.exists(Path.of(body.filePath()))) {
            return ResponseEntity.badRequest().body(Map.of("message", "文件不存在：" + body.filePath()));
        }
        try {
            String source = body.source() == null ? "imported" : body.source();
            InterviewImporter.ImportResult result = (body.maxQuestions() != null && body.maxQuestions() > 0)
                    ? interviewImporter.runFile(body.filePath(), source, body.maxQuestions())
                    : interviewImporter.run(body.filePath(), source);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("message", "导入失败: " + e.getMessage()));
        }
    }

    /** 大厂题库导入：按公司清洗入库到 bigtech_question */
    @PostMapping("/bigtech/import")
    public ResponseEntity<?> importBigtech(@RequestBody BigtechImportBody body) {
        if (body.filePath() == null || body.filePath().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "filePath 不能为空"));
        }
        if (!Files.exists(Path.of(body.filePath()))) {
            return ResponseEntity.badRequest().body(Map.of("message", "文件不存在：" + body.filePath()));
        }
        try {
            String company = body.company() == null ? "未知公司" : body.company();
            int max = body.maxQuestions() == null ? 0 : body.maxQuestions();
            return ResponseEntity.ok(bigtechImporter.runFile(body.filePath(), company, max));
        } catch (Exception e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("message", "导入失败: " + e.getMessage()));
        }
    }

    record CreateUserBody(String username, String password, Boolean admin) {
    }

    record FlagBody(boolean value) {
    }

    record QuotaBody(Integer quota) {
    }

    record ImportBody(String filePath, Integer maxQuestions, String source) {
    }

    record BigtechImportBody(String filePath, String company, Integer maxQuestions) {
    }
}