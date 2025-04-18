package com.cathaybk.codingassistant.common; // 新建一個 common 套件

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 代表一個程式碼規範問題的資訊。
 * 用於在檢查邏輯和報告機制之間解耦。
 */
public class ProblemInfo {
    private final PsiElement element; // 問題所在的 PSI 元素
    private final String description; // 問題描述
    private final ProblemHighlightType highlightType; // 問題嚴重性
    private final String suggestionSource; // (可選) 建議來源，例如用於 ApiMsgId
    private final String suggestedValue;   // (可選) 建議值，例如用於 ApiMsgId

    // 主要建構子
    public ProblemInfo(@NotNull PsiElement element, @NotNull String description, @NotNull ProblemHighlightType highlightType, @Nullable String suggestionSource, @Nullable String suggestedValue) {
        this.element = element;
        this.description = description;
        this.highlightType = highlightType;
        this.suggestionSource = suggestionSource;
        this.suggestedValue = suggestedValue;
    }

    // 簡化建構子
    public ProblemInfo(@NotNull PsiElement element, @NotNull String description, @NotNull ProblemHighlightType highlightType) {
        this(element, description, highlightType, null, null);
    }

    @NotNull
    public PsiElement getElement() {
        return element;
    }

    @NotNull
    public String getDescription() {
        return description;
    }

    @NotNull
    public ProblemHighlightType getHighlightType() {
        return highlightType;
    }

    @Nullable
    public String getSuggestionSource() {
        return suggestionSource;
    }

    @Nullable
    public String getSuggestedValue() {
        return suggestedValue;
    }
}