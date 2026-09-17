/**
 * @author HXN
 * @date 2026-09-15
 * @description HTTP 端点可达性探测器（OkHttp）
 */
package com.platform.ai.engine;

import com.platform.ai.dto.ScanConfigRequest;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * HTTP 端点探测器
 *
 * <p>对提取到的端点拼接 envUrl 发送 HTTP 请求，判断可达性。
 * 支持 none/form/token 三种认证方式。</p>
 */
@Slf4j
@Component
public class HttpProber {

    private static final int CONNECT_TIMEOUT = 10;
    private static final int READ_TIMEOUT = 30;

    /**
     * 探测端点可达性
     *
     * @param endpoint     待探测端点
     * @param envUrl       目标环境基础 URL
     * @param authType     认证方式
     * @param authConfig   认证配置（可为 null）
     * @param excludePaths 排除路径列表
     * @return 探测结果
     */
    public ProbeResult probe(EndpointExtractor.ExtractedEndpoint endpoint,
                             String envUrl,
                             String authType,
                             ScanConfigRequest.AuthConfig authConfig,
                             List<String> excludePaths) {
        String fullPath = endpoint.getPath();

        // 检查排除路径
        if (isExcluded(fullPath, excludePaths)) {
            ProbeResult result = new ProbeResult();
            result.setReachable(false);
            result.setStatusCode(0);
            result.setResponseTimeMs(0);
            return result;
        }

        String url = normalizeUrl(envUrl) + fullPath;
        OkHttpClient client = buildClient();

        try {
            // 构建认证请求头
            Request.Builder reqBuilder = new Request.Builder().url(url);

            if ("token".equals(authType) && authConfig != null && authConfig.getToken() != null) {
                reqBuilder.header("Authorization", "Bearer " + authConfig.getToken());
            } else if ("form".equals(authType) && authConfig != null) {
                // 先执行表单登录获取 Cookie
                String cookie = formLogin(client, envUrl, authConfig);
                if (cookie != null) {
                    reqBuilder.header("Cookie", cookie);
                }
            }

            // 根据 HTTP 方法构建请求
            String method = endpoint.getHttpMethod();
            if ("POST".equals(method)) {
                reqBuilder.post(RequestBody.create("{}", MediaType.parse("application/json")));
            } else if ("PUT".equals(method)) {
                reqBuilder.put(RequestBody.create("{}", MediaType.parse("application/json")));
            } else if ("DELETE".equals(method)) {
                reqBuilder.delete();
            } else {
                reqBuilder.get();
            }

            long start = System.currentTimeMillis();
            try (Response response = client.newCall(reqBuilder.build()).execute()) {
                long elapsed = System.currentTimeMillis() - start;
                ProbeResult result = new ProbeResult();
                result.setStatusCode(response.code());
                result.setReachable(response.code() < 500);
                result.setResponseTimeMs(elapsed);
                // 保存响应体用于后续漏洞检测
                ResponseBody body = response.body();
                if (body != null) {
                    String bodyStr = body.string();
                    result.setResponseBody(bodyStr.length() > 10000 ? bodyStr.substring(0, 10000) : bodyStr);
                }
                return result;
            }
        } catch (IOException e) {
            log.debug("端点探测失败: {} - {}", url, e.getMessage());
            ProbeResult result = new ProbeResult();
            result.setReachable(false);
            result.setStatusCode(0);
            result.setResponseTimeMs(0);
            return result;
        }
    }

    /**
     * 表单登录获取 Cookie
     */
    private String formLogin(OkHttpClient client, String envUrl, ScanConfigRequest.AuthConfig authConfig) {
        String loginUrl = normalizeUrl(envUrl) +
                (authConfig.getLoginUrl() != null ? authConfig.getLoginUrl() : "/login");

        String usernameField = authConfig.getUsernameField() != null ? authConfig.getUsernameField() : "username";
        String passwordField = authConfig.getPasswordField() != null ? authConfig.getPasswordField() : "password";

        FormBody formBody = new FormBody.Builder()
                .add(usernameField, authConfig.getUsername() != null ? authConfig.getUsername() : "")
                .add(passwordField, authConfig.getPassword() != null ? authConfig.getPassword() : "")
                .build();

        Request loginReq = new Request.Builder()
                .url(loginUrl)
                .post(formBody)
                .build();

        try (Response response = client.newCall(loginReq).execute()) {
            // 提取 Set-Cookie 头
            List<String> cookies = response.headers("Set-Cookie");
            if (!cookies.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String c : cookies) {
                    if (sb.length() > 0) sb.append("; ");
                    // 只取 cookie 的 key=value 部分
                    int semi = c.indexOf(';');
                    sb.append(semi > 0 ? c.substring(0, semi) : c);
                }
                return sb.toString();
            }
        } catch (IOException e) {
            log.warn("表单登录失败: {}", loginUrl, e);
        }
        return null;
    }

    private OkHttpClient buildClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }

    private String normalizeUrl(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private boolean isExcluded(String path, List<String> excludePaths) {
        if (excludePaths == null || excludePaths.isEmpty()) return false;
        for (String exclude : excludePaths) {
            if (exclude != null && !exclude.isEmpty() && path.startsWith(exclude)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 探测结果
     */
    @Data
    public static class ProbeResult {
        private boolean reachable;
        private int statusCode;
        private long responseTimeMs;
        private String responseBody;
    }
}
