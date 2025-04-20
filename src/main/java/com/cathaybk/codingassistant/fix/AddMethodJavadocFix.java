package com.cathaybk.codingassistant.fix;

import com.cathaybk.codingassistant.utils.FullJavadocGenerator;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

/**
 * 為方法添加完整 JavaDoc 註解的快速修復。
 * 使用 FullJavadocGenerator 生成包含參數、返回值和異常標籤的完整 JavaDoc 結構。
 */
public class AddMethodJavadocFix implements LocalQuickFix, PriorityAction {

    @Nls(capitalization = Nls.Capitalization.Sentence)
    @NotNull
    @Override
    public String getName() {
        return "添加完整方法 JavaDoc";
    }

    @Nls(capitalization = Nls.Capitalization.Sentence)
    @NotNull
    @Override
    public String getFamilyName() {
        return "添加完整 JavaDoc";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiElement element = descriptor.getPsiElement();
        if (element == null || !element.isValid()) {
            return;
        }

        // 獲取方法元素（如果元素自身是方法或是方法的標識符）
        PsiMethod method;
        if (element instanceof PsiMethod) {
            method = (PsiMethod) element;
        } else if (element instanceof PsiIdentifier) {
            method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        } else {
            method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        }

        if (method == null || !method.isValid()) {
            return;
        }

        // 使用 FullJavadocGenerator 添加完整的 JavaDoc
        FullJavadocGenerator.addJavadocToMethod(project, method);
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.HIGH;
    }
}