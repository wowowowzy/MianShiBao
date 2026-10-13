package com.demo.interview.admin;

import com.demo.interview.auth.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日 0 点把普通用户的使用次数统一重置为默认配额（管理员不受影响）。
 */
@Slf4j
@Component
public class QuotaResetTask {

    private final UserRepository userRepository;

    public QuotaResetTask(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** 每天 00:00:00 触发 */
    @Scheduled(cron = "0 0 0 * * *")
    public void resetDailyQuota() {
        int n = userRepository.resetNormalQuota();
        log.info("每日 0 点重置普通用户配额：影响 {} 个用户（恢复为 {} 次）", n, UserRepository.DEFAULT_QUOTA);
    }
}