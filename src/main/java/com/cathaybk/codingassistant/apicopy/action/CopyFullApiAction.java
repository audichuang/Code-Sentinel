package com.cathaybk.codingassistant.apicopy.action;

import com.cathaybk.codingassistant.apicopy.model.ApiInfo;
import com.cathaybk.codingassistant.apicopy.ui.ApiPreviewDialog;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 複製完整 API 程式碼的 Action
 * 可在 Controller 方法上的右鍵選單中使用
 */
public class CopyFullApiAction extends AnAction {

    private static final Pattern API_ID_PATTERN = Pattern.compile(
            "(?:MSGID-)?([A-Z]{2,4}-[A-Z]-[A-Z0-9]+)"
    );

    public CopyFullApiAction() {
        super("複製完整 API 程式碼", "複製此 API 的所有相關檔案到剪貼簿", null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        PsiMethod method = getTargetMethod(e);
        if (method == null) return;

        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) return;

        String msgId = extractMsgId(method);
        if (msgId == null) {
            // 如果沒有 MSGID，使用方法名稱
            msgId = method.getName();
        }

        String description = extractDescription(method);
        String httpMethod = extractHttpMethod(method);

        ApiInfo apiInfo = new ApiInfo(msgId, description, httpMethod, null, method, containingClass);

        // 顯示預覽對話框
        ApiPreviewDialog dialog = new ApiPreviewDialog(project, apiInfo);
        dialog.show();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        PsiMethod method = getTargetMethod(e);

        // 只有在 Controller 方法上才啟用
        boolean enabled = project != null && method != null && isApiMethod(method);
        e.getPresentation().setEnabledAndVisible(enabled);
    }

    /**
     * 取得游標所在的方法
     */
    @Nullable
    private PsiMethod getTargetMethod(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);

        if (editor == null || psiFile == null) {
            return null;
        }

        int offset = editor.getCaretModel().getOffset();
        PsiElement element = psiFile.findElementAt(offset);

        return PsiTreeUtil.getParentOfType(element, PsiMethod.class);
    }

    /**
     * 判斷是否為 API 方法
     */
    private boolean isApiMethod(@NotNull PsiMethod method) {
        // 檢查是否有 Mapping 註解
        if (method.hasAnnotation("org.springframework.web.bind.annotation.RequestMapping") ||
            method.hasAnnotation("org.springframework.web.bind.annotation.GetMapping") ||
            method.hasAnnotation("org.springframework.web.bind.annotation.PostMapping") ||
            method.hasAnnotation("org.springframework.web.bind.annotation.PutMapping") ||
            method.hasAnnotation("org.springframework.web.bind.annotation.DeleteMapping") ||
            method.hasAnnotation("org.springframework.web.bind.annotation.PatchMapping")) {
            return true;
        }

        // 檢查所在類別是否為 Controller
        PsiClass containingClass = method.getContainingClass();
        if (containingClass != null) {
            return containingClass.hasAnnotation("org.springframework.stereotype.Controller") ||
                   containingClass.hasAnnotation("org.springframework.web.bind.annotation.RestController");
        }

        return false;
    }

    /**
     * 從 Javadoc 中提取 MSGID
     */
    @Nullable
    private String extractMsgId(@NotNull PsiMethod method) {
        PsiDocComment docComment = method.getDocComment();
        if (docComment == null) {
            return null;
        }

        String docText = docComment.getText();
        Matcher matcher = API_ID_PATTERN.matcher(docText);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 從 Javadoc 中提取描述
     */
    @Nullable
    private String extractDescription(@NotNull PsiMethod method) {
        PsiDocComment docComment = method.getDocComment();
        if (docComment == null) {
            return null;
        }

        PsiElement[] descElements = docComment.getDescriptionElements();
        StringBuilder sb = new StringBuilder();
        for (PsiElement element : descElements) {
            String text = element.getText().trim();
            if (!text.isEmpty() && !text.startsWith("@")) {
                sb.append(text).append(" ");
            }
        }

        String result = sb.toString().trim()
                .replaceAll("\\s+", " ")
                .replaceAll("\\*", "")
                .trim();

        return result.isEmpty() ? null : result;
    }

    /**
     * 提取 HTTP 方法
     */
    @Nullable
    private String extractHttpMethod(@NotNull PsiMethod method) {
        if (method.hasAnnotation("org.springframework.web.bind.annotation.GetMapping")) {
            return "GET";
        }
        if (method.hasAnnotation("org.springframework.web.bind.annotation.PostMapping")) {
            return "POST";
        }
        if (method.hasAnnotation("org.springframework.web.bind.annotation.PutMapping")) {
            return "PUT";
        }
        if (method.hasAnnotation("org.springframework.web.bind.annotation.DeleteMapping")) {
            return "DELETE";
        }
        if (method.hasAnnotation("org.springframework.web.bind.annotation.PatchMapping")) {
            return "PATCH";
        }
        return null;
    }
}
