/**
 * @author HXN
 * @date 2026-08-30
 * @description 自定义字段定义数据访问接口
 */
package com.platform.sys.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.sys.entity.CustomField;
import org.apache.ibatis.annotations.Mapper;

/**
 * 自定义字段定义 Mapper
 */
@Mapper
public interface CustomFieldMapper extends BaseMapper<CustomField> {
}
