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
 * 為缺少電文代號的 API 方法提供添加 Javadoc 註解的快速修復。
 * <p>
 * 此修復會：
 * 1. 使用 {@link ApiMsgIdUtil#generateApiMsgId} 生成一個基於類名和方法名的電文代號模板。
 * 2. 檢查方法是否已有 Javadoc：
 *    - 如果沒有，則創建一個包含模板和基本描述提示的新 Javadoc 並添加到方法前。
 *    - 如果有，則嘗試將包含模板的行插入到現有 Javadoc 的描述部分之後、第一個標籤 (@param, @return 等) 之前。
 *      如果找不到合適的插入點或現有 Javadoc 結構複雜，可能會回退到創建新 Javadoc（但會盡力保留）。
 * </p>
 */
public class AddApiIdDocFix implements LocalQuickFix {

    private static final String TODO_DESCRIPTION = "[請填寫API描述]"; // 描述提示文字

    /**
     * @return 顯示在快速修復菜單中的名稱。
     */
    @NotNull
    @Override
    public String getName() {
        return "添加電文代號註解";
    }

    /**
     * @return 快速修復的族名稱，用於將相似的修復分組。
     */
    @NotNull
    @Override
    public String getFamilyName() {
        return getName();
    }

    /**
     * 應用快速修復邏輯。
     *
     * @param project    當前項目。
     * @param descriptor 描述問題及其位置的物件。
     */
    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiElement element = descriptor.getPsiElement(); // 通常是方法名 PsiIdentifier
        if (!(element instanceof PsiIdentifier) || !(element.getParent() instanceof PsiMethod)) {
            return; // 確保我們作用於一個方法
        }
        PsiMethod method = (PsiMethod) element.getParent();

        try {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
            String apiIdTemplate = ApiMsgIdUtil.generateApiMsgId(method);
            String docCommentLine = "* " + apiIdTemplate + " " + TODO_DESCRIPTION; // 要插入的行內容 (* 開頭)

            PsiDocComment existingComment = method.getDocComment();

            if (existingComment != null) {
                // --- 嘗試插入到現有 Javadoc ---
                PsiElement anchor = findJavadocInsertionAnchor(existingComment); // 找到第一個 @tag 或註解末尾
                PsiElement newLine = factory.createDocCommentFromText("/**\n" + docCommentLine + "\n */")
                                           .getDescriptionElements()[0].getPrevSibling(); // 提取換行符*

                if (anchor != null && newLine != null) {
                    existingComment.addBefore(factory.createDocCommentFromText(docCommentLine).getDescriptionElements()[0], anchor); // 插入文本行
                    existingComment.addBefore(newLine, anchor); // 在文本行後插入換行符*
                     // 格式化 Javadoc
                     JavaCodeStyleManager.getInstance(project).shortenClassReferences(existingComment);
                } else {
                    // 插入失敗或找不到錨點，回退到替換 (或可以選擇更保守地不操作)
                     replaceJavadoc(method, factory, apiIdTemplate);
                }

            } else {
                // --- 創建新的 Javadoc ---
                 replaceJavadoc(method, factory, apiIdTemplate); // 用於創建新的或替換（雖然這裡不存在替換）
            }
        } catch (IncorrectOperationException e) {
            // 通常由 PSI 修改操作引起，記錄日誌或忽略
            System.err.println("應用 AddApiIdDocFix 時出錯: " + e.getMessage());
        }
    }

    /**
     * 替換或創建方法的 Javadoc 註解。
     */
    private void replaceJavadoc(PsiMethod method, PsiElementFactory factory, String apiIdTemplate) throws IncorrectOperationException {
        PsiDocComment newDocComment = factory.createDocCommentFromText(
                "/**\n * " + apiIdTemplate + " " + TODO_DESCRIPTION + "\n */");
        PsiDocComment existingComment = method.getDocComment();
        if (existingComment != null) {
            existingComment.replace(newDocComment);
        } else {
             // 在方法簽名之前添加 Javadoc
            method.addBefore(newDocComment, method.getModifierList());
        }
         // 格式化新的 Javadoc
         JavaCodeStyleManager.getInstance(method.getProject()).shortenClassReferences(method.getDocComment());
    }


    /**
     * 在 Javadoc 中查找合適的插入新描述行的錨點元素。
     * 通常是第一個 Javadoc 標籤 (@param, @return 等) 或註解的結束符。
     *
     * @param comment 要查找的 Javadoc 註解。
     * @return 錨點元素，如果找不到則返回註解的最後一個子元素。
     */
    @Nullable
    private PsiElement findJavadocInsertionAnchor(@NotNull PsiDocComment comment) {
        PsiElement[] children = comment.getChildren();
        for (PsiElement child : children) {
            // 尋找第一個 Javadoc 標籤
            if (child instanceof PsiDocTag) {
                // 我們要在標籤之前插入，所以返回標籤本身作為錨點
                // 同時，找到標籤之前的換行符和星號，以確保格式正確
                PsiElement prevSibling = child.getPrevSibling();
                while (prevSibling instanceof PsiWhiteSpace || (prevSibling instanceof PsiDocToken && ((PsiDocToken) prevSibling).getTokenType() == JavaDocTokenType.DOC_COMMENT_LEADING_ASTERISKS)) {
                    child = prevSibling; // 將錨點向前移動到換行符或星號
                    prevSibling = prevSibling.getPrevSibling();
                }
                return child;
            }
        }
        // 如果沒有找到標籤，錨點就是 Javadoc 的結束符 "*/"
        return Arrays.stream(children)
                     .filter(e -> e instanceof PsiDocToken && ((PsiDocToken)e).getTokenType() == JavaDocTokenType.DOC_COMMENT_END)
                     .findFirst()
                     .orElse(children.length > 0 ? children[children.length - 1] : null); // 理論上 Javadoc 總有結束符
    }
}