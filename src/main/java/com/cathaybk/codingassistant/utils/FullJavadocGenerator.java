package com.cathaybk.codingassistant.utils;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 提供生成完整 JavaDoc 結構的工具方法，包括方法參數和返回值標籤。
 */
public class FullJavadocGenerator {

    /**
     * 為方法生成完整的 JavaDoc 文本，包括 @param, @return 和 @throws 標籤。
     *
     * @param method 需要生成 JavaDoc 的方法
     * @return 生成的 JavaDoc 文本
     */
    public static String generateMethodJavadoc(@NotNull PsiMethod method) {
        StringBuilder sb = new StringBuilder();
        sb.append("/**\n");
        sb.append(" * \n"); // 方法描述行，留空待用戶填寫

        // 添加參數標籤
        PsiParameter[] parameters = method.getParameterList().getParameters();
        for (PsiParameter parameter : parameters) {
            String paramName = parameter.getName();
            sb.append(" * @param ").append(paramName).append(" \n");
        }

        // 添加返回值標籤（如果方法不是 void）
        PsiType returnType = method.getReturnType();
        if (returnType != null && !PsiType.VOID.equals(returnType)) {
            sb.append(" * @return \n");
        }

        // 添加異常標籤
        PsiJavaCodeReferenceElement[] throwsReferences = method.getThrowsList().getReferenceElements();
        for (PsiJavaCodeReferenceElement throwsReference : throwsReferences) {
            String exceptionName = throwsReference.getReferenceName();
            if (exceptionName != null) {
                sb.append(" * @throws ").append(exceptionName).append(" \n");
            }
        }

        sb.append(" */");
        return sb.toString();
    }

    /**
     * 從生成的 JavaDoc 文本創建 PsiDocComment 對象。
     *
     * @param project 當前項目
     * @param method  需要添加 JavaDoc 的方法
     * @return 創建的 PsiDocComment 對象
     */
    public static PsiDocComment createMethodJavadoc(@NotNull Project project, @NotNull PsiMethod method) {
        String docText = generateMethodJavadoc(method);
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        return factory.createDocCommentFromText(docText);
    }

    /**
     * 如果方法沒有 JavaDoc，則為其添加完整的 JavaDoc。
     *
     * @param project 當前項目
     * @param method  需要添加 JavaDoc 的方法
     */
    public static void addJavadocToMethod(@NotNull Project project, @NotNull PsiMethod method) {
        if (method.getDocComment() != null) {
            return; // 如果已經有 JavaDoc，則不添加
        }

        PsiDocComment docComment = createMethodJavadoc(project, method);
        PsiElement firstChild = method.getFirstChild();

        method.addBefore(docComment, firstChild);

        // 應用代碼風格格式化
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(method);
    }

    /**
     * 為字段生成簡單的 JavaDoc 文本。
     *
     * @param fieldTypeName 字段類型名稱
     * @return 生成的 JavaDoc 文本
     */
    public static String generateFieldJavadoc(String fieldTypeName) {
        return "/**\n * \n */";
    }

    /**
     * 從生成的 JavaDoc 文本創建字段的 PsiDocComment 對象。
     *
     * @param project       當前項目
     * @param fieldTypeName 字段類型名稱
     * @return 創建的 PsiDocComment 對象
     */
    public static PsiDocComment createFieldJavadoc(@NotNull Project project, String fieldTypeName) {
        String docText = generateFieldJavadoc(fieldTypeName);
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        return factory.createDocCommentFromText(docText);
    }

    /**
     * 如果字段沒有 JavaDoc，則為其添加簡單的 JavaDoc。
     *
     * @param project 當前項目
     * @param field   需要添加 JavaDoc 的字段
     */
    public static void addJavadocToField(@NotNull Project project, @NotNull PsiField field) {
        if (field.getDocComment() != null) {
            return; // 如果已經有 JavaDoc，則不添加
        }

        String typeName = field.getType().getPresentableText();
        PsiDocComment docComment = createFieldJavadoc(project, typeName);
        PsiElement firstChild = field.getFirstChild();

        field.addBefore(docComment, firstChild);

        // 應用代碼風格格式化
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(field);
    }
}