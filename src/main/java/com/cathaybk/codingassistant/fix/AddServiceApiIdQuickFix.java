package com.cathaybk.codingassistant.fix;

import com.cathaybk.codingassistant.utils.JavadocUtil;
import com.cathaybk.codingassistant.settings.GitSettings;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;

/**
 * 為缺少電文代號的 Service 類別提供快速修復選項，
 * 建議從關聯的來源獲取的電文代號來添加 Javadoc。
 * 如果類別沒有 Javadoc，則創建一個包含 API ID 的簡單結構；否則，更新現有 Javadoc。
 */
public class AddServiceApiIdQuickFix implements LocalQuickFix {

    private final String sourceName;
    private final String apiId;

    public AddServiceApiIdQuickFix(@NotNull String sourceName, @NotNull String apiId) {
        this.sourceName = sourceName;
        this.apiId = apiId;
    }

    @NotNull
    @Override
    public String getName() {
        String shortSourceName = sourceName.length() > 30 ? sourceName.substring(0, 27) + "..." : sourceName;
        String shortApiId = apiId.length() > 40 ? apiId.substring(0, 37) + "..." : apiId;
        return String.format("從 %s 添加電文代號 (%s)", shortSourceName, shortApiId);
    }

    @NotNull
    @Override
    public String getFamilyName() {
        return "為 Service 添加電文代號註解";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiElement element = descriptor.getPsiElement();
        if (!(element instanceof PsiIdentifier) && !(element instanceof PsiClass) && !(element instanceof PsiKeyword)) {
            return;
        }
        PsiClass aClass = PsiTreeUtil.getParentOfType(element, PsiClass.class, false);
        if (aClass == null && element instanceof PsiClass) {
            aClass = (PsiClass) element;
        }
        if (aClass == null || !aClass.isValid())
            return;

        try {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
            String newDocLineText = this.apiId;

            PsiDocComment existingComment = aClass.getDocComment();

            // 從設定讀取是否要生成完整 Javadoc
            boolean generateFull = GitSettings.getInstance(project).isGenerateFullJavadoc();

            if (existingComment == null) {
                // --- 情況：類別完全沒有 Javadoc ---
                // 1. 為類別創建一個包含 API ID 的 Javadoc 文本
                // (對類別來說，完整和最小差異不大，都使用此基本模板)
                String classJavadocText = "/**\n * " + newDocLineText + "\n */";

                // 2. 根據文本創建 PsiDocComment
                PsiDocComment newComment = factory.createDocCommentFromText(classJavadocText);

                // 3. 將新註解添加到類別前面
                PsiModifierList modifierList = aClass.getModifierList();
                PsiElement anchor = null;
                if (modifierList != null && modifierList.getFirstChild() != null) {
                    anchor = modifierList;
                } else {
                    anchor = aClass.getFirstChild(); // Fallback
                }
                if (anchor != null) {
                    aClass.addBefore(newComment, anchor);
                } else {
                    aClass.add(newComment); // Should not happen often
                }

                // 4. 格式化程式碼
                JavaCodeStyleManager.getInstance(project).shortenClassReferences(aClass);
                CodeStyleManager.getInstance(project).reformat(aClass);

            } else {
                // --- 情況：類別已有 Javadoc ---
                // 使用 JavadocUtil 處理 Javadoc 的插入或更新（保持原有邏輯）
                JavadocUtil.insertOrUpdateJavadoc(project, factory, aClass, newDocLineText);
            }

        } catch (IncorrectOperationException e) {
            System.err.println("應用 AddServiceApiIdQuickFix 時出錯: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("在 AddServiceApiIdQuickFix 中生成或添加 Javadoc 時發生意外錯誤: " + e.getMessage());
            e.printStackTrace();
        }
    }
}