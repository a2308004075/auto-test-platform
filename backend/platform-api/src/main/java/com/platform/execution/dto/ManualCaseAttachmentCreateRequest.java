/**
 * @author HXN
 * @date 2026-09-22
 * @description 手动用例附件创建请求 DTO
 */
package com.platform.execution.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;

/**
 * 手动用例附件创建请求
 */
@Data
public class ManualCaseAttachmentCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "附件文件名不能为空")
    private String fileName;

    @NotBlank(message = "附件链接不能为空")
    private String fileUrl;

    private Long fileSize;
}
