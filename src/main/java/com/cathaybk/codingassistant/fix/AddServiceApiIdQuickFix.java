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

        final PsiClass finalAClass = aClass; // For use in lambda
        
        // 檢查是否在預覽模式（Preview Mode）
        boolean isInPreviewMode = com.intellij.codeInsight.intention.preview.IntentionPreviewUtils.isPreviewElement(element);
        
        Runnable applyFixRunnable = () -> {
            try {
                PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
                
                // 生成帶有 Service 後綴的 API ID
                String generatedApiId = this.apiId;
                
                // 先移除現有的後綴（Svc 或 SvcImpl）
                generatedApiId = generatedApiId.replaceAll("\\s+(Svc|SvcImpl)$", "");
                
                // 檢查是否需要加上 Service 後綴
                if (finalAClass.isInterface() && com.cathaybk.codingassistant.utils.ApiMsgIdUtil.isServiceInterface(finalAClass)) {
                    // Service 介面，加上 Svc 後綴
                    generatedApiId = generatedApiId + " Svc";
                } else if (!finalAClass.isInterface() && 
                           (finalAClass.hasAnnotation("org.springframework.stereotype.Service") ||
                            finalAClass.hasAnnotation("javax.inject.Named") ||
                            finalAClass.hasAnnotation("jakarta.inject.Named"))) {
                    // Service 實現類，加上 SvcImpl 後綴
                    generatedApiId = generatedApiId + " SvcImpl";
                }
                
                // 調試輸出
                System.err.println("[AddServiceApiIdQuickFix] Class: " + finalAClass.getName() + 
                                   ", isInterface: " + finalAClass.isInterface() + 
                                   ", hasServiceAnnotation: " + finalAClass.hasAnnotation("org.springframework.stereotype.Service") +
                                   ", originalApiId: " + this.apiId +
                                   ", generatedApiId: " + generatedApiId);
                
                String newDocLineText = generatedApiId;

                PsiDocComment existingComment = finalAClass.getDocComment();

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
                    PsiModifierList modifierList = finalAClass.getModifierList();
                    PsiElement anchor = null;
                    if (modifierList != null && modifierList.getFirstChild() != null) {
                        anchor = modifierList;
                    } else {
                        anchor = finalAClass.getFirstChild(); // Fallback
                    }
                    if (anchor != null) {
                        finalAClass.addBefore(newComment, anchor);
                    } else {
                        finalAClass.add(newComment); // Should not happen often
                    }

                    // 4. 格式化程式碼
                    JavaCodeStyleManager.getInstance(project).shortenClassReferences(finalAClass);
                    CodeStyleManager.getInstance(project).reformat(finalAClass);

                } else {
                    // --- 情況：類別已有 Javadoc ---
                    // 使用 JavadocUtil 處理 Javadoc 的插入或更新（保持原有邏輯）
                    JavadocUtil.insertOrUpdateJavadoc(project, factory, finalAClass, newDocLineText);
                }

            } catch (IncorrectOperationException e) {
                System.err.println("應用 AddServiceApiIdQuickFix 時出錯: " + e.getMessage());
            } catch (Exception e) {
                System.err.println("在 AddServiceApiIdQuickFix 中生成或添加 Javadoc 時發生意外錯誤: " + e.getMessage());
                e.printStackTrace();
            }
        };
        
        // 根據是否在預覽模式決定執行方式
        if (isInPreviewMode) {
            // 在預覽模式下直接執行，不使用 CommandProcessor
            applyFixRunnable.run();
        } else {
            // 正常模式下使用 WriteAction 和 CommandProcessor
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                com.intellij.openapi.application.WriteAction.run(() -> applyFixRunnable.run());
            }, getName(), null);
        }
    }
}