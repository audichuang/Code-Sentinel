package com.cathaybk.codingassistant.vcs;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Ref;
import org.jetbrains.annotations.NotNull;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 處理提交前檢查相關的對話框和用戶界面邏輯
 */
public class CheckinDialogHelper {
    private static final Logger LOG = Logger.getInstance(CheckinDialogHelper.class);

    // 預先定義的常量字符串，避免多次創建
    private static final String CONTINUE_COMMIT = "繼續提交";
    private static final String CANCEL_COMMIT = "取消提交";
    private static final String OPEN_SETTINGS = "打開設置";

    private static final String GIT_UNAVAILABLE_MESSAGE =
            "無法執行 Git 操作，因為系統中未檢測到可用的 Git。\n" +
                    "您可以在「設置 > 工具 > CathaybkCodingAssitant」中配置要檢查的目標分支。";

    private static final String GIT_FETCH_FAILED_MESSAGE =
            "Git fetch 操作失敗。\n您可以在設置中配置要檢查的目標分支。";

    private static final String GIT_CHECK_WARNING = "Git 檢查警告";
    private static final String GIT_OPERATION_FAILED = "Git 操作失敗";
    private static final String BRANCH_BEHIND_TITLE = "分支落後提醒";
    private static final String GIT_CHECK_ERROR = "Git 檢查錯誤";

    // 使用 WeakReference 避免對 Project 的強引用
    private final WeakReference<Project> projectRef;

    // 標記對話框是否正在顯示，避免重複顯示
    private final AtomicBoolean isDialogShowing = new AtomicBoolean(false);

    public CheckinDialogHelper(@NotNull Project project) {
        this.projectRef = new WeakReference<>(project);
    }

    /**
     * 顯示 Git 不可用時的對話框
     *
     * @return true 如果用戶選擇取消提交，false 如果用戶選擇繼續
     */
    public boolean showGitUnavailableDialog() {
        if (isDialogShowing.getAndSet(true)) {
            LOG.warn("嘗試同時顯示多個對話框，忽略此次請求");
            return false;
        }

        try {
            Project project = projectRef.get();
            if (project == null) {
                LOG.warn("Project 引用已失效，無法顯示對話框");
                return false;
            }

            final String[] options = {CONTINUE_COMMIT, CANCEL_COMMIT, OPEN_SETTINGS};

            // 使用 EDT 線程顯示對話框
            Ref<Integer> resultRef = new Ref<>(-1);
            ApplicationManager.getApplication().invokeAndWait(() -> {
                int choice = Messages.showDialog(
                        project,
                        GIT_UNAVAILABLE_MESSAGE,
                        GIT_CHECK_WARNING,
                        options,
                        0,
                        Messages.getWarningIcon());
                resultRef.set(choice);
            });

            int choice = resultRef.get();

            if (choice == 1) {
                LOG.info("用戶選擇在 Git 檢查失敗後取消提交");
                return true;
            } else if (choice == 2) {
                LOG.info("用戶選擇打開設置");
                openSettings(project);
                return true;
            }

            return false;
        } finally {
            isDialogShowing.set(false);
        }
    }

    /**
     * 顯示 Git fetch 失敗時的對話框
     *
     * @return true 如果用戶選擇取消提交，false 如果用戶選擇繼續
     */
    public boolean showGitFetchFailedDialog() {
        if (isDialogShowing.getAndSet(true)) {
            LOG.warn("嘗試同時顯示多個對話框，忽略此次請求");
            return false;
        }

        try {
            Project project = projectRef.get();
            if (project == null) {
                LOG.warn("Project 引用已失效，無法顯示對話框");
                return false;
            }

            final String[] options = {CONTINUE_COMMIT, CANCEL_COMMIT, OPEN_SETTINGS};

            // 使用 EDT 線程顯示對話框
            Ref<Integer> resultRef = new Ref<>(-1);
            ApplicationManager.getApplication().invokeAndWait(() -> {
                int choice = Messages.showDialog(
                        project,
                        GIT_FETCH_FAILED_MESSAGE,
                        GIT_OPERATION_FAILED,
                        options,
                        0,
                        Messages.getWarningIcon());
                resultRef.set(choice);
            });

            int choice = resultRef.get();

            if (choice == 1) {
                LOG.info("用戶選擇在 Git fetch 失敗後取消提交");
                return true;
            } else if (choice == 2) {
                LOG.info("用戶選擇打開設置");
                openSettings(project);
                return true;
            }

            return false;
        } finally {
            isDialogShowing.set(false);
        }
    }

