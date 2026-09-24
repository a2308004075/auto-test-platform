/**
 * @author HXN
 * @date 2026-09-22
 * @description 内容模板管理服务
 */
package com.platform.sys.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.sys.dto.ContentTemplateCreateRequest;
import com.platform.sys.dto.ContentTemplateListItem;
import com.platform.sys.entity.ContentTemplate;
import com.platform.sys.mapper.ContentTemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 内容模板管理服务
 *
 * <p>按项目 + 业务类型（缺陷/手动用例/需求）维护"内容"富文本模板（【页面配置-内容模板】），
 * 供各业务新建页自动填入"内容"编辑框（模板仅作用于新建页）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContentTemplateService {

    private final ContentTemplateMapper contentTemplateMapper;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 模板列表（按项目，创建顺序升序；bizType 非空时按业务类型过滤）
     */
    public List<ContentTemplateListItem> listByProject(Long projectId, String bizType) {
        if (projectId == null) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "项目 ID 不能为空");
        }
        LambdaQueryWrapper<ContentTemplate> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ContentTemplate::getProjectId, projectId);
        if (bizType != null && !bizType.trim().isEmpty()) {
            wrapper.eq(ContentTemplate::getBizType, bizType.trim());
        }
        wrapper.orderByAsc(ContentTemplate::getId);
        return contentTemplateMapper.selectList(wrapper).stream()
                .map(this::toListItem)
                .collect(Collectors.toList());
    }

    /**
     * 新建模板（名称在同一项目 + 同一业务类型内不可重复）
     */
    @Transactional(rollbackFor = Exception.class)
    public ContentTemplateListItem create(ContentTemplateCreateRequest request) {
        String bizType = request.getBizType().trim();
        String name = request.getName().trim();
        validateNameUnique(request.getProjectId(), bizType, name, null);

        ContentTemplate template = new ContentTemplate();
        template.setProjectId(request.getProjectId());
        template.setBizType(bizType);
        template.setName(name);
        template.setContent(request.getContent());
        contentTemplateMapper.insert(template);
        return toListItem(template);
    }

    /**
     * 更新模板（归属项目与业务类型不可变更，防止跨项目/跨类型迁移）
     */
    @Transactional(rollbackFor = Exception.class)
    public ContentTemplateListItem update(Long id, ContentTemplateCreateRequest request) {
        ContentTemplate template = contentTemplateMapper.selectById(id);
        if (template == null) {
            throw new BusinessException(ErrorCode.CONTENT_TEMPLATE_NOT_FOUND, "内容模板不存在");
        }
        // 业务类型不可变更：请求类型必须与库中一致（防止跨类型误更新，如缺陷模板被手动用例入口覆盖）
        if (!template.getBizType().equals(request.getBizType().trim())) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "业务类型不可变更");
        }
        String name = request.getName().trim();
        validateNameUnique(template.getProjectId(), template.getBizType(), name, id);

        template.setName(name);
        template.setContent(request.getContent());
        contentTemplateMapper.updateById(template);
        return toListItem(template);
    }

    /**
     * 删除模板（物理删除：模板无下游引用数据）
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        ContentTemplate template = contentTemplateMapper.selectById(id);
        if (template == null) {
            throw new BusinessException(ErrorCode.CONTENT_TEMPLATE_NOT_FOUND, "内容模板不存在");
        }
        contentTemplateMapper.deleteById(id);
    }

    /**
     * 模板名称唯一性校验：同一项目 + 同一业务类型内不可重复
     *
     * <p>编辑时排除自身（excludeId）；比较前去除首尾空格，
     * 库表排序规则为 utf8mb4_unicode_ci（大小写不敏感）
     *
     * @param excludeId 需排除的模板 ID（编辑场景传当前 ID，新建场景传 null）
     */
    private void validateNameUnique(Long projectId, String bizType, String name, Long excludeId) {
        LambdaQueryWrapper<ContentTemplate> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ContentTemplate::getProjectId, projectId)
                .eq(ContentTemplate::getBizType, bizType)
                .eq(ContentTemplate::getName, name);
        if (excludeId != null) {
            wrapper.ne(ContentTemplate::getId, excludeId);
        }
        Long count = contentTemplateMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.CONTENT_TEMPLATE_NAME_DUPLICATE,
                    "模板名称「" + name + "」已存在");
        }
    }

    private ContentTemplateListItem toListItem(ContentTemplate template) {
        ContentTemplateListItem item = new ContentTemplateListItem();
        item.setId(template.getId());
        item.setProjectId(template.getProjectId());
        item.setBizType(template.getBizType());
        item.setName(template.getName());
        item.setContent(template.getContent());
        if (template.getCreatedAt() != null) {
            item.setCreatedAt(template.getCreatedAt().format(DT_FMT));
        }
        if (template.getUpdatedAt() != null) {
            item.setUpdatedAt(template.getUpdatedAt().format(DT_FMT));
        }
        return item;
    }
}
