/**
 * @author HXN
 * @date 2026-08-20 15:34
 * @description API 接口管理服务
 */
package com.platform.apidoc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.apidoc.dto.*;
import com.platform.apidoc.entity.Api;
import com.platform.apidoc.entity.ApiSyncConfig;
import com.platform.apidoc.mapper.ApiMapper;
import com.platform.apidoc.mapper.ApiSyncConfigMapper;
import com.platform.apidoc.util.SwaggerParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.common.response.PageResponse;
import com.platform.environment.service.EnvironmentService;
import com.platform.knowledge.event.ProjectMaterialChangedEvent;
import com.platform.knowledge.service.KnowledgeMaterialCollector;
import com.platform.keyword.entity.ApiKeyword;
import com.platform.keyword.entity.Keyword;
import com.platform.keyword.mapper.ApiKeywordMapper;
import com.platform.keyword.mapper.KeywordMapper;
import com.platform.project.entity.ApiModule;
import com.platform.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 接口文档管理服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiService {

    /** Swagger 同步/导入专用日志：输出到 swagger.log */
    private static final Logger SWAGGER_LOG = LoggerFactory.getLogger("com.platform.apidoc.swagger");

    private final ApiMapper apiMapper;
    private final ApiKeywordMapper apiKeywordMapper;
    private final KeywordMapper keywordMapper;
    private final ProjectService projectService;
    private final EnvironmentService environmentService;
    private final ApiModuleService apiModuleService;
    private final ApiSyncConfigMapper apiSyncConfigMapper;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 分页查询接口列表
     */
    public PageResponse<ApiInfoResponse> list(Long projectId, Long moduleId, String keyword,
                                               String path, String httpMethod, String source,
                                               int page, int pageSize) {
        LambdaQueryWrapper<Api> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Api::getProjectId, projectId);

        if (moduleId != null) {
            // 查询该分组及其所有子孙分组的接口
            Set<Long> moduleIds = apiModuleService.getDescendantModuleIds(moduleId);
            wrapper.in(Api::getModuleId, moduleIds);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(Api::getName, keyword)
                    .or().apply("path LIKE BINARY {0}", "%" + keyword + "%")
                    .or().like(Api::getService, keyword));
        }
        if (StringUtils.hasText(path)) {
            wrapper.apply("path LIKE BINARY {0}", "%" + path + "%");
        }
        if (StringUtils.hasText(httpMethod)) {
            wrapper.eq(Api::getHttpMethod, httpMethod);
        }
        if (StringUtils.hasText(source)) {
            wrapper.eq(Api::getSourceType, source);
        }
        wrapper.orderByDesc(Api::getCreatedAt);

        Page<Api> pageParam = new Page<>(page, pageSize);
        Page<Api> result = apiMapper.selectPage(pageParam, wrapper);

        Map<Long, ApiModule> moduleMap = apiModuleService.getModuleMap(projectId);
        List<ApiInfoResponse> records = new ArrayList<>();
        for (Api api : result.getRecords()) {
            records.add(toResponse(api, moduleMap));
        }
        return PageResponse.of(records, result.getTotal(), page, pageSize);
    }

    /**
     * 创建接口
     */
    public ApiInfoResponse create(ApiCreateRequest request) {
        projectService.findActiveById(request.getProjectId());

        Api api = new Api();
        BeanUtils.copyProperties(request, api);
        api.setSourceType("MANUAL");

        api.setRequestBody(sanitizeJson(api.getRequestBody()));
        api.setResponseBody(sanitizeJson(api.getResponseBody()));
        apiMapper.insert(api);

        // 知识库同步：接口变更归入所属模块重新采集（采集粒度 = 1 模块 = 1 知识库文档）
        publishModuleEvent(api.getProjectId(), api.getModuleId());

        Map<Long, ApiModule> moduleMap = apiModuleService.getModuleMap(api.getProjectId());
        return toResponse(api, moduleMap);
    }

    /**
     * 更新接口
     */
    public ApiInfoResponse update(Long apiId, ApiUpdateRequest request) {
        Api api = findById(apiId);
        Long oldModuleId = api.getModuleId();

        if (request.getModuleId() != null) {
            api.setModuleId(request.getModuleId());
        }
        if (StringUtils.hasText(request.getName())) {
            api.setName(request.getName());
        }
        if (request.getService() != null) {
            api.setService(request.getService());
        }
        if (StringUtils.hasText(request.getHttpMethod())) {
            api.setHttpMethod(request.getHttpMethod());
        }
        if (StringUtils.hasText(request.getPath())) {
            api.setPath(request.getPath());
        }
        if (request.getRequestParams() != null) {
            api.setRequestParams(request.getRequestParams());
        }
        if (request.getRequestBody() != null) {
            api.setRequestBody(request.getRequestBody());
        }
        if (request.getBodyType() != null) {
            api.setBodyType(request.getBodyType());
        }
        if (request.getRawType() != null) {
            api.setRawType(request.getRawType());
        }
        if (request.getResponseBody() != null) {
            api.setResponseBody(request.getResponseBody());
        }
        if (request.getHeaders() != null) {
            api.setHeaders(request.getHeaders());
        }
        if (request.getContentType() != null) {
            api.setContentType(request.getContentType());
        }
        if (request.getDescription() != null) {
            api.setDescription(request.getDescription());
        }

        api.setRequestBody(sanitizeJson(api.getRequestBody()));
        api.setResponseBody(sanitizeJson(api.getResponseBody()));
        apiMapper.updateById(api);

        // 知识库同步：接口变更归入所属模块重新采集；模块移动时新旧模块均触发
        publishModuleEvent(api.getProjectId(), api.getModuleId());
        if (!Objects.equals(oldModuleId, api.getModuleId())) {
            publishModuleEvent(api.getProjectId(), oldModuleId);
        }

        Map<Long, ApiModule> moduleMap = apiModuleService.getModuleMap(api.getProjectId());
        return toResponse(api, moduleMap);
    }

    /**
     * 获取接口详情
     */
    public ApiInfoResponse getById(Long apiId) {
        Api api = findById(apiId);
        Map<Long, ApiModule> moduleMap = apiModuleService.getModuleMap(api.getProjectId());
        return toResponse(api, moduleMap);
    }

    /**
     * 删除接口
     */
    public void delete(Long apiId) {
        Api api = findById(apiId);
        // 删除保护检查 - 被 ApiKeyword 引用时不可删除
        LambdaQueryWrapper<ApiKeyword> kwWrapper = new LambdaQueryWrapper<>();
        kwWrapper.eq(ApiKeyword::getApiId, apiId);
        long refCount = apiKeywordMapper.selectCount(kwWrapper);
        if (refCount > 0) {
            throw new BusinessException(ErrorCode.API_DEPENDENCY_CONFLICT,
                    "接口被 " + refCount + " 个关键字引用，无法删除");
        }
        apiMapper.deleteById(apiId);

        // 知识库同步：接口删除归入所属模块重新采集
        publishModuleEvent(api.getProjectId(), api.getModuleId());
    }

    /**
     * 批量删除接口
     */
    @Transactional(rollbackFor = Exception.class)
    public void batchDelete(List<Long> apiIds) {
        for (Long apiId : apiIds) {
            delete(apiId);
        }
    }

    /**
     * 批量移动接口到指定分组
     */
    @Transactional(rollbackFor = Exception.class)
    public void batchMove(List<Long> apiIds, Long targetModuleId) {
        for (Long apiId : apiIds) {
            Api api = findById(apiId);
            Long oldModuleId = api.getModuleId();
            api.setModuleId(targetModuleId);
            apiMapper.updateById(api);

            // 知识库同步：接口移动，新旧模块均重新采集
            publishModuleEvent(api.getProjectId(), targetModuleId);
            if (!Objects.equals(oldModuleId, targetModuleId)) {
                publishModuleEvent(api.getProjectId(), oldModuleId);
            }
        }
    }

    /**
     * 清空分组及其子孙分组中的所有接口（被关键字引用的接口跳过）
     */
    @Transactional(rollbackFor = Exception.class)
    public void clearByModule(Long moduleId) {
        Set<Long> moduleIds = apiModuleService.getDescendantModuleIds(moduleId);
        LambdaQueryWrapper<Api> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(Api::getModuleId, moduleIds);
        List<Api> apis = apiMapper.selectList(wrapper);
        deleteUnreferenced(apis);
    }

    /**
     * 清空项目下所有接口（被关键字引用的接口跳过）
     */
    @Transactional(rollbackFor = Exception.class)
    public void clearByProject(Long projectId) {
        LambdaQueryWrapper<Api> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Api::getProjectId, projectId);
        List<Api> apis = apiMapper.selectList(wrapper);
        deleteUnreferenced(apis);
    }

    /**
     * 批量删除未被关键字引用的接口，被引用的跳过
     */
    private void deleteUnreferenced(List<Api> apis) {
        if (apis.isEmpty()) {
            return;
        }
        List<Long> apiIds = new ArrayList<>();
        for (Api api : apis) {
            apiIds.add(api.getId());
        }
        // 一次性查出被 ApiKeyword 引用的接口 ID，避免逐条计数
        LambdaQueryWrapper<ApiKeyword> kwWrapper = new LambdaQueryWrapper<>();
        kwWrapper.in(ApiKeyword::getApiId, apiIds).select(ApiKeyword::getApiId);
        Set<Long> referencedIds = new HashSet<>();
        for (ApiKeyword kw : apiKeywordMapper.selectList(kwWrapper)) {
            referencedIds.add(kw.getApiId());
        }
        int deleted = 0;
        for (Api api : apis) {
            if (!referencedIds.contains(api.getId())) {
                apiMapper.deleteById(api.getId());
                deleted++;
            }
        }

        // 知识库同步：按模块聚合发布（接口删除后所属模块重新采集）
        if (deleted > 0) {
            Long projectId = apis.get(0).getProjectId();
            Set<Long> changedModuleIds = new HashSet<>();
            for (Api api : apis) {
                changedModuleIds.add(api.getModuleId());
            }
            for (Long moduleId : changedModuleIds) {
                publishModuleEvent(projectId, moduleId);
            }
        }
    }

    /**
     * Swagger 导入（增量）
     */
    @Transactional(rollbackFor = Exception.class)
    public SwaggerImportResult importSwagger(SwaggerImportRequest request) {
        projectService.findActiveById(request.getProjectId());

        SwaggerParser.ParseResult parseResult = SwaggerParser.parse(request.getSwaggerJson());
        List<SwaggerParser.ApiEntry> entries = parseResult.getApis();
        SWAGGER_LOG.info("Swagger 解析完成，共 {} 个接口，moduleId={}", entries.size(), request.getModuleId());

        // 合并同步配置中的默认请求头到每个接口
        List<Map<String, Object>> defaultHeaders = toDefaultHeaderItems(request.getDefaultHeaders());
        if (!defaultHeaders.isEmpty()) {
            SWAGGER_LOG.info("Swagger 导入附加默认请求头: keys={}", extractHeaderNames(defaultHeaders));
            for (SwaggerParser.ApiEntry entry : entries) {
                String merged = mergeHeadersJson(entry.getHeaders(), defaultHeaders);
                entry.setHeaders(merged);
                SWAGGER_LOG.debug("Swagger 接口请求头合并结果: operationId={}, keys={}",
                        entry.getOperationId(), extractHeaderNames(merged));
            }
        }

        // 附加默认 host 前缀到每个接口 URL 前（如 ${host}）
        if (StringUtils.hasText(request.getHostPrefix())) {
            SWAGGER_LOG.info("Swagger 导入附加默认 host 前缀: {}", request.getHostPrefix());
            for (SwaggerParser.ApiEntry entry : entries) {
                entry.setPath(applyHostPrefix(request.getHostPrefix(), entry.getPath()));
            }
        }

        int created = 0;
        int updated = 0;
        int skipped = 0;

        // 查询该分组下已有的 Swagger 导入接口
        Map<String, Api> existingByOpId = new HashMap<>();
        LambdaQueryWrapper<Api> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Api::getModuleId, request.getModuleId())
                .eq(Api::getSourceType, "SWAGGER_IMPORT")
                .isNotNull(Api::getSwaggerOperationId);
        List<Api> existing = apiMapper.selectList(wrapper);
        for (Api api : existing) {
            if (api.getSwaggerOperationId() != null) {
                existingByOpId.put(api.getSwaggerOperationId(), api);
            }
        }
        SWAGGER_LOG.info("Swagger 导入已有接口数={}, operationIds={}", existingByOpId.size(), existingByOpId.keySet());

        for (SwaggerParser.ApiEntry entry : entries) {
            String opId = entry.getOperationId();
            SWAGGER_LOG.debug("Swagger 接口处理: operationId={}, method={}, path={}, contentType={}",
                    opId, entry.getHttpMethod(), entry.getPath(), entry.getContentType());

            if (opId != null && existingByOpId.containsKey(opId)) {
                // 更新已有接口（内容无变化时跳过，避免无谓的数据库写入与更新时间刷新）
                Api existingApi = existingByOpId.get(opId);
                if (isSwaggerApiUnchanged(existingApi, entry)) {
                    SWAGGER_LOG.info("Swagger 接口内容无变化，跳过更新: operationId={}, path={}",
                            opId, existingApi.getPath());
                    skipped++;
                    continue;
                }
                SWAGGER_LOG.info("Swagger 接口更新: operationId={}, oldPath={}, newPath={}, contentType={}",
                        opId, existingApi.getPath(), entry.getPath(), entry.getContentType());
                existingApi.setName(entry.getName());
                existingApi.setHttpMethod(entry.getHttpMethod());
                existingApi.setPath(entry.getPath());
                existingApi.setService(entry.getService());
                existingApi.setRequestParams(entry.getRequestParams());
                existingApi.setRequestBody(entry.getRequestBody());
                existingApi.setBodyType(entry.getBodyType());
                existingApi.setRawType(entry.getRawType());
                existingApi.setResponseBody(entry.getResponseBody());
                existingApi.setHeaders(entry.getHeaders());
                existingApi.setContentType(entry.getContentType());
                existingApi.setDescription(entry.getDescription());
                apiMapper.updateById(existingApi);
                updated++;
            } else {
                // 创建新接口
                if (opId == null) {
                    SWAGGER_LOG.warn("Swagger 接口缺少 operationId，将创建新接口: method={}, path={}",
                            entry.getHttpMethod(), entry.getPath());
                } else {
                    SWAGGER_LOG.info("Swagger 接口新建: operationId={}, method={}, path={}, contentType={}",
                            opId, entry.getHttpMethod(), entry.getPath(), entry.getContentType());
                }
                Api newApi = SwaggerParser.toApiEntity(entry, request.getProjectId(), request.getModuleId());
                apiMapper.insert(newApi);
                created++;
            }
        }

        SWAGGER_LOG.info("Swagger 导入结果: total={}, created={}, updated={}, skipped={}, moduleId={}",
                entries.size(), created, updated, skipped, request.getModuleId());

        // 知识库同步：Swagger 导入后该模块重新采集（syncFromUrl/syncOneConfig 复用此方法，自动覆盖）
        publishModuleEvent(request.getProjectId(), request.getModuleId());

        return SwaggerImportResult.of(entries.size(), created, updated, skipped);
    }

    /**
     * 判断 Swagger 解析出的接口内容与库中已有接口是否一致（一致则跳过更新）
     * ponytail: 按序列化字符串逐字段比较，上游文档仅字段顺序变化时会多更新一次，随后即稳定；需要语义级比较时再做 JSON 规范化
     */
    private boolean isSwaggerApiUnchanged(Api existingApi, SwaggerParser.ApiEntry entry) {
        return sameValue(existingApi.getName(), entry.getName())
                && sameValue(existingApi.getHttpMethod(), entry.getHttpMethod())
                && sameValue(existingApi.getPath(), entry.getPath())
                && sameValue(existingApi.getService(), entry.getService())
                && sameValue(existingApi.getRequestParams(), entry.getRequestParams())
                && sameValue(existingApi.getRequestBody(), entry.getRequestBody())
                && sameValue(existingApi.getBodyType(), entry.getBodyType())
                && sameValue(existingApi.getRawType(), entry.getRawType())
                && sameValue(existingApi.getResponseBody(), entry.getResponseBody())
                && sameValue(existingApi.getHeaders(), entry.getHeaders())
                && sameValue(existingApi.getContentType(), entry.getContentType())
                && sameValue(existingApi.getDescription(), entry.getDescription());
    }

    /**
     * 字符串等值比较：null 与空串/空白视为相同（库中空串与解析器 null 互不触发更新）
     */
    private static boolean sameValue(String a, String b) {
        return Objects.equals(a, b) || (!StringUtils.hasText(a) && !StringUtils.hasText(b));
    }

    /**
     * 接口调试
     */
    public ApiDebugResponse debug(Long apiId, ApiDebugRequest request) {
        Api api = findById(apiId);

        // 获取环境变量
        Map<String, String> envVars = environmentService.getVariablesAsMap(request.getEnvironmentId());
        if (envVars.isEmpty()) {
            return ApiDebugResponse.error("环境不存在或没有配置变量");
        }

        // 从变量中构建 baseUrl（查找 host 变量）
        String baseUrl = envVars.getOrDefault("host", "");

        // 解析接口所在分组的有效服务前缀（子分组优先）
        Map<Long, ApiModule> moduleMap = apiModuleService.getModuleMap(api.getProjectId());
        String servicePrefix = apiModuleService.resolveServicePrefix(api.getModuleId(), moduleMap);

        String path = api.getPath();
        // 前导占位符：path 以 ${var} 开头时，用该环境变量值替代 baseUrl（host 不写死）
        String leadingVar = extractLeadingPlaceholder(path);
        if (leadingVar != null) {
            String varValue = envVars.get(leadingVar);
            if (!StringUtils.hasText(varValue)) {
                return ApiDebugResponse.error("环境变量不存在或未配置：" + leadingVar);
            }
            baseUrl = varValue;
            path = path.substring(path.indexOf('}') + 1);
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
        }
        // 替换路径参数
        if (request.getPathParams() != null) {
            for (Map.Entry<String, String> entry : request.getPathParams().entrySet()) {
                path = path.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }

        // 拼接查询参数
        StringBuilder urlBuilder = new StringBuilder(joinUrl(baseUrl, servicePrefix, path));
        if (request.getQueryParams() != null && !request.getQueryParams().isEmpty()) {
            urlBuilder.append("?");
            boolean first = true;
            for (Map.Entry<String, String> entry : request.getQueryParams().entrySet()) {
                if (!first) {
                    urlBuilder.append("&");
                }
                urlBuilder.append(URLEncoder.encode(entry.getKey())).append("=")
                        .append(URLEncoder.encode(entry.getValue()));
                first = false;
            }
        }

        long start = System.currentTimeMillis();
        try {
            URL url = new URL(urlBuilder.toString());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(api.getHttpMethod());
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);

            // 设置请求头
            if (request.getHeaders() != null) {
                for (Map.Entry<String, String> entry : request.getHeaders().entrySet()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            // 根据请求体格式构造请求体与 Content-Type
            String bodyType = effectiveBodyType(request, api);
            String contentType = resolveDebugContentType(bodyType, request.getRawType(), api.getContentType());
            byte[] bodyBytes = buildDebugBody(bodyType, request.getBody());
            if (contentType != null && !contentType.isEmpty()) {
                conn.setRequestProperty("Content-Type", contentType);
            }

            // 发送请求体
            if (bodyBytes != null && bodyBytes.length > 0) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bodyBytes);
                }
            }

            // 读取响应
            int statusCode = conn.getResponseCode();
            InputStream is = (statusCode >= 200 && statusCode < 400)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            String responseBody = readStream(is);
            long elapsed = System.currentTimeMillis() - start;

            ApiDebugResponse response = new ApiDebugResponse();
            response.setStatusCode(statusCode);
            response.setStatusText(conn.getResponseMessage());
            response.setRequestUrl(urlBuilder.toString());
            response.setResponseBody(responseBody);
            response.setResponseTimeMs(elapsed);
            response.setResponseSizeBytes((long) responseBody.getBytes(StandardCharsets.UTF_8).length);
            response.setSuccess(statusCode >= 200 && statusCode < 400 ? 1 : 0);

            // 收集响应头
            Map<String, String> respHeaders = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> header : conn.getHeaderFields().entrySet()) {
                if (header.getKey() != null) {
                    respHeaders.put(header.getKey(), String.join(", ", header.getValue()));
                }
            }
            response.setResponseHeaders(respHeaders);

            return response;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("接口调试失败 [{}]: {}", api.getName(), e.getMessage());
            ApiDebugResponse response = ApiDebugResponse.error(e.getMessage());
            response.setResponseTimeMs(elapsed);
            return response;
        }
    }

    /**
     * 确定调试时实际使用的请求体格式
     */
    private String effectiveBodyType(ApiDebugRequest request, Api api) {
        if (StringUtils.hasText(request.getBodyType())) {
            return request.getBodyType();
        }
        if (StringUtils.hasText(api.getBodyType())) {
            return api.getBodyType();
        }
        return "raw";
    }

    /** rawType → Content-Type 静态映射 */
    private static final Map<String, String> RAW_TYPE_CONTENT_TYPE_MAP;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("text", "text/plain");
        m.put("javascript", "application/javascript");
        m.put("json", "application/json");
        m.put("html", "text/html");
        m.put("xml", "application/xml");
        RAW_TYPE_CONTENT_TYPE_MAP = Collections.unmodifiableMap(m);
    }

    /**
     * 根据请求体格式解析 Content-Type
     */
    private String resolveDebugContentType(String bodyType, String rawType, String apiContentType) {
        if ("none".equals(bodyType)) {
            return null;
        }
        if ("x_www_form_urlencoded".equals(bodyType)) {
            return "application/x-www-form-urlencoded";
        }
        if ("form_data".equals(bodyType) || "binary".equals(bodyType)) {
            return "multipart/form-data; boundary=----FormBoundary" + System.currentTimeMillis();
        }
        if ("graphql".equals(bodyType)) {
            return "application/json";
        }
        // raw：按子类型映射，无子类型时回退接口保存的 Content-Type
        String mapped = RAW_TYPE_CONTENT_TYPE_MAP.get(rawType);
        if (mapped != null) {
            return mapped;
        }
        return StringUtils.hasText(apiContentType) ? apiContentType : "application/json";
    }

    /**
     * 根据请求体格式构造字节数组请求体
     */
    private byte[] buildDebugBody(String bodyType, String body) {
        if (!StringUtils.hasText(body) || "none".equals(bodyType)) {
            return null;
        }
        if ("raw".equals(bodyType) || "graphql".equals(bodyType)) {
            return body.getBytes(StandardCharsets.UTF_8);
        }
        if ("x_www_form_urlencoded".equals(bodyType)) {
            return buildUrlEncodedBody(body);
        }
        if ("form_data".equals(bodyType) || "binary".equals(bodyType)) {
            return buildMultipartBody(body);
        }
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] buildUrlEncodedBody(String body) {
        try {
            List<Map<String, Object>> items = objectMapper.readValue(body, new TypeReference<List<Map<String, Object>>>() {});
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Map<String, Object> item : items) {
                String name = item.get("name") == null ? "" : item.get("name").toString();
                String value = item.get("value") == null ? "" : item.get("value").toString();
                if (!first) {
                    sb.append("&");
                }
                sb.append(URLEncoder.encode(name, StandardCharsets.UTF_8.name()))
                        .append("=")
                        .append(URLEncoder.encode(value, StandardCharsets.UTF_8.name()));
                first = false;
            }
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("构造 x-www-form-urlencoded 请求体失败: {}", e.getMessage());
            return body.getBytes(StandardCharsets.UTF_8);
        }
    }

    private byte[] buildMultipartBody(String body) {
        String boundary = "----FormBoundary" + System.currentTimeMillis();
        try {
            List<Map<String, Object>> items = objectMapper.readValue(body, new TypeReference<List<Map<String, Object>>>() {});
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> item : items) {
                String name = item.get("name") == null ? "" : item.get("name").toString();
                String value = item.get("value") == null ? "" : item.get("value").toString();
                sb.append("--").append(boundary).append("\r\n");
                sb.append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n");
                sb.append(value).append("\r\n");
            }
            sb.append("--").append(boundary).append("--\r\n");
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("构造 multipart/form-data 请求体失败: {}", e.getMessage());
            return body.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * 查询接口被关键字引用的关系
     */
    public List<ApiReferenceResponse> getReferences(Long apiId) {
        // 查询绑定了此接口的 api_keyword 记录
        LambdaQueryWrapper<ApiKeyword> kwWrapper = new LambdaQueryWrapper<>();
        kwWrapper.eq(ApiKeyword::getApiId, apiId);
        List<ApiKeyword> apiKeywords = apiKeywordMapper.selectList(kwWrapper);

        List<ApiReferenceResponse> result = new ArrayList<>();
        for (ApiKeyword ak : apiKeywords) {
            Keyword kw = keywordMapper.selectById(ak.getKeywordId());
            if (kw != null) {
                ApiReferenceResponse resp = new ApiReferenceResponse();
                resp.setKeywordId(kw.getId());
                resp.setKeywordName(kw.getName());
                resp.setKeywordType(kw.getType());
                resp.setCategory(kw.getCategory());
                resp.setReferenceCount(0);
                result.add(resp);
            }
        }
        return result;
    }

    /**
     * Swagger 同步（拉取远程 JSON 后增量导入）
     * 支持 doc.html 页面地址自动探测 JSON 端点，认证账号/密码仅用于拉取 Swagger 文档
     */
    @Transactional(rollbackFor = Exception.class)
    public SwaggerImportResult syncFromUrl(SwaggerSyncRequest request) {
        projectService.findActiveById(request.getProjectId());

        // 自动探测：用户粘贴的是 doc.html 页面时，尝试同源 /v3/api-docs 或 /v2/api-docs
        List<String> candidates = resolveApiDocsUrls(request.getUrl());
        SWAGGER_LOG.info("Swagger 同步开始，projectId={}, moduleId={}, candidates={}",
                request.getProjectId(), request.getModuleId(), candidates);

        String swaggerJson = null;
        Exception lastException = null;
        for (String actualUrl : candidates) {
            try {
                swaggerJson = fetchSwaggerJson(actualUrl, request.getHeaders());
                SWAGGER_LOG.info("Swagger 文档拉取成功 [{}]，JSON 长度={}", actualUrl, swaggerJson != null ? swaggerJson.length() : 0);
                break;
            } catch (BusinessException e) {
                lastException = e;
                SWAGGER_LOG.debug("Swagger 同步尝试失败 [{}]: {}", actualUrl, e.getMessage());
            }
        }
        if (swaggerJson == null) {
            String msg = lastException != null ? lastException.getMessage() : "所有端点均失败";
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR,
                    "获取 OpenAPI/Swagger 文档失败：" + msg);
        }

        // 复用已有的增量导入逻辑
        SwaggerImportRequest importRequest = new SwaggerImportRequest();
        importRequest.setProjectId(request.getProjectId());
        importRequest.setModuleId(request.getModuleId());
        importRequest.setSwaggerJson(swaggerJson);
        importRequest.setDefaultHeaders(request.getDefaultHeaders());
        importRequest.setHostPrefix(request.getHostPrefix());
        return importSwagger(importRequest);
    }

    /**
     * 将 doc.html 页面 URL 转换为可能的 JSON 端点 URL 列表
     * 如 https://host/mock/doc.html#/... → [https://host/mock/v3/api-docs, https://host/mock/v2/api-docs]
     */
    private List<String> resolveApiDocsUrls(String rawUrl) {
        // 去掉 fragment（# 及之后的内容）
        String url = rawUrl;
        int hashIdx = url.indexOf('#');
        if (hashIdx >= 0) {
            url = url.substring(0, hashIdx);
        }

        List<String> candidates = new ArrayList<>();
        // 如果不包含 doc.html，视为已是 JSON 端点，直接返回并附加 v2/v3 互备候选
        if (!url.contains("doc.html")) {
            candidates.add(url);
            // 如果填的是 v3 端点，自动加 v2 作为后备（反之亦然）
            if (url.contains("/v3/api-docs")) {
                candidates.add(url.replace("/v3/api-docs", "/v2/api-docs"));
            } else if (url.contains("/v2/api-docs")) {
                candidates.add(url.replace("/v2/api-docs", "/v3/api-docs"));
            }
            return candidates;
        }

        // 截取 doc.html 之前的部分作为 base path
        int docIdx = url.indexOf("doc.html");
        String basePath = url.substring(0, docIdx);

        // 确保以 / 结尾
        if (!basePath.endsWith("/")) {
            basePath = basePath + "/";
        }

        candidates.add(basePath + "v3/api-docs");
        candidates.add(basePath + "v3/api-docs/default");
        candidates.add(basePath + "v3/api-docs?group=default");
        candidates.add(basePath + "v2/api-docs");
        candidates.add(basePath + "v2/api-docs?group=default");
        return candidates;
    }

    private String fetchSwaggerJson(String actualUrl, Map<String, String> headers) {
        try {
            URL url = new URL(actualUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "auto-test-platform/swagger-sync");

            // 应用自定义请求头（用于认证等场景）
            if (headers != null && !headers.isEmpty()) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            int statusCode = conn.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) {
                String errorBody = readStream(conn.getErrorStream());
                SWAGGER_LOG.debug("Swagger 同步远端返回非 2xx [{}] status={}, body={}", actualUrl, statusCode, errorBody);
                throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR,
                        "HTTP 状态码：" + statusCode + ", 响应：" + errorBody);
            }

            String body = readStream(conn.getInputStream());
            String preview = body.length() > 500 ? body.substring(0, 500) + "..." : body;
            SWAGGER_LOG.info("Swagger 文档响应 [{}] status={}, 内容预览={}", actualUrl, statusCode, preview);

            // 检测业务级错误响应（HTTP 200 但 body 是 {"code":401,"message":"..."} 之类的包装错误）
            checkBusinessErrorResponse(body, actualUrl);

            validateSwaggerJson(body, actualUrl);
            return body;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            SWAGGER_LOG.debug("从 URL 获取 OpenAPI/Swagger 文档失败 [{}]: {}", actualUrl, e.getMessage());
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, e.getMessage());
        }
    }

    // ===== Swagger 同步配置管理 =====

    /**
     * 查询项目的所有同步配置
     */
    public List<ApiSyncConfigResponse> listSyncConfigs(Long projectId) {
        LambdaQueryWrapper<ApiSyncConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApiSyncConfig::getProjectId, projectId)
               .orderByDesc(ApiSyncConfig::getCreatedAt);
        List<ApiSyncConfig> configs = apiSyncConfigMapper.selectList(wrapper);
        List<ApiSyncConfigResponse> result = new ArrayList<>();
        for (ApiSyncConfig config : configs) {
            result.add(toSyncConfigResponse(config));
        }
        return result;
    }

    /**
     * 创建同步配置
     */
    public ApiSyncConfigResponse createSyncConfig(Long projectId, ApiSyncConfigRequest request) {
        projectService.findActiveById(projectId);
        ApiSyncConfig config = new ApiSyncConfig();
        config.setProjectId(projectId);
        config.setName(request.getName());
        config.setUrl(request.getUrl());
        config.setModuleId(request.getModuleId());
        config.setHeaders(request.getHeaders());
        config.setHostPrefix(request.getHostPrefix());
        config.setAuthUsername(request.getAuthUsername());
        config.setAuthPassword(request.getAuthPassword());
        apiSyncConfigMapper.insert(config);
        return toSyncConfigResponse(config);
    }

    /**
     * 更新同步配置
     */
    public ApiSyncConfigResponse updateSyncConfig(Long configId, ApiSyncConfigRequest request) {
        ApiSyncConfig config = findSyncConfigById(configId);
        config.setName(request.getName());
        config.setUrl(request.getUrl());
        config.setModuleId(request.getModuleId());
        config.setHeaders(request.getHeaders());
        config.setHostPrefix(request.getHostPrefix());
        config.setAuthUsername(request.getAuthUsername());
        config.setAuthPassword(request.getAuthPassword());
        apiSyncConfigMapper.updateById(config);
        return toSyncConfigResponse(config);
    }

    /**
     * 删除同步配置
     */
    public void deleteSyncConfig(Long configId) {
        findSyncConfigById(configId);
        apiSyncConfigMapper.deleteById(configId);
    }

    /**
     * 复制同步配置
     */
    public ApiSyncConfigResponse copySyncConfig(Long configId) {
        ApiSyncConfig source = findSyncConfigById(configId);
        ApiSyncConfig copy = new ApiSyncConfig();
        copy.setProjectId(source.getProjectId());
        copy.setName(source.getName() + " (副本)");
        copy.setUrl(source.getUrl());
        copy.setModuleId(source.getModuleId());
        copy.setHeaders(source.getHeaders());
        copy.setHostPrefix(source.getHostPrefix());
        copy.setAuthUsername(source.getAuthUsername());
        copy.setAuthPassword(source.getAuthPassword());
        apiSyncConfigMapper.insert(copy);
        return toSyncConfigResponse(copy);
    }

    /**
     * 同步单条配置
     */
    @Transactional(rollbackFor = Exception.class)
    public SwaggerImportResult syncOneConfig(Long configId) {
        ApiSyncConfig config = findSyncConfigById(configId);
        SWAGGER_LOG.info("同步配置读取: configId={}, headersText={}", configId,
                config.getHeaders() == null ? "<null>" : "\"" + config.getHeaders().replace("\n", "\\n") + "\"");
        Map<String, String> defaultHeaders = parseHeadersText(config.getHeaders());
        SWAGGER_LOG.info("同步配置解析默认请求头: configId={}, keys={}", configId,
                defaultHeaders == null ? "<null>" : defaultHeaders.keySet());

        SwaggerSyncRequest syncRequest = new SwaggerSyncRequest();
        syncRequest.setProjectId(config.getProjectId());
        syncRequest.setUrl(config.getUrl());
        syncRequest.setModuleId(config.getModuleId());
        syncRequest.setHeaders(buildSwaggerAuthHeaders(config));
        syncRequest.setDefaultHeaders(defaultHeaders);
        syncRequest.setHostPrefix(config.getHostPrefix());
        SwaggerImportResult result = syncFromUrl(syncRequest);
        // 更新最后同步时间
        config.setLastSyncAt(LocalDateTime.now());
        apiSyncConfigMapper.updateById(config);
        return result;
    }

    /**
     * 全部同步：逐条执行，单条失败不影响其他
     */
    public List<Map<String, Object>> syncAllConfigs(Long projectId) {
        LambdaQueryWrapper<ApiSyncConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApiSyncConfig::getProjectId, projectId)
               .orderByAsc(ApiSyncConfig::getCreatedAt);
        List<ApiSyncConfig> configs = apiSyncConfigMapper.selectList(wrapper);
        List<Map<String, Object>> results = new ArrayList<>();
        for (ApiSyncConfig config : configs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("configId", config.getId());
            item.put("configName", config.getName());
            try {
                SwaggerImportResult sr = syncOneConfig(config.getId());
                item.put("created", sr.getCreated());
                item.put("updated", sr.getUpdated());
                item.put("total", sr.getTotal());
                item.put("error", null);
            } catch (Exception e) {
                item.put("created", 0);
                item.put("updated", 0);
                item.put("total", 0);
                item.put("error", e.getMessage());
            }
            results.add(item);
        }
        return results;
    }

    // ───────────────────── 私有方法 ─────────────────────

    /**
     * 发布接口模块变更事件（接口级操作聚合到所属模块，由采集器按模块整体重采）
     */
    private void publishModuleEvent(Long projectId, Long moduleId) {
        if (projectId == null || moduleId == null) {
            return;
        }
        eventPublisher.publishEvent(new ProjectMaterialChangedEvent(
                projectId, KnowledgeMaterialCollector.SOURCE_API_MODULE, moduleId));
    }

    private Api findById(Long apiId) {
        Api api = apiMapper.selectById(apiId);
        if (api == null) {
            throw new BusinessException(ErrorCode.API_NOT_FOUND, "接口不存在：" + apiId);
        }
        return api;
    }

    /**
     * 确保值为合法 JSON，否则包装为 JSON 字符串（MySQL JSON 列不接受非 JSON 文本）
     */
    private String sanitizeJson(String value) {
        if (!StringUtils.hasText(value)) return value;
        try {
            objectMapper.readTree(value);
            return value;
        } catch (Exception e) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (Exception ex) {
                return null;
            }
        }
    }

    private ApiInfoResponse toResponse(Api api, Map<Long, ApiModule> moduleMap) {
        ApiInfoResponse response = new ApiInfoResponse();
        BeanUtils.copyProperties(api, response);
        response.setServicePrefix(apiModuleService.resolveServicePrefix(api.getModuleId(), moduleMap));
        // ponytail: 逐条 count 引用次数；列表数据量增大时改为批量统计
        LambdaQueryWrapper<ApiKeyword> refWrapper = new LambdaQueryWrapper<>();
        refWrapper.eq(ApiKeyword::getApiId, api.getId());
        long refCount = apiKeywordMapper.selectCount(refWrapper);
        response.setRefCount((int) refCount);
        return response;
    }

    private ApiSyncConfig findSyncConfigById(Long configId) {
        ApiSyncConfig config = apiSyncConfigMapper.selectById(configId);
        if (config == null) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR,
                    "同步配置不存在：" + configId);
        }
        return config;
    }

    private ApiSyncConfigResponse toSyncConfigResponse(ApiSyncConfig config) {
        ApiSyncConfigResponse response = new ApiSyncConfigResponse();
        BeanUtils.copyProperties(config, response);
        return response;
    }

    private Map<String, String> parseHeadersText(String headersText) {
        if (!StringUtils.hasText(headersText)) {
            return null;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line : headersText.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            // 同时支持英文冒号 ':' 和中文全角冒号 '：'，避免用户复制粘贴时格式错误
            int idx = line.indexOf(':');
            if (idx < 0) {
                idx = line.indexOf('：');
            }
            if (idx > 0) {
                headers.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            } else {
                SWAGGER_LOG.warn("默认请求头格式无效，忽略该行: {}", line);
            }
        }
        return headers.isEmpty() ? null : headers;
    }

    private Map<String, String> buildSwaggerAuthHeaders(ApiSyncConfig config) {
        if (StringUtils.hasText(config.getAuthUsername())) {
            String credentials = config.getAuthUsername() + ":" + (config.getAuthPassword() == null ? "" : config.getAuthPassword());
            String basic = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Basic " + basic);
            return headers;
        }
        return null;
    }

    /**
     * 检测业务级错误响应：某些网关/应用在认证失败时返回 HTTP 200 + JSON 包装错误（如 {"code":401,"message":"..."}）
     * 检测到此类响应时抛出异常，以便调用方继续尝试下一个候选 URL
     */
    private void checkBusinessErrorResponse(String body, String actualUrl) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.has("code")) {
                JsonNode codeNode = root.get("code");
                int code = codeNode.isNumber() ? codeNode.intValue() : -1;
                if (code != 0 && code != 200) {
                    String message = root.has("message") ? root.get("message").asText() : "未知错误";
                    SWAGGER_LOG.debug("Swagger 同步检测到业务级错误 [{}] code={}, message={}", actualUrl, code, message);
                    throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR,
                            "服务端返回业务错误（code=" + code + "）：" + message);
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // JSON 解析失败，交给后续 validateSwaggerJson 处理
        }
    }

    private void validateSwaggerJson(String body, String actualUrl) {
        try {
            JsonNode root = objectMapper.readTree(body);
            boolean hasVersion = root.has("openapi") || root.has("swagger");
            boolean hasPaths = root.has("paths") && root.get("paths").isObject();
            if (!hasVersion || !hasPaths) {
                throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR,
                        "响应不是有效的 OpenAPI/Swagger 文档（缺少 openapi/swagger 或 paths）：" + actualUrl);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR,
                    "无法解析 Swagger JSON：" + e.getMessage());
        }
    }

    private List<String> extractHeaderNames(List<Map<String, Object>> headers) {
        if (headers == null) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        for (Map<String, Object> h : headers) {
            Object name = h.get("name");
            if (name != null) {
                names.add(name.toString());
            }
        }
        return names;
    }

    private List<String> extractHeaderNames(String headersJson) {
        if (!StringUtils.hasText(headersJson)) {
            return Collections.emptyList();
        }
        try {
            List<Map<String, Object>> parsed = objectMapper.readValue(headersJson, new TypeReference<List<Map<String, Object>>>() {});
            return extractHeaderNames(parsed);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<Map<String, Object>> toDefaultHeaderItems(Map<String, String> defaultHeaders) {
        if (defaultHeaders == null || defaultHeaders.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : defaultHeaders.entrySet()) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("name", entry.getKey());
            h.put("type", "string");
            h.put("required", false);
            h.put("description", "");
            h.put("value", entry.getValue());
            result.add(h);
        }
        return result;
    }

    private String mergeHeadersJson(String existingJson, List<Map<String, Object>> defaultHeaders) {
        try {
            List<Map<String, Object>> headers = new ArrayList<>();
            if (StringUtils.hasText(existingJson)) {
                List<Map<String, Object>> parsed = objectMapper.readValue(existingJson, new TypeReference<List<Map<String, Object>>>() {});
                if (parsed != null) {
                    headers.addAll(parsed);
                }
            }

            // 默认请求头优先级高于已有同名请求头（用户显式配置，同步时覆盖旧值），
            // 但 Content-Type 除外：它由 Swagger 规范的 requestBody/consumes 决定，默认请求头不应介入
            Set<String> defaultNames = new HashSet<>();
            for (Map<String, Object> h : defaultHeaders) {
                Object name = h.get("name");
                if (name != null) {
                    if ("Content-Type".equalsIgnoreCase(name.toString())) {
                        continue;
                    }
                    defaultNames.add(name.toString().toLowerCase());
                }
            }
            headers.removeIf(h -> {
                Object name = h.get("name");
                return name != null && defaultNames.contains(name.toString().toLowerCase());
            });

            for (Map<String, Object> h : defaultHeaders) {
                Object name = h.get("name");
                if (name != null && "Content-Type".equalsIgnoreCase(name.toString())) {
                    continue;
                }
                headers.add(h);
            }
            return headers.isEmpty() ? null : objectMapper.writeValueAsString(headers);
        } catch (Exception e) {
            log.warn("合并默认请求头失败: {}", e.getMessage());
            return existingJson;
        }
    }

    /**
     * 提取路径前导占位符变量名：${var}/rest → var，无前导占位符返回 null
     */
    private String extractLeadingPlaceholder(String path) {
        if (path == null || !path.startsWith("${")) {
            return null;
        }
        int end = path.indexOf('}');
        if (end <= 1) {
            return null;
        }
        return path.substring(2, end).trim();
    }

    /**
     * 附加默认 host 前缀到接口路径前（如 ${host} + /user/list → ${host}/user/list）
     */
    private String applyHostPrefix(String hostPrefix, String path) {
        if (!StringUtils.hasText(hostPrefix) || !StringUtils.hasText(path)) {
            return path;
        }
        String prefix = hostPrefix.trim();
        String trimmedPath = path.trim();
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        if (!trimmedPath.startsWith("/")) {
            trimmedPath = "/" + trimmedPath;
        }
        return prefix + trimmedPath;
    }

    private String joinUrl(String baseUrl, String prefix, String path) {
        StringBuilder sb = new StringBuilder(baseUrl == null ? "" : baseUrl);
        if (StringUtils.hasText(prefix)) {
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '/' && prefix.startsWith("/")) {
                prefix = prefix.substring(1);
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '/' && !prefix.startsWith("/")) {
                sb.append('/');
            }
            sb.append(prefix);
        }
        if (StringUtils.hasText(path)) {
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '/' && path.startsWith("/")) {
                path = path.substring(1);
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '/' && !path.startsWith("/")) {
                sb.append('/');
            }
            sb.append(path);
        }
        return sb.toString();
    }

    private String readStream(InputStream is) throws IOException {
        if (is == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString().trim();
    }
}
