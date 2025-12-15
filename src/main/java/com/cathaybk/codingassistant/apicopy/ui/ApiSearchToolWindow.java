package com.cathaybk.codingassistant.apicopy.ui;

import com.cathaybk.codingassistant.apicopy.model.ApiInfo;
import com.cathaybk.codingassistant.apicopy.service.ApiCopyService;
import com.cathaybk.codingassistant.apicopy.service.ApiIndexService;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.psi.PsiMethod;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import java.awt.*;
import java.awt.event.*;
import java.util.List;

/**
 * API 搜尋工具視窗
 */
public class ApiSearchToolWindow implements Disposable {

    private final Project project;
    private final ToolWindow toolWindow;

    private JPanel mainPanel;
    private SearchTextField searchField;
    private JBTable apiTable;
    private ApiListTableModel tableModel;
    private JLabel statusLabel;
    private JButton copyButton;
    private JButton previewButton;
    private JButton refreshButton;

    private Timer searchDebounceTimer;
    private static final int SEARCH_DEBOUNCE_MS = 300;

    public ApiSearchToolWindow(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        this.project = project;
        this.toolWindow = toolWindow;
        Disposer.register(project, this);
        initializeUI();
        loadAllApis();
    }

    private void initializeUI() {
        mainPanel = new JPanel(new BorderLayout());

        // 頂部搜尋區域
        JPanel topPanel = createTopPanel();
        mainPanel.add(topPanel, BorderLayout.NORTH);

        // 中間表格區域
        JPanel tablePanel = createTablePanel();
        mainPanel.add(tablePanel, BorderLayout.CENTER);

        // 底部按鈕區域
        JPanel bottomPanel = createBottomPanel();
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);
    }

    private JPanel createTopPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(5));

        // 搜尋框
        searchField = new SearchTextField();
        searchField.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                scheduleSearch();
            }
        });

        // 重新整理按鈕
        refreshButton = new JButton("重新索引");
        refreshButton.addActionListener(e -> refreshIndex());

        JPanel searchPanel = new JPanel(new BorderLayout(5, 0));
        searchPanel.add(new JLabel("搜尋 API: "), BorderLayout.WEST);
        searchPanel.add(searchField, BorderLayout.CENTER);
        searchPanel.add(refreshButton, BorderLayout.EAST);

        panel.add(searchPanel, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createTablePanel() {
        JPanel panel = new JPanel(new BorderLayout());

        tableModel = new ApiListTableModel();
        apiTable = new JBTable(tableModel);
        apiTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        apiTable.setRowHeight(25);

        // 設定欄位寬度
        apiTable.getColumnModel().getColumn(0).setPreferredWidth(150);  // MSGID
        apiTable.getColumnModel().getColumn(1).setPreferredWidth(250);  // 描述
        apiTable.getColumnModel().getColumn(2).setPreferredWidth(80);   // HTTP 方法
        apiTable.getColumnModel().getColumn(3).setPreferredWidth(200);  // 路徑

        // 雙擊跳轉到原始碼
        apiTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    navigateToSource();
                }
            }
        });

        // 監聽選擇變化
        apiTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateButtonState();
            }
        });

        JBScrollPane scrollPane = new JBScrollPane(apiTable);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(5));

        // 狀態標籤
        statusLabel = new JLabel("就緒");

        // 按鈕區域
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));

        previewButton = new JButton("預覽...");
        previewButton.setEnabled(false);
        previewButton.addActionListener(e -> showPreviewDialog());

        copyButton = new JButton("複製完整程式碼");
        copyButton.setEnabled(false);
        copyButton.addActionListener(e -> copySelectedApi());

        buttonPanel.add(previewButton);
        buttonPanel.add(copyButton);

        panel.add(statusLabel, BorderLayout.WEST);
        panel.add(buttonPanel, BorderLayout.EAST);

        return panel;
    }

    /**
     * 排程搜尋（防抖）
     */
    private void scheduleSearch() {
        if (searchDebounceTimer != null) {
            searchDebounceTimer.stop();
        }

        searchDebounceTimer = new Timer(SEARCH_DEBOUNCE_MS, e -> performSearch());
        searchDebounceTimer.setRepeats(false);
        searchDebounceTimer.start();
    }

    /**
     * 執行搜尋
     */
    private void performSearch() {
        String keyword = searchField.getText().trim();
        statusLabel.setText("搜尋中...");

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            ApiIndexService indexService = ApiIndexService.getInstance(project);
            List<ApiInfo> results;

            if (keyword.isEmpty()) {
                results = indexService.getAllApis();
            } else {
                results = indexService.searchApis(keyword);
            }

            ApplicationManager.getApplication().invokeLater(() -> {
                tableModel.setApis(results);
                statusLabel.setText("找到 " + results.size() + " 個 API");
                updateButtonState();
            });
        });
    }

    /**
     * 載入所有 API
     */
    private void loadAllApis() {
        statusLabel.setText("載入中...");

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            ApiIndexService indexService = ApiIndexService.getInstance(project);
            List<ApiInfo> allApis = indexService.getAllApis();

            ApplicationManager.getApplication().invokeLater(() -> {
                tableModel.setApis(allApis);
                statusLabel.setText("共 " + allApis.size() + " 個 API");
            });
        });
    }

    /**
     * 重新建立索引
     */
    private void refreshIndex() {
        statusLabel.setText("重新索引中...");
        refreshButton.setEnabled(false);

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            ApiIndexService indexService = ApiIndexService.getInstance(project);
            indexService.reindex();
            List<ApiInfo> allApis = indexService.getAllApis();

            ApplicationManager.getApplication().invokeLater(() -> {
                tableModel.setApis(allApis);
                statusLabel.setText("索引完成，共 " + allApis.size() + " 個 API");
                refreshButton.setEnabled(true);
            });
        });
    }

    /**
     * 更新按鈕狀態
     */
    private void updateButtonState() {
        int selectedRow = apiTable.getSelectedRow();
        boolean hasSelection = selectedRow >= 0;
        copyButton.setEnabled(hasSelection);
        previewButton.setEnabled(hasSelection);
    }

    /**
     * 跳轉到原始碼
     */
    private void navigateToSource() {
        int selectedRow = apiTable.getSelectedRow();
        if (selectedRow < 0) return;

        ApiInfo api = tableModel.getApiAt(selectedRow);
        if (api != null && api.isValid()) {
            PsiMethod method = api.getMethod();
            if (method != null) {
                method.navigate(true);
            }
        }
    }

    /**
     * 顯示預覽對話框
     */
    private void showPreviewDialog() {
        int selectedRow = apiTable.getSelectedRow();
        if (selectedRow < 0) return;

        ApiInfo api = tableModel.getApiAt(selectedRow);
        if (api != null) {
            ApiPreviewDialog dialog = new ApiPreviewDialog(project, api);
            dialog.show();
        }
    }

    /**
     * 複製選中的 API
     */
    private void copySelectedApi() {
        int selectedRow = apiTable.getSelectedRow();
        if (selectedRow < 0) return;

        ApiInfo api = tableModel.getApiAt(selectedRow);
        if (api != null) {
            statusLabel.setText("分析依賴中...");
            copyButton.setEnabled(false);

            ApiCopyService copyService = ApiCopyService.getInstance(project);
            copyService.analyzeAndCopy(api);

            // 恢復按鈕狀態
            ApplicationManager.getApplication().invokeLater(() -> {
                statusLabel.setText("複製完成");
                copyButton.setEnabled(true);
            });
        }
    }

    /**
     * 取得主面板
     */
    public JPanel getContent() {
        return mainPanel;
    }

    @Override
    public void dispose() {
        if (searchDebounceTimer != null) {
            searchDebounceTimer.stop();
        }
    }
}
