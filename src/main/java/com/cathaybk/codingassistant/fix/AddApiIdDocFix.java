package com.cathaybk.codingassistant.fix;

import com.cathaybk.codingassistant.utils.ApiMsgIdUtil;
import com.cathaybk.codingassistant.utils.JavadocUtil;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;

/**
 * 為缺少電文代號的 API 方法提供添加 Javadoc 註解的快速修復。
 * 使用 JavadocUtil 處理 Javadoc 操作。
 */
public class AddApiIdDocFix implements LocalQuickFix {

    private static final String TODO_DESCRIPTION = "[請填寫API描述]";

    @NotNull
    @Override
    public String getName() {
        return "添加電文代號註解";
    }

    @NotNull
    @Override
    public String getFamilyName() {
        return getName();
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiElement element = descriptor.getPsiElement();
        if (!(element instanceof PsiIdentifier) && !(element instanceof PsiMethod)) {
            return;
        }
        PsiMethod method = (element instanceof PsiMethod) ? (PsiMethod) element : PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        if (method == null) return;

        try {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
            String apiIdTemplate = ApiMsgIdUtil.generateApiMsgId(method);
            String newDocLineText = apiIdTemplate + " " + TODO_DESCRIPTION;

            // 使用 JavadocUtil 處理 Javadoc 的插入或創建
            JavadocUtil.insertOrUpdateJavadoc(project, factory, method, newDocLineText);

        } catch (IncorrectOperationException e) {
            System.err.println("應用 AddApiIdDocFix 時出錯: " + e.getMessage());
        }
    }
}