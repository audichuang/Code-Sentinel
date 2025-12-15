package com.cathaybk.codingassistant.vcs;

import com.cathaybk.codingassistant.settings.GitSettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 處理與 Git 相關的操作
 */
public class GitOperationHelper {
    private static final Logger LOG = Logger.getInstance(GitOperationHelper.class);
    private static final int PROCESS_TIMEOUT_SECONDS = 15;
    private static final int QUICK_CHECK_TIMEOUT_SECONDS = 5;
    private static final int BUFFER_SIZE = 8192;

    private final Project project;

    public GitOperationHelper(@NotNull Project project) {
        this.project = project;
    }

    /**
     * 檢查 Git 是否可用
     *
     * @return true 如果 Git 可用
     */
    public boolean isGitAvailable() {
        LOG.info("正在檢查 Git 是否可用...");
        ProcessResult result = executeGitCommand(null, "git", "--version");

        if (result.isSuccess()) {
            LOG.info("Git 版本信息: " + result.getOutput().trim());
            return true;
        } else {
            LOG.error("檢查 Git 可用性時發生錯誤: " + result.getError());
            return false;
        }
    }

    /**
     * 在背景執行 git fetch 操作，完成後通過回調通知結果
     *
     * @param callback 操作完成時的回調函數
     */
    public void performGitFetchInBackground(Consumer<Boolean> callback) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "更新 Git 資訊", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("執行 git fetch...");
                LOG.info("【背景】執行 git fetch...");

                boolean result = executeGitFetchWithTimeout(indicator);

