/**
 * @author HXN
 * @date 2026-08-22 13:28
 * @description 字典查询服务
 */
package com.platform.sys.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.sys.dto.DictListItem;
import com.platform.sys.entity.Dict;
import com.platform.sys.mapper.DictMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 字典查询服务（字典管理功能已下线，仅保留字典数据读取）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DictService {

    private final DictMapper dictMapper;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 根据字典类型查询字典值列表
     */
    public List<DictListItem> getByType(String dictType) {
        LambdaQueryWrapper<Dict> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Dict::getDictType, dictType)
                .eq(Dict::getIsActive, 1)
                .orderByAsc(Dict::getSortNo)
                .orderByAsc(Dict::getId);
        List<Dict> dicts = dictMapper.selectList(wrapper);
        return dicts.stream().map(this::toListItem).collect(Collectors.toList());
    }

    // ===== 私有方法 =====

    private DictListItem toListItem(Dict dict) {
        DictListItem item = new DictListItem();
        item.setId(dict.getId());
        item.setDictType(dict.getDictType());
        item.setDictTypeName(dict.getDictTypeName());
        item.setDictValue(dict.getDictValue());
        item.setDictValueName(dict.getDictValueName());
        item.setSortNo(dict.getSortNo());
        item.setRemark(dict.getRemark());
        if (dict.getCreatedAt() != null) {
            item.setCreatedAt(dict.getCreatedAt().format(DT_FMT));
        }
        if (dict.getUpdatedAt() != null) {
            item.setUpdatedAt(dict.getUpdatedAt().format(DT_FMT));
        }
        return item;
    }
}
