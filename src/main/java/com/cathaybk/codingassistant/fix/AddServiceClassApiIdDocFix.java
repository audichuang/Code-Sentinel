package com.cathaybk.codingassistant.fix;

import com.cathaybk.codingassistant.utils.ApiMsgIdUtil;
import com.cathaybk.codingassistant.utils.JavadocUtil;
import com.cathaybk.codingassistant.settings.GitSettings;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;

/**
 * 為缺少電文代號的 Service 類別提供添加 Javadoc 註解的快速修復。
 * 會根據類別類型自動添加 Svc 或 SvcImpl 後綴。
 */
public class AddServiceClassApiIdDocFix implements LocalQuickFix {

    private static final String TODO_DESCRIPTION = "[請填寫Service描述]";

    @NotNull
    @Override
    public String getName() {
        return "添加 Service 電文代號註解";
    }

    @NotNull
    @Override
    public String getFamilyName() {
        return getName();
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
            
            // 生成 Service 類別的 API ID
            String className = aClass.getName();
            if (className == null) {
                className = "UNKNOWN";
            }
            
            String classNameAbbr;
            String suffix = "";
            
            // 根據類別類型決定後綴
            if (aClass.isInterface() && ApiMsgIdUtil.isServiceInterface(aClass)) {
                // Service 介面，移除 "Service"，加上 "Svc" 後綴
                classNameAbbr = className.replaceAll("(?i)Service", "").toUpperCase();
                suffix = "_Svc";
            } else if (!aClass.isInterface() && 
                       (aClass.hasAnnotation("org.springframework.stereotype.Service") ||
                        aClass.hasAnnotation("javax.inject.Named") ||
                        aClass.hasAnnotation("jakarta.inject.Named"))) {
                // Service 實現類，移除 "ServiceImpl" 或 "Impl"，加上 "SvcImpl" 後綴
                classNameAbbr = className.replaceAll("(?i)(Service)?Impl", "").toUpperCase();
                suffix = "_SvcImpl";
            } else {
                // 其他 Service 類別
                classNameAbbr = className.toUpperCase();
            }
            
            String apiIdTemplate = "API-" + classNameAbbr + suffix;
            String newDocLineText = apiIdTemplate + " " + TODO_DESCRIPTION;
            
            // 調試輸出
            System.err.println("[AddServiceClassApiIdDocFix] Class: " + className + 
                               ", isInterface: " + aClass.isInterface() + 
                               ", hasServiceAnnotation: " + aClass.hasAnnotation("org.springframework.stereotype.Service") +
                               ", apiId: " + apiIdTemplate);

            PsiDocComment existingComment = aClass.getDocComment();

            if (existingComment == null) {
                // --- 情況：類別完全沒有 Javadoc ---
                String classJavadocText = "/**\n * " + newDocLineText + "\n */";
                PsiDocComment newComment = factory.createDocCommentFromText(classJavadocText);

                // 將新註解添加到類別前面
                PsiModifierList modifierList = aClass.getModifierList();
                PsiElement anchor = null;
                if (modifierList != null && modifierList.getFirstChild() != null) {
                    anchor = modifierList;
                } else {
                    anchor = aClass.getFirstChild();
                }
                if (anchor != null) {
                    aClass.addBefore(newComment, anchor);
                } else {
                    aClass.add(newComment);
                }

                // 格式化程式碼
                JavaCodeStyleManager.getInstance(project).shortenClassReferences(aClass);
                CodeStyleManager.getInstance(project).reformat(aClass);

            } else {
                // --- 情況：類別已有 Javadoc ---
                JavadocUtil.insertOrUpdateJavadoc(project, factory, aClass, newDocLineText);
            }

        } catch (IncorrectOperationException e) {
            System.err.println("應用 AddServiceClassApiIdDocFix 時出錯: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("在 AddServiceClassApiIdDocFix 中生成或添加 Javadoc 時發生意外錯誤: " + e.getMessage());
            e.printStackTrace();
        }
    }
}