package com.platform.apidoc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.apidoc.dto.SwaggerImportRequest;
import com.platform.apidoc.dto.SwaggerImportResult;
import com.platform.apidoc.entity.Api;
import com.platform.apidoc.mapper.ApiMapper;
import com.platform.apidoc.mapper.ApiSyncConfigMapper;
import com.platform.environment.service.EnvironmentService;
import com.platform.keyword.mapper.ApiKeywordMapper;
import com.platform.keyword.mapper.KeywordMapper;
import com.platform.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ApiService Swagger 同步内容无变化跳过更新自检
 */
class ApiServiceUnchangedSkipTest {

    private static final String OPENAPI_3_JSON = "{\n" +
            "  \"openapi\": \"3.0.1\",\n" +
            "  \"info\": {\"title\": \"测试服务\", \"version\": \"v1.0\"},\n" +
            "  \"paths\": {\n" +
            "    \"/test\": {\n" +
            "      \"post\": {\n" +
            "        \"tags\": [\"测试\"],\n" +
            "        \"summary\": \"测试接口\",\n" +
            "        \"operationId\": \"testOp\",\n" +
            "        \"parameters\": [{\"name\": \"id\", \"in\": \"query\", \"required\": true, \"schema\": {\"type\": \"integer\"}}],\n" +
            "        \"responses\": {\n" +
            "          \"200\": {\"description\": \"OK\", \"content\": {\"*/*\": {\"schema\": {\"type\": \"string\"}}}}\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

    private ApiService newService(ApiMapper apiMapper) {
        return new ApiService(
                apiMapper, mock(ApiKeywordMapper.class), mock(KeywordMapper.class),
                mock(ProjectService.class), mock(EnvironmentService.class), mock(ApiModuleService.class),
                mock(ApiSyncConfigMapper.class), new ObjectMapper(), mock(ApplicationEventPublisher.class)
        );
    }

    private SwaggerImportRequest buildRequest() {
        SwaggerImportRequest request = new SwaggerImportRequest();
        request.setProjectId(1L);
        request.setModuleId(2L);
        request.setSwaggerJson(OPENAPI_3_JSON);
        return request;
    }

    @Test
    void importSwagger_unchangedApiSkipsUpdate() {
        ApiMapper apiMapper = mock(ApiMapper.class);
        when(apiMapper.selectList(any())).thenReturn(Collections.emptyList());

        // 第一次导入：创建接口
        SwaggerImportResult first = newService(apiMapper).importSwagger(buildRequest());
        assertEquals(1, first.getCreated());

        ArgumentCaptor<Api> captor = ArgumentCaptor.forClass(Api.class);
        verify(apiMapper, times(1)).insert(captor.capture());

        // 第二次导入：已有记录与解析结果完全一致，应跳过更新
        Api existing = captor.getValue();
        existing.setId(100L);
        when(apiMapper.selectList(any())).thenReturn(Collections.singletonList(existing));
        SwaggerImportResult second = newService(apiMapper).importSwagger(buildRequest());

        assertEquals(1, second.getSkipped(), "内容无变化应计入 skipped");
        assertEquals(0, second.getUpdated(), "内容无变化不应更新");
        verify(apiMapper, never()).updateById(any(Api.class));
    }

    @Test
    void importSwagger_changedApiStillUpdates() {
        ApiMapper apiMapper = mock(ApiMapper.class);
        when(apiMapper.selectList(any())).thenReturn(Collections.emptyList());

        newService(apiMapper).importSwagger(buildRequest());
        ArgumentCaptor<Api> captor = ArgumentCaptor.forClass(Api.class);
        verify(apiMapper, times(1)).insert(captor.capture());

        // 已有接口的 summary 与新文档不同，应触发更新
        Api existing = captor.getValue();
        existing.setId(100L);
        existing.setName("旧接口名");
        when(apiMapper.selectList(any())).thenReturn(Collections.singletonList(existing));
        SwaggerImportResult result = newService(apiMapper).importSwagger(buildRequest());

        assertEquals(1, result.getUpdated(), "内容变化应更新");
        assertEquals(0, result.getSkipped(), "内容变化不应跳过");
        verify(apiMapper, times(1)).updateById(any(Api.class));
    }

    @Test
    void importSwagger_nullAndEmptyStringTreatedSame() {
        ApiMapper apiMapper = mock(ApiMapper.class);
        when(apiMapper.selectList(any())).thenReturn(Collections.emptyList());

        newService(apiMapper).importSwagger(buildRequest());
        ArgumentCaptor<Api> captor = ArgumentCaptor.forClass(Api.class);
        verify(apiMapper, times(1)).insert(captor.capture());

        // 库中空串 vs 解析器 null（如无 requestBody 时）应视为相同，不触发更新
        Api existing = captor.getValue();
        existing.setId(100L);
        existing.setRequestBody("");
        when(apiMapper.selectList(any())).thenReturn(Collections.singletonList(existing));
        SwaggerImportResult result = newService(apiMapper).importSwagger(buildRequest());

        assertEquals(1, result.getSkipped(), "null 与空串应视为相同");
        verify(apiMapper, never()).updateById(any(Api.class));
    }
}
