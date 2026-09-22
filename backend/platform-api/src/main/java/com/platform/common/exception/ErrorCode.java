/**
 * @author HXN
 * @date 2026-08-18 16:20
 * @description 错误码枚举
 */
package com.platform.common.exception;

/**
 * 全局错误码定义
 *
 * <p>错误码范围分配：
 * <ul>
 *   <li>1000-1099: 公共（参数校验、认证、权限）</li>
 *   <li>1100-1199: M1 认证与用户</li>
 *   <li>1200-1299: M2 项目管理</li>
 *   <li>1300-1399: M3 环境配置</li>
 *   <li>1400-1499: M4 接口文档</li>
 *   <li>1500-1599: M5 接口关键字</li>
 *   <li>1600-1699: M6 工具方法</li>
 *   <li>1700-1799: M7 Action</li>
 *   <li>1800-1899: M8 自动化用例</li>
 *   <li>1900-1999: M9 测试执行</li>
 *   <li>2100-2199: 系统管理</li>
 *   <li>2200-2299: M11 测试代码库</li>
 *   <li>2300-2399: M12 界面元素</li>
 *   <li>2400-2499: M13 项目文档</li>
 *   <li>2500-2599: M14 需求文档</li>
 *   <li>2600-2699: M15 AI 渗透测试</li>
 *   <li>2700-2799: M16 知识库</li>
 *   <li>2800-2899: M17 AI 白盒测试</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>
 *   throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "项目不存在");
 * </pre>
 */
public final class ErrorCode {

    private ErrorCode() {
    }

    // ===== 公共错误码 (1000-1099) =====
    public static final int PARAM_VALIDATION_ERROR = 1001;
    public static final int UNAUTHORIZED = 1002;
    public static final int ACCESS_TOKEN_EXPIRED = 1003;
    public static final int REFRESH_TOKEN_EXPIRED = 1004;
    public static final int FORBIDDEN = 1005;
    public static final int RESOURCE_NOT_FOUND = 1006;
    public static final int RESOURCE_CONFLICT = 1007;
    public static final int INTERNAL_ERROR = 1008;
    public static final int TASK_NOT_FOUND = 1009;

    // ===== M1 认证与用户 (1100-1199) =====
    public static final int LOGIN_FAILED = 1100;
    public static final int USER_NOT_FOUND = 1101;
    public static final int USERNAME_DUPLICATE = 1102;
    public static final int ACCOUNT_RESERVED = 1103;
    public static final int ADMIN_PROTECTED = 1104;
    public static final int CAPTCHA_INVALID = 1105;
    public static final int CAPTCHA_EXPIRED = 1106;
    public static final int PASSWORD_INCORRECT = 1107;

    // ===== 角色管理 (1108-1112) =====
    public static final int ROLE_NOT_FOUND = 1108;
    public static final int ROLE_CODE_DUPLICATE = 1109;
    public static final int ROLE_HAS_USERS = 1110;
    public static final int ROLE_IS_BUILTIN = 1111;
    public static final int EXCEL_IMPORT_FAILED = 1112;

    // ===== M2 项目管理 (1200-1299) =====
    public static final int PROJECT_NOT_FOUND = 1200;
    public static final int PROJECT_NAME_DUPLICATE = 1201;

    // ===== M3 环境配置 (1300-1399) =====
    public static final int ENV_NOT_FOUND = 1300;
    public static final int ENV_NAME_DUPLICATE = 1301;
    public static final int ENV_CONNECTION_FAILED = 1303;

    // ===== M4 接口文档 (1400-1499) =====
    public static final int API_NOT_FOUND = 1400;
    public static final int API_PATH_DUPLICATE = 1401;
    public static final int SWAGGER_PARSE_FAILED = 1402;
    public static final int API_DEPENDENCY_CONFLICT = 1403;
    public static final int API_MODULE_NOT_FOUND = 1410;
    public static final int API_MODULE_SYSTEM = 1411;
    public static final int API_MODULE_HAS_APIS = 1412;
    public static final int API_MODULE_NAME_DUPLICATE = 1413;

