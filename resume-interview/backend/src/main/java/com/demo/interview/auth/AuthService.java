package com.demo.interview.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 注册 / 登录：校验入参、BCrypt 加密存库、登录成功签发（含 admin 的）JWT。
 * 公共注册默认限制「一个 IP 只允许注册一个账号」（enforceIp=true）。
 */
@Slf4j
@Service
public class AuthService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_]+");

    private final UserRepository userRepository;
    private final RegisterIpRepository registerIpRepository;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository userRepository, RegisterIpRepository registerIpRepository, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.registerIpRepository = registerIpRepository;
        this.jwtUtil = jwtUtil;
    }

    /** 普通用户注册（不限制 IP，供内部/兼容调用） */
    public User register(String username, String password) {
        return register(username, password, false, null, false);
    }

    /** 注册（管理员可创建管理员账户，不限制 IP） */
    public User register(String username, String password, boolean admin) {
        return register(username, password, admin, null, false);
    }

    /**
     * 注册；enforceIp=true 时同一个来源 IP 只允许注册一个账号。
     */
    public User register(String username, String password, boolean admin, String ip, boolean enforceIp) {
        String name = username == null ? "" : username.trim();
        if (name.length() < 3 || name.length() > 20) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "用户名长度需在 3-20 个字符之间");
        }
        if (!USERNAME_PATTERN.matcher(name).matches()) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "用户名只能包含字母、数字或下划线");
        }
        if (password == null || password.isEmpty()) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "密码不能为空");
        }
        if (userRepository.findByUsername(name) != null) {
            throw new AuthException(HttpStatus.CONFLICT, "用户名已存在");
        }
        if (enforceIp && ip != null && !ip.isBlank() && registerIpRepository.exists(ip)) {
            throw new AuthException(HttpStatus.CONFLICT, "该 IP 已注册过账户（一个 IP 仅可注册一个账号）");
        }
        User user = userRepository.insert(name, encoder.encode(password), LocalDateTime.now().toString(), admin);
        if (ip != null && !ip.isBlank()) {
            try {
                registerIpRepository.save(ip, user.id());
            } catch (Exception e) {
                // 并发下唯一键冲突等：不影响注册结果，仅记日志
                log.warn("注册 IP 记录失败 ip={} userId={}: {}", ip, user.id(), e.getMessage());
            }
        }
        log.info("用户注册成功：{}（admin={}, ip={}）", name, admin, ip);
        return user;
    }

    /** 登录成功返回 token 与规范用户名（失败抛 AuthException 401） */
    public LoginResult login(String username, String password) {
        String name = username == null ? "" : username.trim();
        User user = userRepository.findByUsername(name);
        if (user == null || !encoder.matches(password == null ? "" : password, user.passwordHash())) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.banned()) {
            throw new AuthException(HttpStatus.FORBIDDEN, "账号已被封禁，请联系管理员");
        }
        String token = jwtUtil.generate(user.username(), user.id(), user.admin());
        log.info("用户登录成功：{}（admin={}）", user.username(), user.admin());
        return new LoginResult(token, user.username(), user.admin());
    }

    /** 登录结果：token、库中真实用户名、是否管理员 */
    public record LoginResult(String token, String username, boolean admin) {
    }
}