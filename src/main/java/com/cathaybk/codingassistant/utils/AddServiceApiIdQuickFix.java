package com.cathaybk.codingassistant.utils;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;


/**
 * 為缺少電文代號的 Service 類別提供快速修復選項，
 * 建議從關聯的來源獲取的電文代號來添加 Javadoc。
 * 使用 JavadocUtil 處理 Javadoc 操作。
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
        if (aClass == null) return;


        try {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
            // 使用傳入的 apiId 作為行內容
            String newDocLineText = this.apiId;

            // 使用 JavadocUtil 處理 Javadoc 的插入或創建
            JavadocUtil.insertOrUpdateJavadoc(project, factory, aClass, newDocLineText);

        } catch (IncorrectOperationException e) {
            System.err.println("應用 AddServiceApiIdQuickFix 時出錯: " + e.getMessage());
        }
    }
}