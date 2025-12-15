package com.cathaybk.codingassistant.vcs;

import com.cathaybk.codingassistant.common.ProblemInfo;
import com.cathaybk.codingassistant.fix.AddApiIdDocFix;
import com.cathaybk.codingassistant.fix.AddControllerApiIdFromServiceFix;
import com.cathaybk.codingassistant.fix.AddFieldJavadocFix;
import com.cathaybk.codingassistant.fix.AddServiceApiIdQuickFix;
import com.cathaybk.codingassistant.fix.AddServiceClassApiIdDocFix;
import com.cathaybk.codingassistant.settings.GitSettings;
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
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vcs.CheckinProjectPanel;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.checkin.CheckinHandler;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 處理提交前的檢查邏輯
 * 實作 Disposable 以確保資源正確釋放
 */
public class PreCommitInspectionHandler extends CheckinHandler implements Disposable {
    private static final Logger LOG = Logger.getInstance(PreCommitInspectionHandler.class);
    private static final String TOOL_WINDOW_ID = "Code Sentinel 檢查";
    private static final String SETTING_TITLE_NAME = "Code Sentinel";

    private final CheckinProjectPanel panel;
    private final Project project;
    private final GitOperationHelper gitHelper;
    private final CheckinDialogHelper dialogHelper;
    private final ProblemCollector problemCollector;
    private List<ProblemInfo> collectedProblems;
    private InspectionProblemsPanel currentProblemsPanel;
    // 用於異步操作的標記
    private final AtomicBoolean gitCheckInProgress = new AtomicBoolean(false);

    public PreCommitInspectionHandler(CheckinProjectPanel panel) {
        LOG.info("PreCommitInspectionHandler 建構函式被呼叫！");
        this.panel = panel;
        this.project = panel.getProject();
        this.gitHelper = new GitOperationHelper(project);
        this.dialogHelper = new CheckinDialogHelper(project);
        this.problemCollector = new ProblemCollector(project);

        // 註冊為 Disposable 以確保在適當時機被清理
        if (project != null && !project.isDisposed()) {
            Disposer.register(project, this);
        }
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

    @Override
    public ReturnResult beforeCheckin() {
        LOG.info("PreCommitInspectionHandler beforeCheckin 被呼叫！");

        // 讀取 Git 檢查設定
        GitSettings settings = GitSettings.getInstance(project);
        boolean shouldCheckGit = settings.isCheckGitBranch();

        if (shouldCheckGit) {
            // 添加明顯的訊息，表示開始執行 Git 相關操作
            LOG.info("========== 開始執行 Git 相關操作 ==========");
            // 首先執行 Git 相關檢查
            ReturnResult gitCheckResult = checkGitStatus();
            if (gitCheckResult != ReturnResult.COMMIT) {
                LOG.info("Git 檢查未通過或用戶取消，終止提交。");
                return gitCheckResult;
            }
            LOG.info("========== Git 相關操作結束 ==========");
        } else {
            LOG.info("Git 分支檢查設定已關閉，跳過 Git 相關操作。");
        }

        // 讀取程式碼檢查設定
        boolean shouldCheckCode = settings.isCheckCodeQuality();

        // 根據設定決定是否執行代碼質量檢查
        if (shouldCheckCode) {
            LOG.info("========== 開始執行代碼檢查 ==========");
            ReturnResult codeCheckResult = checkCodeQuality();
            if (codeCheckResult != ReturnResult.COMMIT) {
                LOG.info("程式碼檢查未通過或用戶取消，終止提交。");
                return codeCheckResult;
            }
            LOG.info("========== 程式碼檢查結束 ==========");
            // 如果兩項檢查都通過，才真正允許提交
            return ReturnResult.COMMIT;
        } else {
            LOG.info("程式碼規範檢查設定已關閉，跳過相關檢查。");
            // 如果 Git 檢查被跳過或通過，且程式碼檢查被跳過，則允許提交
            return ReturnResult.COMMIT;
        }
    }

    /**
     * 檢查 Git 狀態
     *
     * @return ReturnResult 代表檢查結果
     */
    private ReturnResult checkGitStatus() {
        // 檢查 Git 是否可用
        if (!gitHelper.isGitAvailable()) {
            LOG.warn("Git 不可用，跳過 Git 相關操作");

            // 顯示通知給用戶
            if (dialogHelper.showGitUnavailableDialog()) {
                return ReturnResult.CANCEL;
            }
        } else {
            LOG.info("Git 可用，執行相關操作");

            // 方案1：使用改進後的同步方法（仍會在EDT上阻塞一點時間，但不應該太久）
            boolean fetchSuccess = gitHelper.performGitFetch();

            if (fetchSuccess) {
                LOG.info("Git fetch 成功執行");

                // 檢查分支是否落後
                List<String> behindBranches = gitHelper.checkBranchStatus();

                // 取得當前分支
                String currentBranch = gitHelper.getCurrentBranch();

                // 檢查是否需要顯示分支落後提示
                if (!behindBranches.isEmpty() && dialogHelper.showBranchBehindDialog(currentBranch, behindBranches)) {
                    LOG.info("用戶選擇取消提交");
                    return ReturnResult.CANCEL;
                }
            } else {
                LOG.warn("Git fetch 執行失敗");

                // 如果 Git 操作失敗，詢問用戶是否繼續
                if (dialogHelper.showGitFetchFailedDialog()) {
                    LOG.info("用戶選擇在 Git fetch 失敗後取消提交");
                    return ReturnResult.CANCEL;
                }
            }
        }

        LOG.info("========== Git 相關操作結束 ==========");
        return ReturnResult.COMMIT;
    }

    /**
     * 檢查代碼質量
     *
     * @return ReturnResult 代表檢查結果
     */
    private ReturnResult checkCodeQuality() {
        Collection<Change> changes = panel.getSelectedChanges();

        ProblemCollector.CheckResult result = problemCollector.collectProblems(changes);

        if (!result.shouldContinue()) {
            if ("CLOSE_WINDOW".equals(result.getAction())) {
                return ReturnResult.CLOSE_WINDOW;
            } else {
                return ReturnResult.CANCEL;
            }
        }

        return ReturnResult.COMMIT;
    }

    private void showProblemsInToolWindow(Project project, List<ProblemInfo> problems) {
        ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
        ToolWindow toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID);

        if (toolWindow == null) {
            toolWindow = toolWindowManager.registerToolWindow(TOOL_WINDOW_ID, true, ToolWindowAnchor.BOTTOM, project,
                    true);
            toolWindow.setIcon(AllIcons.General.InspectionsEye);
            toolWindow.setStripeTitle(SETTING_TITLE_NAME);
        }

        currentProblemsPanel = new InspectionProblemsPanel(project, problems);

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
                finalToolWindow.getComponent()
                        .setPreferredSize(new Dimension(finalToolWindow.getComponent().getWidth(), maxHeight));
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

        // 合併 ReadAction 呼叫以提高效能
        ReadAction.run(() -> {
            PsiElement element = problem.getElement();
            if (element == null || !element.isValid()) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    Messages.showErrorDialog(project, "無法應用修復：關聯的程式碼元素已失效。\n請嘗試重新檢查。", "快速修復失敗");
                    currentProblemsPanel.removeProblem(problem);
                });
                return;
            }

