package com.demo.interview.auth;

import org.springframework.http.HttpStatus;

/**
 * 鉴权业务异常：携带 HTTP 状态码，由 AuthController 捕获后转成 {message} JSON 响应。
 */
public class AuthException extends RuntimeException {

    private final HttpStatus status;

    public AuthException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}