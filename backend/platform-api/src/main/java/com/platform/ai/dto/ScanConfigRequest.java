/**
 * @author HXN
 * @date 2026-09-15
 * @description 扫描配置请求 DTO
 */
package com.platform.ai.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import java.io.Serializable;
import java.util.List;

/**
 * 启动 AI 渗透测试扫描请求
 */
@Data
public class ScanConfigRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 仓库地址列表
     */
    @NotEmpty(message = "至少需要一个代码仓库地址")
    private List<@NotBlank(message = "仓库地址不能为空") @Size(max = 500) String> repos;

    /**
     * 测试环境 URL
     */
    @NotBlank(message = "测试环境 URL 不能为空")
    @Size(max = 500, message = "测试环境 URL 长度不能超过 500")
    private String envUrl;

    /**
     * 认证方式：none/form/token
     */
    private String authType;

    /**
     * 认证配置
     */
    private AuthConfig authConfig;

    /**
     * 排除路径列表
     */
    private List<String> excludePaths;

    /**
     * 认证配置
     */
    @Data
    public static class AuthConfig implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * 登录 URL
         */
        private String loginUrl;

        /**
         * 用户名字段名
         */
        private String usernameField;

        /**
         * 密码字段名
         */
        private String passwordField;

        /**
         * 用户名
         */
        private String username;

        /**
         * 密码
         */
        private String password;

        /**
         * API Token
         */
        private String token;

        /**
         * TOTP Secret
         */
        private String totpSecret;
    }
}
