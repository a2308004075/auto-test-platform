/**
 * @author HXN
 * @date 2026-09-22
 * @description 内容模板列表项响应 DTO
 */
package com.platform.sys.dto;

import lombok.Data;

/**
 * 内容模板列表项（【页面配置-内容模板】与缺陷页模板选择共用）
 */
@Data
public class ContentTemplateListItem {

    private Long id;
    private Long projectId;
    private String bizType;
    private String name;
    private String content;
    private String createdAt;
    private String updatedAt;
}
