package com.cathaybk.codingassistant.apicopy.service;

import com.cathaybk.codingassistant.apicopy.model.ApiInfo;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AllClassesSearch;
import com.intellij.util.Query;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API 索引服務
 * 負責索引專案中的所有 API 並提供搜尋功能
 */
@Service(Service.Level.PROJECT)
public final class ApiIndexService implements Disposable {
    private static final Logger LOG = Logger.getInstance(ApiIndexService.class);

    // API ID 格式：MSGID-XXXX-XXXX 或類似格式
    private static final Pattern API_ID_PATTERN = Pattern.compile(
            "(?:MSGID-)?([A-Z]{2,4})-([A-Z])-([A-Z0-9]+)"
    );

    // 從 Javadoc 中提取 API ID
    private static final Pattern JAVADOC_API_ID_PATTERN = Pattern.compile(
            "@?(?:api[_-]?id|msgid|電文代號|交易代號)\\s*[：:=]?\\s*([A-Z]{2,4}-[A-Z]-[A-Z0-9]+)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of(
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController"
    );

    private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "org.springframework.web.bind.annotation.RequestMapping",
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.DeleteMapping",
            "org.springframework.web.bind.annotation.PatchMapping"
    );

    private final Project project;
    private final Map<String, ApiInfo> apiCache = new ConcurrentHashMap<>();
    private volatile boolean indexed = false;
    private volatile long lastIndexTime = 0;
    private volatile long lastCleanupTime = 0;
    private static final long INDEX_TTL = 300_000; // 5 分鐘過期
    private static final long CLEANUP_INTERVAL = 60_000; // 1 分鐘清理一次

    public static ApiIndexService getInstance(@NotNull Project project) {
        return project.getService(ApiIndexService.class);
    }

    public ApiIndexService(@NotNull Project project) {
        this.project = project;
        Disposer.register(project, this);
    }

    /**
     * 搜尋 API（支援模糊搜尋）
     */
    @NotNull
    public List<ApiInfo> searchApis(@NotNull String keyword) {
        ensureIndexed();
        cleanupStaleEntries();

        return ReadAction.compute(() -> {
            List<ApiInfo> results = new ArrayList<>();
            String lowerKeyword = keyword.toLowerCase();

            for (ApiInfo api : apiCache.values()) {
                if (api.isValid()) {
                    // 搜尋 MSGID
                    if (api.getMsgId().toLowerCase().contains(lowerKeyword)) {
                        results.add(api);
                        continue;
                    }
                    // 搜尋描述
                    if (api.getDescription() != null &&
                        api.getDescription().toLowerCase().contains(lowerKeyword)) {
                        results.add(api);
                        continue;
                    }
                    // 搜尋方法名稱
                    PsiMethod method = api.getMethod();
                    if (method != null && method.getName().toLowerCase().contains(lowerKeyword)) {
                        results.add(api);
                        continue;
                    }
                    // 搜尋路徑
                    if (api.getPath() != null &&
                        api.getPath().toLowerCase().contains(lowerKeyword)) {
                        results.add(api);
                    }
                }
            }

            // 按 MSGID 排序
            results.sort(Comparator.comparing(ApiInfo::getMsgId));
            return results;
        });
    }

    /**
     * 根據 MSGID 取得 API
     */
    @Nullable
    public ApiInfo getApiByMsgId(@NotNull String msgId) {
        ensureIndexed();

        return ReadAction.compute(() -> {
            ApiInfo api = apiCache.get(msgId);
            if (api != null && api.isValid()) {
                return api;
            }
            return null;
        });
    }

    /**
     * 取得所有 API
     */
    @NotNull
    public List<ApiInfo> getAllApis() {
        ensureIndexed();

        return ReadAction.compute(() -> {
            List<ApiInfo> results = new ArrayList<>();
            for (ApiInfo api : apiCache.values()) {
                if (api.isValid()) {
                    results.add(api);
                }
            }
            results.sort(Comparator.comparing(ApiInfo::getMsgId));
            LOG.debug("getAllApis() 返回 " + results.size() + " 個有效 API (快取共 " + apiCache.size() + " 項)");
            return results;
        });
    }

    /**
     * 重新建立索引（原子操作）
     */
    public void reindex() {
        LOG.info("reindex() 開始，當前快取大小: " + apiCache.size());

        // 先建立新索引
        Map<String, ApiInfo> newCache = buildNewIndex();

        // 原子替換
        synchronized (this) {
            apiCache.clear();
            apiCache.putAll(newCache);
            indexed = true;
            lastIndexTime = System.currentTimeMillis();
        }

        LOG.info("reindex() 完成，新快取大小: " + apiCache.size());
    }

    /**
     * 確保索引已建立
     */
    private void ensureIndexed() {
        long now = System.currentTimeMillis();
        if (!indexed || (now - lastIndexTime > INDEX_TTL)) {
            buildIndex();
        }
    }

    /**
     * 建立新的 API 索引並返回（不影響現有快取）
     */
    @NotNull
    private Map<String, ApiInfo> buildNewIndex() {
        Map<String, ApiInfo> newCache = new ConcurrentHashMap<>();

        try {
            ReadAction.nonBlocking(() -> {
                GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
                Query<PsiClass> query = AllClassesSearch.search(scope, project);

                query.forEach(psiClass -> {
                    if (isControllerClass(psiClass)) {
                        indexControllerClassToMap(psiClass, newCache);
                    }
                    return true;
                });
                return null;
            })
            .inSmartMode(project)
            .executeSynchronously();

            LOG.info("buildNewIndex() 建立完成，共 " + newCache.size() + " 個 API");
        } catch (Exception e) {
            LOG.warn("建立 API 索引時發生錯誤: " + e.getMessage());
        }

        return newCache;
    }

    /**
     * 建立 API 索引（用於 ensureIndexed）
     */
    private synchronized void buildIndex() {
        if (indexed && (System.currentTimeMillis() - lastIndexTime < INDEX_TTL)) {
            return;
        }

        LOG.info("開始建立 API 索引...");

        try {
            Map<String, ApiInfo> newCache = buildNewIndex();

            // 原子替換
            apiCache.clear();
            apiCache.putAll(newCache);
            indexed = true;
            lastIndexTime = System.currentTimeMillis();

            LOG.info("API 索引建立完成，共 " + apiCache.size() + " 個 API");
        } catch (Exception e) {
            LOG.warn("建立 API 索引時發生錯誤: " + e.getMessage());
        }
    }

    /**
     * 索引 Controller 類別到指定的 Map
     */
    private void indexControllerClassToMap(@NotNull PsiClass controller, @NotNull Map<String, ApiInfo> targetCache) {
        for (PsiMethod method : controller.getMethods()) {
            if (isApiMethod(method)) {
                String msgId = extractMsgId(method);

                // 如果沒有 MSGID，使用 ControllerName.methodName 作為識別碼
                if (msgId == null) {
                    String className = controller.getName() != null ? controller.getName() : "Unknown";
                    msgId = className + "." + method.getName();
                }

                String description = extractDescription(method);
                String httpMethod = extractHttpMethod(method);
                String path = extractPath(method, controller);

                ApiInfo apiInfo = new ApiInfo(
                        msgId, description, httpMethod, path, method, controller);
                targetCache.put(msgId, apiInfo);
            }
        }
    }

    /**
     * 判斷是否為 Controller 類別
     */
    private boolean isControllerClass(@NotNull PsiClass psiClass) {
        for (String annotation : CONTROLLER_ANNOTATIONS) {
            if (psiClass.hasAnnotation(annotation)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判斷是否為 API 方法
     */
    private boolean isApiMethod(@NotNull PsiMethod method) {
        for (String annotation : MAPPING_ANNOTATIONS) {
            if (method.hasAnnotation(annotation)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 從方法的 Javadoc 中提取 MSGID
     */
    @Nullable
    private String extractMsgId(@NotNull PsiMethod method) {
        PsiDocComment docComment = method.getDocComment();
        if (docComment == null) {
            return null;
        }

        String docText = docComment.getText();

        // 嘗試從 Javadoc 中提取
        Matcher matcher = JAVADOC_API_ID_PATTERN.matcher(docText);
        if (matcher.find()) {
            return matcher.group(1);
        }

        // 嘗試直接匹配 API ID 格式
        Matcher idMatcher = API_ID_PATTERN.matcher(docText);
        if (idMatcher.find()) {
            return idMatcher.group(0);
        }

        return null;
    }

    /**
     * 從 Javadoc 中提取描述
     */
    @Nullable
    private String extractDescription(@NotNull PsiMethod method) {
        PsiDocComment docComment = method.getDocComment();
        if (docComment == null) {
            return null;
        }

        // 取得第一個非空的描述文字
        PsiElement[] children = docComment.getDescriptionElements();
        StringBuilder sb = new StringBuilder();
        for (PsiElement child : children) {
            String text = child.getText().trim();
            if (!text.isEmpty() && !text.startsWith("@")) {
                sb.append(text).append(" ");
            }
        }

        String result = sb.toString().trim();
        // 清理結果
        result = result.replaceAll("\\s+", " ");
        result = result.replaceAll("\\*", "").trim();

        return result.isEmpty() ? null : result;
    }

    /**
     * 提取 HTTP 方法
     */
    @Nullable
    private String extractHttpMethod(@NotNull PsiMethod method) {
        if (method.hasAnnotation("org.springframework.web.bind.annotation.GetMapping")) {
            return "GET";
        }
        if (method.hasAnnotation("org.springframework.web.bind.annotation.PostMapping")) {
            return "POST";
        }
        if (method.hasAnnotation("org.springframework.web.bind.annotation.PutMapping")) {
            return "PUT";
        }
        if (method.hasAnnotation("org.springframework.web.bind.annotation.DeleteMapping")) {
            return "DELETE";
        }
        if (method.hasAnnotation("org.springframework.web.bind.annotation.PatchMapping")) {
            return "PATCH";
        }

        // 從 @RequestMapping 取得
        PsiAnnotation requestMapping = method.getAnnotation(
                "org.springframework.web.bind.annotation.RequestMapping");
        if (requestMapping != null) {
            PsiAnnotationMemberValue methodAttr = requestMapping.findAttributeValue("method");
            if (methodAttr != null) {
                String text = methodAttr.getText();
                if (text.contains("GET")) return "GET";
                if (text.contains("POST")) return "POST";
                if (text.contains("PUT")) return "PUT";
                if (text.contains("DELETE")) return "DELETE";
            }
        }

        return null;
    }

    /**
     * 提取 API 路徑
     */
    @Nullable
    private String extractPath(@NotNull PsiMethod method, @NotNull PsiClass controller) {
        StringBuilder path = new StringBuilder();

        // 從 Controller 取得基礎路徑
        PsiAnnotation controllerMapping = controller.getAnnotation(
                "org.springframework.web.bind.annotation.RequestMapping");
        if (controllerMapping != null) {
            String basePath = getPathFromAnnotation(controllerMapping);
            if (basePath != null) {
                path.append(basePath);
            }
        }

        // 從方法取得路徑
        for (String annotation : MAPPING_ANNOTATIONS) {
            PsiAnnotation methodAnnotation = method.getAnnotation(annotation);
            if (methodAnnotation != null) {
                String methodPath = getPathFromAnnotation(methodAnnotation);
                if (methodPath != null) {
                    if (!methodPath.startsWith("/") && path.length() > 0 && !path.toString().endsWith("/")) {
                        path.append("/");
                    }
                    path.append(methodPath);
                }
                break;
            }
        }

        return path.length() > 0 ? path.toString() : null;
    }

    /**
     * 從註解取得路徑值
     */
    @Nullable
    private String getPathFromAnnotation(@NotNull PsiAnnotation annotation) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue("value");
        if (value == null) {
            value = annotation.findAttributeValue("path");
        }

        if (value != null) {
            String text = value.getText();
            // 移除引號和大括號
            text = text.replaceAll("[\"{}]", "").trim();
            return text.isEmpty() ? null : text;
        }
        return null;
    }

    /**
     * 清理無效的條目
     * 定期執行以防止無效條目累積
     */
    private void cleanupStaleEntries() {
        long now = System.currentTimeMillis();
        // 限制清理頻率，避免過度清理
        if (now - lastCleanupTime < CLEANUP_INTERVAL) {
            return;
        }
        lastCleanupTime = now;

        int beforeSize = apiCache.size();
        int removedCount = 0;

        Iterator<Map.Entry<String, ApiInfo>> iterator = apiCache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ApiInfo> entry = iterator.next();
            ApiInfo api = entry.getValue();
            // 移除無效的條目
            if (!api.isValid()) {
                iterator.remove();
                removedCount++;
            }
        }

        if (removedCount > 0) {
            LOG.debug("清理過期 API 緩存：" + removedCount + " 項（從 " + beforeSize + " 減少到 " + apiCache.size() + "）");
        }
    }

    @Override
    public void dispose() {
        apiCache.clear();
        indexed = false;
        LOG.info("ApiIndexService 已釋放");
    }
}
