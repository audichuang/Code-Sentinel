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
import com.intellij.openapi.util.Disposer;
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
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.ui.UIUtil;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.panels.NonOpaquePanel;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 用於顯示 Code Sentinel 檢查問題的 UI 面板。
 * 提供問題列表的樹狀視圖，並允許用戶查看詳細信息和導航到程式碼。
 */
public class CathayBkProblemsPanel extends JPanel implements com.intellij.openapi.Disposable {

    private static final Logger LOG = Logger.getInstance(CathayBkProblemsPanel.class);
    private static final int HTML_BUFFER_SIZE = 1024;
    private static final int MAX_DETAIL_TEXT_LENGTH = 80;

    private final WeakReference<Project> projectRef;
    // 使用不可變列表存儲原始問題
    private final List<ProblemInfo> originalProblems;

    // 使用過濾器模式替代重複列表
    private Predicate<ProblemInfo> currentFilter = p -> true;

    private SearchTextField searchField;
    private Tree problemTree;
    private JEditorPane detailsPane;
    private ActionListener quickFixListener;
    private ActionListener fixAllListener;

    // 防止重複更新的標記
    private final AtomicBoolean isUpdating = new AtomicBoolean(false);

    // 使用強引用存儲 Listener，在 dispose() 中正確清理
    // 不使用 WeakReference 是因為它可能在清理前被 GC 回收，導致 Listener 無法移除
    private final List<MouseListener> registeredMouseListeners = new ArrayList<>();
    private final List<TreeSelectionListener> registeredTreeListeners = new ArrayList<>();

    public CathayBkProblemsPanel(Project project, List<ProblemInfo> problems) {
        super(new BorderLayout());
        this.projectRef = new WeakReference<>(project);
        // 創建不可變副本避免外部修改
        this.originalProblems = Collections.unmodifiableList(new ArrayList<>(problems));
        initializeUI();
    }

    // --- Public API ---

