/**
 * @author HXN
 * @date 2026-09-16
 * @description 项目资料变更事件
 */
package com.platform.knowledge.event;

/**
 * 项目资料变更事件
 *
 * <p>由五类项目资料（需求文档/项目文档/源代码/接口文档/界面元素）的增删改操作发布，
 * 知识库同步服务监听该事件并自动将变更同步到项目下所有知识库。</p>
 *
 * <p>来源类型（sourceType）与 sourceId 的对应关系：
 * <ul>
 *   <li>REQUIREMENT_VERSION - 需求版本 ID（版本及其下条目整体为一份知识文档）</li>
 *   <li>PROJECT_DOC - 项目文档 ID</li>
 *   <li>CODE_REPOSITORY - 代码仓库 ID</li>
 *   <li>API_MODULE - 接口模块 ID（模块及其下接口整体为一份知识文档）</li>
 *   <li>UI_ELEMENT - 代码仓库 ID（该仓库下全部界面元素为一份知识文档）</li>
 * </ul></p>
 */
public class ProjectMaterialChangedEvent {

    /**
     * 所属项目 ID
     */
    private final Long projectId;

    /**
     * 来源类型
     */
    private final String sourceType;

    /**
     * 来源记录 ID
     */
    private final Long sourceId;

    public ProjectMaterialChangedEvent(Long projectId, String sourceType, Long sourceId) {
        this.projectId = projectId;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getSourceType() {
        return sourceType;
    }

    public Long getSourceId() {
        return sourceId;
    }
}
