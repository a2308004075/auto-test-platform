/**
 * @author HXN
 * @date 2026-09-22
 * @description 手动用例缺陷关联创建请求 DTO
 */
package com.platform.execution.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 手动用例缺陷关联创建请求
 *
 * <p>新建手动用例时暂存的缺陷关联；后端在用例创建完成后，
 * 以 targetType=MANUAL_CASE 写入 defect_relation 表。</p>
 */
@Data
public class ManualCaseDefectRelationCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 关联类型：RELATED/BLOCK/DUPLICATE/CHILD（默认 RELATED）
     */
    private String relationType;

    /**
     * 关联的缺陷 ID
     */
    @NotNull(message = "关联缺陷 ID 不能为空")
    private Long defectId;
}
