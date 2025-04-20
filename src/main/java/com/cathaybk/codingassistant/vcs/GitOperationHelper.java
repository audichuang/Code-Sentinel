package com.cathaybk.codingassistant.vcs;

import com.cathaybk.codingassistant.settings.GitSettings;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 處理與 Git 相關的操作
 */
public class GitOperationHelper {
    private static final Logger LOG = Logger.getInstance(GitOperationHelper.class);
    private final Project project;

    public GitOperationHelper(Project project) {
        this.project = project;
    }

    /**
     * 檢查 Git 是否可用
     *
     * @return true 如果 Git 可用
     */
    public boolean isGitAvailable() {
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
            return false;
        }
    }

    /**
     * 執行 git fetch 操作來更新遠端分支資訊
     *
     * @return 操作是否成功
     */
    public boolean performGitFetch() {
        try {
            LOG.info("執行 git fetch...");
            // 獲取根目錄
            VirtualFile projectDir = project.getBaseDir();
            if (projectDir == null) {
                LOG.warn("無法獲取項目根目錄");
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
            return false;
        }
    }

    /**
     * 檢查當前分支是否落後於設置中的目標分支
     *
     * @return 落後的分支列表，如果沒有落後分支則返回空列表
     */
    public List<String> checkBranchStatus() {
        try {
            // 確保 project 不為空
            if (project == null) {
                LOG.warn("項目為空，無法獲取設置");
                return new ArrayList<>();
            }

            // 獲取用戶配置的目標分支
            GitSettings gitSettings = GitSettings.getInstance(project);
            List<String> targetBranches = gitSettings.getTargetBranches();

            if (targetBranches.isEmpty()) {
                LOG.warn("未設置目標分支，使用預設分支「dev」");
                targetBranches = new ArrayList<>();
                targetBranches.add("dev");
            }

            LOG.info("檢查目標分支: " + String.join(", ", targetBranches));

            VirtualFile projectDir = project.getBaseDir();
            if (projectDir == null) {
                LOG.warn("無法獲取項目根目錄");
                return new ArrayList<>();
            }

            // 獲取當前分支名稱
            String currentBranch = getCurrentBranch(projectDir);
            if (currentBranch == null || currentBranch.isEmpty()) {
                LOG.warn("無法獲取當前分支名稱");
                return new ArrayList<>();
            }

            LOG.info("當前分支: " + currentBranch);

            // 跳過目標分支的檢查
            for (String targetBranch : targetBranches) {
                if (currentBranch.equals(targetBranch)) {
                    LOG.info("當前分支就是目標分支之一，跳過檢查");
                    return new ArrayList<>();
                }
            }

            // 檢查每個目標分支
            List<String> behindBranches = new ArrayList<>();
            for (String targetBranchName : targetBranches) {
                if (isBranchBehind(projectDir, currentBranch, targetBranchName)) {
                    behindBranches.add(targetBranchName);
                }
            }

            return behindBranches;
        } catch (Exception e) {
            LOG.error("檢查分支狀態時發生錯誤", e);
            return new ArrayList<>();
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

    /**
     * 獲取當前分支名稱
     *
     * @return 當前分支名稱，如果發生錯誤則返回 null
     */
    public String getCurrentBranch() {
        VirtualFile projectDir = project.getBaseDir();
        if (projectDir == null) {
            LOG.warn("無法獲取項目根目錄");
            return null;
        }
        return getCurrentBranch(projectDir);
    }
}