package com.demo.interview.auth;

import com.demo.interview.config.WechatProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;

/**
 * 微信小程序静默登录：
 *  前端 wx.login() 取到临时 code → 服务端调用 {@code jscode2session} 用 appid+secret 换 openid
 *  （凭 openid 判定老用户 / 新用户，新用户自动建号）→ 签发与账号体系一致的后端 JWT。
 *  so 仅多接口会消耗 LLM 配额，微信登录本身不设注册 IP 限制。
 */
@Slf4j
@Service
public class WechatLoginService {

    private static final String JSCODE2SESSION =
            "https://api.weixin.qq.com/sns/jscode2session?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code";

    private final WechatProperties props;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final ObjectMapper json;
    private final RestClient rest;

    public WechatLoginService(WechatProperties props, UserRepository userRepository,
                              JwtUtil jwtUtil, ObjectMapper json) {
        this.props = props;
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
        this.json = json;
        this.rest = RestClient.builder().build();
    }

    /**
     * 静默登录：code → openid → 找号/建号 → 签发 token。
     *
     * @return token、库中用户名、是否管理员、是否新注册
     */
    public Result login(String code) {
        if (code == null || code.isBlank()) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "登录凭证不能为空");
        }
        String appid = props.getAppid();
        String secret = props.getSecret();
        if (appid == null || appid.isBlank() || secret == null || secret.isBlank()) {
            log.error("微信小程序登录未配置：wechat.miniapp.appid / wechat.miniapp.secret 缺失");
            throw new AuthException(HttpStatus.INTERNAL_SERVER_ERROR, "服务端尚未配置微信小程序登录，请联系管理员");
        }
        String openid = exchangeOpenid(code, appid, secret);

        User user = userRepository.findByOpenid(openid);
        boolean isNew = user == null;
        if (user == null) {
            user = userRepository.insertWechat(usernameFor(openid), openid, LocalDateTime.now().toString());
            log.info("微信用户首次登录自动建号：openid={}, userId={}", openid, user.id());
        }
        if (user.banned()) {
            throw new AuthException(HttpStatus.FORBIDDEN, "账号已被封禁，请联系管理员");
        }
        String token = jwtUtil.generate(user.username(), user.id(), user.admin());
        return new Result(token, user.username(), user.admin(), isNew, user.nickname());
    }

    /** 调用微信 jscode2session；失败抛 AuthException（401 表示 code 无效） */
    private String exchangeOpenid(String code, String appid, String secret) {
        String url = JSCODE2SESSION.formatted(appid, secret, code);
        try {
            String body = rest.get().uri(url).retrieve().body(String.class);
            JsonNode root = json.readTree(body == null ? "" : body);
            JsonNode errcode = root.get("errcode");
            if (errcode != null && errcode.asInt() != 0) {
                String errmsg = root.path("errmsg").asText("unknown");
                log.warn("微信 jscode2session 失败：errcode={} errmsg={}", errcode.asInt(), errmsg);
                throw new AuthException(HttpStatus.UNAUTHORIZED, "微信登录失败：" + errmsg);
            }
            String openid = root.path("openid").asText(null);
            if (openid == null || openid.isBlank()) {
                throw new AuthException(HttpStatus.UNAUTHORIZED, "微信登录失败：未获取到有效身份");
            }
            return openid;
        } catch (AuthException e) {
            throw e;
        } catch (RestClientResponseException | java.io.IOException e) {
            log.error("调用微信 jscode2session 异常", e);
            throw new AuthException(HttpStatus.BAD_GATEWAY, "微信登录服务暂时不可用，请稍后重试");
        }
    }

    /** 由 openid 派生稳定用户名：wx + sha256(openid) 前 12 位十六进制（3~20 位、仅 [a-z0-9_]） */
    private static String usernameFor(String openid) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(openid.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("wx");
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", digest[i] & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "wx" + Long.toHexString(openid.hashCode() & 0xffffffffL);
        }
    }

    /** 微信登录结果 */
    public record Result(String token, String username, boolean admin, boolean isNew, String nickname) {
    }
}