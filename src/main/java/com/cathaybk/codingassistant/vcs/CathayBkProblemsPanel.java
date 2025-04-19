package com.cathaybk.codingassistant.vcs;

import com.cathaybk.codingassistant.common.ProblemInfo;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiInvalidElementAccessException;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 用於顯示國泰規範檢查問題的 UI 面板。
 */
public class CathayBkProblemsPanel extends JPanel {

    private static final Logger LOG = Logger.getInstance(CathayBkProblemsPanel.class);

    private final Project project;
    private List<ProblemInfo> originalProblems;
    private List<ProblemInfo> currentProblems; // 當前顯示的問題 (過濾後)

    private SearchTextField searchField;
    private Tree problemTree;
    private JEditorPane detailsPane;
    private ActionListener quickFixListener;
    private ActionListener fixAllListener;

    public CathayBkProblemsPanel(Project project, List<ProblemInfo> problems) {
        super(new BorderLayout());
        this.project = project;
        this.originalProblems = new ArrayList<>(problems);
        this.currentProblems = new ArrayList<>(problems);
        initializeUI();
        updateToolWindowContentTitle(); // 初始化時更新標題
    }

    // --- Public API ---

    /**
     * 獲取 PSI 元素的行號
     */
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

    private static String escapeHtml(String text) {
        if (text == null)
            return "";
        // Basic HTML escaping
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&#39;")
                .replace("\"", "&quot;");
    }

    /**
     * 設置"快速修復"按鈕的監聽器。
     */
    public void setQuickFixListener(ActionListener listener) {
        this.quickFixListener = listener;
    }

    /**
     * 設置"一鍵修復全部"按鈕的監聽器。
     */
    public void setFixAllListener(ActionListener listener) {
        this.fixAllListener = listener;
    }

    /**
     * 獲取當前選中的問題。
     *
     * @return 選中的 ProblemInfo，如果沒有選中或選中的不是問題節點，則返回 null。
     */
    @Nullable
    public ProblemInfo getSelectedProblemInfo() {
        if (problemTree == null)
            return null;
        TreePath selectionPath = problemTree.getSelectionPath();
        if (selectionPath == null || !(selectionPath.getLastPathComponent() instanceof DefaultMutableTreeNode)) {
            return null;
        }
        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectionPath.getLastPathComponent();
        if (selectedNode.getUserObject() instanceof ProblemInfoNode) {
            return ((ProblemInfoNode) selectedNode.getUserObject()).getProblemInfo();
        }
        return null;
    }

    /**
     * 獲取當前顯示的問題列表 (可能已過濾)。
     */
    public List<ProblemInfo> getCurrentProblems() {
        return currentProblems;
    }

    // --- UI Initialization ---

    /**
     * 刷新問題樹和計數器。
     *
     * @param updatedProblems 最新的問題列表。
     */
    public void refreshProblems(List<ProblemInfo> updatedProblems) {
        this.originalProblems = new ArrayList<>(updatedProblems);
        // 重新應用過濾
        filterTree(this.problemTree, this.searchField != null ? this.searchField.getText() : "");
        updateToolWindowContentTitle(); // 更新標題
    }

    /**
     * 從列表中移除已修復的問題並刷新UI。
     *
     * @param problemToRemove 已修復的問題。
     */
    public void removeProblem(ProblemInfo problemToRemove) {
        boolean removedOriginal = originalProblems.remove(problemToRemove);
        boolean removedCurrent = currentProblems.remove(problemToRemove);

        if (removedOriginal || removedCurrent) {
            // 刷新樹模型
            DefaultTreeModel newModel = buildTreeModel(project, currentProblems);
            problemTree.setModel(newModel);
            TreeUtil.expandAll(problemTree);
            updateToolWindowContentTitle(); // 更新標題

            // 清空詳情面板或顯示摘要
            if (detailsPane != null) {
                updateDetailsPaneForNode(detailsPane, null, currentProblems.size());
            }
        }
    }

    // --- UI Helper Methods --- (filterTree, buildTreeModel, createProblemTree,
    // updateDetailsPane*, etc.)

