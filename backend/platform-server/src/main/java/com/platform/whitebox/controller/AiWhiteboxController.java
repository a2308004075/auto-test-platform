/**
 * @author HXN
 * @date 2026-09-15
 * @description AI 白盒测试控制器
 */
package com.platform.whitebox.controller;

import com.platform.common.response.ApiResponse;
import com.platform.whitebox.dto.WhiteboxMethodResponse;
import com.platform.whitebox.dto.WhiteboxReportResponse;
import com.platform.whitebox.dto.WhiteboxSaveTestsRequest;
import com.platform.whitebox.dto.WhiteboxStartRequest;
import com.platform.whitebox.dto.WhiteboxTaskResponse;
import com.platform.whitebox.dto.WhiteboxTestResponse;
import com.platform.whitebox.entity.AiWhiteboxReport;
import com.platform.whitebox.service.AiWhiteboxService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * AI 白盒测试控制器
 *
 * <p>所有接口均挂载在项目维度下：{@code /api/v1/projects/{projectId}/ai-whitebox}</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/ai-whitebox")
public class AiWhiteboxController {

    private final AiWhiteboxService whiteboxService;

    public AiWhiteboxController(AiWhiteboxService whiteboxService) {
        this.whiteboxService = whiteboxService;
    }

    /**
     * 启动白盒测试任务
     */
    @PostMapping("/tasks")
    @PreAuthorize("hasAuthority('project:ai-whitebox:run')")
    public ApiResponse<WhiteboxTaskResponse> startTask(@PathVariable Long projectId,
                                                       @Valid @RequestBody WhiteboxStartRequest request) {
        WhiteboxTaskResponse response = whiteboxService.startTask(projectId, request);
        return ApiResponse.ok(response);
    }

    /**
     * 停止白盒测试任务
     */
    @PostMapping("/tasks/{taskId}/stop")
    @PreAuthorize("hasAuthority('project:ai-whitebox:run')")
    public ApiResponse<Void> stopTask(@PathVariable Long projectId,
                                      @PathVariable Long taskId) {
        whiteboxService.stopTask(projectId, taskId);
        return ApiResponse.ok();
    }

    /**
     * 查询项目下的白盒测试任务列表
     */
    @GetMapping("/tasks")
    @PreAuthorize("hasAuthority('project:ai-whitebox')")
    public ApiResponse<List<WhiteboxTaskResponse>> listTasks(@PathVariable Long projectId) {
        List<WhiteboxTaskResponse> response = whiteboxService.listTasks(projectId);
        return ApiResponse.ok(response);
    }

    /**
     * 查询任务详情（含进度与日志）
     */
    @GetMapping("/tasks/{taskId}")
    @PreAuthorize("hasAuthority('project:ai-whitebox')")
    public ApiResponse<WhiteboxTaskResponse> getTask(@PathVariable Long projectId,
                                                     @PathVariable Long taskId) {
        WhiteboxTaskResponse response = whiteboxService.getTask(projectId, taskId);
        return ApiResponse.ok(response);
    }

    /**
     * 查询任务的变更方法列表
     */
    @GetMapping("/tasks/{taskId}/methods")
    @PreAuthorize("hasAuthority('project:ai-whitebox')")
    public ApiResponse<List<WhiteboxMethodResponse>> listMethods(@PathVariable Long projectId,
                                                                 @PathVariable Long taskId) {
        List<WhiteboxMethodResponse> response = whiteboxService.listMethods(projectId, taskId);
        return ApiResponse.ok(response);
    }

    /**
     * 查询任务的生成测试列表
     */
    @GetMapping("/tasks/{taskId}/tests")
    @PreAuthorize("hasAuthority('project:ai-whitebox')")
    public ApiResponse<List<WhiteboxTestResponse>> listTests(@PathVariable Long projectId,
                                                             @PathVariable Long taskId) {
        List<WhiteboxTestResponse> response = whiteboxService.listTests(projectId, taskId);
        return ApiResponse.ok(response);
    }

    /**
     * 保存生成用例到手动用例库
     */
    @PostMapping("/tasks/{taskId}/tests/save")
    @PreAuthorize("hasAuthority('project:ai-whitebox:save')")
    public ApiResponse<List<Long>> saveTests(@PathVariable Long projectId,
                                             @PathVariable Long taskId,
                                             @Valid @RequestBody WhiteboxSaveTestsRequest request) {
        List<Long> savedIds = whiteboxService.saveTests(projectId, taskId, request);
        return ApiResponse.ok(savedIds);
    }

    /**
     * 获取测试报告（默认 Markdown 格式）
     */
    @GetMapping("/tasks/{taskId}/report")
    @PreAuthorize("hasAuthority('project:ai-whitebox')")
    public ApiResponse<WhiteboxReportResponse> getReport(@PathVariable Long projectId,
                                                         @PathVariable Long taskId) {
        WhiteboxReportResponse response = whiteboxService.getReport(projectId, taskId);
        return ApiResponse.ok(response);
    }

    /**
     * 下载报告文件
     *
     * @param format 报告格式（markdown/json），默认 markdown
     */
    @GetMapping("/tasks/{taskId}/report/download")
    @PreAuthorize("hasAuthority('project:ai-whitebox')")
    public ResponseEntity<byte[]> downloadReport(@PathVariable Long projectId,
                                                 @PathVariable Long taskId,
                                                 @RequestParam(defaultValue = "markdown") String format) {
        AiWhiteboxReport report = whiteboxService.getReportForDownload(projectId, taskId, format);

        String contentType = "markdown".equals(report.getReportFormat())
                ? "text/markdown; charset=utf-8"
                : "application/json; charset=utf-8";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + report.getFileName() + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(report.getReportContent().getBytes(StandardCharsets.UTF_8));
    }
}
