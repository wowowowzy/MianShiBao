package com.demo.interview.auth;

/**
 * 注册用户。密码仅存 BCrypt 哈希，不落明文。
 * admin：是否管理员（管理员不消耗每日配额）；banned：是否封禁；quota：今日剩余使用次数。
 */
public record User(Long id, String username, String passwordHash, String createdAt,
                   boolean admin, boolean banned, int quota, String nickname) {
}