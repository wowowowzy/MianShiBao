package com.demo.interview.admin;

import com.demo.interview.auth.User;
import com.demo.interview.auth.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 每日使用配额：管理员不限次；普通用户每天默认 5 次（quota 字段，0 点定时重置）。
 * 每次「生成/复用面试题」计 1 次，同时写入 usage_log 供后台统计。
 */
@Service
public class UsageService {

    public enum Result {
        OK, BANNED, EXHAUSTED
    }

    private final UserRepository userRepository;
    private final UsageRepository usageRepository;

    public UsageService(UserRepository userRepository, UsageRepository usageRepository) {
        this.userRepository = userRepository;
        this.usageRepository = usageRepository;
    }

    /** 校验并消耗一次使用额度；返回值区分成功/封禁/超限 */
    public Result consume(Long userId, boolean admin) {
        if (!admin) {
            User u = userRepository.findById(userId);
            if (u == null || u.banned()) {
                return Result.BANNED;
            }
            if (userRepository.consumeQuota(userId) <= 0) {
                return Result.EXHAUSTED;
            }
        }
        usageRepository.increment(userId, LocalDate.now().toString());
        return Result.OK;
    }
}