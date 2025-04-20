package com.cathaybk.codingassistant.vcs;

import com.cathaybk.codingassistant.common.ProblemInfo;
import com.cathaybk.codingassistant.fix.AddApiIdDocFix;
import com.cathaybk.codingassistant.fix.AddControllerApiIdFromServiceFix;
import com.cathaybk.codingassistant.fix.AddFieldJavadocFix;
import com.cathaybk.codingassistant.fix.AddServiceApiIdQuickFix;
import com.cathaybk.codingassistant.util.CathayBkInspectionUtil;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.QuickFix;
import com.intellij.icons.AllIcons;
import com.intellij.lang.annotation.ProblemGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 負責收集和處理代碼問題
 */
public class ProblemCollector {
    private static final Logger LOG = Logger.getInstance(ProblemCollector.class);
    private static final String TOOL_WINDOW_ID = "國泰規範檢查";

    private final Project project;
    private List<ProblemInfo> collectedProblems;
    private CathayBkProblemsPanel currentProblemsPanel;

    public ProblemCollector(Project project) {
        this.project = project;
    }

    private static int getLineNumber(@Nullable Project project, @Nullable PsiElement element) {
        if (element == null || project == null || !ReadAction.compute(element::isValid))
            return -1;
        return ReadAction.compute(() -> {
            PsiFile containingFile = element.getContainingFile();
            if (containingFile == null)
                return -1;
            Document document = PsiDocumentManager.getInstance(project).getDocument(containingFile);
            if (document != null) {
                try {
                    int offset = element.getTextOffset();
                    if (offset >= 0 && offset <= document.getTextLength()) {
                        return document.getLineNumber(offset) + 1;
                    }
                } catch (IndexOutOfBoundsException | PsiInvalidElementAccessException e) {
                    LOG.warn("獲取行號時出錯: " + e.getMessage());
                }
            }
            return -1;
        });
    }

