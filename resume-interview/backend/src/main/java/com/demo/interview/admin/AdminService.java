package com.demo.interview.admin;

import com.demo.interview.auth.AuthService;
import com.demo.interview.auth.User;
import com.demo.interview.auth.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台业务：概览统计、用户管理（注册/封禁/调额度/设管理员）、简历解析信息。
 */
@Service
public class AdminService {

    private final AdminQueryRepository queryRepository;
    private final UserRepository userRepository;
    private final AuthService authService;

    public AdminService(AdminQueryRepository queryRepository, UserRepository userRepository,
                        AuthService authService) {
        this.queryRepository = queryRepository;
        this.userRepository = userRepository;
        this.authService = authService;
    }

    /** 大屏概览：总计 + 今日使用 + 近 14 天趋势 + 用户使用排行 */
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", queryRepository.stats());
        out.put("today", LocalDate.now().toString());
        out.put("trend", queryRepository.usageTrend(14));
        out.put("userUsage", queryRepository.listUsers());
        out.put("defaultQuota", UserRepository.DEFAULT_QUOTA);
        return out;
    }

    public List<AdminQueryRepository.UserRow> users() {
        return queryRepository.listUsers();
    }

    public List<AdminQueryRepository.ResumeRow> resumes(int limit) {
        return queryRepository.listResumes(limit <= 0 ? 100 : Math.min(limit, 500));
    }

    /** 代注册账号（可创建管理员） */
    public User createUser(String username, String password, boolean admin) {
        return authService.register(username, password, admin);
    }

    public void setBanned(Long userId, boolean banned) {
        userRepository.setBanned(userId, banned);
    }

    public void setQuota(Long userId, int quota) {
        userRepository.setQuota(userId, quota);
    }

    public void setAdmin(Long userId, boolean admin) {
        userRepository.setAdmin(userId, admin);
    }
}