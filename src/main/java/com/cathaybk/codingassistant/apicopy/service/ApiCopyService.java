package com.cathaybk.codingassistant.apicopy.service;

import com.cathaybk.codingassistant.apicopy.analysis.ApiDependencyAnalyzer;
import com.cathaybk.codingassistant.apicopy.model.ApiInfo;
import com.cathaybk.codingassistant.apicopy.model.DependencyGraph;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.datatransfer.StringSelection;
import java.util.function.Consumer;

/**
 * API 複製服務
 * 負責協調依賴分析和複製操作
 */
@Service(Service.Level.PROJECT)
public final class ApiCopyService implements Disposable {
    private static final Logger LOG = Logger.getInstance(ApiCopyService.class);

    private static final String NOTIFICATION_GROUP = "Code Sentinel";
    private static final String DEFAULT_PACKAGE_PREFIX = "com.";

    private final Project project;
    private String projectPackagePrefix = DEFAULT_PACKAGE_PREFIX;

    public static ApiCopyService getInstance(@NotNull Project project) {
        return project.getService(ApiCopyService.class);
    }

    public ApiCopyService(@NotNull Project project) {
        this.project = project;
        Disposer.register(project, this);
    }

    /**
     * 設定專案套件前綴（用於判斷專案類別）
     */
    public void setProjectPackagePrefix(@NotNull String prefix) {
        this.projectPackagePrefix = prefix;
    }

    /**
     * 取得專案套件前綴
     */
    @NotNull
    public String getProjectPackagePrefix() {
        return projectPackagePrefix;
    }

    /**
     * 分析 API 依賴（在背景執行）
     *
     * @param apiInfo  API 資訊
     * @param callback 完成後的回調（在 EDT 執行）
     */
    public void analyzeAsync(@NotNull ApiInfo apiInfo,
                             @NotNull Consumer<DependencyGraph> callback) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "分析 API 依賴...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                indicator.setText("正在分析: " + apiInfo.getMsgId());
                indicator.setFraction(0.1);

                ApiDependencyAnalyzer analyzer = new ApiDependencyAnalyzer(project, projectPackagePrefix);

                indicator.setText("追蹤依賴關係...");
                indicator.setFraction(0.3);

                DependencyGraph graph = analyzer.analyze(apiInfo);

                indicator.setFraction(1.0);

                // 在 EDT 中執行回調，使用 ModalityState.any() 支援 modal dialog
                ApplicationManager.getApplication()
                        .invokeLater(() -> callback.accept(graph), ModalityState.any());
            }
        });
    }

    /**
     * 分析 API 依賴（同步執行）
     */
    @Nullable
    public DependencyGraph analyzeSync(@NotNull ApiInfo apiInfo) {
        ApiDependencyAnalyzer analyzer = new ApiDependencyAnalyzer(project, projectPackagePrefix);
        return analyzer.analyze(apiInfo);
    }

    /**
     * 複製依賴圖的內容到剪貼簿
     */
    public void copyToClipboard(@NotNull DependencyGraph graph) {
        String content = graph.generateMergedContent();
        CopyPasteManager.getInstance().setContents(new StringSelection(content));

        int fileCount = graph.getSelectedNodes().size();
        showNotification(
                "已複製 API 程式碼",
                String.format("已複製 %s 的 %d 個檔案到剪貼簿",
                        graph.getApiInfo().getMsgId(), fileCount),
                NotificationType.INFORMATION
        );

        LOG.info("已複製 API 程式碼: " + graph.getApiInfo().getMsgId() +
                ", 檔案數: " + fileCount);
    }

    /**
     * 分析並複製（一站式操作）
     */
    public void analyzeAndCopy(@NotNull ApiInfo apiInfo) {
        analyzeAsync(apiInfo, graph -> {
            if (graph != null) {
                copyToClipboard(graph);
            } else {
                showNotification(
                        "分析失敗",
                        "無法分析 API 依賴: " + apiInfo.getMsgId(),
                        NotificationType.ERROR
                );
            }
        });
    }

    /**
     * 顯示通知
     */
    private void showNotification(@NotNull String title,
                                  @NotNull String content,
                                  @NotNull NotificationType type) {
        try {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification(title, content, type)
                    .notify(project);
        } catch (Exception e) {
            // 如果通知群組不存在，使用日誌記錄
            LOG.info(title + ": " + content);
        }
    }

    @Override
    public void dispose() {
        LOG.info("ApiCopyService 已釋放");
    }
}
