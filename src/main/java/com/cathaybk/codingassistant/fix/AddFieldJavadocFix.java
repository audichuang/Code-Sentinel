package com.cathaybk.codingassistant.fix;

// 1. 引入 PriorityAction 介面
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

/**
 * 為欄位添加簡單 Javadoc 註解 (/** TypeName * /) 的快速修復。
 * 實現 PriorityAction 以提高其在快速修復列表中的優先級。
 */
// 2. 在 implements 後面加上 PriorityAction
public class AddFieldJavadocFix implements LocalQuickFix, PriorityAction {

    private final String fieldTypeName;

    /**
     * 建構子。
     * @param fieldTypeName 欄位的類型名稱，將用於 Javadoc 內容。
     */
    public AddFieldJavadocFix(@NotNull String fieldTypeName) {
        this.fieldTypeName = fieldTypeName;
    }

    @Nls(capitalization = Nls.Capitalization.Sentence)
    @NotNull
    @Override
    public String getName() {
        // 提供給使用者的快速修復選項文字
        return "為欄位添加 Javadoc (/** " + fieldTypeName + " */)";
    }

    @Nls(capitalization = Nls.Capitalization.Sentence)
    @NotNull
    @Override
    public String getFamilyName() {
        // 屬於哪個修復家族，通常是一個更通用的名稱
        return "添加欄位 Javadoc";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        // 獲取被標記問題的 PSI 元素 (通常是欄位名稱 PsiIdentifier)
        PsiElement element = descriptor.getPsiElement();
        if (element == null || !element.isValid()) {
            return;
        }

        // 從 PsiIdentifier 向上找到 PsiField
        PsiField field = PsiTreeUtil.getParentOfType(element, PsiField.class);
        if (field == null || !field.isValid()) {
            return; // 找不到欄位或欄位無效
        }

        // 檢查是否已經存在 Javadoc (雖然檢查器應該已經過濾，但這裡做個保險)
        if (field.getDocComment() != null) {
            return;
        }

        try {
            // 獲取 PSI 元素工廠
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

            // 構建 Javadoc 文本
            String javadocText = "/** " + this.fieldTypeName + " */";

            // 創建 PsiDocComment 對象
            PsiDocComment newDocComment = factory.createDocCommentFromText(javadocText);

            // 將新的 Javadoc 添加到欄位之前
            PsiElement addedElement = field.addBefore(newDocComment, field.getFirstChild());

            // (可選但推薦) 添加完 Javadoc 後，重新格式化受影響的程式碼
            if (addedElement != null && addedElement.isValid()) {
                CodeStyleManager.getInstance(project).reformat(field); // 格式化整個欄位定義
            } else if (field.isValid()) {
                CodeStyleManager.getInstance(project).reformat(field);
            }

        } catch (IncorrectOperationException e) {
            // 處理 PSI 操作可能拋出的異常
            System.err.println("應用 AddFieldJavadocFix 時出錯: " + e.getMessage());
        }
    }

    // --- 3. 實作 PriorityAction 介面的 getPriority 方法 ---
    /**
     * 指定此快速修復的優先級。
     * Priority.HIGH 會使其在 Alt+Enter 列表中顯示得更靠前。
     * 你也可以試試 Priority.NORMAL 如果 HIGH 太高了。
     * @return 優先級
     */
    @NotNull
    @Override
    public Priority getPriority() {
        return Priority.HIGH; // 設置為高優先級
    }
    // --- 方法結束 ---
}