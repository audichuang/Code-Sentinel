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
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
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
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 負責收集和處理代碼問題
 */
public class ProblemCollector implements Disposable {
    private static final Logger LOG = Logger.getInstance(ProblemCollector.class);
    private static final String TOOL_WINDOW_ID = "Code Sentinel 檢查";
    private static final int BATCH_SIZE = 30; // 批次處理大小，避免過長處理導致 UI 凍結

    private final Project project;
    // 使用 WeakReference 包裝問題列表，允許當記憶體不足時被回收
    private WeakReference<List<ProblemInfo>> collectedProblemsRef;
    private WeakReference<CathayBkProblemsPanel> currentProblemsPanelRef;
    // 保存工具窗口的參考
    private ToolWindow toolWindow;

    public ProblemCollector(@NotNull Project project) {
        this.project = project;
        // 註冊以便專案關閉時釋放資源
        Disposer.register(project, this);
    }

    private static int getLineNumber(@Nullable Project project, @Nullable PsiElement element) {
        if (element == null || project == null || !ReadAction.compute(() -> {
            try {
                return element.isValid();
            } catch (Exception e) {
                LOG.debug("檢查元素有效性時出錯", e);
                return false;
            }
        }))
            return -1;

        return ReadAction.compute(() -> {
            try {
                PsiFile containingFile = element.getContainingFile();
                if (containingFile == null)
                    return -1;
                Document document = PsiDocumentManager.getInstance(project).getDocument(containingFile);
                if (document != null) {
                    int offset = element.getTextOffset();
                    if (offset >= 0 && offset <= document.getTextLength()) {
                        return document.getLineNumber(offset) + 1;
                    }
                }
            } catch (IndexOutOfBoundsException | PsiInvalidElementAccessException e) {
                LOG.warn("獲取行號時出錯: " + e.getMessage());
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

        // 創建新的問題列表
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
            indicator.setText("執行 Code Sentinel 檢查...");

            try {
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
                        try {
                            PsiFile psiFile = psiManager.findFile(vf);
                            if (psiFile instanceof PsiJavaFile)
                                relevantJavaFiles.add((PsiJavaFile) psiFile);
                        } catch (Exception e) {
                            LOG.warn("處理文件時出錯: " + vf.getPath(), e);
                        }
                    });
                }

                // 2. 執行檢查
                LOG.info("找到 " + relevantJavaFiles.size() + " 個 Java 文件需要檢查");
                int filesChecked = 0;
                int totalFiles = relevantJavaFiles.size();

                // 按批次處理文件，避免單一長時間處理
                for (int i = 0; i < relevantJavaFiles.size(); i += BATCH_SIZE) {
                    if (indicator.isCanceled()) {
                        LOG.info("使用者取消了檢查階段");
                        shouldCancel.set(true);
                        return;
                    }

                    int end = Math.min(i + BATCH_SIZE, relevantJavaFiles.size());
                    List<PsiJavaFile> batch = relevantJavaFiles.subList(i, end);

                    for (PsiJavaFile javaFile : batch) {
                        filesChecked++;
                        if (totalFiles > 0) {
                            indicator.setFraction(0.5 + (double) filesChecked / (totalFiles * 2));
                        } else {
                            indicator.setFraction(1.0);
                        }
                        indicator.setText2("檢查 " + javaFile.getName());

                        try {
                            ReadAction.run(() -> {
                                javaFile.accept(new JavaRecursiveElementWalkingVisitor() {
                                    @Override
                                    public void visitClass(@NotNull PsiClass aClass) {
                                        try {
                                            super.visitClass(aClass);
                                            if (indicator.isCanceled())
                                                return;
                                            allProblems.addAll(CathayBkInspectionUtil.checkServiceClassDoc(aClass));
                                        } catch (ProcessCanceledException e) {
                                            throw e; // 重新拋出取消異常
                                        } catch (Exception e) {
                                            LOG.warn("檢查類時出錯: " + aClass.getName(), e);
                                        }
                                    }

                                    @Override
                                    public void visitMethod(@NotNull PsiMethod method) {
                                        try {
                                            super.visitMethod(method);
                                            if (indicator.isCanceled())
                                                return;
                                            allProblems.addAll(CathayBkInspectionUtil.checkApiMethodDoc(method));
                                        } catch (ProcessCanceledException e) {
                                            throw e;
                                        } catch (Exception e) {
                                            LOG.warn("檢查方法時出錯: " + method.getName(), e);
                                        }
                                    }

                                    @Override
                                    public void visitField(@NotNull PsiField field) {
                                        try {
                                            super.visitField(field);
                                            if (indicator.isCanceled())
                                                return;
                                            allProblems.addAll(CathayBkInspectionUtil.checkInjectedFieldDoc(field));
                                        } catch (ProcessCanceledException e) {
                                            throw e;
                                        } catch (Exception e) {
                                            LOG.warn("檢查欄位時出錯: " + field.getName(), e);
                                        }
                                    }
                                });
                            });
                        } catch (ProcessCanceledException e) {
                            throw e; // 重新拋出取消異常
                        } catch (Exception e) {
                            LOG.warn("處理文件時出錯: " + javaFile.getName(), e);
                        }
                    }

                    // 釋放緩存，減少記憶體占用
                    if (i > 0 && i % 100 == 0) {
                        System.gc();
                    }
                }

                LOG.info("檢查完成，發現 " + allProblems.size() + " 個問題");
                // 使用 WeakReference 包裝問題列表，允許垃圾回收
                this.collectedProblemsRef = new WeakReference<>(allProblems);

            } catch (ProcessCanceledException e) {
                LOG.info("檢查被取消");
                shouldCancel.set(true);
            } catch (Exception e) {
                LOG.error("檢查過程中發生錯誤", e);
                shouldCancel.set(true);
            }

        }, "Code Sentinel 檢查", true, project);

        // 如果用戶在檢查過程中取消，則直接返回
        if (shouldCancel.get()) {
            LOG.info("用戶在檢查過程中選擇取消");
            return new CheckResult(false, null);
        }

        // 獲取收集到的問題
        List<ProblemInfo> problems = collectedProblemsRef != null ? collectedProblemsRef.get() : null;

        // 如果發現問題，彈出同步對話框詢問用戶
        if (problems != null && !problems.isEmpty()) {
            int userChoice = Messages.showYesNoCancelDialog(
                    project,
                    "發現 " + problems.size() + " 個 Code Sentinel 問題。是否繼續提交？\n" +
                            "選擇「顯示問題」可查看並修復問題。",
                    "Code Sentinel 檢查發現問題",
                    "繼續提交",
                    "顯示問題",
                    "取消提交",
                    Messages.getWarningIcon());

            if (userChoice == Messages.CANCEL || userChoice == -1) {
                LOG.info("使用者選擇取消提交");
                return new CheckResult(false, null);
            } else if (userChoice == Messages.NO) {
                LOG.info("使用者選擇顯示問題");
                showProblemsInToolWindow(problems);
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
        if (problems == null || problems.isEmpty()) {
            LOG.info("沒有問題需要顯示");
            return;
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
            if (toolWindowManager == null) {
                LOG.warn("ToolWindowManager 為空，無法顯示問題");
                return;
            }

            // 獲取或創建工具窗口
            toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID);
            if (toolWindow == null) {
                toolWindow = toolWindowManager.registerToolWindow(
                        TOOL_WINDOW_ID, true, ToolWindowAnchor.BOTTOM, project, true);
                toolWindow.setIcon(AllIcons.Toolwindows.Problems);
                toolWindow.setStripeTitle("Code Sentinel");
            }

            // 創建問題面板
            CathayBkProblemsPanel problemsPanel = new CathayBkProblemsPanel(project, problems);
            currentProblemsPanelRef = new WeakReference<>(problemsPanel);

            // 設置監聽器
            problemsPanel.setQuickFixListener(e -> applySelectedQuickFix());
            problemsPanel.setFixAllListener(e -> applyAllQuickFixes());

            // 清空舊內容
            toolWindow.getContentManager().removeAllContents(true);

            // 添加新內容
            ContentFactory contentFactory = ContentFactory.getInstance();
            String contentTitle = "檢查結果 (" + problems.size() + ")";
            Content content = contentFactory.createContent(problemsPanel, contentTitle, false);
            toolWindow.getContentManager().addContent(content);

            // 顯示工具窗口
            toolWindow.show(() -> {
                int maxHeight = 200;
                Component component = toolWindow.getComponent();
                if (component != null) {
                    component.setPreferredSize(new Dimension(component.getWidth(), maxHeight));

                    SwingUtilities.invokeLater(() -> {
                        if (component.isDisplayable()) {
                            component.revalidate();
                            component.repaint();
                        }
                    });
                }

                toolWindow.activate(null, true, true);
            });
        });
    }

    private void applySelectedQuickFix() {
        CathayBkProblemsPanel panel = currentProblemsPanelRef != null ? currentProblemsPanelRef.get() : null;
        if (panel == null) {
            LOG.warn("Problem panel is not available for quick fix.");
            return;
        }

        ProblemInfo problem = panel.getSelectedProblemInfo();
        if (problem == null) {
            Messages.showInfoMessage(project, "請先在左側列表中選擇一個具體的問題項", "快速修復");
            return;
        }

        PsiElement element = problem.getElement();
        if (element == null || !ReadAction.compute(() -> {
            try {
                return element.isValid();
            } catch (Exception e) {
                LOG.debug("檢查元素有效性時出錯", e);
                return false;
            }
        })) {
            Messages.showErrorDialog(project, "無法應用修復：關聯的程式碼元素已失效。\n請嘗試重新檢查。", "快速修復失敗");
            panel.removeProblem(problem);
            return;
        }

        LocalQuickFix fixToApply = ReadAction.compute(() -> determineQuickFix(problem));

        if (fixToApply == null) {
            Messages.showWarningDialog(project, "未能找到適用於此問題的自動修復方案。\n描述: " + problem.getDescription(), "快速修復");
            return;
        }

        ProblemDescriptor dummyDescriptor = ReadAction
                .compute(() -> createDummyDescriptor(element, problem.getDescription()));

        ApplicationManager.getApplication().invokeLater(() -> {
            WriteCommandAction.runWriteCommandAction(project, fixToApply.getName(), null, () -> {
                try {
                    fixToApply.applyFix(project, dummyDescriptor);
                    LOG.info("成功應用快速修復: " + fixToApply.getName() + " 到元素: "
                            + ReadAction.compute(() -> element.getText()));

                    // 更新 UI
                    CathayBkProblemsPanel currentPanel = currentProblemsPanelRef != null ? currentProblemsPanelRef.get()
                            : null;
                    if (currentPanel != null) {
                        currentPanel.removeProblem(problem);
                        updateToolWindowContentTitle();
                    }

                    // 從問題列表中移除
                    List<ProblemInfo> problems = collectedProblemsRef != null ? collectedProblemsRef.get() : null;
                    if (problems != null) {
                        problems.remove(problem);
                    }
                } catch (Exception ex) {
                    LOG.error("應用快速修復時出錯 (" + fixToApply.getName() + "): " + ex.getMessage(), ex);
                    Messages.showErrorDialog(project, "應用快速修復時出錯: " + ex.getMessage(), "快速修復失敗");
                }
            });
        });
    }

    private void applyAllQuickFixes() {
        List<ProblemInfo> problems = collectedProblemsRef != null ? collectedProblemsRef.get() : null;
        if (problems == null || problems.isEmpty()) {
            Messages.showInfoMessage(project, "沒有發現問題需要修復", "一鍵修復全部");
            return;
        }

        ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
            if (indicator != null) {
                indicator.setIndeterminate(false);
                indicator.setText("正在修復所有問題...");
            }

            List<ProblemInfo> problemsToFix = new ArrayList<>(problems);
            int totalProblems = problemsToFix.size();
            final AtomicInteger fixedCount = new AtomicInteger(0);
            final AtomicInteger failedCount = new AtomicInteger(0);
            List<ProblemInfo> successfullyFixed = new ArrayList<>();

            LOG.info("開始一鍵修復全部，總共 " + totalProblems + " 個問題");

            // 按批次處理問題，避免 UI 凍結
            for (int batchStart = 0; batchStart < problemsToFix.size(); batchStart += BATCH_SIZE) {
                if (indicator != null && indicator.isCanceled()) {
                    LOG.info("用戶取消了一鍵修復全部操作");
                    break;
                }

                int batchEnd = Math.min(batchStart + BATCH_SIZE, problemsToFix.size());
                List<ProblemInfo> batch = problemsToFix.subList(batchStart, batchEnd);

                for (int i = 0; i < batch.size(); i++) {
                    int overallIndex = batchStart + i;

                    if (indicator != null) {
                        indicator.setFraction((double) overallIndex / totalProblems);
                        if (indicator.isCanceled()) {
                            LOG.info("用戶取消了一鍵修復全部操作");
                            break;
                        }
                    }

                    final ProblemInfo problem = batch.get(i);

                    final PsiElement element = ReadAction.compute(() -> {
                        try {
                            PsiElement el = problem.getElement();
                            return (el != null && el.isValid()) ? el : null;
                        } catch (Exception e) {
                            LOG.debug("檢查元素有效性時出錯", e);
                            return null;
                        }
                    });

                    if (element == null) {
                        LOG.warn("問題元素無效，跳過: " + problem.getDescription());
                        failedCount.incrementAndGet();
                        continue;
                    }

                    if (indicator != null) {
                        indicator.setText2("修復問題 " + (overallIndex + 1) + "/" + totalProblems + ": " +
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

                // 每處理一批次後，釋放記憶體
                if (batchStart > 0 && batchStart % 100 == 0) {
                    System.gc();
                }
            }

            // 從問題列表中移除已修復的問題
            if (!successfullyFixed.isEmpty()) {
                problems.removeAll(successfullyFixed);
            }

            ApplicationManager.getApplication().invokeLater(() -> {
                // 更新 UI
                CathayBkProblemsPanel panel = currentProblemsPanelRef != null ? currentProblemsPanelRef.get() : null;
                if (panel != null) {
                    panel.refreshProblems(problems);
                }
                updateToolWindowContentTitle();

                // 顯示結果訊息
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
        if (toolWindow == null) {
            LOG.warn("無法更新工具窗口標題：ToolWindow 為 null");
            return;
        }

        Content content = toolWindow.getContentManager().getSelectedContent();
        if (content == null && toolWindow.getContentManager().getContentCount() > 0) {
            content = toolWindow.getContentManager().getContent(0);
        }

        if (content != null) {
            int count = 0;
            CathayBkProblemsPanel panel = currentProblemsPanelRef != null ? currentProblemsPanelRef.get() : null;
            if (panel != null) {
                List<ProblemInfo> currentProblems = panel.getCurrentProblems();
                count = currentProblems != null ? currentProblems.size() : 0;
            } else {
                List<ProblemInfo> problems = collectedProblemsRef != null ? collectedProblemsRef.get() : null;
                count = problems != null ? problems.size() : 0;
            }
            content.setDisplayName("檢查結果 (" + count + ")");
        } else {
            LOG.warn("無法更新工具窗口標題：找不到 Content。ToolWindow visible: " + toolWindow.isVisible());
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
                try {
                    PsiField initialField = PsiTreeUtil.getParentOfType(element, PsiField.class, false);
                    if (initialField == null && element instanceof PsiField)
                        return (PsiField) element;
                    return initialField;
                } catch (Exception e) {
                    LOG.debug("獲取欄位時出錯", e);
                    return null;
                }
            });

            if (field != null) {
                String typeName = ReadAction.compute(() -> {
                    try {
                        PsiType type = field.getType();
                        return type != null ? type.getPresentableText() : null;
                    } catch (Exception e) {
                        LOG.debug("獲取欄位類型時出錯", e);
                        return null;
                    }
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
                return ReadAction.compute(() -> {
                    try {
                        return element != null && element.isValid() ? element.getTextRange() : null;
                    } catch (Exception e) {
                        return null;
                    }
                });
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
     * 清理資源，避免記憶體洩漏
     */
    @Override
    public void dispose() {
        // 清除集合和 UI 元素的引用
        if (collectedProblemsRef != null) {
            List<ProblemInfo> problems = collectedProblemsRef.get();
            if (problems != null) {
                problems.clear();
            }
            collectedProblemsRef = null;
        }

        // 清除 UI 面板引用
        if (currentProblemsPanelRef != null) {
            CathayBkProblemsPanel panel = currentProblemsPanelRef.get();
            if (panel != null) {
                Disposer.dispose(panel);
            }
            currentProblemsPanelRef = null;
        }

        // 釋放工具窗口
        if (toolWindow != null) {
            toolWindow.getContentManager().removeAllContents(true);
            toolWindow = null;
        }

        // 強制觸發垃圾回收
        System.gc();

        LOG.info("ProblemCollector 已釋放資源");
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