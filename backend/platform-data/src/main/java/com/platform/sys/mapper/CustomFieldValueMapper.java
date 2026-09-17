/**
 * @author HXN
 * @date 2026-08-30
 * @description 自定义字段值数据访问接口
 */
package com.platform.sys.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.sys.entity.CustomFieldValue;
import org.apache.ibatis.annotations.Mapper;

/**
 * 自定义字段值 Mapper
 */
@Mapper
public interface CustomFieldValueMapper extends BaseMapper<CustomFieldValue> {
}
