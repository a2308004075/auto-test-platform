/**
 * @author HXN
 * @date 2026-08-30
 * @description 手动化用例实体类
 */
package com.platform.execution.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 手动化用例实体
 *
 * <p>对应数据库 manual_case 表。用户手动编写的测试用例，
 * 包含标题、内容（富文本，统一维护前置条件/操作步骤/预期结果）等字段。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("manual_case")
public class ManualCase extends BaseEntity {

    /**
     * 所属项目 ID
     */
    private Long projectId;

    /**
     * 所属分组 ID
     */
    private Long groupId;

    /**
     * 用例标题
     */
    private String title;

    /**
     * 用例内容（富文本：前置条件/操作步骤/预期结果统一在一个编辑器维护，参考缺陷"内容"）
     */
    private String content;

    /**
     * 用例类型：NORMAL-正常，EXCEPTION-异常
     */
    private String caseType;

    /**
     * 优先级：高/中/低
     */
    private String priority;

    /**
     * 测试环境是否执行（1-是，0-否）
     */
    private Integer runInTestEnv;

    /**
     * 生产环境是否执行（1-是，0-否）
     */
    private Integer runInProdEnv;

    /**
     * 用例状态（1-使用，0-废弃）
     */
    private Integer caseStatus;

    /**
     * 创建人 ID
     */
    private Long createdBy;
}
