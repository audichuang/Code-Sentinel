package com.cathaybk.codingassistant.utils;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Service類的快速修復實現
 *
 * <p>提供為Service類添加電文代號註解的快速修復功能</p>
 * <p>改進：在提示中顯示電文代號的具體來源（Controller方法、Service接口或實現類）</p>
 */
public class AddServiceApiIdQuickFix implements LocalQuickFix {
    // 儲存來源及其電文代號的映射
    private final Map<String, String> apiIds;
    private final String sourceKey; // 來源鍵（方法名或類名）
    private final String sourceType; // 來源類型描述

    /**
     * 構造函數
     *
     * @param apiIds 來源及其電文代號的映射
     */
    public AddServiceApiIdQuickFix(Map<String, String> apiIds) {
        this.apiIds = apiIds;
        this.sourceKey = apiIds.keySet().iterator().next();
        this.sourceType = determineSourceType(sourceKey);
    }

    /**
     * 根據鍵名判斷來源類型
     *
     * @param key 來源鍵
     * @return 來源類型描述
     */
    private String determineSourceType(String key) {
        if (key.contains("Impl") || key.endsWith("Service") || key.endsWith("Svc")) {
            return key;
        } else {
            // 假設是方法名，因此很可能是Controller方法
            return "Controller方法 " + key + "()";
        }
    }

    /**
     * 取得修復的名稱
     *
     * @return 修復名稱
     */
    @NotNull
    @Override
    public String getName() {
        // 顯示具體的來源
        return "添加來自" + sourceType + "的電文代號註解";
    }

    /**
     * 取得修復的族名稱
     *
     * @return 族名稱
     */
    @NotNull
    @Override
    public String getFamilyName() {
        return "添加電文代號註解";
    }

    /**
     * 應用修復邏輯
     *
     * <p>為Service類添加或更新包含電文代號的Javadoc註解</p>
     *
     * @param project 當前項目
     * @param descriptor 問題描述
     */
    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        try {
            // 獲取問題元素
            PsiElement element = descriptor.getPsiElement();
            if (!(element instanceof PsiIdentifier)) return;  // 確保元素是標識符

            // 獲取Service類
            PsiClass aClass = (PsiClass) element.getParent();
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

            // 獲取電文代號
            String fullApiId = apiIds.values().iterator().next();

            // 創建Javadoc註解
            StringBuilder docBuilder = new StringBuilder();
            docBuilder.append("/**\n");
            docBuilder.append(" * ").append(fullApiId).append("\n");
            docBuilder.append(" */");

            PsiDocComment docComment = factory.createDocCommentFromText(docBuilder.toString());

            // 更新或添加註解
            PsiDocComment existingComment = aClass.getDocComment();
            if (existingComment != null) {
                existingComment.replace(docComment);
            } else {
                aClass.addBefore(docComment, aClass.getModifierList());
            }
        } catch (Exception e) {
            // 忽略操作異常
        }
    }
}