            LocalQuickFix fixToApply = determineQuickFix(problem);
            if (fixToApply == null) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    Messages.showWarningDialog(project, "未能找到適用於此問題的自動修復方案。\n描述: " + problem.getDescription(), "快速修復");
                });
                return;
            }

            ProblemDescriptor dummyDescriptor = createDummyDescriptor(element, problem.getDescription());

            // 在 WriteAction 中執行修復
            ApplicationManager.getApplication().invokeLater(() -> {
                WriteCommandAction.runWriteCommandAction(project, fixToApply.getName(), null, () -> {
                    try {
                        fixToApply.applyFix(project, dummyDescriptor);
                        LOG.info("成功應用快速修復: " + fixToApply.getName());
                        currentProblemsPanel.removeProblem(problem);
                        updateToolWindowContentTitle();
                    } catch (Exception ex) {
                        LOG.error("應用快速修復時出錯 (" + fixToApply.getName() + "): " + ex.getMessage(), ex);
                        Messages.showErrorDialog(project, "應用快速修復時出錯: " + ex.getMessage(), "快速修復失敗");
                    }
                });
            });
        });
        // 已在上面的 ReadAction 中處理
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
                    indicator.setText2("修復問題 " + (i + 1) + "/" + totalProblems + ": "
                            + problem.getDescription().substring(0, Math.min(50, problem.getDescription().length()))
                            + (problem.getDescription().length() > 50 ? "..." : ""));
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

                String message = "修復完成!\n\n成功修復: " + fixedCount.get() + " 個問題\n"
                        + (failedCount.get() > 0 ? "無法修復: " + failedCount.get() + " 個問題" : "")
                        + (indicator != null && indicator.isCanceled() ? "\n(操作被用戶取消)" : "");
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
            return new AddServiceClassApiIdDocFix();
        }
        // 處理 Service 方法的快速修復（新增功能）
        if ((description.contains("Service 介面方法缺少") || description.contains("Service 實現類方法缺少"))
                && (element instanceof PsiIdentifier || element instanceof PsiMethod)) {
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
                return PreCommitInspectionHandler.getLineNumber(project, element);
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

    @Override
    public void dispose() {
        LOG.info("PreCommitInspectionHandler dispose 被呼叫");

        // 清理 ToolWindow 參考
        if (currentProblemsPanel != null) {
            // 如果 panel 實作了 Disposable，確保它被 dispose
            if (currentProblemsPanel instanceof Disposable) {
                try {
                    Disposer.dispose((Disposable) currentProblemsPanel);
                } catch (Exception e) {
                    LOG.warn("Dispose currentProblemsPanel 時發生錯誤: " + e.getMessage());
                }
            }
            currentProblemsPanel = null;
        }

        // 清理問題列表
        if (collectedProblems != null) {
            collectedProblems.clear();
            collectedProblems = null;
        }

        // 清理原子標記
        gitCheckInProgress.set(false);

        // Dispose problemCollector（它持有緩存和其他資源）
        if (problemCollector != null) {
            try {
                Disposer.dispose(problemCollector);
            } catch (Exception e) {
                LOG.warn("Dispose problemCollector 時發生錯誤: " + e.getMessage());
            }
        }

        LOG.info("PreCommitInspectionHandler 資源已釋放");
    }
}
