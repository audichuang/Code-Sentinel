package com.cathaybk.codingassistant.utils;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager; // 重新引入
import com.intellij.psi.javadoc.PsiDocComment;
// JavadocUtil 內部不再需要 PsiDocTag, PsiDocToken, JavaDocTokenType
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提供操作 Javadoc 的靜態輔助方法 (使用文本替換策略，並在操作後格式化 owner)。
 */
public class JavadocUtil {

    private static final Pattern JAVADOC_CONTENT_LINE = Pattern.compile("^\\s*\\*?(.*)");

    /**
     * 將新的描述行插入或更新到現有 Javadoc 的開頭。
     * 如果目標元素沒有現有 Javadoc，則創建一個新的。
     * 使用文本操作和替換策略，完成後格式化 owner 元素。
     *
     * @param project     當前項目。
     * @param factory     用於創建 PSI 元素的工廠。
     * @param owner       擁有 Javadoc 的元素 (PsiMethod 或 PsiClass)。 必須實現 PsiModifierListOwner。
     * @param lineContent 要插入或更新的行文本內容 (不包含 '*' 或換行符)。
     */
    public static void insertOrUpdateJavadoc(@NotNull Project project, @NotNull PsiElementFactory factory,
                                             @NotNull PsiJavaDocumentedElement owner, @NotNull String lineContent) {
        if (!(owner instanceof PsiModifierListOwner)) {
            System.err.println("無法為非 PsiModifierListOwner 添加 Javadoc: " + owner.getClass());
            return;
        }

        try {
            PsiDocComment existingComment = owner.getDocComment();
            String newCommentText = buildNewCommentText(existingComment, lineContent);

            if (newCommentText != null) {
                PsiDocComment newComment = factory.createDocCommentFromText(newCommentText);
                PsiElement addedOrReplacedElement; // 用於後續格式化

                if (existingComment != null) {
                    addedOrReplacedElement = existingComment.replace(newComment);
                } else {
                    addedOrReplacedElement = createNewJavadocInternal((PsiModifierListOwner) owner, newComment);
                }

                // --- 在 PSI 修改完成後，格式化 owner 元素 ---
                // 檢查 addBefore/replace 是否成功返回了有效的元素，或者直接格式化 owner
                if (owner.isValid()) { // 確保 owner 仍然有效
                    CodeStyleManager.getInstance(project).reformat(owner);
                } else if (addedOrReplacedElement != null && addedOrReplacedElement.isValid() && addedOrReplacedElement.getParent() != null) {
                    // 如果 owner 失效，嘗試格式化新添加/替換後的元素所在的父級（可能幫助不大）
                    // CodeStyleManager.getInstance(project).reformat(addedOrReplacedElement.getParent());
                    // 更安全的方式可能是記錄錯誤或不做任何事
                    System.err.println("Owner element became invalid after Javadoc modification.");
                }
            }
        } catch (IncorrectOperationException e) {
            System.err.println("操作 Javadoc 時出錯: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("構建 Javadoc 文本或格式化時出錯: " + e.getMessage());
            e.printStackTrace();
        }
    }


    /**
     * 根據現有 Javadoc 和新行內容構建新的 Javadoc 文本。
     * (邏輯保持不變)
     */
    @Nullable
    private static String buildNewCommentText(@Nullable PsiDocComment existingComment, @NotNull String newLineContent) {
        StringBuilder builder = new StringBuilder("/**\n");
        builder.append(" * ").append(newLineContent).append("\n");

        if (existingComment != null) {
            String existingText = existingComment.getText();
            if (existingText.length() <= 5) {
                builder.append(" */");
                return builder.toString();
            }
            String innerText = existingText.substring(3, existingText.length() - 2).trim();

            if (!innerText.isEmpty()) {
                String[] lines = innerText.split("\n");
                boolean descriptionSkipped = false;

                for (String line : lines) {
                    String trimmedLine = line.trim();
                    Matcher matcher = JAVADOC_CONTENT_LINE.matcher(line);
                    String content = "";
                    if (matcher.find()) {
                        content = matcher.group(1).trim();
                    } else {
                        content = trimmedLine;
                    }

                    boolean isTagLine = content.startsWith("@");

                    if (!descriptionSkipped) {
                        if (trimmedLine.equals("*") || trimmedLine.isEmpty()) {
                            continue;
                        }
                        descriptionSkipped = true;
                    }

                    if (descriptionSkipped) {
                        if (!trimmedLine.isEmpty() && !trimmedLine.equals("*") ) {
                            builder.append(" *");
                            if (!content.isEmpty()) {
                                builder.append(" ");
                            }
                            builder.append(content).append("\n");
                        } else if (isTagLine) {
                            builder.append(" * ");
                            builder.append(content).append("\n");
                        }
                    }
                }
            }
        }
        builder.append(" */");
        return builder.toString();
    }

    /**
     * 內部輔助方法：將新創建的 Javadoc 添加到 owner 前面。
     * 返回添加的元素。不進行格式化。
     */
    @Nullable
    private static PsiElement createNewJavadocInternal(@NotNull PsiModifierListOwner owner, @NotNull PsiDocComment newComment) throws IncorrectOperationException {
        PsiModifierList modifierList = owner.getModifierList();
        PsiElement anchor = null;

        if (modifierList != null && modifierList.getFirstChild() != null) {
            anchor = modifierList;
        } else {
            if (owner instanceof PsiMethod) {
                anchor = ((PsiMethod) owner).getNameIdentifier();
                if (anchor == null) anchor = ((PsiMethod) owner).getParameterList();
            } else if (owner instanceof PsiClass) {
                anchor = ((PsiClass) owner).getNameIdentifier();
                if (anchor == null) anchor = PsiTreeUtil.getChildOfType(owner, PsiKeyword.class);
            }
            if (anchor == null) anchor = owner.getFirstChild();
        }

        if (anchor != null) {
            return owner.addBefore(newComment, anchor);
        } else {
            return owner.add(newComment);
        }
    }
}