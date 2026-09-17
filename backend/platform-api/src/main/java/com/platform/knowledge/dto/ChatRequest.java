/**
 * @author HXN
 * @date 2026-09-15
 * @description 对话请求
 */
package com.platform.knowledge.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class ChatRequest {

    /**
     * 会话 ID（null 则新建会话）
     */
    private Long conversationId;

    /**
     * 用户消息
     */
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 4000, message = "消息内容不超过 4000 字")
    private String message;

    /**
     * 检索返回的 TopK 分块数（默认 5）
     */
    private Integer topK;

    /**
     * 温度参数（0-2，默认 0.7）
     */
    private Double temperature;

    /**
     * 是否流式输出（默认 true）
     */
    private Boolean stream;
}
