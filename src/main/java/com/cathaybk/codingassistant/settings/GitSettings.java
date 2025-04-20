package com.cathaybk.codingassistant.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 用於保存Git相關設置的持久化組件
 */
@State(name = "com.cathaybk.codingassistant.settings.GitSettings", storages = {
        @Storage("cathaybkGitSettings.xml"),
        @Storage(StoragePathMacros.WORKSPACE_FILE)
})
public class GitSettings implements PersistentStateComponent<GitSettings> {

    // 預設的目標分支
    private static final String[] DEFAULT_TARGET_BRANCHES = { "dev" };

    // 用戶配置的目標分支
    private List<String> targetBranches = new ArrayList<>(Arrays.asList(DEFAULT_TARGET_BRANCHES));

    // 新增設定：是否生成完整 Javadoc (預設為 true)
    private boolean generateFullJavadoc = true;

    public static GitSettings getInstance(@NotNull Project project) {
        return project.getService(GitSettings.class);
    }

    @Nullable
    @Override
    public GitSettings getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull GitSettings state) {
        XmlSerializerUtil.copyBean(state, this);
    }

    public List<String> getTargetBranches() {
        return targetBranches;
    }

    public void setTargetBranches(List<String> targetBranches) {
        this.targetBranches = targetBranches;
    }

    /**
     * 將目標分支重置為預設值
     */
    public void resetToDefaults() {
        targetBranches.clear();
        targetBranches.addAll(Arrays.asList(DEFAULT_TARGET_BRANCHES));
    }

    /**
     * 獲取目標分支的字符串表示（逗號分隔）
     */
    public String getTargetBranchesAsString() {
        return String.join(",", targetBranches);
    }

    /**
     * 從字符串設置目標分支（逗號分隔）
     */
    public void setTargetBranchesFromString(String branchesStr) {
        targetBranches.clear();
        if (branchesStr != null && !branchesStr.isEmpty()) {
            String[] branches = branchesStr.split(",");
            for (String branch : branches) {
                String trimmed = branch.trim();
                if (!trimmed.isEmpty()) {
                    targetBranches.add(trimmed);
                }
            }
        }

        // 確保至少有一個分支
        if (targetBranches.isEmpty()) {
            targetBranches.add(DEFAULT_TARGET_BRANCHES[0]);
        }
    }

    // 新增 getter 和 setter
    public boolean isGenerateFullJavadoc() {
        return generateFullJavadoc;
    }

    public void setGenerateFullJavadoc(boolean generateFullJavadoc) {
        this.generateFullJavadoc = generateFullJavadoc;
    }
}