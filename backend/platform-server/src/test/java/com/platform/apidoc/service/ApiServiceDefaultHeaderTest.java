package com.platform.apidoc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.apidoc.dto.ApiSyncConfigRequest;
import com.platform.apidoc.dto.SwaggerImportRequest;
import com.platform.apidoc.dto.SwaggerImportResult;
import com.platform.apidoc.entity.Api;
import com.platform.apidoc.entity.ApiSyncConfig;
import com.platform.apidoc.mapper.ApiMapper;
import com.platform.apidoc.mapper.ApiSyncConfigMapper;
import com.platform.apidoc.util.SwaggerParser;
import com.platform.environment.service.EnvironmentService;
import com.platform.keyword.mapper.ApiKeywordMapper;
import com.platform.keyword.mapper.KeywordMapper;
import com.platform.project.entity.Project;
import com.platform.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ApiService Swagger 默认请求头合并自检
 */
class ApiServiceDefaultHeaderTest {

    private static final String OPENAPI_3_JSON = "{\n" +
            "  \"openapi\": \"3.0.1\",\n" +
            "  \"info\": {\"title\": \"测试服务\", \"version\": \"v1.0\"},\n" +
            "  \"paths\": {\n" +
            "    \"/test\": {\n" +
            "      \"post\": {\n" +
            "        \"tags\": [\"测试\"],\n" +
            "        \"summary\": \"测试接口\",\n" +
            "        \"operationId\": \"testOp\",\n" +
            "        \"requestBody\": {\n" +
            "          \"content\": {\"application/json\": {\"schema\": {\"type\": \"object\"}}},\n" +
            "          \"required\": true\n" +
            "        },\n" +
            "        \"responses\": {\n" +
            "          \"200\": {\"description\": \"OK\", \"content\": {\"*/*\": {\"schema\": {\"type\": \"string\"}}}}\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

    @Test
    void importSwagger_defaultHeadersMergedIntoApiHeaders() {
        ApiMapper apiMapper = mock(ApiMapper.class);
        ApiKeywordMapper apiKeywordMapper = mock(ApiKeywordMapper.class);
        KeywordMapper keywordMapper = mock(KeywordMapper.class);
        ProjectService projectService = mock(ProjectService.class);
        EnvironmentService environmentService = mock(EnvironmentService.class);
        ApiModuleService apiModuleService = mock(ApiModuleService.class);
        ApiSyncConfigMapper apiSyncConfigMapper = mock(ApiSyncConfigMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();

        ApiService apiService = new ApiService(
                apiMapper, apiKeywordMapper, keywordMapper,
                projectService, environmentService, apiModuleService,
                apiSyncConfigMapper, objectMapper, mock(ApplicationEventPublisher.class)
        );

        when(projectService.findActiveById(anyLong())).thenReturn(new Project());
        when(apiMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());

        Map<String, String> defaultHeaders = new LinkedHashMap<>();
        defaultHeaders.put("Authorization", "${authorization}");

        SwaggerImportRequest request = new SwaggerImportRequest();
        request.setProjectId(1L);
        request.setModuleId(2L);
        request.setSwaggerJson(OPENAPI_3_JSON);
        request.setDefaultHeaders(defaultHeaders);

        SwaggerImportResult result = apiService.importSwagger(request);

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getCreated());

        ArgumentCaptor<Api> captor = ArgumentCaptor.forClass(Api.class);
        verify(apiMapper, times(1)).insert(captor.capture());
        Api inserted = captor.getValue();

        assertNotNull(inserted.getHeaders(), "接口 headers 不应为空");
        assertTrue(inserted.getHeaders().contains("\"name\":\"Authorization\""), "应包含 Authorization 请求头");
        assertTrue(inserted.getHeaders().contains("\"value\":\"${authorization}\""), "Authorization 值应为模板");
        assertTrue(inserted.getHeaders().contains("\"name\":\"Content-Type\""), "应保留自动生成的 Content-Type");
    }

