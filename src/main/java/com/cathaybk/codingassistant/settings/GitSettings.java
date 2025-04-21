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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 用於保存Git相關設置的持久化組件
 */
@State(name = "com.cathaybk.codingassistant.settings.GitSettings", storages = {
        @Storage("cathaybkGitSettings.xml"),
        @Storage(StoragePathMacros.WORKSPACE_FILE)
})
public class GitSettings implements PersistentStateComponent<GitSettings.State> {

    private State myState = new State();

    // 預設的目標分支
    private static final String[] DEFAULT_TARGET_BRANCHES = { "dev" };

    // 用戶配置的目標分支
    private List<String> targetBranches = new ArrayList<>(Arrays.asList(DEFAULT_TARGET_BRANCHES));

    // 新增設定：是否生成完整 Javadoc (預設為 true)
    private boolean generateFullJavadoc = true;

    // 新增設定：是否執行 Git 分支檢查 (預設為 true)
    private boolean checkGitBranch = true;

    // 新增設定：是否執行程式碼規範檢查 (預設為 true)
    private boolean checkCodeQuality = true;

    public static GitSettings getInstance(@NotNull Project project) {
        return project.getService(GitSettings.class);
    }

    @NotNull
    @Override
    public State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
    }

    public List<String> getTargetBranches() {
        return new ArrayList<>(myState.targetBranches);
    }

    public void setTargetBranches(List<String> branches) {
        myState.targetBranches = new HashSet<>(branches);
    }

    /**
     * 將目標分支重置為預設值
     */
    public void resetToDefaults() {
        myState.targetBranches.clear();
        myState.targetBranches.addAll(Arrays.asList(DEFAULT_TARGET_BRANCHES));
    }

    /**
     * 獲取目標分支的字符串表示（逗號分隔）
     */
    public String getTargetBranchesAsString() {
        return String.join(",", myState.targetBranches);
    }

    /**
     * 從字符串設置目標分支（逗號分隔）
     */
    public void setTargetBranchesFromString(String branchesStr) {
        myState.targetBranches.clear();
        if (branchesStr != null && !branchesStr.isEmpty()) {
            String[] branches = branchesStr.split(",");
            for (String branch : branches) {
                String trimmed = branch.trim();
                if (!trimmed.isEmpty()) {
                    myState.targetBranches.add(trimmed);
                }
            }
        }

        // 確保至少有一個分支
        if (myState.targetBranches.isEmpty()) {
            myState.targetBranches.add(DEFAULT_TARGET_BRANCHES[0]);
        }
    }

    // 新增 getter 和 setter
    public boolean isGenerateFullJavadoc() {
        return generateFullJavadoc;
    }

    public void setGenerateFullJavadoc(boolean generateFullJavadoc) {
        this.generateFullJavadoc = generateFullJavadoc;
    }

    // 新增 Git 檢查開關的 getter 和 setter
    public boolean isCheckGitBranch() {
        return myState.checkGitBranch;
    }

    public void setCheckGitBranch(boolean check) {
        myState.checkGitBranch = check;
    }

    // 新增程式碼檢查開關的 getter 和 setter
    public boolean isCheckCodeQuality() {
        return checkCodeQuality;
    }

    public void setCheckCodeQuality(boolean checkCodeQuality) {
        this.checkCodeQuality = checkCodeQuality;
    }

    public boolean isCheckCodeStyle() {
        return myState.checkCodeStyle;
    }

    public void setCheckCodeStyle(boolean check) {
        myState.checkCodeStyle = check;
    }

    public JavadocStyle getJavadocStyle() {
        return myState.javadocStyle;
    }

    public void setJavadocStyle(JavadocStyle style) {
        myState.javadocStyle = style;
    }

    public String getParameterDtoSuffix() {
        return myState.parameterDtoSuffix;
    }

    public void setParameterDtoSuffix(String suffix) {
        myState.parameterDtoSuffix = suffix;
    }

    public String getReturnTypeDtoSuffix() {
        return myState.returnTypeDtoSuffix;
    }

    public void setReturnTypeDtoSuffix(String suffix) {
        myState.returnTypeDtoSuffix = suffix;
    }

    public enum JavadocStyle {
        FULL, MINIMAL
    }

    public static class State {
        public Set<String> targetBranches = new HashSet<>(Arrays.asList("dev", "master"));
        public boolean checkGitBranch = true;
        public boolean checkCodeStyle = true;
        public JavadocStyle javadocStyle = JavadocStyle.FULL;
        public String parameterDtoSuffix = "上行";
        public String returnTypeDtoSuffix = "下行";
    }
}