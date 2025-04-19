package com.cathaybk.codingassistant.vcs;

import com.cathaybk.codingassistant.common.ProblemInfo;
import com.cathaybk.codingassistant.util.CathayBkInspectionUtil;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.DocumentAdapter;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.CheckinProjectPanel;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.checkin.CheckinHandler;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.*;
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
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Handler that runs CathayBk code inspections before committing changes.
 * (使用 CathayBkInspectionUtil 執行檢查)
 */
public class CathayBkCheckinHandler extends CheckinHandler {
    private static final Logger LOG = Logger.getInstance(CathayBkCheckinHandler.class);
    private static final String TOOL_WINDOW_ID = "國泰規範檢查";

    private final CheckinProjectPanel panel;
    private final Project project;

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

            // 1. 收集 Java 文件 (省略重複程式碼)
            for (Change change : changes) {
                if (indicator.isCanceled()) {
                    LOG.info("使用者取消了收集文件階段");
                    result.set(ReturnResult.CANCEL);
                    return;
                }
                processedCount++;
                // 避免 totalChanges 為 0
                if (totalChanges > 0) {
                    indicator.setFraction((double) processedCount / (totalChanges * 2));
                } else {
                    indicator.setFraction(0.5); // 如果沒變更，直接到一半
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

            // 2. 執行檢查 (省略重複程式碼)
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
                            allProblems.addAll(CathayBkInspectionUtil.checkMethodNaming(method));
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

            LOG.info("總共發現問題數量：" + allProblems.size());

            // 顯示問題並詢問是否繼續提交
            if (!allProblems.isEmpty()) {
                LOG.info("發現問題，顯示自訂對話框");
                // 需要在 EDT 中顯示 UI
                ApplicationManager.getApplication().invokeAndWait(() -> {
                    // 修改：不再立即顯示工具窗口，直到用戶點擊「查看問題」按鈕時才顯示
                    // showProblemsInToolWindow(project, allProblems);

                    // --- 修改點：使用 Messages.showDialog ---
                    String title = "國泰規範檢查結果";
                    String message = "發現 " + allProblems.size() + " 個國泰規範問題。\n選擇「查看問題」以查看詳細資訊。";
                    // 定義按鈕文字
                    String[] buttons = { "繼續提交 (Continue)", "取消提交 (Cancel)", "查看問題 (Review Issues)" };
                    // 顯示對話框
                    // 第三個參數是默認按鈕的索引 (0: 繼續, 1: 取消, 2: 查看)
                    // 第四個參數是焦點按鈕的索引
                    // 我們可以讓 "取消提交" 作為默認焦點
                    int choice = Messages.showDialog(
                            project, // 父組件
                            message, // 顯示訊息
                            title, // 標題
                            buttons, // 按鈕文字陣列
                            1, // 默認選中的按鈕索引 (取消提交)
                            AllIcons.General.Warning // 圖標
                    );

                    // 根據使用者的選擇設置結果
                    switch (choice) {
                        case 0: // 繼續提交 (Continue)
                            LOG.info("使用者選擇繼續提交");
                            result.set(ReturnResult.COMMIT);
                            break;
                        case 2: // 查看問題 (Review Issues)
                            LOG.info("使用者選擇查看問題，取消本次提交");
                            // 當用戶選擇「查看問題」時，才顯示工具窗口
                            showProblemsInToolWindow(project, allProblems);
                            // 取消提交，讓使用者查看後決定
                            result.set(ReturnResult.CANCEL);
                            break;
                        case 1: // 取消提交 (Cancel)
                        default: // 包括關閉對話框 (choice == -1)
                            LOG.info("使用者選擇取消提交或關閉了對話框");
                            result.set(ReturnResult.CANCEL);
                            break;
                    }
                    // --- 修改結束 ---
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
            toolWindow.setStripeTitle("國泰規範"); // 設置側邊欄標籤
        }

        toolWindow.getContentManager().removeAllContents(true);
        JComponent problemsPanel = createProblemsPanel(project, problems); // 傳遞 ProblemInfo
        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(problemsPanel, "檢查結果 (" + problems.size() + ")", false);
        toolWindow.getContentManager().addContent(content);
        toolWindow.show(); // 確保顯示
        toolWindow.activate(null); // 嘗試激活
    }

    /**
     * 創建問題顯示面板 (使用 ProblemInfo)
     */
    private JComponent createProblemsPanel(Project project, List<ProblemInfo> problems) {
        JPanel mainPanel = new JPanel(new BorderLayout());
        // 使用 IDEA 風格的背景色
        mainPanel.setBackground(UIManager.getColor("Panel.background"));

        // 使用三等分的布局
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setDividerLocation(350); // 調整分隔位置更合理
        splitPane.setResizeWeight(0.6); // 保持樹區域占主要部分
        splitPane.setBorder(null); // 移除邊框以獲得更整潔的外觀
        splitPane.setDividerSize(3); // 設置分隔線更細，更符合 IDEA 風格

        // 創建問題樹，使用 IDEA 風格
        Tree problemTree = createProblemTree(project, problems);
        JBScrollPane treeScrollPane = new JBScrollPane(problemTree);
        treeScrollPane.setBorder(JBUI.Borders.empty()); // 無邊框效果

        // 使用 IDEA 搜索欄加強工具窗體驗
        SearchTextField searchField = new SearchTextField();
        searchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                String text = searchField.getText().toLowerCase();
                filterTree(problemTree, text);
            }
        });

        // 創建包含搜索框的頂部面板
        JPanel treePanel = new JPanel(new BorderLayout());
        treePanel.add(searchField, BorderLayout.NORTH);
        treePanel.add(treeScrollPane, BorderLayout.CENTER);

        // 詳細信息面板美化
        JEditorPane detailsPane = new JEditorPane();
        detailsPane.setEditable(false);
        detailsPane.setContentType("text/html");
        detailsPane.setBackground(UIManager.getColor("EditorPane.background"));

        // 使用超簡化的HTML，完全避免CSS
        try {
            detailsPane.setText("<html><body><center><br><br><br>" +
                    "<p>ℹ️</p>" +
                    "<p>請選擇一個問題查看詳細信息</p>" +
                    "</center></body></html>");
        } catch (Exception e) {
            LOG.error("設置初始詳情面板HTML時出錯: " + e.getMessage(), e);
            try {
                detailsPane.setText("<html><body><p>請選擇一個問題</p></body></html>");
            } catch (Exception ex) {
                LOG.error("無法設置簡單的初始詳情面板HTML: " + ex.getMessage(), ex);
            }
        }

        JBScrollPane detailsScrollPane = new JBScrollPane(detailsPane);
        detailsScrollPane.setBorder(JBUI.Borders.empty());

        problemTree.addTreeSelectionListener(e -> {
            TreePath path = e.getPath();
            if (path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                Object userObject = node.getUserObject();
                if (userObject instanceof ProblemInfoNode) {
                    ProblemInfoNode problemNode = (ProblemInfoNode) userObject;
                    updateDetailsPane(project, detailsPane, problemNode.getProblemInfo());
                } else {
                    // 如果選擇了目錄節點，顯示摘要信息而不是空白
                    if (userObject instanceof String) {
                        String fileName = (String) userObject;
                        int childCount = node.getChildCount();
                        try {
                            StringBuilder summary = new StringBuilder();
                            summary.append("<html><body>");
                            summary.append("<h2>").append(fileName).append("</h2>");
                            summary.append("<p>此文件中發現 <b>").append(childCount).append("</b> 個問題</p>");
                            if (childCount > 0) {
                                summary.append("<p>點擊左側問題查看詳情，或雙擊問題直接跳轉到代碼位置</p>");
                            }
                            summary.append("</body></html>");
                            detailsPane.setText(summary.toString());
                        } catch (Exception ex) {
                            LOG.error("設置文件節點摘要時出錯: " + ex.getMessage(), ex);
                            try {
                                detailsPane.setText(
                                        "<html><body><p>" + fileName + " - " + childCount + " 個問題</p></body></html>");
                            } catch (Exception e2) {
                                LOG.error("無法設置簡單的文件節點摘要: " + e2.getMessage(), e2);
                            }
                        }
                    } else {
                        // 處理根節點選擇
                        try {
                            StringBuilder summary = new StringBuilder();
                            summary.append("<html><body>");
                            summary.append("<h2>國泰規範檢查結果</h2>");
                            summary.append("<p>共發現 <b>").append(problems.size()).append("</b> 個問題，分布在 ")
                                    .append(((DefaultMutableTreeNode) problemTree.getModel().getRoot()).getChildCount())
                                    .append(" 個文件中</p>");

                            // 按嚴重程度分類統計
                            Map<ProblemHighlightType, Long> typeCounts = problems.stream()
                                    .collect(Collectors.groupingBy(ProblemInfo::getHighlightType,
                                            Collectors.counting()));

                            if (!typeCounts.isEmpty()) {
                                summary.append("<h3>問題分布</h3>");
                                summary.append("<ul>");

                                // 錯誤
                                long errorCount = typeCounts.getOrDefault(ProblemHighlightType.ERROR, 0L);
                                if (errorCount > 0) {
                                    summary.append("<li><b>錯誤:</b> ").append(errorCount).append(" 個</li>");
                                }

                                // 警告
                                long warningCount = typeCounts.getOrDefault(ProblemHighlightType.WARNING, 0L) +
                                        typeCounts.getOrDefault(ProblemHighlightType.WEAK_WARNING, 0L);
                                if (warningCount > 0) {
                                    summary.append("<li><b>警告:</b> ").append(warningCount).append(" 個</li>");
                                }

                                // 資訊
                                long infoCount = typeCounts.getOrDefault(ProblemHighlightType.INFORMATION, 0L);
                                if (infoCount > 0) {
                                    summary.append("<li><b>資訊:</b> ").append(infoCount).append(" 個</li>");
                                }

                                summary.append("</ul>");
                            }

                            summary.append("</body></html>");
                            detailsPane.setText(summary.toString());
                        } catch (Exception ex) {
                            LOG.error("設置根節點摘要時出錯: " + ex.getMessage(), ex);
                            try {
                                detailsPane.setText(
                                        "<html><body><p>國泰規範檢查結果 - " + problems.size() + " 個問題</p></body></html>");
                            } catch (Exception e2) {
                                LOG.error("無法設置簡單的根節點摘要: " + e2.getMessage(), e2);
                            }
                        }
                    }
                }
            }
        });

        // 添加雙擊跳轉功能 (保持不變)
        problemTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    TreePath path = problemTree.getPathForLocation(e.getX(), e.getY());
                    if (path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode) {
                        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                        Object userObject = node.getUserObject();
                        if (userObject instanceof ProblemInfoNode) {
                            ProblemInfoNode problemNode = (ProblemInfoNode) userObject;
                            navigateToProblem(project, problemNode.getProblemInfo());
                        }
                    }
                }
            }
        });

        splitPane.setTopComponent(treePanel);
        splitPane.setBottomComponent(detailsScrollPane);

        // 頂部工具欄，包含標題和操作按鈕
        JPanel toolbarPanel = new JPanel(new BorderLayout());
        toolbarPanel.setBorder(JBUI.Borders.empty(6, 10));

        // 左側標題
        JLabel titleLabel = new JLabel("國泰規範檢查結果");
        titleLabel.setIcon(AllIcons.General.InspectionsEye);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, titleLabel.getFont().getSize() + 2));
        toolbarPanel.add(titleLabel, BorderLayout.WEST);

        // 右側按鈕組
        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));

        // 刷新按鈕
        JButton refreshButton = new JButton("重新檢查", AllIcons.Actions.Refresh);
        refreshButton.addActionListener(e -> Messages.showInfoMessage(project, "此功能將在未來版本實現", "功能預告"));

        // 過濾按鈕
        JButton filterButton = new JButton("過濾", AllIcons.General.Filter);
        filterButton.addActionListener(e -> Messages.showInfoMessage(project, "此功能將在未來版本實現", "功能預告"));

        // 幫助按鈕
        JButton helpButton = new JButton("", AllIcons.Actions.Help);
        helpButton.setToolTipText("顯示幫助");
        helpButton.addActionListener(e -> Messages.showInfoMessage(project,
                "雙擊問題：導航到代碼位置\n" +
                        "選擇問題：在下方面板查看詳情\n" +
                        "搜索框：輸入文字過濾問題",
                "使用幫助"));

        // 將按鈕添加到面板
        actionsPanel.add(refreshButton);
        actionsPanel.add(filterButton);
        actionsPanel.add(helpButton);
        toolbarPanel.add(actionsPanel, BorderLayout.EAST);

        // 組合最終面板
        mainPanel.add(toolbarPanel, BorderLayout.NORTH);
        mainPanel.add(splitPane, BorderLayout.CENTER);

        // 自動展開樹
        TreeUtil.expandAll(problemTree);

        return mainPanel;
    }

    /**
     * 根據搜索文本過濾樹
     */
    private void filterTree(Tree tree, String searchText) {
        DefaultTreeModel model = (DefaultTreeModel) tree.getModel();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) model.getRoot();

        // 如果搜索文本為空，展開所有節點
        if (searchText.isEmpty()) {
            TreeUtil.expandAll(tree);
            return;
        }

        // 關閉所有節點
        TreeUtil.collapseAll(tree, 0);

        // 遍歷樹節點查找匹配項
        Enumeration<TreeNode> fileNodes = root.children();
        while (fileNodes.hasMoreElements()) {
            DefaultMutableTreeNode fileNode = (DefaultMutableTreeNode) fileNodes.nextElement();
            boolean fileMatched = false;

            // 檢查文件名是否匹配
            if (fileNode.getUserObject().toString().toLowerCase().contains(searchText)) {
                fileMatched = true;
            }

            // 檢查問題是否匹配
            Enumeration<TreeNode> problemNodes = fileNode.children();
            while (problemNodes.hasMoreElements()) {
                DefaultMutableTreeNode problemNode = (DefaultMutableTreeNode) problemNodes.nextElement();
                if (problemNode.getUserObject() instanceof ProblemInfoNode) {
                    ProblemInfoNode infoNode = (ProblemInfoNode) problemNode.getUserObject();
                    if (infoNode.getShortDescription().toLowerCase().contains(searchText)) {
                        fileMatched = true;
                        // 展開到這個問題節點
                        TreePath path = new TreePath(model.getPathToRoot(problemNode));
                        tree.expandPath(path);
                    }
                }
            }

            // 如果文件節點匹配，展開它
            if (fileMatched) {
                TreePath path = new TreePath(model.getPathToRoot(fileNode));
                tree.expandPath(path);
            }
        }
    }

    /**
     * 創建問題樹 (使用 ProblemInfo)
     */
    private Tree createProblemTree(Project project, List<ProblemInfo> problems) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("規範問題");
        DefaultTreeModel treeModel = new DefaultTreeModel(root);

        // 按文件分組
        problems.stream()
                .collect(Collectors.groupingBy(problem -> {
                    PsiElement element = problem.getElement();
                    // 增加檢查 element 是否有效
                    if (element != null && element.isValid() && element.getContainingFile() != null) {
                        return element.getContainingFile().getName();
                    }
                    return "未知或無效文件"; // 處理無效元素的情況
                }))
                .forEach((fileName, fileProblems) -> {
                    DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(fileName);
                    root.add(fileNode);

                    // 按問題嚴重程度排序
                    fileProblems.stream()
                            .filter(p -> p.getElement() != null && p.getElement().isValid())
                            .sorted((p1, p2) -> compareHighlightType(p1.getHighlightType(), p2.getHighlightType()))
                            .forEach(problem -> {
                                DefaultMutableTreeNode problemNode = new DefaultMutableTreeNode(
                                        new ProblemInfoNode(problem));
                                fileNode.add(problemNode);
                            });
                });

        Tree tree = new Tree(treeModel);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                    boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                // 提高文字對比度
                setForeground(UIManager.getColor("Tree.foreground"));

                Object userObject = ((DefaultMutableTreeNode) value).getUserObject();
                if (userObject instanceof ProblemInfoNode) {
                    ProblemInfoNode problemNode = (ProblemInfoNode) userObject;
                    setText(problemNode.getShortDescription());

                    // 根據問題類型設置圖標
                    ProblemHighlightType highlightType = problemNode.getProblemInfo().getHighlightType();
                    if (highlightType == ProblemHighlightType.ERROR) {
                        setIcon(AllIcons.General.Error);
                    } else if (highlightType == ProblemHighlightType.WARNING
                            || highlightType == ProblemHighlightType.WEAK_WARNING) {
                        setIcon(AllIcons.General.Warning);
                    } else {
                        setIcon(AllIcons.General.Information);
                    }
                } else if (userObject instanceof String) {
                    // 美化文件節點
                    setText(userObject.toString());
                    // 根據檔案類型選擇圖示
                    if (userObject.toString().endsWith(".java")) {
                        setIcon(AllIcons.FileTypes.Java);
                    } else {
                        setIcon(AllIcons.Nodes.Folder);
                    }
                } else {
                    // 根節點
                    setText("規範問題");
                    setIcon(AllIcons.General.InspectionsEye);
                }
                return this;
            }
        });

        return tree;
    }

    /**
     * 比較問題嚴重程度，用於排序
     */
    private int compareHighlightType(ProblemHighlightType type1, ProblemHighlightType type2) {
        // 錯誤 > 警告 > 弱警告 > 信息
        int priority1 = getPriorityForType(type1);
        int priority2 = getPriorityForType(type2);
        return Integer.compare(priority1, priority2);
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
     * 更新詳細信息面板 (使用 ProblemInfo)
     */
    private void updateDetailsPane(Project project, JEditorPane detailsPane, ProblemInfo problem) {
        PsiElement element = problem.getElement();
        PsiFile file = (element != null && element.isValid()) ? element.getContainingFile() : null;

        try {
            // 使用極簡HTML，完全避免CSS樣式
            StringBuilder html = new StringBuilder();
            html.append("<html><body>");

            // 標題
            html.append("<h2>問題詳情</h2>");
            html.append("<hr>");

            // 問題嚴重程度
            ProblemHighlightType highlightType = problem.getHighlightType();
            String severityText = highlightType == ProblemHighlightType.ERROR ? "錯誤"
                    : (highlightType == ProblemHighlightType.WARNING
                            || highlightType == ProblemHighlightType.WEAK_WARNING)
                                    ? "警告"
                                    : "資訊";

            html.append("<p><b>嚴重程度：</b> ").append(severityText).append("</p>");

            // 問題描述
            html.append("<p><b>描述：</b><br>").append(escapeHtml(problem.getDescription())).append("</p>");

            // 文件信息
            if (file != null) {
                html.append("<p><b>文件：</b> ").append(escapeHtml(file.getName())).append("</p>");
                int lineNumber = getLineNumber(project, element);
                if (lineNumber > 0) {
                    html.append("<p><b>行號：</b> ").append(lineNumber).append("</p>");
                }
            } else {
                html.append("<p><b>文件：</b> 無法確定 (元素可能已失效)</p>");
            }

            // 代碼片段
            if (element != null && element.isValid()) {
                html.append("<h3>代碼片段</h3>");
                try {
                    String elementText = ReadAction.compute(() -> element.getText());
                    html.append("<pre>").append(escapeHtml(elementText)).append("</pre>");
                } catch (Exception e) {
                    html.append("<p>無法顯示代碼片段</p>");
                    LOG.warn("無法獲取問題元素的文本: " + e.getMessage());
                }
            }

            // 建議部分
            if (problem.getSuggestionSource() != null || problem.getSuggestedValue() != null) {
                html.append("<h3>建議</h3>");

                if (problem.getSuggestionSource() != null) {
                    html.append("<p><b>來源：</b> ").append(escapeHtml(problem.getSuggestionSource())).append("</p>");
                }

                if (problem.getSuggestedValue() != null) {
                    html.append("<p><b>建議值：</b></p>");
                    html.append("<pre>").append(escapeHtml(problem.getSuggestedValue())).append("</pre>");
                }
            } else {
                html.append("<h3>修復建議</h3>");
                html.append("<p>請根據問題描述手動修改代碼。</p>");
            }

            // 快速操作提示
            html.append("<h3>快速操作</h3>");
            html.append("<ul>");
            html.append("<li>雙擊問題樹中的問題項可直接跳轉至代碼位置</li>");
            html.append("<li>修復後，重新運行檢查以更新結果</li>");
            html.append("</ul>");

            html.append("</body></html>");

            // 在設置文本之前，確保 detailsPane 仍然可用
            if (detailsPane.isDisplayable()) {
                detailsPane.setText(html.toString());
                detailsPane.setCaretPosition(0); // 確保滾動到頂部
            } else {
                LOG.warn("Details pane is not displayable when trying to set text.");
            }
        } catch (Exception e) {
            // 捕獲 setText 可能拋出的其他異常
            LOG.error("Error setting text in JEditorPane: " + e.getMessage(), e);
            // 嘗試設置一個超級簡單的錯誤訊息，不使用任何CSS
            try {
                detailsPane.setText("<html><body><p>顯示問題詳情時發生錯誤。</p></body></html>");
            } catch (Exception ex) {
                LOG.error("無法設置簡單的錯誤訊息: " + ex.getMessage(), ex);
            }
        }
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
        // 導航前再次檢查有效性
        if (element != null && element.isValid()) {
            PsiFile containingFile = element.getContainingFile();
            if (containingFile != null) {
                VirtualFile virtualFile = containingFile.getVirtualFile();
                if (virtualFile != null && virtualFile.isValid()) {
                    int offset = element.getTextOffset();
                    OpenFileDescriptor descriptor = new OpenFileDescriptor(project, virtualFile, offset);
                    // 嘗試打開編輯器並定位
                    FileEditorManager.getInstance(project).openTextEditor(descriptor, true); // true for focus
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
            // 獲取行號時傳入 project
            int lineNumber = getLineNumber(element != null ? element.getProject() : null, element);

            String lineSuffix = (lineNumber > 0) ? " (第 " + lineNumber + " 行)" : "";
            final int MAX_LEN = 80; // 最大顯示長度
            // 避免 NullPointerException
            String safeDesc = desc != null ? desc : "描述為空";
            if (safeDesc.length() > MAX_LEN - lineSuffix.length()) {
                // 計算截斷位置，確保不切斷 UTF-8 字符
                int endIndex = MAX_LEN - lineSuffix.length() - 3;
                if (endIndex < 0)
                    endIndex = 0; // 避免負數索引
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
        if (element == null || !element.isValid() || project == null) {
            return -1;
        }
        // 確保在 ReadAction 中獲取文件和文檔
        return ReadAction.compute(() -> {
            PsiFile containingFile = element.getContainingFile();
            if (containingFile == null) {
                return -1;
            }
            Document document = PsiDocumentManager.getInstance(project).getDocument(containingFile);
            if (document != null) {
                try {
                    int offset = element.getTextOffset();
                    if (offset >= 0 && offset <= document.getTextLength()) {
                        return document.getLineNumber(offset) + 1;
                    } else {
                        LOG.warn("問題元素的偏移量超出文檔範圍: offset=" + offset + ", length=" + document.getTextLength()
                                + ", elementText=" + element.getText());
                        return -1;
                    }
                } catch (IndexOutOfBoundsException e) {
                    LOG.debug("獲取行號時發生 IndexOutOfBoundsException: elementText=" + element.getText(), e);
                    return -1;
                } catch (PsiInvalidElementAccessException e) {
                    LOG.warn("獲取行號時元素失效: " + e.getMessage());
                    return -1;
                }
            }
            return -1;
        });
    }

    /**
     * 輔助方法：HTML 轉義，將特殊字符替換為 HTML 實體。
     *
     * @param text 要轉義的文本
     * @return 轉義後的 HTML 安全文本
     */
    private static String escapeHtml(String text) {
        if (text == null)
            return "";
        // 注意替換的順序，& 必須第一個替換
        return text.replace("&", "&")
                .replace("<", "<")
                .replace(">", ">")
                .replace("'", "'");
    }
}