    @Test
    void importSwagger_defaultHeadersOverrideExistingOnes() {
        ApiMapper apiMapper = mock(ApiMapper.class);
        ApiKeywordMapper apiKeywordMapper = mock(ApiKeywordMapper.class);
        KeywordMapper keywordMapper = mock(KeywordMapper.class);
        ProjectService projectService = mock(ProjectService.class);
        EnvironmentService environmentService = mock(EnvironmentService.class);
        ApiModuleService apiModuleService = mock(ApiModuleService.class);
        ApiSyncConfigMapper apiSyncConfigMapper = mock(ApiSyncConfigMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();

        ApiService apiService = new ApiService(
                apiMapper, apiKeywordMapper, keywordMapper,
                projectService, environmentService, apiModuleService,
                apiSyncConfigMapper, objectMapper, mock(ApplicationEventPublisher.class)
        );

        when(projectService.findActiveById(anyLong())).thenReturn(new Project());

        Api existing = new Api();
        existing.setId(100L);
        existing.setSwaggerOperationId("testOp");
        existing.setHeaders("[{\"name\":\"Authorization\",\"type\":\"string\",\"required\":false,\"description\":\"\",\"value\":\"Basic old\"}]");
        when(apiMapper.selectList(any())).thenReturn(java.util.Collections.singletonList(existing));

        Map<String, String> defaultHeaders = new LinkedHashMap<>();
        defaultHeaders.put("Authorization", "${authorization}");

        SwaggerImportRequest request = new SwaggerImportRequest();
        request.setProjectId(1L);
        request.setModuleId(2L);
        request.setSwaggerJson(OPENAPI_3_JSON);
        request.setDefaultHeaders(defaultHeaders);

        SwaggerImportResult result = apiService.importSwagger(request);

        assertEquals(1, result.getTotal());
        assertEquals(0, result.getCreated());
        assertEquals(1, result.getUpdated());

        ArgumentCaptor<Api> captor = ArgumentCaptor.forClass(Api.class);
        verify(apiMapper, times(1)).updateById(captor.capture());
        Api updated = captor.getValue();

        assertNotNull(updated.getHeaders());
        assertTrue(updated.getHeaders().contains("\"value\":\"${authorization}\""), "默认请求头应覆盖旧值");
        assertFalse(updated.getHeaders().contains("\"Basic old\""), "旧 Authorization 值应被移除");
    }

    @Test
    void createSyncConfig_persistsHeadersText() {
        ApiMapper apiMapper = mock(ApiMapper.class);
        ApiKeywordMapper apiKeywordMapper = mock(ApiKeywordMapper.class);
        KeywordMapper keywordMapper = mock(KeywordMapper.class);
        ProjectService projectService = mock(ProjectService.class);
        EnvironmentService environmentService = mock(EnvironmentService.class);
        ApiModuleService apiModuleService = mock(ApiModuleService.class);
        ApiSyncConfigMapper apiSyncConfigMapper = mock(ApiSyncConfigMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();

        ApiService apiService = new ApiService(
                apiMapper, apiKeywordMapper, keywordMapper,
                projectService, environmentService, apiModuleService,
                apiSyncConfigMapper, objectMapper, mock(ApplicationEventPublisher.class)
        );

        when(projectService.findActiveById(anyLong())).thenReturn(new Project());

        ApiSyncConfigRequest request = new ApiSyncConfigRequest();
        request.setName("测试配置");
        request.setUrl("http://example.com/v3/api-docs");
        request.setModuleId(2L);
        request.setHeaders("Authorization: ${authorization}\nX-Request-Source: swagger");

        apiService.createSyncConfig(1L, request);

        ArgumentCaptor<ApiSyncConfig> captor = ArgumentCaptor.forClass(ApiSyncConfig.class);
        verify(apiSyncConfigMapper, times(1)).insert(captor.capture());
        ApiSyncConfig saved = captor.getValue();

        assertEquals("Authorization: ${authorization}\nX-Request-Source: swagger", saved.getHeaders(),
                "默认请求头文本应原样持久化");
    }

    @Test
    void parseHeadersText_supportsChineseColon() throws Exception {
        ApiMapper apiMapper = mock(ApiMapper.class);
        ApiKeywordMapper apiKeywordMapper = mock(ApiKeywordMapper.class);
        KeywordMapper keywordMapper = mock(KeywordMapper.class);
        ProjectService projectService = mock(ProjectService.class);
        EnvironmentService environmentService = mock(EnvironmentService.class);
        ApiModuleService apiModuleService = mock(ApiModuleService.class);
        ApiSyncConfigMapper apiSyncConfigMapper = mock(ApiSyncConfigMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();

        ApiService apiService = new ApiService(
                apiMapper, apiKeywordMapper, keywordMapper,
                projectService, environmentService, apiModuleService,
                apiSyncConfigMapper, objectMapper, mock(ApplicationEventPublisher.class)
        );

        java.lang.reflect.Method method = ApiService.class.getDeclaredMethod("parseHeadersText", String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) method.invoke(apiService, "Authorization：${authorization}");

        assertNotNull(result);
        assertEquals("${authorization}", result.get("Authorization"));
    }
}
