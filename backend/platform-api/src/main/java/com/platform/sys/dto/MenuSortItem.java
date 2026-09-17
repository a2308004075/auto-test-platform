/**
 * @author HXN
 * @date 2026-09-16
 * @description MenuSortItem
 */
package com.platform.sys.dto;

import lombok.Data;

/**
 * 菜单排序项（菜单管理编辑模式拖拽保存）
 */
@Data
public class MenuSortItem {

    /**
     * 菜单 ID
     */
    private Long id;

    /**
     * 父级菜单 ID（0 为顶级）
     */
    private Long parentId;

    /**
     * 排序号（同级内升序）
     */
    private Integer sortNo;
}