    private void initializeUI() {
        setBackground(UIManager.getColor("Panel.background"));

        // 水平分割（左右格式）- 7:3比例
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setResizeWeight(0.7); // 設置左側佔70%比重
        splitPane.setBorder(null);
        splitPane.setDividerSize(5);

        // --- Left Panel (Tree) ---
        this.problemTree = createProblemTree(project, currentProblems);
        JBScrollPane treeScrollPane = new JBScrollPane(this.problemTree);
        treeScrollPane.setBorder(JBUI.Borders.empty());

        JPanel leftPanel = new JPanel(new BorderLayout());
        JLabel treeTitle = new JLabel("問題列表（單擊查看詳情，雙擊跳轉到代碼）");
        treeTitle.setBorder(JBUI.Borders.empty(5, 8));
        treeTitle.setFont(treeTitle.getFont().deriveFont(Font.BOLD));

        leftPanel.add(treeTitle, BorderLayout.NORTH);
        leftPanel.add(treeScrollPane, BorderLayout.CENTER);

        // --- Right Panel (Details) ---
        JPanel rightPanel = new JPanel(new BorderLayout());
        JLabel detailsTitle = new JLabel("問題詳情");
        detailsTitle.setBorder(JBUI.Borders.empty(5, 8));
        detailsTitle.setFont(detailsTitle.getFont().deriveFont(Font.BOLD));
        rightPanel.add(detailsTitle, BorderLayout.NORTH);

        this.detailsPane = new JEditorPane();
        this.detailsPane.setEditable(false);
        this.detailsPane.setContentType("text/html");
        this.detailsPane.setBackground(UIManager.getColor("EditorPane.background"));
        updateDetailsPaneForNode(this.detailsPane, null, currentProblems.size()); // 初始顯示摘要

        JBScrollPane detailsScrollPane = new JBScrollPane(this.detailsPane);
        detailsScrollPane.setBorder(JBUI.Borders.empty());
        rightPanel.add(detailsScrollPane, BorderLayout.CENTER);

        // --- Split Pane Setup ---
        splitPane.setLeftComponent(leftPanel);
        splitPane.setRightComponent(rightPanel);
        // 確保面板顯示後，根據實際寬度調整分隔位置
        SwingUtilities.invokeLater(() -> {
            if (splitPane.isDisplayable()) {
                splitPane.setDividerLocation(0.7);
            } else {
                splitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, evt -> {
                    if ("dividerLocation".equals(evt.getPropertyName()) && splitPane.getWidth() > 0) {
                        splitPane.setDividerLocation(0.7);
                        splitPane.removePropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, (e) -> {
                        }); // Remove listener after first adjustment
                    }
                });
            }
        });

        // --- Toolbar ---
        JPanel toolbarPanel = new JPanel(new BorderLayout());
        toolbarPanel.setBorder(JBUI.Borders.empty(6, 10));
        JLabel titleLabel = new JLabel("國泰規範檢查結果");
        titleLabel.setIcon(AllIcons.General.InspectionsEye);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, titleLabel.getFont().getSize() + 2));
        toolbarPanel.add(titleLabel, BorderLayout.WEST);

        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        JButton fixAllButton = new JButton("一鍵修復全部", AllIcons.Actions.QuickfixOffBulb);
        fixAllButton.setToolTipText("嘗試修復所有可自動修復的問題");
        fixAllButton.addActionListener(e -> {
            if (fixAllListener != null) {
                fixAllListener.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "fixAll"));
            }
        });

        JButton quickFixButton = new JButton("快速修復", AllIcons.Actions.QuickfixBulb);
        quickFixButton.setToolTipText("嘗試快速修復所選問題");
        quickFixButton.addActionListener(e -> {
            if (quickFixListener != null) {
                quickFixListener
                        .actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "quickFixSelected"));
            }
        });

        actionsPanel.add(fixAllButton);
        actionsPanel.add(quickFixButton);
        toolbarPanel.add(actionsPanel, BorderLayout.EAST);

        // --- Status Bar ---
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBorder(JBUI.Borders.empty(5, 10, 5, 10));
        JLabel statusLabel = new JLabel("提示: 單擊問題可查看詳情，雙擊問題可直接跳轉到代碼位置");
        statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        statusPanel.add(statusLabel, BorderLayout.CENTER);

        // --- Final Layout ---
        add(toolbarPanel, BorderLayout.NORTH);
        add(splitPane, BorderLayout.CENTER);
        add(statusPanel, BorderLayout.SOUTH);

        // --- Event Listeners ---
        setupTreeListeners();

        // --- Initial Focus ---
        ApplicationManager.getApplication().invokeLater(() -> {
            if (this.problemTree != null) // 如果沒有搜尋框，則聚焦問題樹
                this.problemTree.requestFocusInWindow();
        });
    }

    private void setupTreeListeners() {
        this.problemTree.addTreeSelectionListener(e -> {
            TreePath path = e.getPath();
            if (path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                Object userObject = node.getUserObject();
                if (userObject instanceof ProblemInfoNode) {
                    // 單擊時顯示詳情
                    updateDetailsPane(project, detailsPane, ((ProblemInfoNode) userObject).getProblemInfo());
                } else {
                    updateDetailsPaneForNode(detailsPane, node, currentProblems.size());
                }
            }
        });

        this.problemTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    TreePath path = problemTree.getPathForLocation(e.getX(), e.getY());
                    if (path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode) {
                        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                        if (node.getUserObject() instanceof ProblemInfoNode) {
                            // 雙擊時跳轉到代碼位置
                            navigateToProblem(project, ((ProblemInfoNode) node.getUserObject()).getProblemInfo());
                        }
                    }
                }
            }
        });
    }

    /**
     * 根據搜索文本過濾樹
     */
    private void filterTree(Tree tree, String searchText) {
        if (originalProblems == null || tree == null) {
            return;
        }

        String lowerSearchText = searchText.toLowerCase();

        if (searchText.isEmpty()) {
            currentProblems = new ArrayList<>(originalProblems);
        } else {
            // 移除過濾邏輯中對 searchField 的依賴性，但保留過濾本身
            // （如果希望完全移除過濾功能，需要進一步修改）
            currentProblems = originalProblems.stream()
                    .filter(p -> {
                        if (p == null)
                            return false;
                        // 檢查描述
                        if (p.getDescription() != null && p.getDescription().toLowerCase().contains(lowerSearchText)) {
                            return true;
                        }
                        // 檢查文件名
                        PsiElement element = p.getElement();
                        if (element != null && element.isValid()) {
                            PsiFile file = ReadAction.compute(() -> element.getContainingFile());
                            if (file != null && file.getName().toLowerCase().contains(lowerSearchText)) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .collect(Collectors.toList());
        }

        DefaultTreeModel newModel = buildTreeModel(project, currentProblems);
        tree.setModel(newModel);
        TreeUtil.expandAll(tree);
        updateToolWindowContentTitle(); // 更新標題
    }

    /**
     * 根據問題列表構建樹模型
     */
    private DefaultTreeModel buildTreeModel(Project project, List<ProblemInfo> problems) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("規範問題");
        problems.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(problem -> ReadAction.compute(() -> {
                    PsiElement element = problem.getElement();
                    if (element != null && element.isValid()) {
                        PsiFile file = element.getContainingFile();
                        if (file != null)
                            return file.getName();
                    }
                    return "未知或無效文件";
                })))
                .forEach((fileName, fileProblems) -> {
                    DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(fileName);
                    root.add(fileNode);
                    fileProblems.stream()
                            .filter(p -> p != null
                                    && ReadAction.compute(() -> p.getElement() != null && p.getElement().isValid()))
                            .sorted(Comparator.comparingInt((ProblemInfo p) -> getPriorityForType(p.getHighlightType()))
                                    .thenComparingInt(p -> getLineNumber(project, p.getElement())))
                            .forEach(problem -> {
                                DefaultMutableTreeNode problemNode = new DefaultMutableTreeNode(
                                        new ProblemInfoNode(problem));
                                fileNode.add(problemNode);
                            });
                    if (fileNode.getChildCount() == 0 && !"未知或無效文件".equals(fileName)) {
                        // 如果過濾後文件節點下沒有問題，則移除該文件節點（除非是未知文件組）
                        // root.remove(fileNode); // 暫時註釋，避免在過濾時移除空的文件節點
                    }
                });
        // 如果根節點只有一個子節點（規範問題），並且該子節點是"未知或無效文件"且沒有實際問題，則清空
        if (root.getChildCount() == 1) {
            DefaultMutableTreeNode firstChild = (DefaultMutableTreeNode) root.getFirstChild();
            if ("未知或無效文件".equals(firstChild.getUserObject()) && firstChild.getChildCount() == 0) {
                root.removeAllChildren();
            }
        }
        return new DefaultTreeModel(root);
    }

    /**
     * 更新工具窗口內容標題中的問題數量 (現在使用 currentProblems)
     */
    private void updateToolWindowContentTitle() {
        // 這個方法需要在 Handler 中被調用，因為 Panel 無法直接訪問 ToolWindow
        // 可以通過事件或回調通知 Handler 更新
        // 暫時保留空實現，或在 Handler 中實現
        LOG.debug("Requesting title update for problem count: " + currentProblems.size());
    }

    /**
     * 創建問題樹
     */
    private Tree createProblemTree(Project project, List<ProblemInfo> problems) {
        DefaultTreeModel treeModel = buildTreeModel(project, problems);
        Tree tree = new Tree(treeModel);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                                                          boolean leaf, int row, boolean hasFocus) {
                // 調用父類實現獲取基礎組件
                Component c = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

                // 根據選擇狀態設置正確的前景和背景顏色
                if (sel) {
                    // 強制設置選中時的文本顏色和背景色
                    setForeground(UIManager.getColor("Tree.selectionForeground"));
                    setBackground(UIManager.getColor("Tree.selectionBackground"));
                } else {
                    setForeground(UIManager.getColor("Tree.foreground"));
                    setBackground(UIManager.getColor("Tree.background"));
                }
                // 確保組件是不透明的，背景色才能顯示
                setOpaque(true);

                Object userObject = ((DefaultMutableTreeNode) value).getUserObject();
                if (userObject instanceof ProblemInfoNode) {
                    ProblemInfoNode problemNode = (ProblemInfoNode) userObject;
                    setText(problemNode.getShortDescription());
                    ProblemHighlightType highlightType = problemNode.getProblemInfo().getHighlightType();
                    // 根據問題嚴重性設置圖標
                    if (highlightType == ProblemHighlightType.ERROR)
                        setIcon(AllIcons.General.Error);
                    else if (highlightType == ProblemHighlightType.WARNING
                            || highlightType == ProblemHighlightType.WEAK_WARNING)
                        setIcon(AllIcons.General.Warning);
                    else
                        setIcon(AllIcons.General.Information);
                } else if (userObject instanceof String) {
                    setText(userObject.toString());
                    // 根據節點類型（根、文件、文件夾）設置圖標
                    if (row == 0 && value == tree.getModel().getRoot()) { // 更可靠的根節點判斷
                        setIcon(AllIcons.Nodes.ModuleGroup);
                    } else if (userObject.toString().endsWith(".java")) {
                        setIcon(AllIcons.FileTypes.Java);
                    } else {
                        // 假設非 Java 文件是文件夾節點
                        setIcon(AllIcons.Nodes.Folder);
                    }
                } else {
                    setText("未知節點");
                    setIcon(AllIcons.Actions.Help);
                }
                return this; // 返回 this，因為我們修改了 DefaultTreeCellRenderer 自身
            }
        });
        tree.setBackground(UIManager.getColor("Tree.background")); // 設置樹本身的背景色

        return tree;
    }

    /**
     * 更新詳細信息面板以顯示節點摘要（文件或根節點）
     */
    private void updateDetailsPaneForNode(JEditorPane detailsPane, @Nullable DefaultMutableTreeNode node,
                                          int totalProblemsInCurrentView) {
        if (detailsPane == null)
            return;

        Object userObject = (node != null) ? node.getUserObject() : null;

        StringBuilder summary = new StringBuilder();
        summary.append("<html><body style='margin: 12px;'>");

        if (node == null || node.isRoot()) {
            summary.append("<h2>國泰規範檢查結果</h2>");
            summary.append("<p>總共發現 <b>").append(originalProblems.size()).append("</b> 個問題");
            if (originalProblems.size() != totalProblemsInCurrentView) {
                summary.append("（當前視圖顯示 <b>").append(totalProblemsInCurrentView).append("</b> 個）");
            }
            summary.append("。</p>");
            if (originalProblems != null && !originalProblems.isEmpty()) {
                long fileCount = originalProblems.stream()
                        .map(p -> ReadAction.compute(() -> p.getElement() != null && p.getElement().isValid()
                                ? p.getElement().getContainingFile()
                                : null))
                        .filter(Objects::nonNull)
                        .distinct()
                        .count();
                summary.append("<p>分布在 <b>").append(fileCount).append("</b> 個文件中。</p>");
            }
            summary.append("<p>請選擇左側的文件或問題查看詳細信息。</p>");
        } else if (userObject instanceof String) { // File node
            String fileName = (String) userObject;
            int childCount = node.getChildCount();
            summary.append("<h2>").append(escapeHtml(fileName)).append("</h2>");
            summary.append("<p>此文件中發現 <b>").append(childCount).append("</b> 個問題</p>");
            if (childCount > 0) {
                summary.append("<p>點擊左側問題查看詳情，或雙擊問題跳轉到代碼。</p>");
            }
        } else if (userObject instanceof ProblemInfoNode) {
            updateDetailsPane(project, detailsPane, ((ProblemInfoNode) userObject).getProblemInfo());
            return;
        } else {
            summary.append("<p>請選擇一個問題查看詳細信息。</p>");
        }

        summary.append("</body></html>");
        detailsPane.setText(summary.toString());
        detailsPane.setCaretPosition(0);
    }

    /**
     * 更新詳細信息面板以顯示具體問題
     */
    private void updateDetailsPane(Project project, JEditorPane detailsPane, ProblemInfo problem) {
        if (problem == null || detailsPane == null)
            return;

        // --- 恢復 Theme Colors 計算 ---
        Color background = UIManager.getColor("EditorPane.background");
        Color foreground = UIManager.getColor("EditorPane.foreground");
        boolean isDarkTheme = isDarkTheme(background); // 假設 isDarkTheme 存在
        String bgColorHex = colorToHex(background); // 假設 colorToHex 存在
        String fgColorHex = colorToHex(foreground);
        String headerColor = isDarkTheme ? "#dadada" : "#1a1a1a";
        String codeBackground = colorToHex(
                UIManager.getColor(isDarkTheme ? "EditorPane.background" : "TextArea.background"));
        String codeBorder = colorToHex(UIManager.getColor("Component.borderColor"));
        String boxBackground = colorToHex(
                UIManager.getColor(isDarkTheme ? "Panel.background" : "TextField.background"));
        String boxBorder = colorToHex(UIManager.getColor("Component.borderColor"));
        String codeColor = fgColorHex;

        StringBuilder html = new StringBuilder();
        // 使用固定的 'sans-serif' 字體，並確保顏色值有效
        html.append("<html><body style='font-family: sans-serif; margin: 10px; background-color: ")
                .append(ensureHexPrefix(bgColorHex)).append("; color: ").append(ensureHexPrefix(fgColorHex))
                .append(";'>"); // 使用 ensureHexPrefix

        html.append("<h2 style='margin-top:0; margin-bottom:10px; color:").append(ensureHexPrefix(headerColor))
                .append(";'>問題詳情</h2>"); // 使用 ensureHexPrefix

        // --- Severity ---
        ProblemHighlightType highlightType = problem.getHighlightType();
        String severityColor = highlightType == ProblemHighlightType.ERROR ? "#ff5261"
                : (highlightType == ProblemHighlightType.WARNING || highlightType == ProblemHighlightType.WEAK_WARNING)
                ? "#eea800"
                : "#589df6"; // severityColor 已是 hex
        String severityText = highlightType == ProblemHighlightType.ERROR ? "錯誤"
                : (highlightType == ProblemHighlightType.WARNING || highlightType == ProblemHighlightType.WEAK_WARNING)
                ? "警告"
                : "資訊";

        // 再次嘗試移除 border-radius
        html.append("<div style='margin-bottom:15px; padding:8px; border:1px solid ").append(ensureHexPrefix(boxBorder))
                .append("; background-color:").append(ensureHexPrefix(boxBackground)).append(";'>"); // 移除 border-radius
        html.append("<p style='margin:0 0 5px 0;'><b style='color:").append(severityColor).append(";'>") // severityColor
                // 已是 hex
                .append(severityText).append(":</b></p>");
        html.append("<p style='margin:0;'>").append(escapeHtml(problem.getDescription())).append("</p>");
        html.append("</div>");

        // --- File and Line ---
        PsiElement element = problem.getElement();
        String fileName = ReadAction.compute(() -> {
            PsiFile file = (element != null && element.isValid()) ? element.getContainingFile() : null;
            return file != null ? file.getName() : null;
        });

        // 恢復樣式
        if (fileName != null) {
            html.append("<p style='margin:0 0 5px 0;'><b>文件:</b> ").append(escapeHtml(fileName)).append("</p>");
            int lineNumber = getLineNumber(project, element);
            if (lineNumber > 0) {
                html.append("<p style='margin:0 0 10px 0;'><b>行號:</b> ").append(lineNumber).append("</p>");
            }
        } else {
            html.append("<p style='margin:0 0 10px 0; color:#888;'><b>文件:</b> 無法確定</p>"); // 可以保留這個簡單樣式
        }

        // --- Code Snippet ---
        if (element != null && element.isValid()) {
            // 恢復樣式
            html.append("<p style='margin:10px 0 5px 0;'><b>代碼片段:</b></p>");
            String elementText = ReadAction.compute(() -> {
                try {
                    return element.getText();
                } catch (Exception e) {
                    return null;
                }
            });
            if (elementText != null) {
                // 再次嘗試移除 border-radius
                html.append("<pre style='padding:8px; background-color:").append(ensureHexPrefix(codeBackground))
                        .append("; border:1px solid ").append(ensureHexPrefix(codeBorder))
                        .append("; margin:0; white-space: pre-wrap; word-wrap: break-word; color:")
                        .append(ensureHexPrefix(codeColor)).append(";'>") // 移除 border-radius
                        .append(escapeHtml(elementText))
                        .append("</pre>");
            } else {
                html.append("<p style='color:red;'>無法顯示代碼片段</p>");
                LOG.warn("無法獲取問題元素的文本: " + problem.getDescription());
            }
        }

        // --- Suggestion ---
        if (problem.getSuggestionSource() != null || problem.getSuggestedValue() != null) {
            // 恢復樣式
            html.append("<p style='margin:15px 0 5px 0;'><b>建議:</b></p>");
            // 再次嘗試移除 border-radius
            html.append("<div style='padding:8px; background-color:").append(ensureHexPrefix(boxBackground))
                    .append("; border:1px solid ").append(ensureHexPrefix(boxBorder))
                    .append("; margin:0;'>"); // 移除 border-radius
            if (problem.getSuggestionSource() != null) {
                html.append("<p style='margin:0 0 5px 0;'>來源: ").append(escapeHtml(problem.getSuggestionSource()))
                        .append("</p>");
            }
            if (problem.getSuggestedValue() != null) {
                // 再次嘗試移除 border-radius
                html.append("<p style='margin:0;'>建議值:</p><pre style='padding: 5px; background-color: ")
                        .append(ensureHexPrefix(codeBackground))
                        .append("; margin: 5px 0 0 0; white-space: pre-wrap; word-wrap: break-word; color:")
                        .append(ensureHexPrefix(codeColor)).append(";'>") // 移除 border-radius
                        .append(escapeHtml(problem.getSuggestedValue()))
                        .append("</pre>");
            }
            html.append("</div>");
        } else {
            // 恢復樣式
            html.append("<p style='margin:15px 0 5px 0;'><b>修復建議:</b> 請根據問題描述手動修改代碼。</p>");
        }

        html.append("</body></html>");
        // 保留 try-catch
        try {
            detailsPane.setText(html.toString());
        } catch (Exception e) {
            LOG.error("設置 JEditorPane 文本時出錯. HTML:\n" + html.toString(), e);
            detailsPane.setText(
                    "<html><body style='font-family: sans-serif; margin: 10px;'>加載問題詳情時發生內部錯誤，請檢查IDE日誌獲取更多信息。</body></html>");
        }
        detailsPane.setCaretPosition(0); // Scroll to top
    }

    /**
     * 導航到問題位置
     */
    private void navigateToProblem(Project project, ProblemInfo problem) {
        ReadAction.run(() -> {
            PsiElement element = problem.getElement();
            if (element != null && element.isValid()) {
                PsiFile containingFile = element.getContainingFile();
                if (containingFile != null) {
                    VirtualFile virtualFile = containingFile.getVirtualFile();
                    if (virtualFile != null && virtualFile.isValid()) {
                        int offset = element.getTextOffset();
                        OpenFileDescriptor descriptor = new OpenFileDescriptor(project, virtualFile, offset);
                        ApplicationManager.getApplication().invokeLater(
                                () -> FileEditorManager.getInstance(project).openTextEditor(descriptor, true));
                    }
                }
            } else {
                LOG.warn("無法導航到無效的問題元素: " + problem.getDescription());
                ApplicationManager.getApplication()
                        .invokeLater(() -> Messages.showWarningDialog(project, "無法導航到選定的問題，對應的程式碼可能已變更或失效。", "導航失敗"));
            }
        });
    }

    // --- Theme Helper Methods ---
    private int getPriorityForType(ProblemHighlightType type) {
        if (type == ProblemHighlightType.ERROR)
            return 0;
        if (type == ProblemHighlightType.WARNING)
            return 1;
        if (type == ProblemHighlightType.WEAK_WARNING)
            return 2;
        return 3; // 信息和其他
    }

    private boolean isDarkTheme(Color background) {
        if (background == null)
            return false;
        float[] hsb = Color.RGBtoHSB(background.getRed(), background.getGreen(), background.getBlue(), null);
        return hsb[2] < 0.5f;
    }

    private String colorToHex(Color color) {
        if (color == null)
            return "#ffffff"; // Default to white
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    // 確保 ensureHexPrefix 方法存在且正確
    private String ensureHexPrefix(String colorHex) {
        if (colorHex == null) {
            LOG.warn("ensureHexPrefix received null color, defaulting to #ffffff");
            return "#ffffff";
        }
        String trimmedHex = colorHex.trim();
        if (trimmedHex.startsWith("#")) {
            if (trimmedHex.matches("#[0-9a-fA-F]{6}")) {
                return trimmedHex;
            } else {
                // 移除錯誤插入的程式碼，修正為記錄錯誤並返回預設值
                LOG.warn("Invalid hex color format (starts with # but invalid): " + trimmedHex
                        + ", defaulting to #ffffff");
                return "#ffffff";
            }
        } else {
            // 嘗試添加 '#'，並驗證
            String prefixedHex = "#" + trimmedHex;
            if (prefixedHex.matches("#[0-9a-fA-F]{6}")) {
                return prefixedHex;
            } else {
                LOG.warn("Invalid hex color format (after adding # or originally invalid): " + colorHex
                        + ", defaulting to #ffffff");
                return "#ffffff";
            }
        }
    }

    /**
     * 問題樹節點的包裝類 - 恢復完整定義
     */
    public static class ProblemInfoNode {
        private final ProblemInfo problemInfo;

        public ProblemInfoNode(ProblemInfo problemInfo) {
            this.problemInfo = problemInfo;
        }

        public ProblemInfo getProblemInfo() {
            return problemInfo;
        }

        public String getShortDescription() {
            String desc = problemInfo.getDescription();
            String safeDesc = desc != null ? desc : "描述為空";
            // 使用外部類名調用靜態方法
            int lineNumber = CathayBkProblemsPanel.getLineNumber(
                    problemInfo.getElement() != null ? problemInfo.getElement().getProject() : null,
                    problemInfo.getElement());
            String lineSuffix = (lineNumber > 0) ? " (第 " + lineNumber + " 行)" : "";
            final int MAX_LEN = 80;
            if (safeDesc.length() > MAX_LEN - lineSuffix.length()) {
                int endIndex = MAX_LEN - lineSuffix.length() - 3;
                safeDesc = safeDesc.substring(0, Math.max(0, endIndex)) + "...";
            }
            return safeDesc + lineSuffix;
        }

        @Override
        public String toString() {
            return getShortDescription();
        }
    }
}