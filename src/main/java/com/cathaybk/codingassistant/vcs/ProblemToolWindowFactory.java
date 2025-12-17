package com.cathaybk.codingassistant.vcs;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Code Sentinel 檢查結果工具視窗工廠
 *
 * 此工廠用於靜態註冊工具視窗，取代動態 registerToolWindow() 調用。
 * 工具視窗的內容由 ProblemCollector 動態管理。
 */
public class ProblemToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // 設定工具視窗圖標和標題
        toolWindow.setIcon(AllIcons.Toolwindows.Problems);
        toolWindow.setStripeTitle("Code Sentinel");

        // 工具視窗的內容會由 ProblemCollector.showProblemsInToolWindow() 動態添加
        // 這裡不需要添加初始內容
    }

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        // 預設隱藏，直到 ProblemCollector 發現問題並呼叫 setAvailable(true) 時才顯示
        // 這樣可以避免使用者點開看到空白視窗
        return false;
    }
}
