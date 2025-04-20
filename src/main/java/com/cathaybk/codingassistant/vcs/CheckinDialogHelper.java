package com.cathaybk.codingassistant.vcs;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import java.util.List;

/**
 * 處理提交前檢查相關的對話框和用戶界面邏輯
 */
public class CheckinDialogHelper {
    private static final Logger LOG = Logger.getInstance(CheckinDialogHelper.class);

    private final Project project;

    public CheckinDialogHelper(Project project) {
        this.project = project;
    }

    /**
     * 顯示 Git 不可用時的對話框
     *
     * @return true 如果用戶選擇取消提交，false 如果用戶選擇繼續
     */
    public boolean showGitUnavailableDialog() {
        String[] options = {"繼續提交", "取消提交", "打開設置"};
        int choice = Messages.showDialog(
                project,
                "無法執行 Git 操作，因為系統中未檢測到可用的 Git。\n" +
                        "您可以在「設置 > 工具 > CathaybkCodingAssitant」中配置要檢查的目標分支。",
                "Git 檢查警告",
                options,
                0,
                Messages.getWarningIcon());

        if (choice == 1) {
            LOG.info("用戶選擇在 Git 檢查失敗後取消提交");
            return true;
        } else if (choice == 2) {
            LOG.info("用戶選擇打開設置");
            openSettings();
            return true;
        }
        return false;
    }

    /**
     * 顯示 Git fetch 失敗時的對話框
     *
     * @return true 如果用戶選擇取消提交，false 如果用戶選擇繼續
     */
    public boolean showGitFetchFailedDialog() {
        String[] options = {"繼續提交", "取消提交", "打開設置"};
        int choice = Messages.showDialog(
                project,
                "Git fetch 操作失敗。\n您可以在設置中配置要檢查的目標分支。",
                "Git 操作失敗",
                options,
                0,
                Messages.getWarningIcon());

        if (choice == 1) {
            LOG.info("用戶選擇在 Git fetch 失敗後取消提交");
            return true;
        } else if (choice == 2) {
            LOG.info("用戶選擇打開設置");
            openSettings();
            return true;
        }
        return false;
    }

    /**
     * 顯示分支落後提醒對話框
     *
     * @param currentBranch  當前分支名稱
     * @param behindBranches 落後的分支列表
     * @return true 如果用戶選擇取消提交，false 如果用戶選擇繼續
     */
    public boolean showBranchBehindDialog(String currentBranch, List<String> behindBranches) {
        if (behindBranches.isEmpty()) {
            return false;
        }

        StringBuilder message = new StringBuilder("當前分支 ")
                .append(currentBranch)
                .append(" 落後於以下分支：\n");

        for (String branch : behindBranches) {
            message.append("- ").append(branch).append("\n");
        }

        message.append("\n請考慮在提交後進行 rebase 操作，以保持代碼同步。")
                .append("\n\n您可以在「設置 > 工具 > CathaybkCodingAssitant」中更改目標分支。");

        String[] options = {"繼續提交", "取消提交", "打開設置"};
        int choice = Messages.showDialog(
                project,
                message.toString(),
                "分支落後提醒",
                options,
                0, // 預設選項: 繼續提交
                Messages.getWarningIcon());

        if (choice == 1) {
            // 取消提交
            LOG.info("用戶選擇取消提交");
            return true;
        } else if (choice == 2) {
            // 打開設置
            LOG.info("用戶選擇打開設置");
            openSettings();
            return true;
        }
        return false;
    }

    /**
     * 打開設置對話框，並導航到指定的設置頁面
     */
    private void openSettings() {
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
        return Messages.showYesNoDialog(
                project,
                "檢查分支狀態時發生錯誤：" + errorMessage + "\n要取消提交嗎？",
                "Git 檢查錯誤",
                "取消提交",
                "繼續提交",
                Messages.getErrorIcon()) == Messages.YES;
    }

    /**
     * 顯示 Git 錯誤消息對話框
     *
     * @param message 錯誤信息
     * @param title   對話框標題
     */
    public void showErrorDialog(String message, String title) {
        Messages.showErrorDialog(project, message, title);
    }
}