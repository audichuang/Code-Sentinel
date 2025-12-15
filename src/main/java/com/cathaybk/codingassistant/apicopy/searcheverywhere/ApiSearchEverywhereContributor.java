package com.cathaybk.codingassistant.apicopy.searcheverywhere;

import com.cathaybk.codingassistant.apicopy.model.ApiInfo;
import com.cathaybk.codingassistant.apicopy.service.ApiIndexService;
import com.intellij.ide.actions.searcheverywhere.FoundItemDescriptor;
import com.intellij.ide.actions.searcheverywhere.WeightedSearchEverywhereContributor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiMethod;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.List;

/**
 * Search Everywhere 整合 - API 搜尋貢獻者
 * 允許使用者在 Shift+Shift 對話框中搜尋 API
 */
public class ApiSearchEverywhereContributor implements WeightedSearchEverywhereContributor<ApiInfo> {
    private static final Logger LOG = Logger.getInstance(ApiSearchEverywhereContributor.class);

    private final Project project;

    public ApiSearchEverywhereContributor(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @NotNull String getSearchProviderId() {
        return "com.cathaybk.ApiSearchEverywhereContributor";
    }

    @Override
    public @NotNull String getGroupName() {
        return "APIs";
    }

    @Override
    public int getSortWeight() {
        // 設定權重，數值越小越靠左
        // Classes 是 100, Files 是 200
        // 設定 300 讓它排在 Files 後面
        return 300;
    }

    @Override
    public boolean showInFindResults() {
        return true;
    }

    /**
     * 關鍵方法：回傳 true 讓 IntelliJ 建立獨立的 "APIs" 分頁
     */
    @Override
    public boolean isShownInSeparateTab() {
        return true;
    }

    @Override
    public void fetchWeightedElements(@NotNull String pattern,
                                      @NotNull ProgressIndicator progressIndicator,
                                      @NotNull Processor<? super FoundItemDescriptor<ApiInfo>> consumer) {
        if (pattern.isEmpty()) {
            return;
        }

        try {
            ApiIndexService indexService = ApiIndexService.getInstance(project);
            if (indexService == null) {
                return;
            }

            List<ApiInfo> results = indexService.searchApis(pattern);

            for (ApiInfo api : results) {
                if (progressIndicator.isCanceled()) {
                    break;
                }
                int weight = calculateWeight(api, pattern);
                if (!consumer.process(new FoundItemDescriptor<>(api, weight))) {
                    break;
                }
            }
        } catch (Exception e) {
            LOG.warn("Error searching APIs: " + e.getMessage(), e);
        }
    }

    /**
     * 計算搜尋結果的權重
     * 權重越高，排名越前
     */
    private int calculateWeight(@NotNull ApiInfo api, @NotNull String pattern) {
        String lowerPattern = pattern.toLowerCase();
        String msgId = api.getMsgId().toLowerCase();
        String path = api.getPath() != null ? api.getPath().toLowerCase() : "";

        // MSGID 完全匹配
        if (msgId.equals(lowerPattern)) {
            return 10000;
        }
        // MSGID 開頭匹配
        if (msgId.startsWith(lowerPattern)) {
            return 5000;
        }
        // 路徑完全匹配
        if (path.equals(lowerPattern)) {
            return 4000;
        }
        // MSGID 包含
        if (msgId.contains(lowerPattern)) {
            return 3000;
        }
        // 路徑包含
        if (path.contains(lowerPattern)) {
            return 2000;
        }
        // 描述包含
        if (api.getDescription() != null && api.getDescription().toLowerCase().contains(lowerPattern)) {
            return 1000;
        }

        return 100;
    }

    @Override
    public boolean processSelectedItem(@NotNull ApiInfo selected, int modifiers, @NotNull String searchText) {
        PsiMethod method = selected.getMethod();
        if (method != null && method.isValid()) {
            ApplicationManager.getApplication().invokeLater(() -> {
                method.navigate(true);
            });
            return true;
        }
        return false;
    }

    @Override
    public @NotNull ListCellRenderer<? super ApiInfo> getElementsRenderer() {
        return new ApiSearchCellRenderer();
    }

    @Override
    public @Nullable Object getDataForItem(@NotNull ApiInfo element, @NotNull String dataId) {
        return null;
    }

    @Override
    public boolean isEmptyPatternSupported() {
        return false;
    }

    @Override
    public boolean isDumbAware() {
        return true; // 允許在 dumb mode 時也顯示分頁
    }
}