    // ===== M5 接口关键字 (1500-1599) =====
    public static final int KEYWORD_NOT_FOUND = 1500;
    public static final int KEYWORD_NAME_DUPLICATE = 1501;
    public static final int KEYWORD_DATA_INVALID = 1502;
    public static final int KEYWORD_DEPENDENCY_CONFLICT = 1503;
    public static final int KEYWORD_GROUP_NOT_FOUND = 1510;
    public static final int KEYWORD_GROUP_NAME_DUPLICATE = 1511;
    public static final int KEYWORD_GROUP_SYSTEM = 1512;

    // ===== M6 工具方法 (1600-1699) =====
    public static final int TOOL_NOT_FOUND = 1600;
    public static final int TOOL_NAME_DUPLICATE = 1601;
    public static final int TOOL_EXECUTION_TIMEOUT = 1602;
    public static final int TOOL_SECURITY_CHECK_FAILED = 1603;

    // ===== M7 Action (1700-1799) =====
    public static final int ACTION_NOT_FOUND = 1700;
    public static final int ACTION_NAME_DUPLICATE = 1701;
    public static final int ACTION_NODE_SERIALIZE_FAILED = 1702;
    public static final int ACTION_CIRCULAR_REFERENCE = 1703;
    public static final int ACTION_DEPENDENCY_CONFLICT = 1704;
    public static final int ACTION_GROUP_NOT_FOUND = 1705;
    public static final int ACTION_GROUP_SYSTEM = 1706;

    // ===== M8 自动化用例 (1800-1899) =====
    public static final int STEP_VALIDATION_FAILED = 1800;
    public static final int PARAM_DATA_INVALID = 1801;
    public static final int AUTO_SUITE_NOT_FOUND = 1802;
    public static final int AUTO_SUITE_NAME_DUPLICATE = 1803;
    public static final int AUTO_CASE_NOT_FOUND = 1804;
    public static final int AUTO_CASE_NAME_DUPLICATE = 1805;
    public static final int AUTO_SUITE_GROUP_NOT_FOUND = 1806;
    public static final int AUTO_SUITE_GROUP_NAME_DUPLICATE = 1807;
    public static final int AUTO_SUITE_GROUP_HAS_CHILDREN = 1808;
    public static final int AUTO_CASE_GROUP_NOT_FOUND = 1809;
    public static final int AUTO_CASE_GROUP_SYSTEM = 1810;
    public static final int AUTO_CASE_NOT_IN_SUITE = 1811;
    public static final int MANUAL_CASE_NOT_FOUND = 1820;
    public static final int MANUAL_CASE_GROUP_NOT_FOUND = 1821;
    public static final int MANUAL_CASE_GROUP_SYSTEM = 1822;

    // ===== 缺陷管理 (1830-1849) =====
    public static final int DEFECT_NOT_FOUND = 1830;
    public static final int DEFECT_GROUP_NOT_FOUND = 1831;
    public static final int DEFECT_GROUP_SYSTEM = 1832;

    // ===== M9 测试执行 (1900-1999) =====
    public static final int EXECUTION_QUEUE_FULL = 1900;
    public static final int PLAN_NO_ENVIRONMENT = 1901;
    public static final int PLAN_NOT_FOUND = 1902;
    public static final int PLAN_NAME_DUPLICATE = 1903;
    public static final int EXECUTION_NOT_FOUND = 1904;

    // ===== 系统管理 (2100-2199) =====
    public static final int MENU_NOT_FOUND = 2100;
    public static final int CACHE_KEY_NOT_FOUND = 2102;
    public static final int CUSTOM_FIELD_NOT_FOUND = 2103;
    public static final int CUSTOM_FIELD_KEY_DUPLICATE = 2104;
    public static final int CUSTOM_FIELD_LABEL_DUPLICATE = 2105;

    // ===== M11 测试代码库 (2200-2299) =====
    public static final int REPOSITORY_NOT_FOUND = 2200;
    public static final int REPOSITORY_NAME_DUPLICATE = 2201;
    public static final int REPOSITORY_CRYPTO_ERROR = 2202;
    public static final int REPOSITORY_BRANCH_FETCH_FAILED = 2203;
    public static final int REPOSITORY_GROUP_NOT_FOUND = 2204;
    public static final int REPOSITORY_GROUP_NAME_DUPLICATE = 2205;
    public static final int REPOSITORY_GROUP_SYSTEM = 2206;
    public static final int REPOSITORY_GROUP_NOT_EMPTY = 2207;