    /**
     * 獲取 PSI 元素的行號 (優化版)
     */
    private static int getLineNumber(@Nullable Project project, @Nullable PsiElement element) {
        if (element == null || project == null) {
            return -1;
        }

        Boolean isValid = ReadAction.compute(() -> {
            try {
                return element.isValid();
            } catch (Exception e) {
                LOG.debug("檢查元素有效性時出錯", e);
                return false;
            }
        });

        if (!isValid) {
            return -1;
        }

        return ReadAction.compute(() -> {
            try {
                PsiFile containingFile = element.getContainingFile();
                if (containingFile == null) {
                    return -1;
                }

                Document document = PsiDocumentManager.getInstance(project).getDocument(containingFile);
                if (document != null) {
                    int offset = element.getTextOffset();
                    if (offset >= 0 && offset <= document.getTextLength()) {
                        return document.getLineNumber(offset) + 1;
                    }
                }
            } catch (IndexOutOfBoundsException | PsiInvalidElementAccessException e) {
                LOG.warn("獲取行號時出錯: " + e.getMessage());
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
     * 獲取當前過濾後的問題列表
     */
    public List<ProblemInfo> getCurrentProblems() {
        return originalProblems.stream()
                .filter(currentFilter)
                .collect(Collectors.toList());
    }

    /**
     * 刷新問題樹和計數器。
     *
     * @param updatedProblems 最新的問題列表。
     */
    public void refreshProblems(List<ProblemInfo> updatedProblems) {
        if (isUpdating.getAndSet(true)) {
            return; // 避免重複更新
        }

        try {
            // 應用新的過濾器並重建樹
            ApplicationManager.getApplication().invokeLater(() -> {
                if (problemTree != null) {
                    problemTree.setModel(buildTreeModel(getCurrentProblems()));
                    TreeUtil.expandAll(problemTree);

                    // 清空詳情面板或顯示摘要
                    if (detailsPane != null) {
                        updateDetailsPaneForNode(null, getCurrentProblems().size());
                    }
                }
            });
        } finally {
            isUpdating.set(false);
        }
    }

    /**
     * 從列表中移除已修復的問題並刷新UI。
     *
     * @param problemToRemove 已修復的問題。
     */
    public void removeProblem(ProblemInfo problemToRemove) {
        // 由於使用過濾器模式，無法直接修改問題列表
        // 採用重新構建過濾器的方式，排除已修復的問題
        Predicate<ProblemInfo> excludeRemoved = p -> !p.equals(problemToRemove);
        currentFilter = currentFilter.and(excludeRemoved);

        // 刷新 UI
        ApplicationManager.getApplication().invokeLater(() -> {
            // 更新樹模型
            DefaultTreeModel newModel = buildTreeModel(getCurrentProblems());
            problemTree.setModel(newModel);
            TreeUtil.expandAll(problemTree);

            // 清空詳情面板或顯示摘要
            if (detailsPane != null) {
                updateDetailsPaneForNode(null, getCurrentProblems().size());
            }
        });
    }

    // --- UI Initialization ---

    private void initializeUI() {
        // 使用 IntelliJ 的顏色主題
        setBackground(UIUtil.getPanelBackground());

        // 使用 OnePixelSplitter 替代 JSplitPane (更輕量級)
        OnePixelSplitter splitPane = new OnePixelSplitter(false, 0.7f);
        splitPane.setHonorComponentsMinimumSize(true);

        // --- Left Panel (Tree) ---
        this.problemTree = createProblemTree(getCurrentProblems());
        JBScrollPane treeScrollPane = new JBScrollPane(this.problemTree);
        treeScrollPane.setBorder(JBUI.Borders.empty());

        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setBackground(UIUtil.getPanelBackground());
        
        // 使用 JBLabel 替代 JLabel
        JBLabel treeTitle = new JBLabel("問題列表（單擊查看詳情，雙擊跳轉到代碼）");
        treeTitle.setBorder(JBUI.Borders.empty(5, 8));
        treeTitle.setFont(UIUtil.getLabelFont(UIUtil.FontSize.NORMAL).deriveFont(Font.BOLD));
        treeTitle.setForeground(UIUtil.getLabelForeground());

        leftPanel.add(treeTitle, BorderLayout.NORTH);
        leftPanel.add(treeScrollPane, BorderLayout.CENTER);

        // --- Right Panel (Details) ---
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setBackground(UIUtil.getPanelBackground());
        JBLabel detailsTitle = new JBLabel("問題詳情");
        detailsTitle.setBorder(JBUI.Borders.empty(5, 8));
        detailsTitle.setFont(UIUtil.getLabelFont(UIUtil.FontSize.NORMAL).deriveFont(Font.BOLD));
        detailsTitle.setForeground(UIUtil.getLabelForeground());
        rightPanel.add(detailsTitle, BorderLayout.NORTH);

        this.detailsPane = new JEditorPane();
        this.detailsPane.setEditable(false);
        this.detailsPane.setContentType("text/html");
        this.detailsPane.setBackground(UIUtil.getEditorPaneBackground());
        this.detailsPane.setFont(UIUtil.getLabelFont());
        // 設置 HTML 樣式以匹配 IDE 主題
        String cssStyle = String.format(
            "<style>body { font-family: %s; font-size: %dpt; color: %s; }</style>",
            UIUtil.getLabelFont().getFamily(),
            UIUtil.getLabelFont().getSize(),
            ColorUtil.toHtmlColor(UIUtil.getLabelForeground())
        );
        updateDetailsPaneForNode(null, getCurrentProblems().size()); // 初始顯示摘要

        JBScrollPane detailsScrollPane = new JBScrollPane(this.detailsPane);
        detailsScrollPane.setBorder(JBUI.Borders.empty());
        rightPanel.add(detailsScrollPane, BorderLayout.CENTER);

        // --- Split Pane Setup ---
        splitPane.setFirstComponent(leftPanel);
        splitPane.setSecondComponent(rightPanel);

        // --- Toolbar ---
        JPanel toolbarPanel = new JPanel(new BorderLayout());
        toolbarPanel.setBackground(UIUtil.getPanelBackground());
        toolbarPanel.setBorder(JBUI.Borders.empty(6, 10));
        JBLabel titleLabel = new JBLabel("Code Sentinel 檢查結果");
        titleLabel.setIcon(AllIcons.General.InspectionsEye);
        titleLabel.setFont(UIUtil.getLabelFont(UIUtil.FontSize.NORMAL).deriveFont(Font.BOLD, UIUtil.getLabelFont().getSize() + 2));
        titleLabel.setForeground(UIUtil.getLabelForeground());
        toolbarPanel.add(titleLabel, BorderLayout.WEST);

        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(5), 0));
        actionsPanel.setOpaque(false);
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
                quickFixListener.actionPerformed(
                        new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "quickFixSelected"));
            }
        });
        
        // 使用 IntelliJ 的按鈕樣式
        fixAllButton.putClientProperty("JButton.buttonType", "segmented-only");
        quickFixButton.putClientProperty("JButton.buttonType", "segmented-only");

        actionsPanel.add(fixAllButton);
        actionsPanel.add(quickFixButton);
        toolbarPanel.add(actionsPanel, BorderLayout.EAST);

        // --- Status Bar ---
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBackground(UIUtil.getPanelBackground());
        statusPanel.setBorder(JBUI.Borders.empty(5, 10, 5, 10));
        JBLabel statusLabel = new JBLabel("提示: 單擊問題可查看詳情，雙擊問題可直接跳轉到代碼位置");
        statusLabel.setForeground(UIUtil.getContextHelpForeground());
        statusPanel.add(statusLabel, BorderLayout.CENTER);

        // --- Final Layout ---
        add(toolbarPanel, BorderLayout.NORTH);
        add(splitPane, BorderLayout.CENTER);
        add(statusPanel, BorderLayout.SOUTH);

        // --- Event Listeners ---
        setupTreeListeners();
    }

    private void setupTreeListeners() {
        TreeSelectionListener selectionListener = e -> {
            TreePath path = e.getPath();
            if (path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                Object userObject = node.getUserObject();

                Project project = projectRef.get();
                if (project == null) return;

                if (userObject instanceof ProblemInfoNode) {
                    // 單擊時顯示詳情
                    updateDetailsPane(project, ((ProblemInfoNode) userObject).getProblemInfo());
                } else {
                    updateDetailsPaneForNode(node, getCurrentProblems().size());
                }
            }
        };

        this.problemTree.addTreeSelectionListener(selectionListener);
        registeredTreeListeners.add(selectionListener);

        MouseAdapter mouseAdapter = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    TreePath path = problemTree.getPathForLocation(e.getX(), e.getY());
                    if (path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode) {
                        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                        if (node.getUserObject() instanceof ProblemInfoNode) {
                            // 雙擊時跳轉到代碼位置
                            Project project = projectRef.get();
                            if (project != null) {
                                navigateToProblem(project, ((ProblemInfoNode) node.getUserObject()).getProblemInfo());
                            }
                        }
                    }
                }
            }
        };

        this.problemTree.addMouseListener(mouseAdapter);
        registeredMouseListeners.add(mouseAdapter);
    }

    /**
     * 根據問題列表構建樹模型（優化版）
     */
    private DefaultTreeModel buildTreeModel(List<ProblemInfo> problems) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("規範問題");
        Project project = projectRef.get();

        if (project == null) {
            return new DefaultTreeModel(root);
        }

        // 使用 stream 和 groupingBy 更高效地組織數據
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

                    // 使用 stream 和 sorted 更高效處理排序
                    fileProblems.stream()
                            .filter(p -> p != null && ReadAction.compute(() ->
                                    p.getElement() != null && p.getElement().isValid()))
                            .sorted(Comparator.comparingInt((ProblemInfo p) -> getPriorityForType(p.getHighlightType()))
                                    .thenComparingInt(p -> getLineNumber(project, p.getElement())))
                            .forEach(problem -> {
                                DefaultMutableTreeNode problemNode = new DefaultMutableTreeNode(
                                        new ProblemInfoNode(problem));
                                fileNode.add(problemNode);
                            });

                    // 如果文件節點沒有子節點，移除它
                    if (fileNode.getChildCount() == 0) {
                        root.remove(fileNode);
                    }
                });

        return new DefaultTreeModel(root);
    }

    /**
     * 創建問題樹（優化版）
     */
    private Tree createProblemTree(List<ProblemInfo> problems) {
        DefaultTreeModel treeModel = buildTreeModel(problems);
        Tree tree = new Tree(treeModel) {
            // 覆寫方法確保資源釋放
            @Override
            public void removeNotify() {
                super.removeNotify();
                clearSelection();
            }
        };

        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                                                          boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

                // 設置正確的顏色
                if (sel) {
                    setForeground(UIUtil.getTreeSelectionForeground(hasFocus));
                    setBackground(UIUtil.getTreeSelectionBackground(hasFocus));
                } else {
                    setForeground(UIUtil.getTreeForeground());
                    setBackground(UIUtil.getTreeBackground());
                }

                // 確保背景色
                setOpaque(true);

                // 處理不同類型的節點
                if (value instanceof DefaultMutableTreeNode) {
                    Object userObject = ((DefaultMutableTreeNode) value).getUserObject();
                    if (userObject instanceof ProblemInfoNode) {
                        ProblemInfoNode problemNode = (ProblemInfoNode) userObject;
                        setText(problemNode.getShortDescription());
                        // 根據問題類型設置圖標
                        setIconForProblemType(problemNode.getProblemInfo().getHighlightType());
                    } else if (userObject instanceof String) {
                        setText(userObject.toString());
                        setIconForNodeType(userObject.toString(), row, tree.getModel().getRoot() == value);
                    } else {
                        setText("未知節點");
                        setIcon(AllIcons.Actions.Help);
                    }
                }

                return this;
            }

            // 設置問題圖標
            private void setIconForProblemType(ProblemHighlightType highlightType) {
                if (highlightType == ProblemHighlightType.ERROR) {
                    setIcon(AllIcons.General.Error);
                } else if (highlightType == ProblemHighlightType.WARNING
                        || highlightType == ProblemHighlightType.WEAK_WARNING) {
                    setIcon(AllIcons.General.Warning);
                } else {
                    setIcon(AllIcons.General.Information);
                }
            }

            // 設置節點圖標
            private void setIconForNodeType(String nodeText, int row, boolean isRoot) {
                if (isRoot) {
                    setIcon(AllIcons.Nodes.ModuleGroup);
                } else if (nodeText.endsWith(".java")) {
                    setIcon(AllIcons.FileTypes.Java);
                } else {
                    setIcon(AllIcons.Nodes.Folder);
                }
            }
        });

        // 設置樹背景色
        tree.setBackground(UIUtil.getTreeBackground());

        return tree;
    }

    /**
     * 更新詳細信息面板以顯示節點摘要（優化版）
     */
    private void updateDetailsPaneForNode(@Nullable DefaultMutableTreeNode node, int totalProblemsInCurrentView) {
        if (detailsPane == null)
            return;

        Object userObject = (node != null) ? node.getUserObject() : null;

        // 使用 StringBuilder 預設容量，減少記憶體重分配
        StringBuilder summary = new StringBuilder(HTML_BUFFER_SIZE);
        summary.append("<html><body style='margin: 12px;'>");

        if (node == null || node.isRoot()) {
            buildRootNodeSummary(summary, totalProblemsInCurrentView);
        } else if (userObject instanceof String) { // File node
            buildFileNodeSummary(summary, (String) userObject, node.getChildCount());
        } else if (userObject instanceof ProblemInfoNode) {
            Project project = projectRef.get();
            if (project != null) {
                updateDetailsPane(project, ((ProblemInfoNode) userObject).getProblemInfo());
            }
            return;
        } else {
            summary.append("<p>請選擇一個問題查看詳細信息。</p>");
        }

        summary.append("</body></html>");

        // 使用 EDT 線程更新 UI
        ApplicationManager.getApplication().invokeLater(() -> {
            detailsPane.setText(summary.toString());
            detailsPane.setCaretPosition(0);
        });
    }

    /**
     * 構建根節點的摘要信息
     */
    private void buildRootNodeSummary(StringBuilder summary, int totalProblemsInCurrentView) {
        summary.append("<h2>Code Sentinel 檢查結果</h2>");
        summary.append("<p>總共發現 <b>").append(originalProblems.size()).append("</b> 個問題");

        if (originalProblems.size() != totalProblemsInCurrentView) {
            summary.append("（當前視圖顯示 <b>").append(totalProblemsInCurrentView).append("</b> 個）");
        }

        summary.append("。</p>");

        if (!originalProblems.isEmpty()) {
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
    }

    /**
     * 構建文件節點的摘要信息
     */
    private void buildFileNodeSummary(StringBuilder summary, String fileName, int childCount) {
        summary.append("<h2>").append(escapeHtml(fileName)).append("</h2>");
        summary.append("<p>此文件中發現 <b>").append(childCount).append("</b> 個問題</p>");

        if (childCount > 0) {
            summary.append("<p>點擊左側問題查看詳情，或雙擊問題跳轉到代碼。</p>");
        }
    }

    /**
     * 更新詳細信息面板以顯示具體問題（優化版）
     */
    private void updateDetailsPane(Project project, ProblemInfo problem) {
        if (problem == null || detailsPane == null || project == null)
            return;

        // 計算顏色值一次，避免重複計算
        Color background = UIManager.getColor("EditorPane.background");
        Color foreground = UIManager.getColor("EditorPane.foreground");
        boolean isDarkTheme = isDarkTheme(background);
        String bgColorHex = colorToHex(background);
        String fgColorHex = colorToHex(foreground);
        String headerColor = isDarkTheme ? "#dadada" : "#1a1a1a";
        String codeBackground = colorToHex(
                UIManager.getColor(isDarkTheme ? "EditorPane.background" : "TextArea.background"));
        String codeBorder = colorToHex(UIManager.getColor("Component.borderColor"));
        String boxBackground = colorToHex(
                UIManager.getColor(isDarkTheme ? "Panel.background" : "TextField.background"));
        String boxBorder = colorToHex(UIManager.getColor("Component.borderColor"));
        String codeColor = fgColorHex;

        // 使用預設容量的 StringBuilder 減少擴容頻率
        StringBuilder html = new StringBuilder(HTML_BUFFER_SIZE);

        // 簡化 HTML 結構，減少渲染負擔
        html.append("<html><body style='font-family:sans-serif;margin:10px;background-color:")
                .append(bgColorHex).append(";color:").append(fgColorHex).append(";'>");
        html.append("<h2 style='margin-top:0;margin-bottom:10px;color:").append(headerColor).append(";'>問題詳情</h2>");

        // 問題嚴重性
        appendProblemSeverity(html, problem.getHighlightType(), boxBackground, boxBorder);

        // 文件和行號
        appendFileAndLineInfo(html, project, problem.getElement());

        // 代碼片段
        appendCodeSnippet(html, problem.getElement(), codeBackground, codeBorder, codeColor);

        // 建議
        appendSuggestions(html, problem, boxBackground, boxBorder, codeBackground, codeColor);

        html.append("</body></html>");

        // 使用 EDT 線程更新 UI
        final String htmlContent = html.toString();
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                detailsPane.setText(htmlContent);
                detailsPane.setCaretPosition(0);
            } catch (Exception e) {
                LOG.error("設置 JEditorPane 文本時出錯", e);
                detailsPane.setText("<html><body>加載問題詳情時發生內部錯誤，請檢查IDE日誌獲取更多信息。</body></html>");
            }
        });
    }

    /**
     * 添加問題嚴重性標記
     */
    private void appendProblemSeverity(StringBuilder html, ProblemHighlightType highlightType,
                                       String boxBackground, String boxBorder) {
        String severityColor = getSeverityColor(highlightType);
        String severityText = getSeverityText(highlightType);

        html.append("<div style='margin-bottom:15px;padding:8px;border:1px solid ")
                .append(boxBorder).append(";background-color:").append(boxBackground).append(";'>");
        html.append("<p style='margin:0 0 5px 0;'><b style='color:").append(severityColor).append(";'>")
                .append(severityText).append(":</b></p>");
        html.append("<p style='margin:0;'>").append(escapeHtml(getElementDescription())).append("</p>");
        html.append("</div>");
    }

    /**
     * 獲取問題嚴重性的顏色
     */
    private String getSeverityColor(ProblemHighlightType highlightType) {
        if (highlightType == ProblemHighlightType.ERROR) {
            return "#ff5261";
        } else if (highlightType == ProblemHighlightType.WARNING
                || highlightType == ProblemHighlightType.WEAK_WARNING) {
            return "#eea800";
        } else {
            return "#589df6";
        }
    }

    /**
     * 獲取問題嚴重性的文本
     */
    private String getSeverityText(ProblemHighlightType highlightType) {
        if (highlightType == ProblemHighlightType.ERROR) {
            return "錯誤";
        } else if (highlightType == ProblemHighlightType.WARNING
                || highlightType == ProblemHighlightType.WEAK_WARNING) {
            return "警告";
        } else {
            return "資訊";
        }
    }

    /**
     * 添加文件和行號信息
     */
    private void appendFileAndLineInfo(StringBuilder html, Project project, PsiElement element) {
        String fileName = ReadAction.compute(() -> {
            if (element != null && element.isValid()) {
                PsiFile file = element.getContainingFile();
                return file != null ? file.getName() : null;
            }
            return null;
        });

        if (fileName != null) {
            html.append("<p style='margin:0 0 5px 0;'><b>文件:</b> ").append(escapeHtml(fileName)).append("</p>");
            int lineNumber = getLineNumber(project, element);
            if (lineNumber > 0) {
                html.append("<p style='margin:0 0 10px 0;'><b>行號:</b> ").append(lineNumber).append("</p>");
            }
        } else {
            html.append("<p style='margin:0 0 10px 0;color:#888;'><b>文件:</b> 無法確定</p>");
        }
    }

    /**
     * 添加代碼片段
     */
    private void appendCodeSnippet(StringBuilder html, PsiElement element,
                                   String codeBackground, String codeBorder, String codeColor) {
        if (element != null && ReadAction.compute(() -> element.isValid())) {
            html.append("<p style='margin:10px 0 5px 0;'><b>代碼片段:</b></p>");
            String elementText = ReadAction.compute(() -> {
                try {
                    return element.getText();
                } catch (Exception e) {
                    return null;
                }
            });

            if (elementText != null) {
                html.append("<pre style='padding:8px;background-color:").append(codeBackground)
                        .append(";border:1px solid ").append(codeBorder)
                        .append(";margin:0;white-space:pre-wrap;word-wrap:break-word;color:")
                        .append(codeColor).append(";'>")
                        .append(escapeHtml(elementText))
                        .append("</pre>");
            } else {
                html.append("<p style='color:red;'>無法顯示代碼片段</p>");
            }
        }
    }

    /**
     * 添加建議信息
     */
    private void appendSuggestions(StringBuilder html, ProblemInfo problem,
                                   String boxBackground, String boxBorder,
                                   String codeBackground, String codeColor) {
        if (problem.getSuggestionSource() != null || problem.getSuggestedValue() != null) {
            html.append("<p style='margin:15px 0 5px 0;'><b>建議:</b></p>");
            html.append("<div style='padding:8px;background-color:").append(boxBackground)
                    .append(";border:1px solid ").append(boxBorder).append(";margin:0;'>");

            if (problem.getSuggestionSource() != null) {
                html.append("<p style='margin:0 0 5px 0;'>來源: ")
                        .append(escapeHtml(problem.getSuggestionSource())).append("</p>");
            }

            if (problem.getSuggestedValue() != null) {
                html.append("<p style='margin:0;'>建議值:</p><pre style='padding:5px;background-color:")
                        .append(codeBackground)
                        .append(";margin:5px 0 0 0;white-space:pre-wrap;word-wrap:break-word;color:")
                        .append(codeColor).append(";'>")
                        .append(escapeHtml(problem.getSuggestedValue()))
                        .append("</pre>");
            }

            html.append("</div>");
        } else {
            html.append("<p style='margin:15px 0 5px 0;'><b>修復建議:</b> 請根據問題描述手動修改代碼。</p>");
        }
    }

    // 樣例方法，實際應根據 ProblemInfo 取得描述
    private String getElementDescription() {
        return "說明...";
    }

    /**
     * 導航到問題位置（優化版）
     */
    private void navigateToProblem(Project project, ProblemInfo problem) {
        if (project == null || problem == null) return;

        ReadAction.run(() -> {
            PsiElement element = problem.getElement();
            if (element == null || !element.isValid()) {
                LOG.warn("無法導航到無效的問題元素");
                ApplicationManager.getApplication().invokeLater(() ->
                        Messages.showWarningDialog(project,
                                "無法導航到選定的問題，對應的程式碼可能已變更或失效。", "導航失敗"));
                return;
            }

            PsiFile containingFile = element.getContainingFile();
            if (containingFile == null) return;

            VirtualFile virtualFile = containingFile.getVirtualFile();
            if (virtualFile == null || !virtualFile.isValid()) return;

            int offset = element.getTextOffset();
            OpenFileDescriptor descriptor = new OpenFileDescriptor(project, virtualFile, offset);

            ApplicationManager.getApplication().invokeLater(() ->
                    FileEditorManager.getInstance(project).openTextEditor(descriptor, true));
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

    /**
     * 問題樹節點的包裝類
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

            // 獲取行號資訊
            Project project = problemInfo.getElement() != null ?
                    problemInfo.getElement().getProject() : null;
            int lineNumber = CathayBkProblemsPanel.getLineNumber(project, problemInfo.getElement());
            String lineSuffix = (lineNumber > 0) ? " (第 " + lineNumber + " 行)" : "";

            // 限制描述長度
            if (safeDesc.length() > MAX_DETAIL_TEXT_LENGTH - lineSuffix.length()) {
                safeDesc = safeDesc.substring(0,
                        Math.max(0, MAX_DETAIL_TEXT_LENGTH - lineSuffix.length() - 3)) + "...";
            }

            return safeDesc + lineSuffix;
        }

        @Override
        public String toString() {
            return getShortDescription();
        }
    }

    // --- Dispose 方法，確保資源釋放 ---

    @Override
    public void dispose() {
        // 清除所有 listener 和引用
        if (problemTree != null) {
            // 移除所有樹選擇監聽器
            for (TreeSelectionListener listener : registeredTreeListeners) {
                if (listener != null) {
                    problemTree.removeTreeSelectionListener(listener);
                }
            }
            registeredTreeListeners.clear();

            // 移除所有滑鼠監聯器
            for (MouseListener listener : registeredMouseListeners) {
                if (listener != null) {
                    problemTree.removeMouseListener(listener);
                }
            }
            registeredMouseListeners.clear();

            // 清除模型和選擇路徑
            problemTree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode()));
            problemTree.clearSelection();
            problemTree = null; // 釋放樹組件引用
        }

        // 清除引用
        quickFixListener = null;
        fixAllListener = null;

        // 清除詳情面板
        if (detailsPane != null) {
            detailsPane.setText("");
            detailsPane = null; // 釋放詳情面板引用
        }

        // 重置過濾器，避免閉包持有 ProblemInfo 引用
        currentFilter = p -> true;

        // 清除搜索欄位
        searchField = null;

        LOG.info("CathayBkProblemsPanel 已釋放資源");
    }
}
