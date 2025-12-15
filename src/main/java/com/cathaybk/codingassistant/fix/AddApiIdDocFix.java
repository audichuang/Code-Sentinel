package com.cathaybk.codingassistant.fix;

import com.cathaybk.codingassistant.utils.ApiMsgIdUtil;
import com.cathaybk.codingassistant.utils.JavadocUtil;
import com.cathaybk.codingassistant.utils.FullJavadocGenerator;
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
import org.jetbrains.annotations.Nullable;
import com.cathaybk.codingassistant.settings.GitSettings;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.Logger;

/**
 * 為缺少電文代號的 API 方法提供添加 Javadoc 註解的快速修復。
 * 如果方法沒有 Javadoc，則生成完整結構；否則，更新現有 Javadoc。
 */
public class AddApiIdDocFix implements LocalQuickFix {
    private static final Logger LOG = Logger.getInstance(AddApiIdDocFix.class);
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
        
        @Nullable
        PsiMethod method = (element instanceof PsiMethod) ? (PsiMethod) element
                : PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        if (method == null || !method.isValid()) {
            return;
        }

        // 檢查是否在預覽模式（Preview Mode）
        boolean isInPreviewMode = com.intellij.codeInsight.intention.preview.IntentionPreviewUtils.isPreviewElement(element);
        
        Runnable applyFixRunnable = () -> {
                try {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
            String apiIdTemplate = ApiMsgIdUtil.generateApiMsgId(method);
            String newDocLineText = apiIdTemplate + " " + TODO_DESCRIPTION;

            PsiDocComment existingComment = method.getDocComment();

            // 從設定讀取是否要生成完整 Javadoc
            boolean generateFull = GitSettings.getInstance(project).isGenerateFullJavadoc();

            if (existingComment == null) {
                // --- 情況：方法完全沒有 Javadoc ---
                PsiDocComment newComment;
                if (generateFull) {
                    // --- 設定為生成完整 Javadoc ---
                    // 1. 使用 FullJavadocGenerator 生成完整 Javadoc 模板
                    String fullTemplate = FullJavadocGenerator.generateMethodJavadoc(method);
                    // 2. 將 API ID 插入到模板的描述部分
                    String apiLine = " * " + newDocLineText + "\n";
                    int placeholderIndex = fullTemplate.indexOf("* \n");
                    String modifiedTemplate;
                    if (placeholderIndex != -1) {
                        modifiedTemplate = fullTemplate.substring(0, placeholderIndex) + apiLine
                                + fullTemplate.substring(placeholderIndex + 3);
                    } else {
                        // Fallback...
                        modifiedTemplate = fullTemplate.replaceFirst("\\*\\s*\\n", "* " + newDocLineText + "\n");
                        if (!modifiedTemplate.contains(newDocLineText)) {
                            int insertPoint = fullTemplate.indexOf("/**\n");
                            if (insertPoint != -1) {
                                modifiedTemplate = fullTemplate.substring(0, insertPoint + 4) + "* " + newDocLineText
                                        + "\n" + fullTemplate.substring(insertPoint + 4);
                            } else {
                                modifiedTemplate = "/**\n * " + newDocLineText + "\n */";
                                System.err.println("無法在 Javadoc 模板中找到插入點: " + method.getName());
                            }
                        }
                    }
                    // 3. 根據修改後的模板創建 PsiDocComment
                    newComment = factory.createDocCommentFromText(modifiedTemplate);
                } else {
                    // --- 設定為生成最小 Javadoc ---
                    String minimalTemplate = "/**\n * " + newDocLineText + "\n */";
                    newComment = factory.createDocCommentFromText(minimalTemplate);
                }

                // 4. 將新註解添加到方法前面
                PsiElement firstChild = method.getFirstChild();
                method.addBefore(newComment, firstChild);

                // 5. 格式化程式碼
                JavaCodeStyleManager.getInstance(project).shortenClassReferences(method);
                CodeStyleManager.getInstance(project).reformat(method);

            } else {
                // --- 情況：方法已有 Javadoc ---
                JavadocUtil.insertOrUpdateJavadoc(project, factory, method, newDocLineText);
            }

                } catch (IncorrectOperationException e) {
                    LOG.error("應用 AddApiIdDocFix 時出錯", e);
                } catch (Exception e) {
                    LOG.error("在 AddApiIdDocFix 中生成或添加 Javadoc 時發生意外錯誤", e);
                }
        };
        
        // 根據是否在預覽模式決定執行方式
        if (isInPreviewMode) {
            // 在預覽模式下直接執行，不使用 CommandProcessor
            applyFixRunnable.run();
        } else {
            // 正常模式下使用 WriteAction 和 CommandProcessor
            CommandProcessor.getInstance().executeCommand(project, () -> {
                WriteAction.run(() -> applyFixRunnable.run());
            }, getName(), null);
        }
    }
}