    // ===== M12 界面元素 (2300-2399) =====
    public static final int UI_ELEMENT_IMPORT_FAILED = 2300;

    // ===== M13 项目文档 (2400-2499) =====
    public static final int PROJECT_DOC_NOT_FOUND = 2400;
    public static final int PROJECT_DOC_GROUP_NOT_FOUND = 2401;
    public static final int PROJECT_DOC_GROUP_SYSTEM = 2402;
    public static final int PROJECT_DOC_FILE_ERROR = 2403;

    // ===== M14 需求文档 (2500-2599) =====
    public static final int REQUIREMENT_GROUP_NOT_FOUND = 2500;
    public static final int REQUIREMENT_GROUP_NAME_DUPLICATE = 2501;
    public static final int REQUIREMENT_GROUP_SYSTEM = 2502;
    public static final int REQUIREMENT_GROUP_NOT_EMPTY = 2503;

    // ===== M15 AI 渗透测试 (2600-2699) =====
    public static final int SCAN_TASK_NOT_FOUND = 2600;
    public static final int SCAN_ALREADY_RUNNING = 2601;
    public static final int SCAN_NOT_RUNNING = 2602;
    public static final int SCAN_REPO_CLONE_FAILED = 2603;
    public static final int SCAN_REPORT_NOT_FOUND = 2604;

    // ===== M16 知识库 (2700-2799) =====
    public static final int KB_NOT_FOUND = 2700;
    public static final int KB_NAME_DUPLICATE = 2701;
    public static final int KB_PROCESSING = 2702;
    public static final int KB_DOC_NOT_FOUND = 2710;
    public static final int KB_DOC_ALREADY_IMPORTED = 2711;
    public static final int KB_DOC_PARSE_FAILED = 2712;
    public static final int KB_CONVERSATION_NOT_FOUND = 2720;
    public static final int KB_LLM_CALL_FAILED = 2730;
    public static final int KB_EMBEDDING_FAILED = 2731;
    public static final int KB_QDRANT_UNAVAILABLE = 2732;

    // ===== M17 AI 白盒测试 (2800-2899) =====
    public static final int WHITEBOX_TASK_NOT_FOUND = 2800;
    public static final int WHITEBOX_ALREADY_RUNNING = 2801;
    public static final int WHITEBOX_NOT_RUNNING = 2802;
    public static final int WHITEBOX_REPO_SYNC_FAILED = 2803;
    public static final int WHITEBOX_NO_BASELINE = 2804;
    public static final int WHITEBOX_NO_CHANGES = 2805;
    public static final int WHITEBOX_LLM_GENERATE_FAILED = 2806;
    public static final int WHITEBOX_BUILD_FAILED = 2807;

    // ===== 业务错误码 → HTTP 状态码映射 =====
    private static final int[] UNAUTHORIZED_CODES = {UNAUTHORIZED, ACCESS_TOKEN_EXPIRED, REFRESH_TOKEN_EXPIRED};
    private static final int[] FORBIDDEN_CODES = {FORBIDDEN, ADMIN_PROTECTED, ROLE_IS_BUILTIN, AUTO_CASE_GROUP_SYSTEM, ACTION_GROUP_SYSTEM, KEYWORD_GROUP_SYSTEM, MANUAL_CASE_GROUP_SYSTEM, DEFECT_GROUP_SYSTEM, PROJECT_DOC_GROUP_SYSTEM, REPOSITORY_GROUP_SYSTEM, REQUIREMENT_GROUP_SYSTEM};

