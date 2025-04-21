package com.cathaybk.codingassistant.intention;

import com.cathaybk.codingassistant.settings.GitSettings;
import com.intellij.codeInsight.intention.impl.BaseIntentionAction;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.ide.util.PackageUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CreateDtoIntentionAction extends BaseIntentionAction {

    private static final Logger LOG = Logger.getInstance(CreateDtoIntentionAction.class);

    @Override
    public @Nls(capitalization = Nls.Capitalization.Sentence) @NotNull String getFamilyName() {
        return "Code Sentinel 模板生成"; // Group name for intentions
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Sentence) @NotNull String getText() {
        // 實際顯示的文字會在 isAvailable 中動態設定
        return "創建 DTO 模板類...";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        if (!(file instanceof PsiJavaFile)) {
            return false;
        }

        int offset = editor.getCaretModel().getOffset();
        PsiElement elementAtCaret = file.findElementAt(offset);

        // 找到光標處的未解析引用
        PsiJavaCodeReferenceElement refElement = findUnresolvedReference(elementAtCaret);
        if (refElement == null) {
            return false;
        }

        // 檢查是否在方法簽名中 (參數或返回類型)
        PsiMethod containingMethod = PsiTreeUtil.getParentOfType(refElement, PsiMethod.class);
        if (containingMethod == null) {
            return false;
        }
        if (!isInMethodSignature(refElement, containingMethod)) {
            return false;
        }

        // 檢查方法是否有 Mapping 註解
        if (!hasMappingAnnotation(containingMethod)) {
            return false;
        }

        // 檢查類別是否有 @Controller 或 @RestController 註解
        PsiClass containingClass = PsiTreeUtil.getParentOfType(containingMethod, PsiClass.class);
        if (containingClass == null || !isControllerClass(containingClass)) {
            return false;
        }

        // 如果所有條件都滿足，動態設定顯示文字
        String className = refElement.getReferenceName();
        if (className != null && !className.isEmpty() && PsiNameHelper.getInstance(project).isIdentifier(className)) {
            setText("創建 DTO 模板類 '" + className + "'"); // 動態設定顯示文字
            return true;
        }

        return false;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
        // 1. 獲取未解析的引用元素
        int offset = editor.getCaretModel().getOffset();
        PsiElement elementAtCaret = file.findElementAt(offset);
        final PsiJavaCodeReferenceElement refElement = findUnresolvedReference(elementAtCaret);

        if (refElement == null) {
            LOG.warn("invoke called but no valid unresolved reference found at caret.");
            return;
        }

        // 2. 提取類名
        final String className = refElement.getReferenceName();
        if (className == null || className.isEmpty() || !PsiNameHelper.getInstance(project).isIdentifier(className)) {
            LOG.warn("Invalid class name extracted: " + className);
            return;
        }

        // 獲取觸發意圖的 Controller 方法
        final PsiMethod containingMethod = PsiTreeUtil.getParentOfType(refElement, PsiMethod.class);
        if (containingMethod == null) {
            LOG.warn("Cannot find containing method for the reference.");
            return; // 缺少上下文
        }

        // 嘗試從 Controller 方法提取電文代號
        final String apiMsgId = com.cathaybk.codingassistant.utils.ApiMsgIdUtil
                .extractApiMsgId(containingMethod.getDocComment());

        // 從設定獲取 DTO 後綴
        GitSettings settings = GitSettings.getInstance(project);
        String parameterSuffix = settings.getParameterDtoSuffix();
        String returnTypeSuffix = settings.getReturnTypeDtoSuffix();

        // 判斷 DTO 的上下文 (參數或返回)
        String dtoContextSuffix = "";
        if (isInReturnType(refElement, containingMethod)) {
            dtoContextSuffix = returnTypeSuffix != null ? returnTypeSuffix : ""; // 使用設定值
        } else if (isInParameter(refElement, containingMethod)) {
            dtoContextSuffix = parameterSuffix != null ? parameterSuffix : ""; // 使用設定值
        }

        // 3. 確定包名和目錄
        PsiClass containingClass = PsiTreeUtil.getParentOfType(refElement, PsiClass.class); // Controller
        if (containingClass == null) {
            LOG.warn("Cannot find containing class (Controller) for the reference.");
            return; // Cannot determine package without controller context
        }
        PsiFile controllerFile = containingClass.getContainingFile();
        if (!(controllerFile instanceof PsiJavaFile)) {
            LOG.warn("Controller class is not in a Java file.");
            return;
        }
        String controllerPackageName = ((PsiJavaFile) controllerFile).getPackageName();
        final String dtoPackageName = controllerPackageName.isEmpty() ? "dto" : controllerPackageName + ".dto";

        // 找到 Controller 類別所在的源根目錄
        VirtualFile controllerVF = controllerFile.getVirtualFile();
        if (controllerVF == null) {
            LOG.warn("Controller file has no VirtualFile.");
            return;
        }
        ProjectFileIndex fileIndex = ProjectRootManager.getInstance(project).getFileIndex();
        VirtualFile sourceRoot = fileIndex.getSourceRootForFile(controllerVF);

        if (sourceRoot == null) {
            // 備用方案：嘗試使用當前文件的源根目錄
            sourceRoot = fileIndex.getSourceRootForFile(file.getVirtualFile());
            if (sourceRoot == null) {
                LOG.error("Cannot determine source root for DTO creation for package: " + dtoPackageName);
                Messages.showErrorDialog(project, "無法確定用於創建 DTO 的源根目錄。", "創建失敗");
                return;
            } else {
                LOG.warn("Using current file's source root as fallback.");
            }
        }

        final PsiDirectory baseDir = PsiManager.getInstance(project).findDirectory(sourceRoot);
        if (baseDir == null) {
            LOG.error("Cannot find base directory for source root: " + sourceRoot.getPath());
            Messages.showErrorDialog(project, "找不到源根目錄對應的目錄。", "創建失敗");
            return;
        }

        // 4. 生成 DTO 文件內容
        final String fileContent = generateDtoContent(dtoPackageName, className, apiMsgId, dtoContextSuffix);

        // 5. 在 WriteCommandAction 中創建文件和目錄
        WriteCommandAction.runWriteCommandAction(project, getText(), getFamilyName(), () -> {
            try {
                // 查找或創建目標包目錄
                final PsiDirectory targetDirectory = PackageUtil.findOrCreateDirectoryForPackage(project,
                        dtoPackageName, baseDir, true);

                if (targetDirectory == null) {
                    LOG.error("Failed to find or create target directory for package " + dtoPackageName
                            + " within WriteAction.");
                    Messages.showErrorDialog(project, "無法查找或創建包目錄 '" + dtoPackageName + "'。", "創建失敗");
                    return;
                }

                // 檢查類別是否已存在
                if (targetDirectory.findFile(className + ".java") != null) {
                    LOG.warn("DTO class " + className + " already exists in "
                            + targetDirectory.getVirtualFile().getPath());
                    // 如果已存在，提示用戶並打開現有文件
                    ApplicationManager.getApplication().invokeLater(() -> {
                        Messages.showWarningDialog(project,
                                "類別 '" + className + ".java' 已存在於包 '" + dtoPackageName + "' 中。", "類別已存在");
                        PsiFile existingFile = targetDirectory.findFile(className + ".java");
                        if (existingFile != null) {
                            OpenFileDescriptor descriptor = new OpenFileDescriptor(project,
                                    existingFile.getVirtualFile());
                            FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
                        }
                    });
                    return;
                }

                // 創建新的 PSI 文件
                PsiFile newPsiFile = PsiFileFactory.getInstance(project)
                        .createFileFromText(className + ".java", JavaFileType.INSTANCE, fileContent);

                // 將文件添加到目標目錄
                PsiElement addedElement = targetDirectory.add(newPsiFile);

                // 格式化代碼
                CodeStyleManager.getInstance(project).reformat(addedElement);

                // 打開新創建的文件
                if (addedElement instanceof PsiFile) {
                    OpenFileDescriptor descriptor = new OpenFileDescriptor(project,
                            addedElement.getContainingFile().getVirtualFile());
                    FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
                } else {
                    LOG.warn("Added element is not a PsiFile, cannot open in editor.");
                }

            } catch (IncorrectOperationException e) {
                LOG.error("Operation exception during DTO class creation", e);
                Messages.showErrorDialog(project, "創建 DTO 類時發生操作錯誤: " + e.getMessage(), "創建失敗");
            } catch (Exception e) {
                LOG.error("Failed to create DTO class", e);
                Messages.showErrorDialog(project, "創建 DTO 類時出錯: " + e.getMessage(), "創建失敗");
            }
        });
    }

    // --- Helper Methods ---

    /**
     * 查找光標處或其父級的未解析 Java 引用元素。
     */
    @Nullable
    private PsiJavaCodeReferenceElement findUnresolvedReference(@Nullable PsiElement elementAtCaret) {
        if (elementAtCaret == null)
            return null;
        PsiJavaCodeReferenceElement refElement = PsiTreeUtil.getParentOfType(elementAtCaret,
                PsiJavaCodeReferenceElement.class, false);
        if (refElement != null && refElement.resolve() == null) {
            return refElement;
        }
        // 檢查父級，以防光標直接在標識符上
        if (elementAtCaret.getParent() instanceof PsiJavaCodeReferenceElement) {
            refElement = (PsiJavaCodeReferenceElement) elementAtCaret.getParent();
            if (refElement.resolve() == null) {
                return refElement;
            }
        }
        return null;
    }

    /**
     * 檢查引用是否在方法簽名中 (參數或返回類型)。
     */
    private boolean isInMethodSignature(@NotNull PsiJavaCodeReferenceElement refElement,
                                        @NotNull PsiMethod containingMethod) {
        if (PsiTreeUtil.isAncestor(containingMethod.getReturnTypeElement(), refElement, false)) {
            return true;
        }
        for (PsiParameter parameter : containingMethod.getParameterList().getParameters()) {
            if (PsiTreeUtil.isAncestor(parameter.getTypeElement(), refElement, false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 檢查方法是否有 Spring MVC 映射註解。
     */
    private boolean hasMappingAnnotation(@NotNull PsiMethod method) {
        for (PsiAnnotation annotation : method.getModifierList().getAnnotations()) {
            String annotationName = annotation.getQualifiedName();
            if (annotationName != null && annotationName.startsWith("org.springframework.web.bind.annotation")
                    && annotationName.endsWith("Mapping")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 檢查類別是否有 @Controller 或 @RestController 註解。
     */
    private boolean isControllerClass(@NotNull PsiClass psiClass) {
        for (PsiAnnotation annotation : psiClass.getModifierList().getAnnotations()) {
            String annotationName = annotation.getQualifiedName();
            if ("org.springframework.stereotype.Controller".equals(annotationName) ||
                    "org.springframework.web.bind.annotation.RestController".equals(annotationName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 生成 DTO 類別的模板內容。
     */
    private String generateDtoContent(String packageName, String className, @Nullable String apiMsgId,
                                      String dtoContextSuffix) {
        StringBuilder sb = new StringBuilder();
        if (packageName != null && !packageName.isEmpty()) {
            sb.append("package ").append(packageName).append(";\n\n");
        }
        sb.append("import lombok.Data;\n");
        sb.append("import java.io.Serializable;\n\n");

        // 添加 Javadoc
        String author = getSystemUserName();
        boolean hasJavadocContent = (apiMsgId != null && !apiMsgId.isEmpty()) || !author.isEmpty();

        if (hasJavadocContent) {
            sb.append("/**\n");
            if (apiMsgId != null && !apiMsgId.isEmpty()) {
                sb.append(" * ").append(apiMsgId).append(" ").append(dtoContextSuffix).append("\n");
            }
            if (!author.isEmpty()) {
                // 如果有電文代號，加一個空行分隔
                if (apiMsgId != null && !apiMsgId.isEmpty()) {
                    sb.append(" *\n");
                }
                sb.append(" * @author ").append(author).append("\n"); // 添加 @author
            }
            sb.append(" */\n");
        }

        sb.append("@Data\n");
        sb.append("public class ").append(className).append(" implements Serializable {\n\n");
        // 添加 Javadoc 和 serialVersionUID
        sb.append("    /** Serializable */\n");
        sb.append("    private static final long serialVersionUID = 1L;\n\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * 獲取當前系統使用者名稱。
     *
     * @return 使用者名稱，如果無法獲取則返回空字串
     */
    private String getSystemUserName() {
        try {
            String userName = System.getProperty("user.name");
            return (userName != null) ? userName : ""; // 返回空字串如果為 null
        } catch (SecurityException e) {
            LOG.warn("無法獲取系統屬性 'user.name'", e);
            return ""; // 返回空字串
        }
    }

    /**
     * 檢查引用是否在方法的返回類型中。
     */
    private boolean isInReturnType(@NotNull PsiJavaCodeReferenceElement refElement,
                                   @NotNull PsiMethod containingMethod) {
        PsiTypeElement returnTypeElement = containingMethod.getReturnTypeElement();
        return returnTypeElement != null && PsiTreeUtil.isAncestor(returnTypeElement, refElement, false);
    }

    /**
     * 檢查引用是否在方法的參數列表中。
     */
    private boolean isInParameter(@NotNull PsiJavaCodeReferenceElement refElement,
                                  @NotNull PsiMethod containingMethod) {
        for (PsiParameter parameter : containingMethod.getParameterList().getParameters()) {
            PsiTypeElement typeElement = parameter.getTypeElement();
            if (typeElement != null && PsiTreeUtil.isAncestor(typeElement, refElement, false)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean startInWriteAction() {
        // invoke 方法內部處理 WriteCommandAction
        return false;
    }
}