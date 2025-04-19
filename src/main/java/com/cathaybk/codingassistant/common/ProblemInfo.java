package com.cathaybk.codingassistant.common; // 新建一個 common 套件

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 代表程式碼檢查問題的資訊類。
 * 用於在檢查邏輯和顯示邏輯間傳遞問題資訊。
 */
public class ProblemInfo {
    private final PsiElement element; // 問題所在的 PSI 元素
    private final String description; // 問題描述
    private final ProblemHighlightType highlightType; // 問題嚴重性
    private final String suggestionSource; // (可選) 建議來源，例如用於 ApiMsgId
    private final String suggestedValue; // (可選) 建議值，例如用於 ApiMsgId

    /**
     * 創建一個基本的問題資訊
     *
     * @param element       問題所在的 PSI 元素
     * @param description   問題描述
     * @param highlightType 問題的高亮類型 (嚴重程度)
     */
    public ProblemInfo(@NotNull PsiElement element, @NotNull String description,
                       @NotNull ProblemHighlightType highlightType) {
        this(element, description, highlightType, null, null);
    }

    /**
     * 創建一個帶建議的問題資訊
     *
     * @param element          問題所在的 PSI 元素
     * @param description      問題描述
     * @param highlightType    問題的高亮類型 (嚴重程度)
     * @param suggestionSource 建議的來源 (如: Service名稱)
     * @param suggestedValue   建議值 (如: API ID)
     */
    public ProblemInfo(@NotNull PsiElement element, @NotNull String description,
                       @NotNull ProblemHighlightType highlightType,
                       @Nullable String suggestionSource, @Nullable String suggestedValue) {
        this.element = element;
        this.description = description;
        this.highlightType = highlightType;
        this.suggestionSource = suggestionSource;
        this.suggestedValue = suggestedValue;
    }

    /**
     * 取得問題所在的 PSI 元素
     */
    @NotNull
    public PsiElement getElement() {
        return element;
    }

    /**
     * 取得問題描述
     */
    @NotNull
    public String getDescription() {
        return description;
    }

    /**
     * 取得問題的高亮類型 (嚴重程度)
     */
    @NotNull
    public ProblemHighlightType getHighlightType() {
        return highlightType;
    }

    /**
     * 取得建議的來源 (可能為 null)
     */
    @Nullable
    public String getSuggestionSource() {
        return suggestionSource;
    }

    /**
     * 取得建議值 (可能為 null)
     */
    @Nullable
    public String getSuggestedValue() {
        return suggestedValue;
    }

    @Override
    public String toString() {
        return description + " [" + highlightType + "]";
    }
}