package com.cathaybk.codingassistant.utils;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.javadoc.PsiDocComment;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * 從Service獲取電文代號的快速修復實現 - 為 Controller 方法設計
 *
 * <p>新增功能：在提示中顯示具體的Service類名，讓使用者清楚電文代號的來源</p>
 */
public class AddControllerApiIdFromServiceFix implements LocalQuickFix {

    private final Map<String, String> serviceApiIds;
    private final String sourceClassName; // 來源Service類名

    public AddControllerApiIdFromServiceFix(Map<String, String> serviceApiIds) {
        this.serviceApiIds = serviceApiIds;
        this.sourceClassName = serviceApiIds.keySet().iterator().next();
    }

    @NotNull
    @Override
    public String getName() {
        // 顯示具體的來源Service類名
        return "從 " + sourceClassName + " 添加電文代號註解";
    }

    @NotNull
    @Override
    public String getFamilyName() {
        return "從Service添加電文代號註解";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        try {
            PsiElement element = descriptor.getPsiElement();
            if (element == null) return;

            // 獲取方法元素
            PsiMethod method = (PsiMethod) element.getParent();
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

            // 獲取電文代號
            String fullApiId = serviceApiIds.values().iterator().next();

            // 建立包含電文代號的Javadoc註解
            StringBuilder docBuilder = new StringBuilder();
            docBuilder.append("/**\n");
            docBuilder.append(" * ").append(fullApiId).append("\n");
            docBuilder.append(" */");

            // 創建Javadoc註解
            PsiDocComment newDocComment = factory.createDocCommentFromText(docBuilder.toString());

            // 更新或添加註解
            PsiDocComment existingComment = method.getDocComment();
            if (existingComment != null) {
                existingComment.replace(newDocComment);
            } else {
                method.addBefore(newDocComment, method.getModifierList());
            }
        } catch (Exception e) {
            // 忽略操作異常
        }
    }
}