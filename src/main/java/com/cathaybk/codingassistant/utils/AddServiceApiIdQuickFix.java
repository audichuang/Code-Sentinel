package com.cathaybk.codingassistant.utils;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.javadoc.PsiDocTag;
import com.intellij.psi.javadoc.PsiDocToken;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * 為缺少電文代號的 Service 類別提供快速修復選項，
 * 建議從關聯的來源（如 Controller 方法、接口、實現類）獲取的電文代號來添加 Javadoc。
 * <p>
 * {@link ApiMsgIdUtil#findApiIdsForServiceClass} 目前設計為只查找一個最相關的來源，
 * 因此此修復通常只處理單個來源建議。
 * </p>
 */
public class AddServiceApiIdQuickFix implements LocalQuickFix {

    private final String sourceName; // 電文代號的來源名稱
    private final String apiId;      // 從來源獲取的完整電文代號字串

    /**
     * 構造函數。
     *
     * @param sourceName 建議的電文代號來源的名稱。
     * @param apiId      要應用的電文代號字串。
     */
    public AddServiceApiIdQuickFix(@NotNull String sourceName, @NotNull String apiId) {
        this.sourceName = sourceName;
        this.apiId = apiId;
    }

    /**
     * @return 顯示在快速修復菜單中的名稱，包含來源信息。
     */
    @NotNull
    @Override
    public String getName() {
        String shortSourceName = sourceName.length() > 30 ? sourceName.substring(0, 27) + "..." : sourceName;
        String shortApiId = apiId.length() > 40 ? apiId.substring(0, 37) + "..." : apiId;
        return String.format("從 %s 添加電文代號 (%s)", shortSourceName, shortApiId);
    }

    /**
     * @return 快速修復的族名稱。
     */
    @NotNull
    @Override
    public String getFamilyName() {
        return "為 Service 添加電文代號註解";
    }

    /**
     * 應用快速修復邏輯，將來源的電文代號添加到 Service 類別的 Javadoc 中。
     * <p>
     * 目前實現仍為替換或創建新的 Javadoc。更優雅的方式是使用 PSI API 進行插入。
     * </p>
     *
     * @param project    當前項目。
     * @param descriptor 描述問題及其位置的物件。
     */
    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiElement element = descriptor.getPsiElement(); // 通常是類名 PsiIdentifier
        if (!(element instanceof PsiIdentifier) || !(element.getParent() instanceof PsiClass)) {
            // 有時問題可能直接標記在 PsiClass 上
            if (!(element instanceof PsiClass)) {
                return;
            }
        }
        PsiClass aClass = (element instanceof PsiClass) ? (PsiClass) element : (PsiClass) element.getParent();


        try {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
            // 要插入的行內容
            String docCommentLine = "* " + this.apiId;

            PsiDocComment existingComment = aClass.getDocComment();

            // --- 為了簡單起見，暫時保留替換邏輯 ---
            // TODO: 優化此處邏輯，類似 AddApiIdDocFix，嘗試插入而非替換
            replaceJavadoc(aClass, factory, this.apiId);

            /* // --- 插入邏輯的示例框架 (待完善) ---
            if (existingComment != null) {
                PsiElement anchor = findJavadocInsertionAnchor(existingComment);
                 PsiDocComment tempComment = factory.createDocCommentFromText("/** " + docCommentLine + " * /");
                 PsiElement contentToInsert = tempComment.getDescriptionElements()[0];
                 PsiElement newLine = contentToInsert.getPrevSibling();

                if (anchor != null && contentToInsert != null && newLine != null) {
                    existingComment.addBefore(contentToInsert, anchor);
                    existingComment.addBefore(newLine, anchor);
                     JavaCodeStyleManager.getInstance(project).shortenClassReferences(existingComment);
                } else {
                    replaceJavadoc(aClass, factory, this.apiId); // 回退
                }
            } else {
                replaceJavadoc(aClass, factory, this.apiId); // 創建新的
            }
            */
        } catch (IncorrectOperationException e) {
            System.err.println("應用 AddServiceApiIdQuickFix 時出錯: " + e.getMessage());
        }
    }

    /**
     * 替換或創建類別的 Javadoc 註解。
     */
    private void replaceJavadoc(PsiClass aClass, PsiElementFactory factory, String apiId) throws IncorrectOperationException {
        PsiDocComment newDocComment = factory.createDocCommentFromText(
                "/**\n * " + apiId + "\n */");
        PsiDocComment existingComment = aClass.getDocComment();
        if (existingComment != null) {
            existingComment.replace(newDocComment);
        } else {
            // 在類簽名之前添加 Javadoc
            aClass.addBefore(newDocComment, aClass.getModifierList());
        }
        // 格式化 Javadoc
        JavaCodeStyleManager.getInstance(aClass.getProject()).shortenClassReferences(aClass.getDocComment());
    }

    /**
     * 查找 Javadoc 中用於插入新描述行的錨點元素。
     * （與 AddApiIdDocFix 中的方法相同）
     */
    @Nullable
    private PsiElement findJavadocInsertionAnchor(@NotNull PsiDocComment comment) {
        PsiElement[] children = comment.getChildren();
        for (PsiElement child : children) {
            if (child instanceof PsiDocTag) {
                PsiElement prevSibling = child.getPrevSibling();
                while (prevSibling instanceof PsiWhiteSpace || (prevSibling instanceof PsiDocToken && ((PsiDocToken) prevSibling).getTokenType() == JavaDocTokenType.DOC_COMMENT_LEADING_ASTERISKS)) {
                    child = prevSibling;
                    prevSibling = prevSibling.getPrevSibling();
                }
                return child;
            }
        }
        return Arrays.stream(children)
                .filter(e -> e instanceof PsiDocToken && ((PsiDocToken)e).getTokenType() == JavaDocTokenType.DOC_COMMENT_END)
                .findFirst()
                .orElse(children.length > 0 ? children[children.length - 1] : null);
    }
}