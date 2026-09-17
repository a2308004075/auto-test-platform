/**
 * @author HXN
 * @date 2026-08-30
 * @description 业务类型常量
 */
package com.platform.common.constant;

/**
 * 评论与变更记录支持的通用业务类型
 */
public final class BizType {

    private BizType() {
    }

    /**
     * 需求条目
     */
    public static final String REQUIREMENT_ITEM = "REQUIREMENT_ITEM";

    /**
     * 手动化用例
     */
    public static final String MANUAL_CASE = "MANUAL_CASE";

    /**
     * 缺陷
     */
    public static final String DEFECT = "DEFECT";
}
