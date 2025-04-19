package com.cathaybk.codingassistant.vcs;

import com.cathaybk.codingassistant.common.ProblemInfo;
import com.cathaybk.codingassistant.fix.AddApiIdDocFix;
import com.cathaybk.codingassistant.fix.AddControllerApiIdFromServiceFix;
import com.cathaybk.codingassistant.fix.AddFieldJavadocFix;
import com.cathaybk.codingassistant.fix.AddServiceApiIdQuickFix;
import com.cathaybk.codingassistant.util.CathayBkInspectionUtil;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.QuickFix;
import com.intellij.icons.AllIcons;
import com.intellij.lang.annotation.ProblemGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vcs.CheckinProjectPanel;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.checkin.CheckinHandler;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class CathayBkCheckinHandler extends CheckinHandler {
    private static final Logger LOG = Logger.getInstance(CathayBkCheckinHandler.class);
    private static final String TOOL_WINDOW_ID = "國泰規範檢查";

    private final CheckinProjectPanel panel;
    private final Project project;
    private SearchTextField searchField;
    private Tree problemTree;
    private List<ProblemInfo> originalProblems;

    public CathayBkCheckinHandler(CheckinProjectPanel panel) {
        LOG.info("CathayBk Checkin Handler 建構函式被呼叫！");
        this.panel = panel;
        this.project = panel.getProject();
    }

    @Override
    public ReturnResult beforeCheckin() {
        LOG.info("CathayBk Checkin Handler beforeCheckin 被呼叫！");

        Collection<Change> changes = panel.getSelectedChanges();
        LOG.info("檢查的變更數量：" + changes.size());

        if (changes.isEmpty()) {
            LOG.info("沒有要檢查的變更，直接返回 COMMIT");
            return ReturnResult.COMMIT;
        }

        changes.forEach(change -> {
            if (change.getAfterRevision() != null) {
                LOG.debug("計劃檢查文件: " + change.getAfterRevision().getFile().getPath());
            }
        });

        List<ProblemInfo> allProblems = new ArrayList<>();
        AtomicReference<ReturnResult> result = new AtomicReference<>(ReturnResult.COMMIT);

        LOG.info("開始執行進度檢查...");

        ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            LOG.info("進度檢查執行中...");
            ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
            if (indicator == null) {
                LOG.warn("Progress Indicator 為 null，退出檢查");
                return;
            }
            indicator.setIndeterminate(false);
            indicator.setText("執行國泰規範檢查...");

            PsiManager psiManager = PsiManager.getInstance(project);
            int processedCount = 0;
            int totalChanges = changes.size();
            List<PsiJavaFile> relevantJavaFiles = new ArrayList<>();

            // 1. 收集 Java 文件
            for (Change change : changes) {
                if (indicator.isCanceled()) {
                    LOG.info("使用者取消了收集文件階段");
                    result.set(ReturnResult.CANCEL);
                    return;
                }
                processedCount++;
                if (totalChanges > 0) {
                    indicator.setFraction((double) processedCount / (totalChanges * 2));
                } else {
                    indicator.setFraction(0.5);
                }

                ContentRevision afterRevision = change.getAfterRevision();
                if (afterRevision == null)
                    continue;

                VirtualFile vf = ReadAction.compute(() -> {
                    try {
                        return afterRevision.getFile() != null ? afterRevision.getFile().getVirtualFile() : null;
                    } catch (Exception e) {
                        LOG.error("獲取 VirtualFile 時發生錯誤", e);
                        return null;
                    }
                });

                if (vf == null || !vf.isValid() || vf.getFileType().isBinary() || !vf.getName().endsWith(".java"))
                    continue;

                ReadAction.run(() -> {
                    PsiFile psiFile = psiManager.findFile(vf);
                    if (psiFile instanceof PsiJavaFile)
                        relevantJavaFiles.add((PsiJavaFile) psiFile);
                });
            }

            // 2. 執行檢查
            LOG.info("找到 " + relevantJavaFiles.size() + " 個 Java 文件需要檢查");
            int filesChecked = 0;
            int totalFiles = relevantJavaFiles.size();
            for (PsiJavaFile javaFile : relevantJavaFiles) {
                if (indicator.isCanceled()) {
                    LOG.info("使用者取消了檢查階段");
                    result.set(ReturnResult.CANCEL);
                    return;
                }
                filesChecked++;
                if (totalFiles > 0) {
                    indicator.setFraction(0.5 + (double) filesChecked / (totalFiles * 2));
                } else {
                    indicator.setFraction(1.0);
                }
                indicator.setText2("檢查 " + javaFile.getName());

                ReadAction.run(() -> {
                    javaFile.accept(new JavaRecursiveElementWalkingVisitor() {
                        @Override
                        public void visitClass(@NotNull PsiClass aClass) {
                            super.visitClass(aClass);
                            if (indicator.isCanceled())
                                return;
                            allProblems.addAll(CathayBkInspectionUtil.checkServiceClassDoc(aClass));
                        }

                        @Override
                        public void visitMethod(@NotNull PsiMethod method) {
                            super.visitMethod(method);
                            if (indicator.isCanceled())
                                return;
                            allProblems.addAll(CathayBkInspectionUtil.checkApiMethodDoc(method));
                            // 移除對 checkMethodNaming 的呼叫
                        }

                        @Override
                        public void visitField(@NotNull PsiField field) {
                            super.visitField(field);
                            if (indicator.isCanceled())
                                return;
                            allProblems.addAll(CathayBkInspectionUtil.checkInjectedFieldDoc(field));
                        }
                    });
                });
            }

            // 存儲原始問題列表以供過濾使用
            this.originalProblems = new ArrayList<>(allProblems);

            LOG.info("總共發現問題數量：" + allProblems.size());

            // 顯示問題並詢問是否繼續提交
            if (!allProblems.isEmpty()) {
                LOG.info("發現問題，顯示自訂對話框");
                ApplicationManager.getApplication().invokeAndWait(() -> {
                    String title = "國泰規範檢查結果";
                    String message = "發現 " + allProblems.size() + " 個國泰規範問題。\n選擇「查看問題」以查看詳細資訊。";
                    String[] buttons = { "繼續 (Continue)", "取消 (Cancel)", "查看問題 (Review Issues)" };
                    int choice = Messages.showDialog(project, message, title, buttons, 1, AllIcons.General.Warning);

                    switch (choice) {
                        case 0:
                            LOG.info("使用者選擇繼續提交");
                            result.set(ReturnResult.COMMIT);
                            break;
                        case 2:
                            LOG.info("使用者選擇查看問題，取消本次提交");
                            showProblemsInToolWindow(project, this.originalProblems);
                            result.set(ReturnResult.CANCEL);
                            break;
                        case 1:
                        default:
                            LOG.info("使用者選擇取消提交或關閉了對話框");
                            result.set(ReturnResult.CANCEL);
                            break;
                    }
                });
            } else {
                LOG.info("未發現問題，自動繼續提交");
                result.set(ReturnResult.COMMIT);
            }
            LOG.info("檢查完成，處理結果：" + result.get());

        }, "執行國泰規範檢查", true, project);

        ReturnResult returnResult = result.get();
        LOG.info("最終檢查結果：" + returnResult);
        return returnResult;
    }

    /**
     * 在工具窗口中顯示問題列表 (使用 ProblemInfo)
     */
    private void showProblemsInToolWindow(Project project, List<ProblemInfo> problems) {
        ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
        ToolWindow toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID);

        if (toolWindow == null) {
            toolWindow = toolWindowManager.registerToolWindow(
                    TOOL_WINDOW_ID, true, ToolWindowAnchor.BOTTOM, project, true);
            toolWindow.setIcon(AllIcons.General.InspectionsEye);
            toolWindow.setStripeTitle("國泰規範");
        }

        toolWindow.getContentManager().removeAllContents(true);
        JComponent problemsPanel = createProblemsPanel(project, problems);
        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(problemsPanel, "檢查結果 (" + problems.size() + ")", false);
        toolWindow.getContentManager().addContent(content);
        final ToolWindow finalToolWindow = toolWindow;

        // 調整工具窗口的尺寸為較小的值（佔據螢幕的30%）
        ApplicationManager.getApplication().invokeLater(() -> {
            finalToolWindow.show(() -> {
                // 獲取IDE框架尺寸，計算30%的高度
                java.awt.Window frame = WindowManager.getInstance().getFrame(project);
                if (frame != null) {
                    int frameHeight = frame.getHeight();
                    int preferredHeight = (int) (frameHeight * 0.3); // 設置為30%的高度

                    // 調整工具窗口高度
                    finalToolWindow.getComponent().setPreferredSize(new Dimension(
                            finalToolWindow.getComponent().getWidth(),
                            preferredHeight));
                }

                finalToolWindow.activate(null, true, true);
            });
        });
    }

    /**
     * 創建問題顯示面板 (包含快速修復按鈕)
     */
    private JComponent createProblemsPanel(Project project, List<ProblemInfo> problems) {
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBackground(UIManager.getColor("Panel.background"));

        // 修改為水平分割（左右格式）- 調整為7:3比例
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        // 初始設置一個像素值，但真正的調整會在組件顯示後通過比例完成
        splitPane.setDividerLocation(700); // 設置一個初始位置，具體會根據實際寬度調整
        splitPane.setResizeWeight(0.7); // 設置左側佔70%比重
        splitPane.setBorder(null);
        splitPane.setDividerSize(5);

        // 確保面板顯示後，根據實際寬度調整分隔位置
        splitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, evt -> {
            // 只在初始顯示時調整一次
            if ((int) evt.getNewValue() == 700) {
                int totalWidth = splitPane.getWidth();
                if (totalWidth > 0) {
                    int newLocation = (int) (totalWidth * 0.7);
                    splitPane.setDividerLocation(newLocation);
                }
            }
        });

        this.problemTree = createProblemTree(project, problems);
        JBScrollPane treeScrollPane = new JBScrollPane(this.problemTree);
        treeScrollPane.setBorder(JBUI.Borders.empty());

        // 加入標題
        JPanel leftPanel = new JPanel(new BorderLayout());
        JLabel treeTitle = new JLabel("問題列表（單擊查看詳情，雙擊跳轉到代碼）");
        treeTitle.setBorder(JBUI.Borders.empty(5, 8));
        treeTitle.setFont(treeTitle.getFont().deriveFont(Font.BOLD));

        this.searchField = new SearchTextField();
        // 修正：獲取內部編輯器並添加 Swing 的 DocumentListener
        JTextField textEditor = this.searchField.getTextEditor(); // 獲取內部 JTextField
        textEditor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                filter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                filter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                filter();
            }

            private void filter() {
                // 確保在 Swing 事件線程中執行過濾
                SwingUtilities.invokeLater(() -> {
                    if (problemTree != null && searchField != null) {
                        filterTree(problemTree, searchField.getText());
                    }
                });
            }
        });

        JPanel searchPanel = new JPanel(new BorderLayout());
        searchPanel.add(new JLabel("搜尋: "), BorderLayout.WEST);
        searchPanel.add(this.searchField, BorderLayout.CENTER);
        searchPanel.setBorder(JBUI.Borders.empty(5, 8, 10, 8));

        leftPanel.add(treeTitle, BorderLayout.NORTH);
        leftPanel.add(searchPanel, BorderLayout.SOUTH);
        leftPanel.add(treeScrollPane, BorderLayout.CENTER);

        // 右側詳情面板
        JPanel rightPanel = new JPanel(new BorderLayout());
        JLabel detailsTitle = new JLabel("問題詳情");
        detailsTitle.setBorder(JBUI.Borders.empty(5, 8));
        detailsTitle.setFont(detailsTitle.getFont().deriveFont(Font.BOLD));
        rightPanel.add(detailsTitle, BorderLayout.NORTH);

        JEditorPane detailsPane = new JEditorPane();
        detailsPane.setEditable(false);
        detailsPane.setContentType("text/html");
        detailsPane.setBackground(UIManager.getColor("EditorPane.background"));
        updateDetailsPaneForNode(detailsPane, null, problems.size()); // 初始顯示摘要

        JBScrollPane detailsScrollPane = new JBScrollPane(detailsPane);
        detailsScrollPane.setBorder(JBUI.Borders.empty());
        rightPanel.add(detailsScrollPane, BorderLayout.CENTER);

        this.problemTree.addTreeSelectionListener(e -> {
            TreePath path = e.getPath();
            if (path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                Object userObject = node.getUserObject();
                if (userObject instanceof ProblemInfoNode) {
                    // 單擊時顯示詳情
                    updateDetailsPane(project, detailsPane, ((ProblemInfoNode) userObject).getProblemInfo());
                } else {
                    updateDetailsPaneForNode(detailsPane, node,
                            this.originalProblems != null ? this.originalProblems.size() : problems.size());
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

        splitPane.setLeftComponent(leftPanel);
        splitPane.setRightComponent(rightPanel);

        JPanel toolbarPanel = new JPanel(new BorderLayout());
        toolbarPanel.setBorder(JBUI.Borders.empty(6, 10));
        JLabel titleLabel = new JLabel("國泰規範檢查結果");
        titleLabel.setIcon(AllIcons.General.InspectionsEye);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, titleLabel.getFont().getSize() + 2));
        toolbarPanel.add(titleLabel, BorderLayout.WEST);

        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        JButton fixAllButton = new JButton("一鍵修復全部", AllIcons.Actions.QuickfixOffBulb);
        fixAllButton.setToolTipText("嘗試修復所有可自動修復的問題");
        fixAllButton.addActionListener(e -> applyAllQuickFixes());

        JButton quickFixButton = new JButton("快速修復", AllIcons.Actions.QuickfixBulb);
        quickFixButton.setToolTipText("嘗試快速修復所選問題");
        quickFixButton.addActionListener(e -> applySelectedQuickFix());

        actionsPanel.add(fixAllButton);
        actionsPanel.add(quickFixButton);
        toolbarPanel.add(actionsPanel, BorderLayout.EAST);

        mainPanel.add(toolbarPanel, BorderLayout.NORTH);
        mainPanel.add(splitPane, BorderLayout.CENTER);

        // 增加底部狀態行，提供使用提示
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBorder(JBUI.Borders.empty(5, 10, 5, 10));
        JLabel statusLabel = new JLabel("提示: 單擊問題可查看詳情，雙擊問題可直接跳轉到代碼位置");
        statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        statusPanel.add(statusLabel, BorderLayout.CENTER);
        mainPanel.add(statusPanel, BorderLayout.SOUTH);

        ApplicationManager.getApplication().invokeLater(() -> {
            if (this.searchField != null)
                this.searchField.requestFocusInWindow();
            else if (this.problemTree != null)
                this.problemTree.requestFocusInWindow();
        });

        return mainPanel;
    }

    /**
     * 根據搜索文本過濾樹（改進版）
     */
    private void filterTree(Tree tree, String searchText) {
        if (originalProblems == null) {
            LOG.warn("originalProblems is null, cannot filter tree.");
            return;
        }
        if (tree == null) {
            LOG.warn("problemTree is null, cannot filter.");
            return;
        }

        List<ProblemInfo> filteredProblems;
        String lowerSearchText = searchText.toLowerCase();

        if (searchText.isEmpty()) {
            filteredProblems = new ArrayList<>(originalProblems);
        } else {
            filteredProblems = originalProblems.stream()
                    .filter(p -> {
                        if (p == null)
                            return false;
                        if (p.getDescription() != null && p.getDescription().toLowerCase().contains(lowerSearchText)) {
                            return true;
                        }
                        PsiElement element = p.getElement();
                        if (element != null && element.isValid() && element.getContainingFile() != null) {
                            if (element.getContainingFile().getName().toLowerCase().contains(lowerSearchText)) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .collect(Collectors.toList());
        }

        DefaultTreeModel newModel = buildTreeModel(project, filteredProblems);
        tree.setModel(newModel);
        TreeUtil.expandAll(tree);
        updateToolWindowContentTitle(filteredProblems.size());
    }

    /**
     * 根據問題列表構建樹模型
     */
    private DefaultTreeModel buildTreeModel(Project project, List<ProblemInfo> problems) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("規範問題");
        problems.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(problem -> {
                    PsiElement element = problem.getElement();
                    if (element != null && element.isValid() && element.getContainingFile() != null) {
                        return element.getContainingFile().getName();
                    }
                    return "未知或無效文件";
                }))
                .forEach((fileName, fileProblems) -> {
                    DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(fileName);
                    root.add(fileNode);
                    fileProblems.stream()
                            .filter(p -> p != null && p.getElement() != null && p.getElement().isValid())
                            .sorted(Comparator.comparingInt((ProblemInfo p) -> getPriorityForType(p.getHighlightType()))
                                    .thenComparingInt(p -> getLineNumber(project, p.getElement())))
                            .forEach(problem -> {
                                DefaultMutableTreeNode problemNode = new DefaultMutableTreeNode(
                                        new ProblemInfoNode(problem));
                                fileNode.add(problemNode);
                            });
                    if (fileNode.getChildCount() == 0 && !"未知或無效文件".equals(fileName)) {
                        root.remove(fileNode);
                    }
                });
        return new DefaultTreeModel(root);
    }

    /**
     * 更新工具窗口內容標題中的問題數量
     */
    private void updateToolWindowContentTitle(int problemCount) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow != null) {
            Content content = toolWindow.getContentManager().getSelectedContent();
            if (content == null && toolWindow.getContentManager().getContentCount() > 0) {
                content = toolWindow.getContentManager().getContent(0);
            }
            if (content != null) {
                content.setDisplayName("檢查結果 (" + problemCount + ")");
            } else {
                LOG.warn("無法更新工具窗口標題：找不到 Content。");
            }
        }
    }

    /**
     * 創建問題樹 (使用 ProblemInfo)
     */
    private Tree createProblemTree(Project project, List<ProblemInfo> problems) {
        DefaultTreeModel treeModel = buildTreeModel(project, problems);
        Tree tree = new Tree(treeModel);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                    boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                setForeground(UIManager.getColor("Tree.foreground"));
                Object userObject = ((DefaultMutableTreeNode) value).getUserObject();
                if (userObject instanceof ProblemInfoNode) {
                    ProblemInfoNode problemNode = (ProblemInfoNode) userObject;
                    setText(problemNode.getShortDescription());
                    ProblemHighlightType highlightType = problemNode.getProblemInfo().getHighlightType();
                    if (highlightType == ProblemHighlightType.ERROR)
                        setIcon(AllIcons.General.Error);
                    else if (highlightType == ProblemHighlightType.WARNING
                            || highlightType == ProblemHighlightType.WEAK_WARNING)
                        setIcon(AllIcons.General.Warning);
                    else
                        setIcon(AllIcons.General.Information);
                } else if (userObject instanceof String) {
                    setText(userObject.toString());
                    if (userObject.toString().endsWith(".java"))
                        setIcon(AllIcons.FileTypes.Java);
                    else
                        setIcon(AllIcons.Nodes.Folder);
                } else {
                    setText("規範問題");
                    setIcon(AllIcons.General.InspectionsEye);
                }
                return this;
            }
        });
        TreeUtil.expandAll(tree);
        return tree;
    }

    /**
     * 比較問題嚴重程度，用於排序
     */
    private int compareHighlightType(ProblemHighlightType type1, ProblemHighlightType type2) {
        return Integer.compare(getPriorityForType(type1), getPriorityForType(type2));
    }

    private int getPriorityForType(ProblemHighlightType type) {
        if (type == ProblemHighlightType.ERROR)
            return 0;
        if (type == ProblemHighlightType.WARNING)
            return 1;
        if (type == ProblemHighlightType.WEAK_WARNING)
            return 2;
        return 3; // 信息和其他
    }

    /**
     * 更新詳細信息面板以顯示節點摘要（文件或根節點）
     */
    private void updateDetailsPaneForNode(JEditorPane detailsPane, @Nullable DefaultMutableTreeNode node,
            int totalProblems) {
        if (detailsPane == null)
            return;

        Object userObject = (node != null) ? node.getUserObject() : null;
        String fontFamily = getDefaultFontFamily();

        // 獲取當前主題顏色
        Color background = UIManager.getColor("Panel.background");
        Color foreground = UIManager.getColor("Panel.foreground");
        boolean isDarkTheme = isDarkTheme(background);

        // 為不同主題設置適合的顏色
        String bgColorHex = colorToHex(background);
        String fgColorHex = colorToHex(foreground);
        String headerColor = isDarkTheme ? "#589df6" : "#4b6eaf";
        String secondaryTextColor = isDarkTheme ? "#b0b0b0" : "#666666";
        String borderColor = isDarkTheme ? "#555555" : "#dddddd";

        StringBuilder summary = new StringBuilder();
        summary.append("<html><body style='font-family: ").append(fontFamily)
                .append("; margin: 12px; background-color: ").append(bgColorHex)
                .append("; color: ").append(fgColorHex).append(";'>");

        if (node == null || node.isRoot()) {
            summary.append("<h2 style='color:").append(headerColor).append(";'>國泰規範檢查結果</h2>");
            summary.append("<p>共發現 <b>").append(totalProblems).append("</b> 個問題。</p>");
            if (originalProblems != null && !originalProblems.isEmpty()) {
                int fileCount = (int) originalProblems.stream()
                        .map(p -> p.getElement() != null && p.getElement().isValid()
                                ? p.getElement().getContainingFile()
                                : null)
                        .filter(Objects::nonNull)
                        .distinct()
                        .count();
                summary.append("<p>分布在 <b>").append(fileCount).append("</b> 個文件中。</p>");
            }
            summary.append("<p style='margin-top:10px; color:").append(secondaryTextColor)
                    .append(";'>請選擇左側的文件或問題查看詳細信息。</p>");
        } else if (userObject instanceof String) {
            String fileName = (String) userObject;
            int childCount = node.getChildCount();
            summary.append("<h2 style='color:").append(headerColor).append(";'>").append(escapeHtml(fileName))
                    .append("</h2>");
            summary.append("<p>此文件中發現 <b>").append(childCount).append("</b> 個問題</p>");
            if (childCount > 0) {
                summary.append("<p style='margin-top:10px; color:").append(secondaryTextColor)
                        .append(";'>點擊左側問題查看詳情，或雙擊問題跳轉到代碼。</p>");
            }
        } else {
            summary.append("<p>請選擇一個問題查看詳細信息。</p>");
        }

        summary.append("</body></html>");
        try {
            detailsPane.setText(summary.toString());
            detailsPane.setCaretPosition(0);
        } catch (Exception ex) {
            LOG.error("設置節點摘要時出錯: " + ex.getMessage(), ex);
            try {
                detailsPane.setText("<html><body><p>無法顯示摘要信息。</p></body></html>");
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 更新詳細信息面板 (極度簡化 CSS)
     */
    private void updateDetailsPane(Project project, JEditorPane detailsPane, ProblemInfo problem) {
        PsiElement element = problem.getElement();
        PsiFile file = (element != null && element.isValid()) ? element.getContainingFile() : null;

        // 獲取當前主題顏色
        Color background = UIManager.getColor("Panel.background");
        Color foreground = UIManager.getColor("Panel.foreground");
        boolean isDarkTheme = isDarkTheme(background);

        // 為不同主題設置適合的顏色
        String bgColorHex = colorToHex(background);
        String fgColorHex = colorToHex(foreground);
        String headerColor = isDarkTheme ? "#e8e8e8" : "#333333";
        String codeBackground = isDarkTheme ? "#2b2b2b" : "#f5f5f5";
        String codeBorder = isDarkTheme ? "#555555" : "#dddddd";
        String boxBackground = isDarkTheme ? "#3c3f41" : "#f9f9f9";
        String boxBorder = isDarkTheme ? "#555555" : "#cccccc";

        StringBuilder html = new StringBuilder();
        html.append("<html><body style='font-family: ").append(getDefaultFontFamily())
                .append("; margin: 10px; background-color: ").append(bgColorHex)
                .append("; color: ").append(fgColorHex).append(";'>");

        html.append("<h2 style='margin-top:0; margin-bottom:10px; color:").append(headerColor).append(";'>問題詳情</h2>");

        ProblemHighlightType highlightType = problem.getHighlightType();
        String severityColor = highlightType == ProblemHighlightType.ERROR ? "#ff5261"
                : (highlightType == ProblemHighlightType.WARNING || highlightType == ProblemHighlightType.WEAK_WARNING)
                        ? "#eea800"
                        : "#589df6";
        String severityText = highlightType == ProblemHighlightType.ERROR ? "錯誤"
                : (highlightType == ProblemHighlightType.WARNING || highlightType == ProblemHighlightType.WEAK_WARNING)
                        ? "警告"
                        : "資訊";

        html.append("<div style='margin-bottom:15px; padding:8px; border:1px solid ").append(boxBorder)
                .append("; background-color:").append(boxBackground).append(";'>");
        html.append("<p style='margin:0 0 5px 0;'><b style='color:").append(severityColor).append(";'>")
                .append(severityText).append(":</b></p>");
        html.append("<p style='margin:0;'>").append(escapeHtml(problem.getDescription())).append("</p>");
        html.append("</div>");

        if (file != null) {
            html.append("<p style='margin:0 0 5px 0;'><b>文件:</b> ").append(escapeHtml(file.getName())).append("</p>");
            int lineNumber = getLineNumber(project, element);
            if (lineNumber > 0) {
                html.append("<p style='margin:0 0 10px 0;'><b>行號:</b> ").append(lineNumber).append("</p>");
            }
        } else {
            html.append("<p style='margin:0 0 10px 0; color:#888;'><b>文件:</b> 無法確定</p>");
        }

        if (element != null && element.isValid()) {
            html.append("<p style='margin:10px 0 5px 0;'><b>代碼片段:</b></p>");
            try {
                String elementText = ReadAction.compute(() -> element.getText());
                html.append("<pre style='padding:8px; background-color:").append(codeBackground)
                        .append("; border:1px solid ").append(codeBorder)
                        .append("; margin:0; white-space: pre-wrap; word-wrap: break-word; color:")
                        .append(isDarkTheme ? "#cccccc" : "#000000").append(";'>")
                        .append(escapeHtml(elementText))
                        .append("</pre>");
            } catch (Exception e) {
                html.append("<p style='color:red;'>無法顯示代碼片段</p>");
                LOG.warn("無法獲取問題元素的文本: " + e.getMessage());
            }
        }

        if (problem.getSuggestionSource() != null || problem.getSuggestedValue() != null) {
            html.append("<p style='margin:15px 0 5px 0;'><b>建議:</b></p>");
            html.append("<div style='padding:8px; background-color:").append(boxBackground)
                    .append("; border:1px solid ").append(boxBorder).append("; margin:0;'>");
            if (problem.getSuggestionSource() != null) {
                html.append("<p style='margin:0 0 5px 0;'>來源: ").append(escapeHtml(problem.getSuggestionSource()))
                        .append("</p>");
            }
            if (problem.getSuggestedValue() != null) {
                html.append("<p style='margin:0;'>建議值:</p><pre style='padding: 5px; background-color: ")
                        .append(codeBackground)
                        .append("; margin: 5px 0 0 0; white-space: pre-wrap; word-wrap: break-word; color:")
                        .append(isDarkTheme ? "#cccccc" : "#000000").append(";'>")
                        .append(escapeHtml(problem.getSuggestedValue()))
                        .append("</pre>");
            }
            html.append("</div>");
        } else {
            html.append("<p style='margin:15px 0 5px 0;'><b>修復建議:</b> 請根據問題描述手動修改代碼。</p>");
        }

        html.append("</body></html>");

        try {
            if (detailsPane.isDisplayable()) {
                detailsPane.setText(html.toString());
                detailsPane.setCaretPosition(0);
            } else {
                LOG.warn("Details pane is not displayable when trying to set text.");
            }
        } catch (Exception e) {
            LOG.error("Error setting text in JEditorPane: " + e.getMessage(), e);
            try {
                detailsPane.setText("<html><body>顯示詳情時出錯。</body></html>");
            } catch (Exception ex) {
                LOG.error("無法設置後備錯誤訊息: " + ex.getMessage(), ex);
            }
        }
    }

    /**
     * 輔助方法：判斷是否為深色主題
     */
    private boolean isDarkTheme(Color background) {
        if (background == null)
            return false;
        // 計算亮度 (HSL 亮度)
        float[] hsb = Color.RGBtoHSB(background.getRed(), background.getGreen(), background.getBlue(), null);
        return hsb[2] < 0.5f; // 亮度低於50%認為是深色主題
    }

    /**
     * 輔助方法：將顏色轉換為十六進制表示
     */
    private String colorToHex(Color color) {
        if (color == null)
            return "#ffffff";
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    /**
     * 獲取系統默認字體
     */
    private String getDefaultFontFamily() {
        Font defaultFont = UIManager.getFont("Label.font");
        return defaultFont != null ? defaultFont.getFamily() : "sans-serif";
    }

    /**
     * 導航到問題位置 (使用 ProblemInfo)
     */
    private void navigateToProblem(Project project, ProblemInfo problem) {
        PsiElement element = problem.getElement();
        if (element != null && element.isValid()) {
            PsiFile containingFile = element.getContainingFile();
            if (containingFile != null) {
                VirtualFile virtualFile = containingFile.getVirtualFile();
                if (virtualFile != null && virtualFile.isValid()) {
                    int offset = element.getTextOffset();
                    OpenFileDescriptor descriptor = new OpenFileDescriptor(project, virtualFile, offset);
                    FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
                }
            }
        } else {
            LOG.warn("無法導航到無效的問題元素: " + problem.getDescription());
            Messages.showWarningDialog(project, "無法導航到選定的問題，對應的程式碼可能已變更或失效。", "導航失敗");
        }
    }

    /**
     * 問題樹節點的包裝類 (使用 ProblemInfo)
     */
    private static class ProblemInfoNode {
        private final ProblemInfo problemInfo;

        public ProblemInfoNode(ProblemInfo problemInfo) {
            this.problemInfo = problemInfo;
        }

        public ProblemInfo getProblemInfo() {
            return problemInfo;
        }

        public String getShortDescription() {
            String desc = problemInfo.getDescription();
            PsiElement element = problemInfo.getElement();
            int lineNumber = getLineNumber(element != null ? element.getProject() : null, element);
            String lineSuffix = (lineNumber > 0) ? " (第 " + lineNumber + " 行)" : "";
            final int MAX_LEN = 80;
            String safeDesc = desc != null ? desc : "描述為空";
            if (safeDesc.length() > MAX_LEN - lineSuffix.length()) {
                int endIndex = MAX_LEN - lineSuffix.length() - 3;
                if (endIndex < 0)
                    endIndex = 0;
                safeDesc = safeDesc.substring(0, endIndex) + "...";
            }
            return safeDesc + lineSuffix;
        }

        @Override
        public String toString() {
            return getShortDescription();
        }
    }

    /**
     * 輔助方法：獲取 PSI 元素的行號
     */
    private static int getLineNumber(@Nullable Project project, @Nullable PsiElement element) {
        if (element == null || !element.isValid() || project == null)
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
                    } else {
                        LOG.warn("問題元素的偏移量超出文檔範圍: offset=" + offset + ", length=" + document.getTextLength());
                        return -1;
                    }
                } catch (IndexOutOfBoundsException | PsiInvalidElementAccessException e) {
                    LOG.warn("獲取行號時出錯: " + e.getMessage());
                    return -1;
                }
            }
            return -1;
        });
    }

    /**
     * 嘗試對當前選中的問題執行快速修復
     */
    private void applySelectedQuickFix() {
        if (problemTree == null)
            return;

        TreePath selectionPath = problemTree.getSelectionPath();
        if (selectionPath == null || !(selectionPath.getLastPathComponent() instanceof DefaultMutableTreeNode)) {
            Messages.showInfoMessage(project, "請先在樹中選擇一個具體的問題項", "快速修復");
            return;
        }

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectionPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof ProblemInfoNode)) {
            Messages.showInfoMessage(project, "請選擇一個問題項，而不是文件夾", "快速修復");
            return;
        }

        ProblemInfoNode problemNode = (ProblemInfoNode) selectedNode.getUserObject();
        ProblemInfo problem = problemNode.getProblemInfo();
        PsiElement element = problem.getElement();

        if (element == null || !element.isValid()) {
            Messages.showErrorDialog(project, "無法應用修復：關聯的程式碼元素已失效。\n請嘗試重新檢查。", "快速修復失敗");
            removeNodeFromTreeAndList(selectedNode, problem);
            return;
        }

        LocalQuickFix fixToApply = determineQuickFix(problem);

        if (fixToApply == null) {
            Messages.showWarningDialog(project, "未能找到適用於此問題的自動修復方案。\n描述: " + problem.getDescription(), "快速修復");
            return;
        }

        ProblemDescriptor dummyDescriptor = createDummyDescriptor(element, problem.getDescription());

        WriteCommandAction.runWriteCommandAction(project, fixToApply.getName(), null, () -> {
            try {
                fixToApply.applyFix(project, dummyDescriptor);
                LOG.info("成功應用快速修復: " + fixToApply.getName() + " 到元素: " + ReadAction.compute(() -> element.getText()));
                removeNodeFromTreeAndList(selectedNode, problem);

                JEditorPane detailsPane = findDetailsPane(problemTree);
                if (detailsPane != null) {
                    updateDetailsPaneForNode(detailsPane, null, originalProblems.size());
                }

            } catch (Exception ex) {
                LOG.error("應用快速修復時出錯 (" + fixToApply.getName() + "): " + ex.getMessage(), ex);
                Messages.showErrorDialog(project, "應用快速修復時出錯: " + ex.getMessage(), "快速修復失敗");
            }
        });
    }

    /**
     * 從樹模型和原始列表中移除節點
     */
    private void removeNodeFromTreeAndList(DefaultMutableTreeNode nodeToRemove, ProblemInfo problemToRemove) {
        if (problemTree != null) {
            DefaultTreeModel model = (DefaultTreeModel) problemTree.getModel();
            if (nodeToRemove.getParent() != null) {
                model.removeNodeFromParent(nodeToRemove);
            }
        }
        if (originalProblems != null) {
            originalProblems.remove(problemToRemove);
            updateToolWindowContentTitle(originalProblems.size());
        }
    }

    private LocalQuickFix determineQuickFix(ProblemInfo problem) {
        String description = problem.getDescription();
        PsiElement element = problem.getElement();

        if (element == null || !element.isValid())
            return null;

        if (problem.getSuggestionSource() != null && problem.getSuggestedValue() != null) {
            if (description.contains("API 方法缺少")
                    && (element instanceof PsiIdentifier || element instanceof PsiMethod)) {
                return new AddControllerApiIdFromServiceFix(problem.getSuggestionSource(), problem.getSuggestedValue());
            }
            if (description.contains("Service 類別缺少") && (element instanceof PsiIdentifier || element instanceof PsiClass
                    || element instanceof PsiKeyword)) {
                return new AddServiceApiIdQuickFix(problem.getSuggestionSource(), problem.getSuggestedValue());
            }
        }

        if (description.contains("API 方法缺少") && (element instanceof PsiIdentifier || element instanceof PsiMethod)) {
            return new AddApiIdDocFix();
        }
        if (description.contains("Service 類別缺少")
                && (element instanceof PsiIdentifier || element instanceof PsiClass || element instanceof PsiKeyword)) {
            return new AddApiIdDocFix();
        }
        if (description.contains("注入的欄位") && description.contains("缺少 Javadoc")) {
            // 獲取初始欄位 - 使用ReadAction包裝
            final PsiField initialField = ReadAction
                    .compute(() -> PsiTreeUtil.getParentOfType(element, PsiField.class, false));

            // 如果初始欄位為空但元素本身是欄位，則使用元素作為欄位
            final PsiField field = ReadAction.compute(() -> {
                if (initialField == null && element instanceof PsiField)
                    return (PsiField) element;
                return initialField;
            });

            if (field != null) {
                String typeName = ReadAction.compute(() -> field.getType().getPresentableText());
                if (typeName != null && !typeName.isEmpty()) {
                    return new AddFieldJavadocFix(typeName);
                } else {
                    return new AddFieldJavadocFix("UnknownType");
                }
            }
        }

        LOG.warn("未能為問題找到匹配的 LocalQuickFix: " + description);
        return null;
    }

    /**
     * 創建一個臨 neighbourhoods 的 ProblemDescriptor 實現
     */
    private ProblemDescriptor createDummyDescriptor(PsiElement element, String description) {
        return new ProblemDescriptor() {
            @Override
            public PsiElement getPsiElement() {
                return element;
            }

            @Override
            public PsiElement getStartElement() {
                return element;
            }

            @Override
            public PsiElement getEndElement() {
                return element;
            }

            @Nullable
            @Override
            public TextRange getTextRangeInElement() {
                return element != null ? element.getTextRange() : null;
            }

            // 修正：實現無參 getLineNumber
            @Override
            public int getLineNumber() {
                return CathayBkCheckinHandler.getLineNumber(project, element);
            }

            @NotNull
            @Override
            public ProblemHighlightType getHighlightType() {
                return ProblemHighlightType.GENERIC_ERROR_OR_WARNING;
            }

            @Override
            public boolean isAfterEndOfLine() {
                return false;
            }

            // 修正：添加 @NotNull
            @Override
            public void setTextAttributes(@NotNull TextAttributesKey key) {
            }

            @Nullable
            @Override
            public ProblemGroup getProblemGroup() {
                return null;
            }

            @Override
            public void setProblemGroup(@Nullable ProblemGroup problemGroup) {
            }

            @Override
            public boolean showTooltip() {
                return false;
            }

            @Nullable
            @Override
            public String getDescriptionTemplate() {
                return description;
            }

            @Nullable
            @Override
            public QuickFix<?>[] getFixes() {
                return null;
            }
            // 移除 getProject 的 @Override
            // @NotNull public Project getProject() { return project; }
        };
    }

    /**
     * 輔助方法：查找 JTree 所在的 JSplitPane 下方的 JEditorPane
     */
    private JEditorPane findDetailsPane(JTree tree) {
        if (tree == null)
            return null;
        Container parent = tree;
        while (parent != null && !(parent instanceof JSplitPane)) {
            parent = parent.getParent();
        }
        if (parent instanceof JSplitPane) {
            JSplitPane splitPane = (JSplitPane) parent;
            Component bottomComponent = splitPane.getBottomComponent();
            if (bottomComponent instanceof JBScrollPane) {
                Component view = ((JBScrollPane) bottomComponent).getViewport().getView();
                if (view instanceof JEditorPane) {
                    return (JEditorPane) view;
                }
            } else if (bottomComponent instanceof JEditorPane) {
                return (JEditorPane) bottomComponent;
            }
        }
        return null;
    }

    /**
     * 輔助方法：HTML 轉義，將特殊字符替換為 HTML 實體。(已修正)
     */
    private static String escapeHtml(String text) {
        if (text == null)
            return "";
        return text.replace("&", "&")
                .replace("<", "<")
                .replace(">", ">")
                .replace("'", "'");
    }

    /**
     * 嘗試修復所有可自動修復的問題
     */
    private void applyAllQuickFixes() {
        if (problemTree == null || originalProblems == null || originalProblems.isEmpty()) {
            Messages.showInfoMessage(project, "沒有發現問題需要修復", "一鍵修復全部");
            return;
        }

        // 創建進度對話框
        ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
            if (indicator != null) {
                indicator.setIndeterminate(false);
                indicator.setText("正在修復所有問題...");
            }

            // 創建問題副本，因為修復過程中會修改原始列表
            List<ProblemInfo> problemsToFix = new ArrayList<>(originalProblems);
            int totalProblems = problemsToFix.size();
            final AtomicInteger fixedCount = new AtomicInteger(0);
            final AtomicInteger failedCount = new AtomicInteger(0);

            LOG.info("開始一鍵修復全部，總共 " + totalProblems + " 個問題");

            for (int i = 0; i < problemsToFix.size(); i++) {
                if (indicator != null) {
                    indicator.setFraction((double) i / totalProblems);
                    if (indicator.isCanceled()) {
                        LOG.info("用戶取消了一鍵修復全部操作");
                        break;
                    }
                }

                final ProblemInfo problem = problemsToFix.get(i);

                // 使用 ReadAction 包裝所有對 PSI 元素的訪問
                final PsiElement element = ReadAction.compute(() -> {
                    PsiElement el = problem.getElement();
                    return (el != null && el.isValid()) ? el : null;
                });

                if (element == null) {
                    LOG.warn("問題元素無效，跳過: " + problem.getDescription());
                    failedCount.incrementAndGet();
                    continue;
                }

                // 設置當前處理的問題信息
                if (indicator != null) {
                    indicator.setText2("修復問題 " + (i + 1) + "/" + totalProblems + ": " +
                            problem.getDescription().substring(0, Math.min(50, problem.getDescription().length())) +
                            (problem.getDescription().length() > 50 ? "..." : ""));
                }

                // 在 ReadAction 中確定要應用的修復方法
                final LocalQuickFix fixToApply = ReadAction.compute(() -> determineQuickFix(problem));

                if (fixToApply == null) {
                    LOG.warn("找不到適合的修復方法: " + problem.getDescription());
                    failedCount.incrementAndGet();
                    continue;
                }

                try {
                    // 確保在 ReadAction 中創建問題描述
                    final ProblemDescriptor dummyDescriptor = ReadAction
                            .compute(() -> createDummyDescriptor(element, problem.getDescription()));

                    WriteCommandAction.runWriteCommandAction(project, () -> {
                        try {
                            fixToApply.applyFix(project, dummyDescriptor);
                            LOG.info("成功修復: " + problem.getDescription());
                            originalProblems.remove(problem);
                            fixedCount.incrementAndGet();
                        } catch (Exception ex) {
                            LOG.error("應用修復時出錯: " + ex.getMessage(), ex);
                            failedCount.incrementAndGet();
                        }
                    });
                } catch (Exception ex) {
                    LOG.error("創建修復描述時出錯: " + ex.getMessage(), ex);
                    failedCount.incrementAndGet();
                }
            }

            // 更新UI（在EDT執行）
            ApplicationManager.getApplication().invokeLater(() -> {
                if (fixedCount.get() > 0) {
                    // 刷新樹
                    if (problemTree != null) {
                        DefaultTreeModel newModel = buildTreeModel(project, originalProblems);
                        problemTree.setModel(newModel);
                        TreeUtil.expandAll(problemTree);
                    }
                    updateToolWindowContentTitle(originalProblems.size());

                    String message = "修復完成!\n\n成功修復: " + fixedCount.get() + " 個問題\n" +
                            (failedCount.get() > 0 ? "無法修復: " + failedCount.get() + " 個問題" : "");
                    Messages.showInfoMessage(project, message, "一鍵修復結果");
                } else if (failedCount.get() > 0) {
                    Messages.showWarningDialog(project, "沒有問題被修復，" + failedCount.get() + " 個問題無法自動修復。", "一鍵修復結果");
                } else {
                    Messages.showInfoMessage(project, "沒有問題需要修復", "一鍵修復結果");
                }
            });

        }, "一鍵修復所有問題", true, project);
    }
}