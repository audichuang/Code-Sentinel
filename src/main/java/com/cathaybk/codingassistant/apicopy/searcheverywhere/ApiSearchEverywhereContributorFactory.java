package com.cathaybk.codingassistant.apicopy.searcheverywhere;

import com.cathaybk.codingassistant.apicopy.model.ApiInfo;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Search Everywhere 貢獻者工廠
 * 負責建立 ApiSearchEverywhereContributor 實例
 */
public class ApiSearchEverywhereContributorFactory implements SearchEverywhereContributorFactory<ApiInfo> {

    @Override
    public @Nullable SearchEverywhereContributor<ApiInfo> createContributor(@NotNull AnActionEvent initEvent) {
        Project project = initEvent.getProject();
        if (project == null) {
            return null;
        }
        return new ApiSearchEverywhereContributor(project);
    }
}