    public static int toHttpStatus(int errorCode) {
        for (int code : UNAUTHORIZED_CODES) {
            if (code == errorCode) return 401;
        }
        for (int code : FORBIDDEN_CODES) {
            if (code == errorCode) return 403;
        }
        switch (errorCode) {
            case PARAM_VALIDATION_ERROR:
            case CAPTCHA_INVALID:
            case CAPTCHA_EXPIRED:
            case PASSWORD_INCORRECT:
            case EXCEL_IMPORT_FAILED:
            case UI_ELEMENT_IMPORT_FAILED:
            case AUTO_CASE_NOT_IN_SUITE:
            case REPOSITORY_BRANCH_FETCH_FAILED:
                return 400;
            case RESOURCE_NOT_FOUND:
            case TASK_NOT_FOUND:
            case AUTO_SUITE_NOT_FOUND:
            case AUTO_CASE_NOT_FOUND:
            case PLAN_NOT_FOUND:
            case AUTO_SUITE_GROUP_NOT_FOUND:
            case AUTO_CASE_GROUP_NOT_FOUND:
            case MANUAL_CASE_NOT_FOUND:
            case MANUAL_CASE_GROUP_NOT_FOUND:
            case DEFECT_NOT_FOUND:
            case DEFECT_GROUP_NOT_FOUND:
            case PROJECT_DOC_NOT_FOUND:
            case PROJECT_DOC_GROUP_NOT_FOUND:
            case ACTION_GROUP_NOT_FOUND:
            case KEYWORD_GROUP_NOT_FOUND:
            case ROLE_NOT_FOUND:
            case MENU_NOT_FOUND:
            case CACHE_KEY_NOT_FOUND:
            case CUSTOM_FIELD_NOT_FOUND:
            case REPOSITORY_NOT_FOUND:
            case REPOSITORY_GROUP_NOT_FOUND:
            case REQUIREMENT_GROUP_NOT_FOUND:
            case SCAN_TASK_NOT_FOUND:
            case SCAN_REPORT_NOT_FOUND:
            case KB_NOT_FOUND:
            case KB_DOC_NOT_FOUND:
            case KB_CONVERSATION_NOT_FOUND:
            case WHITEBOX_TASK_NOT_FOUND:
                return 404;
            case RESOURCE_CONFLICT:
            case API_DEPENDENCY_CONFLICT:
            case KEYWORD_DEPENDENCY_CONFLICT:
            case USERNAME_DUPLICATE:
            case PROJECT_NAME_DUPLICATE:
            case ENV_NAME_DUPLICATE:
            case API_PATH_DUPLICATE:
            case KEYWORD_NAME_DUPLICATE:
            case KEYWORD_GROUP_NAME_DUPLICATE:
            case AUTO_SUITE_NAME_DUPLICATE:
            case AUTO_CASE_NAME_DUPLICATE:
            case AUTO_SUITE_GROUP_NAME_DUPLICATE:
            case AUTO_SUITE_GROUP_HAS_CHILDREN:
            case PLAN_NAME_DUPLICATE:
            case ROLE_CODE_DUPLICATE:
            case ROLE_HAS_USERS:
            case REPOSITORY_NAME_DUPLICATE:
            case REPOSITORY_GROUP_NAME_DUPLICATE:
            case REPOSITORY_GROUP_NOT_EMPTY:
            case REQUIREMENT_GROUP_NAME_DUPLICATE:
            case REQUIREMENT_GROUP_NOT_EMPTY:
            case API_MODULE_NAME_DUPLICATE:
            case CUSTOM_FIELD_KEY_DUPLICATE:
            case CUSTOM_FIELD_LABEL_DUPLICATE:
                return 409;
            case EXECUTION_QUEUE_FULL:
                return 429;
            case KB_NAME_DUPLICATE:
                return 409;
            case KB_PROCESSING:
            case KB_DOC_ALREADY_IMPORTED:
                return 409;
            case SCAN_ALREADY_RUNNING:
            case SCAN_NOT_RUNNING:
            case SCAN_REPO_CLONE_FAILED:
                return 409;
            case WHITEBOX_ALREADY_RUNNING:
            case WHITEBOX_NOT_RUNNING:
            case WHITEBOX_REPO_SYNC_FAILED:
            case WHITEBOX_NO_CHANGES:
                return 409;
            case WHITEBOX_NO_BASELINE:
                return 400;
            case WHITEBOX_LLM_GENERATE_FAILED:
            case WHITEBOX_BUILD_FAILED:
                return 500;
            default:
                return 500;
        }
    }
}
