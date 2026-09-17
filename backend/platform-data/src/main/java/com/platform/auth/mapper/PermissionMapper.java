/**
 * @author HXN
 * @date 2026-08-22 13:27
 * @description 权限数据访问接口
 */
package com.platform.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.auth.entity.Permission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 权限 Mapper
 */
@Mapper
public interface PermissionMapper extends BaseMapper<Permission> {

    /**
     * 查询全部启用状态的权限编码列表（内置超管/管理员角色全量展开用）
     */
    @Select("SELECT permission_code FROM permission WHERE is_active = 1")
    List<String> selectAllActivePermissionCodes();
}
