/**
 * @author HXN
 * @date 2026-09-17
 * @description 自定义字段批量排序请求 DTO
 */
package com.platform.sys.dto;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 自定义字段批量排序请求
 *
 * <p>按传入顺序整体重写 sortNo 为连续序号 1、2、3…，
 * orderedIds 必须恰好覆盖同一项目+模块+视图下的全部字段
 */
@Data
public class CustomFieldSortRequest {

    /**
     * 按目标顺序排列的字段 ID 列表
     */
    @NotEmpty(message = "字段 ID 列表不能为空")
    private List<Long> orderedIds;
}
