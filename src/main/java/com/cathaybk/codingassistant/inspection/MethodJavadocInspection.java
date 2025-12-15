package com.cathaybk.codingassistant.inspection;

import com.cathaybk.codingassistant.fix.AddMethodJavadocFix;
import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import org.jetbrains.annotations.NotNull;

/**
 * 檢查是否缺少方法的 JavaDoc 註釋。
 * 對於沒有 JavaDoc 的方法提供快速修復選項。
 */
public class MethodJavadocInspection extends AbstractBaseJavaLocalInspectionTool {

    @NotNull
    @Override
    public String getShortName() {
        return "MethodJavadocMissing";
    }

    @NotNull
    @Override
    public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                // 檢查取消狀態，支援長時間操作中斷
                ProgressManager.checkCanceled();

                super.visitMethod(method);

                // 忽略私有方法、構造函數和簡單的 getter/setter（可依需求調整）
                if (method.hasModifierProperty(PsiModifier.PRIVATE) ||
                        method.isConstructor()) {
                    return;
                }

                // 檢查是否存在 JavaDoc 註釋
                PsiDocComment docComment = method.getDocComment();
                if (docComment == null) {
                    // 方法名稱作為報告問題的元素
                    PsiIdentifier nameIdentifier = method.getNameIdentifier();
                    if (nameIdentifier != null) {
                        holder.registerProblem(
                                nameIdentifier,
                                "方法缺少 JavaDoc 註釋",
                                new AddMethodJavadocFix());
                    }
                }
            }
        };
    }
}