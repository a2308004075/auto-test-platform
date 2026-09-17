/**
 * @author HXN
 * @date 2026-09-15
 * @description 需求分组数据访问接口
 */
package com.platform.requirement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.requirement.entity.RequirementGroup;
import org.apache.ibatis.annotations.Mapper;

/**
 * 需求分组 Mapper
 */
@Mapper
public interface RequirementGroupMapper extends BaseMapper<RequirementGroup> {
}
