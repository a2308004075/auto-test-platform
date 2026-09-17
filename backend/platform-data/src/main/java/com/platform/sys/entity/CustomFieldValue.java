/**
 * @author HXN
 * @date 2026-08-30
 * @description 自定义字段值实体类
 */
package com.platform.sys.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 自定义字段值实体
 *
 * <p>存储动态字段在业务实体上的实际值
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_custom_field_value")
public class CustomFieldValue extends BaseEntity {

    /**
     * 关联 sys_custom_field.id
     */
    private Long fieldId;

    /**
     * 模块标识：defect / requirement
     */
    private String module;

    /**
     * 业务实体 ID（如 defect.id / requirement_item.id）
     */
    private Long entityId;

    /**
     * 字段值（文本形式存储）
     */
    private String fieldValue;
}