    /**
     * 檢查代碼變更中的問題
     *
     * @param changes 要檢查的變更集合
     * @return 結果類型, COMMIT 繼續提交, CANCEL 取消提交, CLOSE_WINDOW 關閉窗口
     */
    public CheckResult collectProblems(Collection<Change> changes) {
        LOG.info("檢查的變更數量：" + changes.size());

        if (changes.isEmpty()) {
            LOG.info("沒有要檢查的變更，直接返回 COMMIT");
            return new CheckResult(true, null);
        }

        List<ProblemInfo> allProblems = new ArrayList<>();
        AtomicReference<Boolean> shouldCancel = new AtomicReference<>(false);

        LOG.info("開始執行進度檢查...");

        // 使用同步方式執行代碼檢查
        ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            LOG.info("進度檢查執行中...");
            ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
            if (indicator == null) {
                LOG.warn("Progress Indicator 為 null，退出檢查");
                return;
            }
            indicator.setIndeterminate(false);
            indicator.setText("執行國泰規範檢查...");

            PsiManager psiManager = PsiManager.getInstance(project);
            int processedCount = 0;
            int totalChanges = changes.size();
            List<PsiJavaFile> relevantJavaFiles = new ArrayList<>();

            // 1. 收集 Java 文件
            for (Change change : changes) {
                if (indicator.isCanceled()) {
                    LOG.info("使用者取消了收集文件階段");
                    shouldCancel.set(true);
                    return;
                }
                processedCount++;
                if (totalChanges > 0) {
                    indicator.setFraction((double) processedCount / (totalChanges * 2));
                } else {
                    indicator.setFraction(0.5);
                }

                ContentRevision afterRevision = change.getAfterRevision();
                if (afterRevision == null)
                    continue;

                VirtualFile vf = ReadAction.compute(() -> {
                    try {
                        return afterRevision.getFile() != null ? afterRevision.getFile().getVirtualFile() : null;
                    } catch (Exception e) {
                        LOG.error("獲取 VirtualFile 時發生錯誤", e);
                        return null;
                    }
                });

                if (vf == null || !vf.isValid() || vf.getFileType().isBinary() || !vf.getName().endsWith(".java"))
                    continue;

                ReadAction.run(() -> {
                    PsiFile psiFile = psiManager.findFile(vf);
                    if (psiFile instanceof PsiJavaFile)
                        relevantJavaFiles.add((PsiJavaFile) psiFile);
                });
            }

            // 2. 執行檢查
            LOG.info("找到 " + relevantJavaFiles.size() + " 個 Java 文件需要檢查");
            int filesChecked = 0;
            int totalFiles = relevantJavaFiles.size();
            for (PsiJavaFile javaFile : relevantJavaFiles) {
                if (indicator.isCanceled()) {
                    LOG.info("使用者取消了檢查階段");
                    shouldCancel.set(true);
                    return;
                }
                filesChecked++;
                if (totalFiles > 0) {
                    indicator.setFraction(0.5 + (double) filesChecked / (totalFiles * 2));
                } else {
                    indicator.setFraction(1.0);
                }
                indicator.setText2("檢查 " + javaFile.getName());

                ReadAction.run(() -> {
                    javaFile.accept(new JavaRecursiveElementWalkingVisitor() {
                        @Override
                        public void visitClass(@NotNull PsiClass aClass) {
                            super.visitClass(aClass);
                            if (indicator.isCanceled())
                                return;
                            allProblems.addAll(CathayBkInspectionUtil.checkServiceClassDoc(aClass));
                        }

                        @Override
                        public void visitMethod(@NotNull PsiMethod method) {
                            super.visitMethod(method);
                            if (indicator.isCanceled())
                                return;
                            allProblems.addAll(CathayBkInspectionUtil.checkApiMethodDoc(method));
                        }

                        @Override
                        public void visitField(@NotNull PsiField field) {
                            super.visitField(field);
                            if (indicator.isCanceled())
                                return;
                            allProblems.addAll(CathayBkInspectionUtil.checkInjectedFieldDoc(field));
                        }
                    });
                });
            }

            LOG.info("檢查完成，發現 " + allProblems.size() + " 個問題");
            collectedProblems = allProblems;

        }, "國泰規範檢查", true, project);

        // 如果用戶在檢查過程中取消，則直接返回
        if (shouldCancel.get()) {
            LOG.info("用戶在檢查過程中選擇取消");
            return new CheckResult(false, null);
        }

        // 如果發現問題，彈出同步對話框詢問用戶
        if (collectedProblems != null && !collectedProblems.isEmpty()) {
            int userChoice = Messages.showYesNoCancelDialog(
                    project,
                    "發現 " + collectedProblems.size() + " 個國泰規範問題。是否繼續提交？\n" +
                            "選擇「顯示問題」可查看並修復問題。",
                    "國泰規範檢查發現問題",
                    "繼續提交",
                    "顯示問題",
                    "取消提交",
                    Messages.getWarningIcon());

            if (userChoice == Messages.CANCEL || userChoice == -1) {
                LOG.info("使用者選擇取消提交");
                return new CheckResult(false, null);
            } else if (userChoice == Messages.NO) {
                LOG.info("使用者選擇顯示問題");
                showProblemsInToolWindow(collectedProblems);
                return new CheckResult(false, "CLOSE_WINDOW");
            } else {
                LOG.info("使用者選擇繼續提交");
                return new CheckResult(true, null);
            }
        }

        LOG.info("檢查完成，沒有發現問題");
        return new CheckResult(true, null);
    }

    /**
     * 顯示問題在工具窗口中
     */
    private void showProblemsInToolWindow(List<ProblemInfo> problems) {
        ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
        ToolWindow toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID);

        if (toolWindow == null) {
            toolWindow = toolWindowManager.registerToolWindow(
                    TOOL_WINDOW_ID, true, ToolWindowAnchor.BOTTOM, project, true);
            toolWindow.setIcon(AllIcons.General.InspectionsEye);
            toolWindow.setStripeTitle("國泰規範");
        }

        currentProblemsPanel = new CathayBkProblemsPanel(project, problems);

        currentProblemsPanel.setQuickFixListener(e -> applySelectedQuickFix());
        currentProblemsPanel.setFixAllListener(e -> applyAllQuickFixes());

        toolWindow.getContentManager().removeAllContents(true);
        ContentFactory contentFactory = ContentFactory.getInstance();
        String contentTitle = "檢查結果 (" + problems.size() + ")";
        Content content = contentFactory.createContent(currentProblemsPanel, contentTitle, false);
        toolWindow.getContentManager().addContent(content);

        final ToolWindow finalToolWindow = toolWindow;

        ApplicationManager.getApplication().invokeLater(() -> {
            finalToolWindow.show(() -> {
                int maxHeight = 200;
                finalToolWindow.getComponent().setPreferredSize(new Dimension(
                        finalToolWindow.getComponent().getWidth(), maxHeight));
                SwingUtilities.invokeLater(() -> {
                    JComponent component = finalToolWindow.getComponent();
                    if (component != null) {
                        component.setPreferredSize(new Dimension(component.getWidth(), maxHeight));
                        component.setSize(new Dimension(component.getWidth(), maxHeight));
                        component.revalidate();
                        component.repaint();
                    }
                    if (currentProblemsPanel != null) {
                        currentProblemsPanel
                                .setPreferredSize(new Dimension(currentProblemsPanel.getWidth(), maxHeight - 40));
                        currentProblemsPanel.revalidate();
                    }
                });
                finalToolWindow.activate(null, true, true);
            });
        });
    }

    private void applySelectedQuickFix() {
        if (currentProblemsPanel == null) {
            LOG.warn("Problem panel is not available for quick fix.");
            return;
        }

        ProblemInfo problem = currentProblemsPanel.getSelectedProblemInfo();
        if (problem == null) {
            Messages.showInfoMessage(project, "請先在左側列表中選擇一個具體的問題項", "快速修復");
            return;
        }

        PsiElement element = problem.getElement();
        if (element == null || !ReadAction.compute(element::isValid)) {
            Messages.showErrorDialog(project, "無法應用修復：關聯的程式碼元素已失效。\n請嘗試重新檢查。", "快速修復失敗");
            currentProblemsPanel.removeProblem(problem);
            return;
        }

        LocalQuickFix fixToApply = ReadAction.compute(() -> determineQuickFix(problem));

        if (fixToApply == null) {
            Messages.showWarningDialog(project, "未能找到適用於此問題的自動修復方案。\n描述: " + problem.getDescription(), "快速修復");
            return;
        }

        ProblemDescriptor dummyDescriptor = ReadAction
                .compute(() -> createDummyDescriptor(element, problem.getDescription()));
        WriteCommandAction.runWriteCommandAction(project, fixToApply.getName(), null, () -> {
            try {
                fixToApply.applyFix(project, dummyDescriptor);
                LOG.info("成功應用快速修復: " + fixToApply.getName() + " 到元素: " + ReadAction.compute(() -> element.getText()));
                currentProblemsPanel.removeProblem(problem);
                updateToolWindowContentTitle();
            } catch (Exception ex) {
                LOG.error("應用快速修復時出錯 (" + fixToApply.getName() + "): " + ex.getMessage(), ex);
                Messages.showErrorDialog(project, "應用快速修復時出錯: " + ex.getMessage(), "快速修復失敗");
            }
        });
    }

    private void applyAllQuickFixes() {
        if (collectedProblems == null || collectedProblems.isEmpty()) {
            Messages.showInfoMessage(project, "沒有發現問題需要修復", "一鍵修復全部");
            return;
        }

        ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
            if (indicator != null) {
                indicator.setIndeterminate(false);
                indicator.setText("正在修復所有問題...");
            }

            List<ProblemInfo> problemsToFix = new ArrayList<>(collectedProblems);
            int totalProblems = problemsToFix.size();
            final AtomicInteger fixedCount = new AtomicInteger(0);
            final AtomicInteger failedCount = new AtomicInteger(0);
            List<ProblemInfo> successfullyFixed = new ArrayList<>();

            LOG.info("開始一鍵修復全部，總共 " + totalProblems + " 個問題");

            for (int i = 0; i < problemsToFix.size(); i++) {
                if (indicator != null) {
                    indicator.setFraction((double) i / totalProblems);
                    if (indicator.isCanceled()) {
                        LOG.info("用戶取消了一鍵修復全部操作");
                        break;
                    }
                }

                final ProblemInfo problem = problemsToFix.get(i);

                final PsiElement element = ReadAction.compute(() -> {
                    PsiElement el = problem.getElement();
                    return (el != null && el.isValid()) ? el : null;
                });

                if (element == null) {
                    LOG.warn("問題元素無效，跳過: " + problem.getDescription());
                    failedCount.incrementAndGet();
                    continue;
                }

                if (indicator != null) {
                    indicator.setText2("修復問題 " + (i + 1) + "/" + totalProblems + ": " +
                            problem.getDescription().substring(0, Math.min(50, problem.getDescription().length())) +
                            (problem.getDescription().length() > 50 ? "..." : ""));
                }

                final LocalQuickFix fixToApply = ReadAction.compute(() -> determineQuickFix(problem));

                if (fixToApply == null) {
                    LOG.warn("找不到適合的修復方法: " + problem.getDescription());
                    failedCount.incrementAndGet();
                    continue;
                }

                try {
                    final ProblemDescriptor dummyDescriptor = ReadAction
                            .compute(() -> createDummyDescriptor(element, problem.getDescription()));

                    WriteCommandAction.runWriteCommandAction(project, () -> {
                        try {
                            fixToApply.applyFix(project, dummyDescriptor);
                            LOG.info("成功修復: " + problem.getDescription());
                            successfullyFixed.add(problem);
                            fixedCount.incrementAndGet();
                        } catch (Exception ex) {
                            LOG.error("應用修復時出錯: " + ex.getMessage(), ex);
                            failedCount.incrementAndGet();
                        }
                    });
                } catch (Exception ex) {
                    LOG.error("創建修復描述時出錯: " + ex.getMessage(), ex);
                    failedCount.incrementAndGet();
                }
            }

            if (!successfullyFixed.isEmpty()) {
                collectedProblems.removeAll(successfullyFixed);
            }

            ApplicationManager.getApplication().invokeLater(() -> {
                if (currentProblemsPanel != null) {
                    currentProblemsPanel.refreshProblems(collectedProblems);
                }
                updateToolWindowContentTitle();

                String message = "修復完成!\n\n成功修復: " + fixedCount.get() + " 個問題\n" +
                        (failedCount.get() > 0 ? "無法修復: " + failedCount.get() + " 個問題" : "") +
                        (indicator != null && indicator.isCanceled() ? "\n(操作被用戶取消)" : "");
                if (fixedCount.get() > 0 || failedCount.get() > 0) {
                    Messages.showInfoMessage(project, message, "一鍵修復結果");
                } else {
                    Messages.showInfoMessage(project, "沒有問題需要修復", "一鍵修復結果");
                }
            });

        }, "一鍵修復所有問題", true, project);
    }

    /**
     * 更新工具窗口標題。
     */
    private void updateToolWindowContentTitle() {
        ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
        ToolWindow toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow != null) {
            Content content = toolWindow.getContentManager().getSelectedContent();
            if (content == null && toolWindow.getContentManager().getContentCount() > 0) {
                content = toolWindow.getContentManager().getContent(0);
            }
            if (content != null) {
                int count = (currentProblemsPanel != null && currentProblemsPanel.getCurrentProblems() != null)
                        ? currentProblemsPanel.getCurrentProblems().size()
                        : (collectedProblems != null ? collectedProblems.size() : 0);
                content.setDisplayName("檢查結果 (" + count + ")");
            } else {
                LOG.warn("無法更新工具窗口標題：找不到 Content。ToolWindow visible: " + toolWindow.isVisible());
            }
        } else {
            LOG.warn("無法更新工具窗口標題：找不到 ToolWindow。ID: " + TOOL_WINDOW_ID);
        }
    }

    private LocalQuickFix determineQuickFix(ProblemInfo problem) {
        String description = problem.getDescription();
        PsiElement element = problem.getElement();

        if (element == null)
            return null;

        if (problem.getSuggestionSource() != null && problem.getSuggestedValue() != null) {
            if (description.contains("API 方法缺少")
                    && (element instanceof PsiIdentifier || element instanceof PsiMethod)) {
                return new AddControllerApiIdFromServiceFix(problem.getSuggestionSource(), problem.getSuggestedValue());
            }
            if (description.contains("Service 類別缺少") && (element instanceof PsiIdentifier || element instanceof PsiClass
                    || element instanceof PsiKeyword)) {
                return new AddServiceApiIdQuickFix(problem.getSuggestionSource(), problem.getSuggestedValue());
            }
        }

        if (description.contains("API 方法缺少") && (element instanceof PsiIdentifier || element instanceof PsiMethod)) {
            return new AddApiIdDocFix();
        }
        if (description.contains("Service 類別缺少")
                && (element instanceof PsiIdentifier || element instanceof PsiClass || element instanceof PsiKeyword)) {
            return new AddApiIdDocFix();
        }
        if (description.contains("注入的欄位") && description.contains("缺少 Javadoc")) {
            final PsiField field = ReadAction.compute(() -> {
                PsiField initialField = PsiTreeUtil.getParentOfType(element, PsiField.class, false);
                if (initialField == null && element instanceof PsiField)
                    return (PsiField) element;
                return initialField;
            });

            if (field != null) {
                String typeName = ReadAction.compute(() -> {
                    PsiType type = field.getType();
                    return type != null ? type.getPresentableText() : null;
                });
                if (typeName != null && !typeName.isEmpty()) {
                    return new AddFieldJavadocFix(typeName);
                } else {
                    return new AddFieldJavadocFix("UnknownType");
                }
            }
        }

        LOG.warn("未能為問題找到匹配的 LocalQuickFix: " + description);
        return null;
    }

    private ProblemDescriptor createDummyDescriptor(PsiElement element, String description) {
        return new ProblemDescriptor() {
            @Override
            public PsiElement getPsiElement() {
                return element;
            }

            @Override
            public PsiElement getStartElement() {
                return element;
            }

            @Override
            public PsiElement getEndElement() {
                return element;
            }

            @Nullable
            @Override
            public TextRange getTextRangeInElement() {
                return ReadAction.compute(() -> element != null ? element.getTextRange() : null);
            }

            @Override
            public int getLineNumber() {
                return ProblemCollector.getLineNumber(project, element);
            }

            @NotNull
            @Override
            public ProblemHighlightType getHighlightType() {
                return ProblemHighlightType.GENERIC_ERROR_OR_WARNING;
            }

            @Override
            public boolean isAfterEndOfLine() {
                return false;
            }

            @Override
            public void setTextAttributes(@NotNull TextAttributesKey key) {
            }

            @Nullable
            @Override
            public ProblemGroup getProblemGroup() {
                return null;
            }

            @Override
            public void setProblemGroup(@Nullable ProblemGroup problemGroup) {
            }

            @Override
            public boolean showTooltip() {
                return false;
            }

            @Nullable
            @Override
            public String getDescriptionTemplate() {
                return description;
            }

            @Nullable
            @Override
            public QuickFix<?>[] getFixes() {
                return null;
            }
        };
    }

    /**
     * 檢查結果類
     */
    public static class CheckResult {
        private final boolean shouldContinue;
        private final String action;

        public CheckResult(boolean shouldContinue, String action) {
            this.shouldContinue = shouldContinue;
            this.action = action;
        }

        public boolean shouldContinue() {
            return shouldContinue;
        }

        public String getAction() {
            return action;
        }
    }
}