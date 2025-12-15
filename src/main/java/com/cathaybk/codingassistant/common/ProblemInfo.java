package com.cathaybk.codingassistant.common;

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 代表程式碼檢查問題的資訊類。
 * 用於在檢查邏輯和顯示邏輯間傳遞問題資訊。
 * 
 * <p>
 * 使用 {@link SmartPsiElementPointer} 取代直接持有 PSI 元素，
 * 避免長時間持有 PSI 元素造成記憶體洩漏。當檔案被修改導致 PSI 元素失效時，
 * SmartPointer 會自動處理並返回 null。
 * </p>
 */
public class ProblemInfo {
    // 使用 SmartPointer 以避免記憶體洩漏和處理失效的 PSI 元素
    private final SmartPsiElementPointer<PsiElement> elementPointer;
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
        // 使用 SmartPointerManager 創建 SmartPointer，避免直接持有 PSI 元素
        this.elementPointer = SmartPointerManager.getInstance(element.getProject())
                .createSmartPsiElementPointer(element);
        this.description = description;
        this.highlightType = highlightType;
        this.suggestionSource = suggestionSource;
        this.suggestedValue = suggestedValue;
    }

    /**
     * 取得問題所在的 PSI 元素。
     * 
     * <p>
     * 注意：如果原始 PSI 元素已失效（例如檔案被修改），此方法可能返回 null。
     * 呼叫方應檢查返回值是否為 null。
     * </p>
     * 
     * @return PSI 元素，如果已失效則返回 null
     */
    @Nullable
    public PsiElement getElement() {
        return elementPointer.getElement();
    }

    /**
     * 檢查 PSI 元素是否仍然有效
     * 
     * @return 如果元素有效則返回 true，否則返回 false
     */
    public boolean isValid() {
        PsiElement element = elementPointer.getElement();
        return element != null && element.isValid();
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