                // 在 EDT 線程上調用回調
                ApplicationManager.getApplication().invokeLater(() -> callback.accept(result));
            }
        });
    }

    /**
     * 執行 git fetch 操作來更新遠端分支資訊
     *
     * @return true 如果操作成功，false 如果失敗
     */
    public boolean performGitFetch() {
        final AtomicBoolean success = new AtomicBoolean(false);

        // 使用 ProgressManager.runProcessWithProgressSynchronously 確保顯示進度對話框
        ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
            if (indicator != null) {
                indicator.setText("執行 git fetch...");
            }

            boolean result = executeGitFetchWithTimeout(indicator);
            success.set(result);

        }, "正在更新 Git 資訊", true, project);

        return success.get();
    }

    /**
     * 使用超時機制執行 Git Fetch，確保不會無限期阻塞
     */
    private boolean executeGitFetchWithTimeout(ProgressIndicator indicator) {
        LOG.info("執行 git fetch（超時機制）...");

        VirtualFile projectDir = project.getBaseDir();
        if (projectDir == null) {
            LOG.warn("無法獲取項目根目錄");
            return false;
        }

        LOG.info("項目根目錄: " + projectDir.getPath());
        File workDir = new File(projectDir.getPath());

        try {
            // 創建進程但不在此處等待結果
            ProcessBuilder processBuilder = new ProcessBuilder("git", "fetch", "--prune");
            processBuilder.directory(workDir);
            processBuilder.redirectErrorStream(true);

            // 啟動進程
            Process process = processBuilder.start();

            // 在一個單獨的線程中讀取輸出
            StringBuilder output = new StringBuilder();
            CountDownLatch outputLatch = new CountDownLatch(1);

            Thread outputThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()), BUFFER_SIZE)) {
                    String line;
                    while ((line = reader.readLine()) != null && !Thread.currentThread().isInterrupted()) {
                        output.append(line).append('\n');
                        if (indicator != null) {
                            indicator.setText2(line);
                        }
                    }
                } catch (IOException e) {
                    if (!Thread.currentThread().isInterrupted()) {
                        LOG.error("讀取進程輸出時發生錯誤", e);
                    }
                } finally {
                    outputLatch.countDown();
                }
            }, "Git-Output-Reader");
            outputThread.setDaemon(true); // 設定為 daemon thread，確保不會阻止 JVM 關閉
            outputThread.start();

            // 使用 CompletableFuture 來等待進程完成並獲取結果
            CompletableFuture<Integer> exitCodeFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    boolean completed = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (!completed) {
                        LOG.warn("Git fetch 操作超時，強制終止進程");
                        process.destroyForcibly();
                        return -1;
                    }
                    return process.exitValue();
                } catch (InterruptedException e) {
                    LOG.error("等待 git fetch 進程時被中斷", e);
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                    return -1;
                }
            });

            // 使用更高效的等待機制，避免忙等待
            Integer exitCode = null;
            try {
                // 使用更短的超時時間來檢查取消狀態
                exitCode = exitCodeFuture.get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // 超時是正常的，繼續等待但檢查取消狀態
                while (exitCode == null) {
                    try {
                        // 檢查是否取消
                        if (indicator != null && indicator.isCanceled()) {
                            LOG.info("用戶取消了 Git fetch 操作");
                            process.destroyForcibly();
                            outputThread.interrupt();
                            return false;
                        }
                        
                        // 使用更短的等待時間來提高響應性
                        exitCode = exitCodeFuture.get(200, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException te) {
                        // 繼續等待
                        continue;
                    } catch (InterruptedException ie) {
                        LOG.error("等待 git fetch 進程時被中斷", ie);
                        process.destroyForcibly();
                        outputThread.interrupt();
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            } catch (InterruptedException e) {
                LOG.error("等待 git fetch 進程時被中斷", e);
                process.destroyForcibly();
                outputThread.interrupt();
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException e) {
                LOG.error("Git fetch 進程執行時發生錯誤", e);
                process.destroyForcibly();
                outputThread.interrupt();
                return false;
            }

            // 等待輸出線程完成（設置短超時，避免無限等待）
            try {
                if (!outputLatch.await(1, TimeUnit.SECONDS)) {
                    // 如果輸出線程沒有在 1 秒內完成，嘗試中斷它
                    if (outputThread.isAlive()) {
                        outputThread.interrupt();
                        LOG.warn("輸出線程超時，已中斷");
                    }
                }
            } catch (InterruptedException e) {
                // 確保輸出線程被中斷
                outputThread.interrupt();
                Thread.currentThread().interrupt();
                LOG.warn("等待輸出線程時被中斷");
            }

            boolean success = exitCode == 0;
            if (success) {
                LOG.info("git fetch 執行成功" + (output.length() > 0 ? "，輸出：" + output : "（無輸出）"));
            } else {
                LOG.warn("git fetch 命令執行失敗，退出碼: " + exitCode);
            }

            return success;

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
        List<String> behindBranches = new ArrayList<>();

        if (project == null) {
            LOG.warn("項目為空，無法獲取設置");
            return behindBranches;
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
            return behindBranches;
        }

        // 獲取當前分支名稱
        String currentBranch = getCurrentBranch();
        if (currentBranch == null || currentBranch.isEmpty()) {
            LOG.warn("無法獲取當前分支名稱");
            return behindBranches;
        }

        LOG.info("當前分支: " + currentBranch);

        // 跳過目標分支的檢查
        for (String targetBranch : targetBranches) {
            if (currentBranch.equals(targetBranch)) {
                LOG.info("當前分支就是目標分支之一，跳過檢查");
                return behindBranches;
            }
        }

        // 檢查每個目標分支
        File workDir = new File(projectDir.getPath());
        for (String targetBranchName : targetBranches) {
            if (isBranchBehind(workDir, currentBranch, targetBranchName)) {
                behindBranches.add(targetBranchName);
            }
        }

        return behindBranches;
    }

    /**
     * 獲取當前分支名稱
     *
     * @return 當前分支名稱，如果發生錯誤則返回 null
     */
    @Nullable
    public String getCurrentBranch() {
        VirtualFile projectDir = project.getBaseDir();
        if (projectDir == null) {
            LOG.warn("無法獲取項目根目錄");
            return null;
        }

        ProcessResult result = executeGitCommand(
                new File(projectDir.getPath()),
                "git", "rev-parse", "--abbrev-ref", "HEAD"
        );

        if (result.isSuccess()) {
            return result.getOutput().trim();
        } else {
            LOG.error("獲取當前分支時發生錯誤: " + result.getError());
            return null;
        }
    }

    /**
     * 判斷當前分支是否落後於目標分支
     */
    private boolean isBranchBehind(File workDir, String currentBranch, String targetBranch) {
        // 使用數組形式的命令，避免分支名稱中的特殊字符造成問題
        // 使用 -- 分隔選項和引用，確保 Git 正確解析
        String[] commandArray = new String[] {
            "git", "rev-list", "--count", 
            currentBranch + "..origin/" + targetBranch
        };
        ProcessResult result = executeGitCommand(workDir, commandArray);

        if (result.isSuccess()) {
            String output = result.getOutput().trim();
            if (output != null && !output.isEmpty()) {
                try {
                    int behindCount = Integer.parseInt(output);
                    return behindCount > 0;
                } catch (NumberFormatException e) {
                    LOG.warn("無法解析 git rev-list 輸出: " + output, e);
                }
            }
        } else {
            // 如果第一種方式失敗，嘗試使用更安全的語法
            // 某些特殊分支名稱可能需要完整的引用路徑
            String[] alternativeCommand = new String[] {
                "git", "rev-list", "--count",
                "HEAD..refs/remotes/origin/" + targetBranch
            };
            ProcessResult altResult = executeGitCommand(workDir, alternativeCommand);
            
            if (altResult.isSuccess()) {
                String output = altResult.getOutput().trim();
                if (output != null && !output.isEmpty()) {
                    try {
                        int behindCount = Integer.parseInt(output);
                        return behindCount > 0;
                    } catch (NumberFormatException e) {
                        LOG.warn("無法解析 git rev-list 輸出: " + output, e);
                    }
                }
            } else {
                LOG.error("檢查分支是否落後時發生錯誤: " + result.getError() + 
                         " (備選命令也失敗: " + altResult.getError() + ")");
            }
        }

        return false;
    }

    /**
     * 執行 Git 命令並返回結果
     *
     * @param workDir 工作目錄
     * @param command 命令及參數
     * @return 命令執行結果
     */
    private ProcessResult executeGitCommand(@Nullable File workDir, String... command) {
        Process process = null;
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            if (workDir != null) {
                processBuilder.directory(workDir);
            }

            process = processBuilder.start();

            // 使用單獨的線程讀取標準輸出和錯誤輸出
            CompletableFuture<String> outputFuture = readProcessOutputAsync(process.getInputStream());
            CompletableFuture<String> errorFuture = readProcessOutputAsync(process.getErrorStream());

            // 設置超時，避免進程卡住
            boolean completed = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return new ProcessResult(false, "", "命令執行超時：" + String.join(" ", command));
            }

            // 獲取輸出結果
            String outputStr = outputFuture.get(1, TimeUnit.SECONDS);
            String errorStr = errorFuture.get(1, TimeUnit.SECONDS);

            int exitCode = process.exitValue();
            return new ProcessResult(exitCode == 0, outputStr, errorStr);

        } catch (Exception e) {
            LOG.error("執行命令時發生錯誤: " + String.join(" ", command), e);
            return new ProcessResult(false, "", e.getMessage());
        } finally {
            // 確保進程資源被釋放
            if (process != null) {
                try {
                    process.getInputStream().close();
                    process.getErrorStream().close();
                    process.getOutputStream().close();
                } catch (IOException e) {
                    LOG.warn("關閉進程流時發生錯誤", e);
                }
                process.destroy();
            }
        }
    }

    /**
     * 非阻塞式讀取進程輸出的輔助方法
     */
    private CompletableFuture<String> readProcessOutputAsync(java.io.InputStream inputStream) {
        return CompletableFuture.supplyAsync(() -> {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream), BUFFER_SIZE)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            } catch (IOException e) {
                LOG.warn("讀取進程輸出時發生錯誤", e);
            }
            return sb.toString();
        });
    }

    /**
     * 命令執行結果封裝類
     */
    private static class ProcessResult {
        private final boolean success;
        private final String output;
        private final String error;

        ProcessResult(boolean success, String output, String error) {
            this.success = success;
            this.output = output;
            this.error = error;
        }

        boolean isSuccess() {
            return success;
        }

        String getOutput() {
            return output;
        }

        String getError() {
            return error;
        }
    }
}