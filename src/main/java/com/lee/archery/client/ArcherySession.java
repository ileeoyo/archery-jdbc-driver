package com.lee.archery.client;

/**
 * Archery 登录会话状态，保存 Django CSRF token 供后续 POST 请求使用。
 */
public final class ArcherySession {
    private final String csrfToken;


    public ArcherySession(String csrfToken) {
        this.csrfToken = csrfToken;
    }


    public String getCsrfToken() {
        return csrfToken;
    }
}
