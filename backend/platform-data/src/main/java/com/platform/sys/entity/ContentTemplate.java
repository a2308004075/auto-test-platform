/**
 * @author HXN
 * @date 2026-09-22
 * @description 内容模板实体类
 */
package com.platform.sys.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 内容模板实体
 *
 * <p>按项目 + 业务类型（缺陷/手动用例/需求）维护"内容"富文本模板（【页面配置-内容模板】），
 * 供各业务新建页自动填入"内容"编辑框（模板仅作用于新建页）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_content_template")
public class ContentTemplate extends BaseEntity {

    /**
     * 所属项目 ID
     */
    private Long projectId;

    /**
     * 业务类型：defect-缺陷，manual_case-手动用例，requirement-需求
     * （同一项目 + 同一业务类型仅维护一个模板）
     */
    private String bizType;

    /**
     * 模板名称（同一项目 + 同一业务类型内不可重复）
     */
    private String name;

    /**
     * 模板内容（富文本 HTML）
     */
    private String content;
}
