package com.cathaybk.codingassistant.apicopy.ui;

import com.cathaybk.codingassistant.apicopy.model.ApiFileType;
import com.cathaybk.codingassistant.apicopy.model.ApiInfo;
import com.cathaybk.codingassistant.apicopy.model.DependencyGraph;
import com.cathaybk.codingassistant.apicopy.model.DependencyGraph.DependencyNode;
import com.cathaybk.codingassistant.apicopy.service.ApiCopyService;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.psi.PsiFile;
import com.intellij.ui.CheckBoxList;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * API 預覽對話框
 * 顯示依賴分析結果，允許使用者選擇要複製的檔案
 */
public class ApiPreviewDialog extends DialogWrapper {

    private final Project project;
    private final ApiInfo apiInfo;
    private DependencyGraph dependencyGraph;

    private JPanel mainPanel;
    private JBLabel summaryLabel;
    private CheckBoxList<DependencyNode> fileList;
    private JBTextArea previewArea;
    private JProgressBar progressBar;
    private JButton selectAllButton;
    private JButton deselectAllButton;

    public ApiPreviewDialog(@NotNull Project project, @NotNull ApiInfo apiInfo) {
        super(project, true);
        this.project = project;
        this.apiInfo = apiInfo;

        setTitle("API 依賴預覽 - " + apiInfo.getMsgId());
        setOKButtonText("複製選中的檔案");
        setCancelButtonText("取消");

        init();
        loadDependencies();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        mainPanel = new JPanel(new BorderLayout());
        mainPanel.setPreferredSize(new Dimension(800, 600));

        // 頂部摘要區域
        JPanel topPanel = createTopPanel();
        mainPanel.add(topPanel, BorderLayout.NORTH);

        // 中間分割區域
        JSplitPane splitPane = createSplitPane();
        mainPanel.add(splitPane, BorderLayout.CENTER);

        return mainPanel;
    }

    private JPanel createTopPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(10));

        summaryLabel = new JBLabel("正在分析依賴...");
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD));

        progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);

        panel.add(summaryLabel, BorderLayout.CENTER);
        panel.add(progressBar, BorderLayout.SOUTH);

        return panel;
    }

    private JSplitPane createSplitPane() {
        // 左側：檔案列表
        JPanel leftPanel = createFileListPanel();

        // 右側：預覽區域
        JPanel rightPanel = createPreviewPanel();

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        splitPane.setDividerLocation(350);
        splitPane.setResizeWeight(0.4);

        return splitPane;
    }

    private JPanel createFileListPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(5));

        JLabel title = new JLabel("依賴檔案列表");
        title.setBorder(JBUI.Borders.emptyBottom(5));

        // 檔案列表
        fileList = new CheckBoxList<>();
        fileList.setCheckBoxListListener((index, selected) -> {
            DependencyNode node = fileList.getItemAt(index);
            if (node != null) {
                node.setSelected(selected);
                updatePreview();
            }
        });

        JBScrollPane scrollPane = new JBScrollPane(fileList);

        // 按鈕區域
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        selectAllButton = new JButton("全選");
        selectAllButton.addActionListener(e -> selectAll(true));
        deselectAllButton = new JButton("取消全選");
        deselectAllButton.addActionListener(e -> selectAll(false));

        buttonPanel.add(selectAllButton);
        buttonPanel.add(deselectAllButton);

        panel.add(title, BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createPreviewPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(5));

        JLabel title = new JLabel("內容預覽");
        title.setBorder(JBUI.Borders.emptyBottom(5));

        previewArea = new JBTextArea();
        previewArea.setEditable(false);
        previewArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JBScrollPane scrollPane = new JBScrollPane(previewArea);

        panel.add(title, BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    /**
     * 載入依賴
     */
    private void loadDependencies() {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "分析 API 依賴...", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                ApiCopyService copyService = ApiCopyService.getInstance(project);
                dependencyGraph = copyService.analyzeSync(apiInfo);

                // DialogWrapper 是 modal，使用 ModalityState.any() 確保 runnable 能在對話框顯示期間執行，
                // 否則預設的 NON_MODAL 可能導致 UI 永遠不更新（列表顯示 "Nothing to show"）。
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (isDisposed()) return;
                    progressBar.setVisible(false);
                    if (dependencyGraph != null) {
                        updateUI();
                    } else {
                        summaryLabel.setText("分析失敗");
                    }
                }, ModalityState.any());
            }
        });
    }

    /**
     * 更新 UI
     */
    private void updateUI() {
        if (dependencyGraph == null) return;

        // 更新摘要
        summaryLabel.setText(String.format(
                "API: %s | 檔案數: %d | 行數: %d",
                apiInfo.getMsgId(),
                dependencyGraph.getTotalFileCount(),
                dependencyGraph.getTotalLineCount()
        ));

        // 使用 setItems() 批量更新（取代 clear + addItem 循環）
        List<DependencyNode> nodes = dependencyGraph.getNodes();
        fileList.setItems(nodes, node -> String.format("[%s] %s",
                node.getFileType().getChineseName(),
                node.getDisplayName()));

        // 恢復選中狀態
        for (DependencyNode node : nodes) {
            fileList.setItemSelected(node, node.isSelected());
        }

        // 更新預覽
        updatePreview();
    }

    /**
     * 更新預覽內容
     */
    private void updatePreview() {
        if (dependencyGraph == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append("// ==========================================\n");
        sb.append("// 選中的檔案預覽\n");
        sb.append("// ==========================================\n\n");

        List<DependencyNode> selectedNodes = dependencyGraph.getSelectedNodes();
        for (DependencyNode node : selectedNodes) {
            sb.append("// --- ").append(node.getFileType().getChineseName())
              .append(": ").append(node.getDisplayName()).append(" ---\n");

            String content = node.getContent();
            if (content != null) {
                // 只顯示前 50 行作為預覽
                String[] lines = content.split("\n");
                int maxLines = Math.min(50, lines.length);
                for (int i = 0; i < maxLines; i++) {
                    sb.append(lines[i]).append("\n");
                }
                if (lines.length > maxLines) {
                    sb.append("\n// ... 還有 ").append(lines.length - maxLines).append(" 行 ...\n");
                }
            }
            sb.append("\n");
        }

        previewArea.setText(sb.toString());
        previewArea.setCaretPosition(0);
    }

    /**
     * 全選或取消全選
     */
    private void selectAll(boolean select) {
        if (dependencyGraph == null) return;

        for (int i = 0; i < fileList.getItemsCount(); i++) {
            DependencyNode node = fileList.getItemAt(i);
            if (node != null) {
                node.setSelected(select);
                fileList.setItemSelected(node, select);
            }
        }
        updatePreview();
    }

    @Override
    protected void doOKAction() {
        if (dependencyGraph != null) {
            ApiCopyService copyService = ApiCopyService.getInstance(project);
            copyService.copyToClipboard(dependencyGraph);
        }
        super.doOKAction();
    }
}