    /**
     * 顯示分支落後提醒對話框
     *
     * @param currentBranch  當前分支名稱
     * @param behindBranches 落後的分支列表
     * @return true 如果用戶選擇取消提交，false 如果用戶選擇繼續
     */
    public boolean showBranchBehindDialog(String currentBranch, List<String> behindBranches) {
        if (isDialogShowing.getAndSet(true)) {
            LOG.warn("嘗試同時顯示多個對話框，忽略此次請求");
            return false;
        }

        try {
            if (behindBranches == null || behindBranches.isEmpty()) {
                return false;
            }

            Project project = projectRef.get();
            if (project == null) {
                LOG.warn("Project 引用已失效，無法顯示對話框");
                return false;
            }

            // 構建消息
            StringBuilder message = new StringBuilder(256)
                    .append("當前分支 ")
                    .append(currentBranch)
                    .append(" 落後於以下分支：\n");

            for (String branch : behindBranches) {
                message.append("- ").append(branch).append("\n");
            }

            message.append("\n請考慮在提交後進行 rebase 操作，以保持代碼同步。")
                    .append("\n\n您可以在「設置 > 工具 > CathaybkCodingAssitant」中更改目標分支。");

            final String[] options = {CONTINUE_COMMIT, CANCEL_COMMIT, OPEN_SETTINGS};

            // 使用 EDT 線程顯示對話框
            Ref<Integer> resultRef = new Ref<>(-1);
            ApplicationManager.getApplication().invokeAndWait(() -> {
                int choice = Messages.showDialog(
                        project,
                        message.toString(),
                        BRANCH_BEHIND_TITLE,
                        options,
                        0, // 預設選項: 繼續提交
                        Messages.getWarningIcon());
                resultRef.set(choice);
            });

            int choice = resultRef.get();

            if (choice == 1) {
                // 取消提交
                LOG.info("用戶選擇取消提交");
                return true;
            } else if (choice == 2) {
                // 打開設置
                LOG.info("用戶選擇打開設置");
                openSettings(project);
                return true;
            }

            return false;
        } finally {
            isDialogShowing.set(false);
        }
    }

    /**
     * 打開設置對話框，並導航到指定的設置頁面
     */
    private void openSettings(Project project) {
        if (project == null) return;

        // 確保在 EDT 線程上執行 UI 操作
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            ApplicationManager.getApplication().invokeLater(() -> openSettings(project));
            return;
        }

        ShowSettingsUtil.getInstance().showSettingsDialog(
                project,
                "CathaybkCodingAssitant");
    }

    /**
     * 當 Git 操作出現錯誤時顯示對話框
     *
     * @param errorMessage 錯誤信息
     * @return true 如果用戶選擇取消提交，false 如果用戶選擇繼續
     */
    public boolean showGitErrorDialog(String errorMessage) {
        if (isDialogShowing.getAndSet(true)) {
            LOG.warn("嘗試同時顯示多個對話框，忽略此次請求");
            return false;
        }

        try {
            Project project = projectRef.get();
            if (project == null) {
                LOG.warn("Project 引用已失效，無法顯示對話框");
                return false;
            }

            Ref<Boolean> resultRef = new Ref<>(false);
            ApplicationManager.getApplication().invokeAndWait(() -> {
                int result = Messages.showYesNoDialog(
                        project,
                        "檢查分支狀態時發生錯誤：" + errorMessage + "\n要取消提交嗎？",
                        GIT_CHECK_ERROR,
                        "取消提交",
                        "繼續提交",
                        Messages.getErrorIcon());
                resultRef.set(result == Messages.YES);
            });

            return resultRef.get();
        } finally {
            isDialogShowing.set(false);
        }
    }

    /**
     * 顯示 Git 錯誤消息對話框
     *
     * @param message 錯誤信息
     * @param title   對話框標題
     */
    public void showErrorDialog(String message, String title) {
        if (isDialogShowing.getAndSet(true)) {
            LOG.warn("嘗試同時顯示多個對話框，忽略此次請求");
            return;
        }

        try {
            Project project = projectRef.get();
            if (project == null) {
                LOG.warn("Project 引用已失效，無法顯示對話框");
                return;
            }

            ApplicationManager.getApplication().invokeAndWait(() ->
                    Messages.showErrorDialog(project, message, title));
        } finally {
            isDialogShowing.set(false);
        }
    }
}