/**
 * @author HXN
 * @date 2026-09-15
 * @description 创建知识库请求
 */
package com.platform.knowledge.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class KnowledgeBaseCreateRequest {

    @NotBlank(message = "知识库名称不能为空")
    @Size(max = 200, message = "知识库名称不超过 200 字")
    private String name;

    @Size(max = 2000, message = "描述不超过 2000 字")
    private String description;
}
