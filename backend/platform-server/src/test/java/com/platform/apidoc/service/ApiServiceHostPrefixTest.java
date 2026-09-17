package com.platform.apidoc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.apidoc.dto.ApiDebugRequest;
import com.platform.apidoc.dto.ApiDebugResponse;
import com.platform.apidoc.dto.ApiSyncConfigRequest;
import com.platform.apidoc.dto.SwaggerImportRequest;
import com.platform.apidoc.dto.SwaggerImportResult;
import com.platform.apidoc.entity.Api;
import com.platform.apidoc.entity.ApiSyncConfig;
import com.platform.apidoc.mapper.ApiMapper;
import com.platform.apidoc.mapper.ApiSyncConfigMapper;
import com.platform.environment.service.EnvironmentService;
import com.platform.keyword.mapper.ApiKeywordMapper;
import com.platform.keyword.mapper.KeywordMapper;
import com.platform.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ApiService 导入附加默认 host 前缀与前导占位符自检
 */
class ApiServiceHostPrefixTest {

    private static final String OPENAPI_3_JSON = "{\n" +
            "  \"openapi\": \"3.0.1\",\n" +
            "  \"info\": {\"title\": \"测试服务\", \"version\": \"v1.0\"},\n" +
            "  \"paths\": {\n" +
            "    \"/test\": {\n" +
            "      \"post\": {\n" +
            "        \"tags\": [\"测试\"],\n" +
            "        \"summary\": \"测试接口\",\n" +
            "        \"operationId\": \"testOp\",\n" +
            "        \"responses\": {\n" +
            "          \"200\": {\"description\": \"OK\", \"content\": {\"*/*\": {\"schema\": {\"type\": \"string\"}}}}\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

    private ApiService newService(ApiMapper apiMapper, ApiSyncConfigMapper apiSyncConfigMapper,
                                  EnvironmentService environmentService, ApiModuleService apiModuleService) {
        return new ApiService(
                apiMapper, mock(ApiKeywordMapper.class), mock(KeywordMapper.class),
                mock(ProjectService.class), environmentService, apiModuleService,
                apiSyncConfigMapper, new ObjectMapper(), mock(ApplicationEventPublisher.class)
        );
    }

    @Test
    void importSwagger_hostPrefixPrependedToPath() {
        ApiMapper apiMapper = mock(ApiMapper.class);
        when(apiMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());

        SwaggerImportRequest request = new SwaggerImportRequest();
        request.setProjectId(1L);
        request.setModuleId(2L);
        request.setSwaggerJson(OPENAPI_3_JSON);
        request.setHostPrefix("${host}");

        SwaggerImportResult result = newService(apiMapper, mock(ApiSyncConfigMapper.class),
                mock(EnvironmentService.class), mock(ApiModuleService.class)).importSwagger(request);

        assertEquals(1, result.getCreated());

        ArgumentCaptor<Api> captor = ArgumentCaptor.forClass(Api.class);
        verify(apiMapper, times(1)).insert(captor.capture());
        assertEquals("${host}/test", captor.getValue().getPath(), "host 前缀应附加到路径前");
    }

    @Test
    void importSwagger_withoutHostPrefixKeepsRelativePath() {
        ApiMapper apiMapper = mock(ApiMapper.class);
        when(apiMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());

        SwaggerImportRequest request = new SwaggerImportRequest();
        request.setProjectId(1L);
        request.setModuleId(2L);
        request.setSwaggerJson(OPENAPI_3_JSON);

        newService(apiMapper, mock(ApiSyncConfigMapper.class),
                mock(EnvironmentService.class), mock(ApiModuleService.class)).importSwagger(request);

        ArgumentCaptor<Api> captor = ArgumentCaptor.forClass(Api.class);
        verify(apiMapper, times(1)).insert(captor.capture());
        assertEquals("/test", captor.getValue().getPath(), "未配置 host 前缀时路径应保持相对路径");
    }

    @Test
    void importSwagger_reimportDoesNotAccumulatePrefix() {
        ApiMapper apiMapper = mock(ApiMapper.class);
        Api existing = new Api();
        existing.setId(100L);
        existing.setSwaggerOperationId("testOp");
        existing.setPath("${host}/test");
        when(apiMapper.selectList(any())).thenReturn(java.util.Collections.singletonList(existing));

        SwaggerImportRequest request = new SwaggerImportRequest();
        request.setProjectId(1L);
        request.setModuleId(2L);
        request.setSwaggerJson(OPENAPI_3_JSON);
        request.setHostPrefix("${host}");

        SwaggerImportResult result = newService(apiMapper, mock(ApiSyncConfigMapper.class),
                mock(EnvironmentService.class), mock(ApiModuleService.class)).importSwagger(request);

        assertEquals(1, result.getUpdated());

        ArgumentCaptor<Api> captor = ArgumentCaptor.forClass(Api.class);
        verify(apiMapper, times(1)).updateById(captor.capture());
        assertEquals("${host}/test", captor.getValue().getPath(), "重复导入时前缀不应累积");
    }

    @Test
    void createSyncConfig_persistsHostPrefix() {
        ApiSyncConfigMapper apiSyncConfigMapper = mock(ApiSyncConfigMapper.class);

        ApiSyncConfigRequest request = new ApiSyncConfigRequest();
        request.setName("测试配置");
        request.setUrl("http://example.com/v3/api-docs");
        request.setModuleId(2L);
        request.setHostPrefix("${host}");

        newService(mock(ApiMapper.class), apiSyncConfigMapper,
                mock(EnvironmentService.class), mock(ApiModuleService.class)).createSyncConfig(1L, request);

        ArgumentCaptor<ApiSyncConfig> captor = ArgumentCaptor.forClass(ApiSyncConfig.class);
        verify(apiSyncConfigMapper, times(1)).insert(captor.capture());
        assertEquals("${host}", captor.getValue().getHostPrefix(), "host 前缀应持久化");
    }

    @Test
    void debug_leadingPlaceholderWithMissingVariableReturnsError() {
        ApiMapper apiMapper = mock(ApiMapper.class);
        Api api = new Api();
        api.setId(10L);
        api.setProjectId(1L);
        api.setModuleId(2L);
        api.setHttpMethod("GET");
        api.setPath("${userHost}/list");
        when(apiMapper.selectById(10L)).thenReturn(api);

        EnvironmentService environmentService = mock(EnvironmentService.class);
        Map<String, String> envVars = new HashMap<>();
        envVars.put("host", "http://localhost:8080");
        when(environmentService.getVariablesAsMap(3L)).thenReturn(envVars);

        ApiDebugRequest request = new ApiDebugRequest();
        request.setEnvironmentId(3L);

        ApiDebugResponse response = newService(apiMapper, mock(ApiSyncConfigMapper.class),
                environmentService, mock(ApiModuleService.class)).debug(10L, request);

        assertEquals(0, response.getSuccess().intValue(), "缺少前导占位符变量时应返回失败");
        assertTrue(response.getErrorMessage().contains("userHost"), "错误信息应包含缺失的变量名");
    }
}
