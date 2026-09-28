/**
 * @author HXN
 * @date 2026-09-28
 * @description AI 服务提供商切换服务
 */
package com.platform.ai.service;

import com.platform.ai.config.AiProviderConfig;
import com.platform.ai.dto.AiProviderStatusResponse;
import com.platform.auth.entity.GlobalSettings;
import com.platform.auth.mapper.GlobalSettingsMapper;
import com.platform.common.exception.BusinessException;
import com.platform.common.exception.ErrorCode;
import com.platform.knowledge.config.KnowledgeConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * AI 服务提供商切换服务
 *
 * <p>当前启用的对话提供商存于 {@code global_settings} 的 {@code ai.provider}
 * （值 bailian / qoder），前端【AI 服务】页切换、运行时生效无需重启。
 * 读取走内存缓存（单体应用无多实例一致性问题），切换后失效重建；
 * 数据库无记录时回退 yml {@code ai.provider} 兜底默认值。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiProviderService {

    /** global_settings 配置键 */
    private static final String CONFIG_KEY = "ai.provider";

    private static final String PROVIDER_BAILIAN = "bailian";
    private static final String PROVIDER_QODER = "qoder";

    private final GlobalSettingsMapper globalSettingsMapper;
    private final AiProviderConfig aiConfig;
    private final KnowledgeConfig knowledgeConfig;
    private final com.platform.ai.client.QoderCloudClient qoderCloudClient;

    /** 当前提供商缓存（null=未加载） */
    private volatile String cachedProvider;

    /**
     * 当前启用的提供商（bailian / qoder）
     */
    public String currentProvider() {
        String cached = cachedProvider;
        if (cached != null) {
            return cached;
        }
        GlobalSettings settings = globalSettingsMapper.selectByConfigKey(CONFIG_KEY);
        String provider = settings != null && settings.getConfigValue() != null
                ? settings.getConfigValue() : aiConfig.getProvider();
        if (!PROVIDER_QODER.equals(provider)) {
            // 兜底：未知值一律按百炼处理（百炼为默认后端，配置缺失时仍可用 yml 默认值）
            provider = PROVIDER_BAILIAN;
        }
        cachedProvider = provider;
        return provider;
    }

    /**
     * 当前是否启用 Qoder（LlmClient 路由判断入口）
     */
    public boolean isQoderCurrent() {
        return PROVIDER_QODER.equals(currentProvider());
    }

    /**
     * 切换提供商
     *
     * @param provider 目标提供商（bailian / qoder）
     */
    @Transactional(rollbackFor = Exception.class)
    public void switchProvider(String provider) {
        if (provider == null || provider.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR, "目标提供商不能为空");
        }
        provider = provider.trim();
        if (!PROVIDER_BAILIAN.equals(provider) && !PROVIDER_QODER.equals(provider)) {
            throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR,
                    "不支持的提供商：" + provider + "（可选：bailian / qoder）");
        }
        // 目标为 qoder 时校验连接配置完整性，避免切换后所有对话调用直接失败
        if (PROVIDER_QODER.equals(provider)) {
            AiProviderStatusResponse.ProviderInfo info = buildQoderInfo(false);
            if (!Boolean.TRUE.equals(info.getConfigured())) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_CONFIG_INVALID,
                        "Qoder 连接配置不完整（" + missingQoderFields() + "），请先在后端完成配置并测试连接");
            }
        }

        GlobalSettings settings = globalSettingsMapper.selectByConfigKey(CONFIG_KEY);
        if (settings == null) {
            // 兜底插入（正常路径由 V79 迁移预置）
            settings = new GlobalSettings();
            settings.setConfigKey(CONFIG_KEY);
            settings.setConfigValue(provider);
            settings.setDescription("AI 对话提供商切换：bailian-阿里云百炼，qoder-Qoder Cloud Agents");
            settings.setUpdatedAt(LocalDateTime.now());
            globalSettingsMapper.insert(settings);
        } else {
            settings.setConfigValue(provider);
            settings.setUpdatedAt(LocalDateTime.now());
            globalSettingsMapper.updateById(settings);
        }
        cachedProvider = provider;
        log.info("AI 提供商切换为: {}", provider);
    }

    /**
     * 两提供商只读状态（凭据不回显）
     */
    public AiProviderStatusResponse status() {
        String current = currentProvider();
        AiProviderStatusResponse resp = new AiProviderStatusResponse();
        resp.setCurrentProvider(current);

        List<AiProviderStatusResponse.ProviderInfo> providers = new ArrayList<>();
        providers.add(buildBailianInfo(PROVIDER_BAILIAN.equals(current)));
        providers.add(buildQoderInfo(PROVIDER_QODER.equals(current)));
        resp.setProviders(providers);
        return resp;
    }

    /**
     * 连接测试
     *
     * <p>bailian：配置完整性校验（不真实调用，避免消耗 token）；
     * qoder：真实调用 Cloud Agents environments 接口验证 Token 有效性。</p>
     *
     * @return 成功提示文案
     */
    public String testProvider(String provider) {
        if (PROVIDER_QODER.equals(provider)) {
            return qoderCloudClient.testConnection();
        }
        if (PROVIDER_BAILIAN.equals(provider)) {
            KnowledgeConfig.LlmConfig llm = knowledgeConfig.getLlm();
            List<String> missing = new ArrayList<>();
            if (isBlank(llm.getBaseUrl())) {
                missing.add("base-url");
            }
            if (isBlank(llm.getApiKey()) || "sk-xxx".equals(llm.getApiKey())) {
                missing.add("api-key（LLM_API_KEY）");
            }
            if (isBlank(llm.getChatModel())) {
                missing.add("chat-model");
            }
            if (!missing.isEmpty()) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_CONFIG_INVALID,
                        "百炼连接配置不完整，缺失：" + String.join("、", missing));
            }
            return "百炼配置完整（" + llm.getChatModel() + "），未实际调用以避免消耗 token";
        }
        throw new BusinessException(ErrorCode.PARAM_VALIDATION_ERROR,
                "不支持的提供商：" + provider + "（可选：bailian / qoder）");
    }

    // ===== 私有方法 =====

    /** 百炼状态卡片（配置沿用 knowledge.llm 段） */
    private AiProviderStatusResponse.ProviderInfo buildBailianInfo(boolean active) {
        KnowledgeConfig.LlmConfig llm = knowledgeConfig.getLlm();
        AiProviderStatusResponse.ProviderInfo info = new AiProviderStatusResponse.ProviderInfo();
        info.setProvider(PROVIDER_BAILIAN);
        info.setDisplayName("阿里云百炼");
        info.setActive(active);
        info.setBaseUrl(llm.getBaseUrl());
        info.setChatModel(llm.getChatModel());
        boolean credentialConfigured = !isBlank(llm.getApiKey()) && !"sk-xxx".equals(llm.getApiKey());
        info.setCredentialConfigured(credentialConfigured);
        info.setConfigured(credentialConfigured && !isBlank(llm.getBaseUrl()) && !isBlank(llm.getChatModel()));
        return info;
    }

    /** Qoder 状态卡片 */
    private AiProviderStatusResponse.ProviderInfo buildQoderInfo(boolean active) {
        AiProviderConfig.QoderProps qoder = aiConfig.getQoder();
        AiProviderStatusResponse.ProviderInfo info = new AiProviderStatusResponse.ProviderInfo();
        info.setProvider(PROVIDER_QODER);
        info.setDisplayName("Qoder Cloud Agents");
        info.setActive(active);
        info.setBaseUrl(qoder.getApiBaseUrl());
        info.setAgentId(qoder.getAgentId());
        info.setEnvironmentId(qoder.getEnvironmentId());
        boolean credentialConfigured = !isBlank(qoder.getAccessToken());
        info.setCredentialConfigured(credentialConfigured);
        info.setConfigured(credentialConfigured && !isBlank(qoder.getAgentId()) && !isBlank(qoder.getEnvironmentId()));
        return info;
    }

    /** Qoder 缺失配置项描述（错误提示用） */
    private String missingQoderFields() {
        AiProviderConfig.QoderProps qoder = aiConfig.getQoder();
        List<String> missing = new ArrayList<>();
        if (isBlank(qoder.getAccessToken())) {
            missing.add("访问令牌 QODER_ACCESS_TOKEN");
        }
        if (isBlank(qoder.getAgentId())) {
            missing.add("QODER_AGENT_ID");
        }
        if (isBlank(qoder.getEnvironmentId())) {
            missing.add("QODER_ENVIRONMENT_ID");
        }
        return String.join("、", missing);
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
