package com.cathaybk.codingassistant.fix;

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
import com.cathaybk.codingassistant.settings.GitSettings;

/**
 * 為缺少電文代號的 Controller API 方法提供快速修復選項，
 * 建議從其內部使用的某個 Service 類別關聯的電文代號來添加 Javadoc。
 * 如果方法沒有 Javadoc，則生成完整結構；否則，更新現有 Javadoc。
 */
public class AddControllerApiIdFromServiceFix implements LocalQuickFix {

    private final String sourceName;
    private final String apiId;

    public AddControllerApiIdFromServiceFix(@NotNull String sourceName, @NotNull String apiId) {
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
        return "從 Service 添加電文代號註解";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiElement element = descriptor.getPsiElement();
        if (!(element instanceof PsiIdentifier) && !(element instanceof PsiMethod)) {
            return;
        }
        PsiMethod method = (element instanceof PsiMethod) ? (PsiMethod) element
                : PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        if (method == null || !method.isValid())
            return;

        // 檢查是否在預覽模式（Preview Mode）
        boolean isInPreviewMode = com.intellij.codeInsight.intention.preview.IntentionPreviewUtils.isPreviewElement(element);
        
        Runnable applyFixRunnable = () -> {
                try {
                    PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
                    // Controller 方法不應該有 Service 後綴，移除 Svc 或 SvcImpl
                    String newDocLineText = this.apiId.replaceAll("\\s+(Svc|SvcImpl)$", "");

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
                    System.err.println("應用 AddControllerApiIdFromServiceFix 時出錯: " + e.getMessage());
                } catch (Exception e) {
                    System.err.println("在 AddControllerApiIdFromServiceFix 中生成或添加 Javadoc 時發生意外錯誤: " + e.getMessage());
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