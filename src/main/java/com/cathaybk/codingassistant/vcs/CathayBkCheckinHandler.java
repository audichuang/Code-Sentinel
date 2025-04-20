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
import com.intellij.openapi.vcs.CheckinProjectPanel;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.checkin.CheckinHandler;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsRoot;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.VcsType;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
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
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class CathayBkCheckinHandler extends CheckinHandler {
    private static final Logger LOG = Logger.getInstance(CathayBkCheckinHandler.class);
    private static final String TOOL_WINDOW_ID = "國泰規範檢查";
    private static final String[] TARGET_BRANCHES = { "master", "dev" };

    private final CheckinProjectPanel panel;
    private final Project project;
    private List<ProblemInfo> collectedProblems;
    private CathayBkProblemsPanel currentProblemsPanel;
    private volatile boolean shouldCancelCommit = false;

    public CathayBkCheckinHandler(CheckinProjectPanel panel) {
        LOG.info("CathayBk Checkin Handler 建構函式被呼叫！");
        this.panel = panel;
        this.project = panel.getProject();
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
        LOG.info("CathayBk Checkin Handler beforeCheckin 被呼叫！");

        // 確保每次提交前重置狀態
        shouldCancelCommit = false;

        // 添加明顯的訊息，表示開始執行 Git 相關操作
        LOG.info("========== 開始執行 Git 相關操作 ==========");

        // 檢查 Git 存在性
        if (isGitAvailable()) {
            LOG.info("Git 可用，執行相關操作");

            // 執行 git fetch
            boolean fetchSuccess = performGitFetch();
            if (fetchSuccess) {
                LOG.info("Git fetch 成功執行");

                // 檢查分支是否落後
                checkBranchStatus();

                // 檢查用戶是否選擇取消提交
                if (shouldCancelCommit) {
                    LOG.info("用戶在 Git 相關操作中選擇取消提交");
                    return ReturnResult.CANCEL;
                }
            } else {
                LOG.warn("Git fetch 執行失敗");

                // 如果 Git 操作失敗，詢問用戶是否繼續
                int choice = Messages.showYesNoDialog(
                        project,
                        "Git fetch 操作失敗。是否繼續提交？",
                        "Git 操作失敗",
                        "繼續提交",
                        "取消提交",
                        Messages.getWarningIcon());

                if (choice == Messages.NO) {
                    LOG.info("用戶選擇在 Git fetch 失敗後取消提交");
                    return ReturnResult.CANCEL;
                }
            }
        } else {
            LOG.warn("Git 不可用，跳過 Git 相關操作");

            // 顯示通知給用戶
            int choice = Messages.showYesNoDialog(
                    project,
                    "無法執行 Git 操作，因為系統中未檢測到可用的 Git。\n是否繼續提交？",
                    "Git 檢查警告",
                    "繼續提交",
                    "取消提交",
                    Messages.getWarningIcon());

            if (choice == Messages.NO) {
                LOG.info("用戶選擇在 Git 檢查失敗後取消提交");
                return ReturnResult.CANCEL;
            }
        }

        LOG.info("========== Git 相關操作結束 ==========");

        // 檢查最終 shouldCancelCommit 狀態
        if (shouldCancelCommit) {
            LOG.info("Git 相關操作設置了取消提交標誌");
            return ReturnResult.CANCEL;
        }

        // 以下是原有的代碼檢查邏輯
        LOG.info("========== 開始執行代碼檢查 ==========");

        Collection<Change> changes = panel.getSelectedChanges();
        LOG.info("檢查的變更數量：" + changes.size());

        if (changes.isEmpty()) {
            LOG.info("沒有要檢查的變更，直接返回 COMMIT");
            return ReturnResult.COMMIT;
        }

        List<ProblemInfo> allProblems = new ArrayList<>();
        AtomicReference<ReturnResult> result = new AtomicReference<>(ReturnResult.COMMIT);

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
                    result.set(ReturnResult.CANCEL);
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
                    result.set(ReturnResult.CANCEL);
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

            if (collectedProblems.isEmpty()) {
                LOG.info("沒有發現問題，提交將繼續");
                return;
            }
        }, "國泰規範檢查", true, project);

        // 如果用戶在檢查過程中取消，則直接返回
        if (result.get() == ReturnResult.CANCEL) {
            LOG.info("用戶在檢查過程中選擇取消");
            return ReturnResult.CANCEL;
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
                return ReturnResult.CANCEL;
            } else if (userChoice == Messages.NO) {
                LOG.info("使用者選擇顯示問題");
                showProblemsInToolWindow(project, collectedProblems);
                return ReturnResult.CLOSE_WINDOW;
            } else {
                LOG.info("使用者選擇繼續提交");
                return ReturnResult.COMMIT;
            }
        }

        LOG.info("檢查完成，沒有發現問題");
        return ReturnResult.COMMIT;
    }

    /**
     * 檢查 Git 是否可用
     */
    private boolean isGitAvailable() {
        try {
            LOG.info("正在檢查 Git 是否可用...");
            // 嘗試執行 git --version 命令
            Process process = Runtime.getRuntime().exec("git --version");

            // 獲取輸出以顯示 git 版本
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String versionInfo = reader.readLine();
                LOG.info("Git 版本信息: " + versionInfo);
            }

            int exitCode = process.waitFor();
            boolean isAvailable = exitCode == 0;
            LOG.info("Git 檢查結果: " + (isAvailable ? "可用" : "不可用") + " (退出碼: " + exitCode + ")");
            return isAvailable;
        } catch (Exception e) {
            LOG.error("檢查 Git 可用性時發生錯誤", e);
            Messages.showErrorDialog(
                    project,
                    "無法檢查 Git 可用性: " + e.getMessage(),
                    "Git 檢查錯誤");
            return false;
        }
    }

    /**
     * 執行 git fetch 操作來更新遠端分支資訊
     * 
     * @return 操作是否成功
     */
    private boolean performGitFetch() {
        try {
            LOG.info("執行 git fetch...");
            // 獲取根目錄
            VirtualFile projectDir = project.getBaseDir();
            if (projectDir == null) {
                LOG.warn("無法獲取項目根目錄");
                Messages.showErrorDialog(
                        project,
                        "無法獲取項目根目錄，無法執行 git fetch",
                        "Git 操作錯誤");
                return false;
            }

            LOG.info("項目根目錄: " + projectDir.getPath());

            // 執行 git fetch 命令
            ProcessBuilder processBuilder = new ProcessBuilder("git", "fetch", "--prune");
            processBuilder.directory(new java.io.File(projectDir.getPath()));
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();

            // 讀取命令輸出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();

            if (exitCode != 0) {
                LOG.warn("git fetch 命令執行失敗，退出碼: " + exitCode + ", 輸出: " + output.toString());
                return false;
            }

            LOG.info("git fetch 執行成功" + (output.length() > 0 ? "，輸出：" + output.toString() : "（無輸出）"));
            return true;
        } catch (Exception e) {
            LOG.error("執行 git fetch 時發生錯誤", e);
            Messages.showErrorDialog(
                    project,
                    "執行 git fetch 時發生錯誤: " + e.getMessage(),
                    "Git 操作錯誤");
            return false;
        }
    }

    /**
     * 檢查當前分支是否落後於 dev 或 master 分支
     */
    private void checkBranchStatus() {
        try {
            VirtualFile projectDir = project.getBaseDir();
            if (projectDir == null) {
                LOG.warn("無法獲取項目根目錄");
                return;
            }

            // 獲取當前分支名稱
            String currentBranch = getCurrentBranch(projectDir);
            if (currentBranch == null || currentBranch.isEmpty()) {
                LOG.warn("無法獲取當前分支名稱");
                return;
            }

            LOG.info("當前分支: " + currentBranch);

            // 移除此通知，因為我們將使用下方的對話框
            // ApplicationManager.getApplication().invokeLater(() -> {
            // Messages.showInfoMessage(
            // project,
            // "正在檢查分支狀態：當前分支 " + currentBranch,
            // "國泰 Git 檢查"
            // );
            // });

            // 跳過目標分支的檢查
            for (String targetBranch : TARGET_BRANCHES) {
                if (currentBranch.equals(targetBranch)) {
                    LOG.info("當前分支就是目標分支之一，跳過檢查");
                    return;
                }
            }

            // 檢查每個目標分支
            List<String> behindBranches = new ArrayList<>();
            for (String targetBranchName : TARGET_BRANCHES) {
                if (isBranchBehind(projectDir, currentBranch, targetBranchName)) {
                    behindBranches.add(targetBranchName);
                }
            }

            // 如果落後於任何目標分支，顯示提醒並允許用戶取消提交
            if (!behindBranches.isEmpty()) {
                StringBuilder message = new StringBuilder("當前分支 ")
                        .append(currentBranch)
                        .append(" 落後於以下分支：\n");

                for (String branch : behindBranches) {
                    message.append("- ").append(branch).append("\n");
                }

                message.append("\n請考慮在提交後進行 rebase 操作，以保持代碼同步。");

                // 使用同步對話框，讓用戶選擇是否繼續提交
                int choice = Messages.showYesNoDialog(
                        project,
                        message.toString(),
                        "分支落後提醒",
                        "繼續提交",
                        "取消提交",
                        Messages.getWarningIcon());

                // 處理用戶選擇
                if (choice == Messages.NO) {
                    LOG.info("用戶選擇取消提交");
                    shouldCancelCommit = true;
                }
            } else {
                // 分支未落後，顯示一個簡單的通知
                LOG.info("分支檢查完成：當前分支 " + currentBranch + " 沒有落後於任何目標分支");
            }
        } catch (Exception e) {
            LOG.error("檢查分支狀態時發生錯誤", e);

            // 發生錯誤時也顯示通知
            boolean cancelOnError = Messages.showYesNoDialog(
                    project,
                    "檢查分支狀態時發生錯誤：" + e.getMessage() + "\n要取消提交嗎？",
                    "Git 檢查錯誤",
                    "取消提交",
                    "繼續提交",
                    Messages.getErrorIcon()) == Messages.YES;

            if (cancelOnError) {
                shouldCancelCommit = true;
            }
        }
    }

    /**
     * 獲取當前分支名稱
     */
    private String getCurrentBranch(VirtualFile projectDir) {
        try {
            Process process = Runtime.getRuntime().exec("git rev-parse --abbrev-ref HEAD", null,
                    new java.io.File(projectDir.getPath()));
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                process.waitFor();
                return line;
            }
        } catch (Exception e) {
            LOG.error("獲取當前分支時發生錯誤", e);
            return null;
        }
    }

    /**
     * 判斷當前分支是否落後於目標分支
     */
    private boolean isBranchBehind(VirtualFile projectDir, String currentBranch, String targetBranch) {
        try {
            Process process = Runtime.getRuntime().exec(
                    "git rev-list --count " + currentBranch + "..origin/" + targetBranch,
                    null,
                    new java.io.File(projectDir.getPath()));

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String output = reader.readLine();
                process.waitFor();

                if (output != null && !output.isEmpty()) {
                    try {
                        int behindCount = Integer.parseInt(output.trim());
                        return behindCount > 0;
                    } catch (NumberFormatException e) {
                        LOG.warn("無法解析 git rev-list 輸出: " + output);
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("檢查分支是否落後時發生錯誤", e);
        }
        return false;
    }

    private void showProblemsInToolWindow(Project project, List<ProblemInfo> problems) {
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
                return CathayBkCheckinHandler.getLineNumber(project, element);
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
}