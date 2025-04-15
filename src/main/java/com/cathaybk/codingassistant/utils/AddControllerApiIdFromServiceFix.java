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
 * 為缺少電文代號的 Controller API 方法提供快速修復選項，
 * 建議從其內部使用的某個 Service 類別關聯的電文代號來添加 Javadoc。
 * <p>
 * 此類的每個實例代表一個建議來源。如果一個 Controller 方法使用了多個 Service 且它們都有關聯 ID，
 * 檢查器會為每個來源創建一個此類的實例，讓用戶在 IDE 中選擇。
 * </p>
 */
public class AddControllerApiIdFromServiceFix implements LocalQuickFix {

    private final String sourceName; // 電文代號的來源名稱 (例如 Service 類名或 Controller 方法名)
    private final String apiId;      // 從來源獲取的完整電文代號字串 (含描述)

    /**
     * 構造函數。
     *
     * @param sourceName 建議的電文代號來源的名稱 (用於顯示在修復名稱中)。
     * @param apiId      要應用的電文代號字串 (包含 ID 和描述)。
     */
    public AddControllerApiIdFromServiceFix(@NotNull String sourceName, @NotNull String apiId) {
        this.sourceName = sourceName;
        this.apiId = apiId;
    }

    /**
     * @return 顯示在快速修復菜單中的名稱，包含來源信息。
     */
    @NotNull
    @Override
    public String getName() {
        // 截斷過長的來源名或 ID，避免菜單項太寬
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
        return "從 Service 添加電文代號註解"; // 保持族名稱通用
    }

    /**
     * 應用快速修復邏輯，將來源的電文代號添加到 Controller 方法的 Javadoc 中。
     * <p>
     * 與 {@link AddApiIdDocFix} 類似，會嘗試插入到現有 Javadoc，失敗則替換。
     * </p>
     *
     * @param project    當前項目。
     * @param descriptor 描述問題及其位置的物件。
     */
    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiElement element = descriptor.getPsiElement();
        if (!(element instanceof PsiIdentifier) || !(element.getParent() instanceof PsiMethod)) {
            return;
        }
        PsiMethod method = (PsiMethod) element.getParent();

        try {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
            // 要插入的行內容 (* 開頭)，直接使用從來源獲取的 apiId
            String docCommentLine = "* " + this.apiId;

            PsiDocComment existingComment = method.getDocComment();

            if (existingComment != null) {
                // --- 嘗試插入 ---
                PsiElement anchor = findJavadocInsertionAnchor(existingComment);
                // 創建要插入的文本節點和換行符*節點
                PsiDocComment tempComment = factory.createDocCommentFromText("/** " + docCommentLine + " */");
                PsiElement contentToInsert = tempComment.getDescriptionElements()[0]; // * ID 描述
                PsiElement newLine = contentToInsert.getPrevSibling(); // 提取前導的 換行符*

                if (anchor != null && contentToInsert != null && newLine != null) {
                    existingComment.addBefore(contentToInsert, anchor);
                    existingComment.addBefore(newLine, anchor); // 插入換行符*
                    JavaCodeStyleManager.getInstance(project).shortenClassReferences(existingComment);
                } else {
                    replaceJavadoc(method, factory, this.apiId); // 插入失敗，回退
                }
            } else {
                // --- 創建新的 ---
                replaceJavadoc(method, factory, this.apiId);
            }
        } catch (IncorrectOperationException e) {
            System.err.println("應用 AddControllerApiIdFromServiceFix 時出錯: " + e.getMessage());
        }
    }

    /**
     * 替換或創建方法的 Javadoc 註解。
     */
    private void replaceJavadoc(PsiMethod method, PsiElementFactory factory, String apiId) throws IncorrectOperationException {
        // 創建包含來源電文代號的 Javadoc
        PsiDocComment newDocComment = factory.createDocCommentFromText(
                "/**\n * " + apiId + "\n */");
        PsiDocComment existingComment = method.getDocComment();
        if (existingComment != null) {
            existingComment.replace(newDocComment);
        } else {
            method.addBefore(newDocComment, method.getModifierList());
        }
        JavaCodeStyleManager.getInstance(method.getProject()).shortenClassReferences(method.getDocComment());
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