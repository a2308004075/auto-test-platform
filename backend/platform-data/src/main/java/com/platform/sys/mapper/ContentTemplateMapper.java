/**
 * @author HXN
 * @date 2026-09-22
 * @description 内容模板数据访问接口
 */
package com.platform.sys.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.sys.entity.ContentTemplate;
import org.apache.ibatis.annotations.Mapper;

/**
 * 内容模板 Mapper
 */
@Mapper
public interface ContentTemplateMapper extends BaseMapper<ContentTemplate> {
}
