/**
 * @author HXN
 * @date 2026-09-28
 * @description 计划级自定义测试结果列 Mapper 接口
 */
package com.platform.execution.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.execution.entity.PlanResultColumn;
import org.apache.ibatis.annotations.Mapper;

/**
 * 计划级自定义测试结果列 Mapper
 */
@Mapper
public interface PlanResultColumnMapper extends BaseMapper<PlanResultColumn> {
}
