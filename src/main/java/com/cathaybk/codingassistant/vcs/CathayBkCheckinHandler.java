package com.cathaybk.codingassistant.vcs;

import com.cathaybk.codingassistant.common.ProblemInfo;
import com.cathaybk.codingassistant.util.CathayBkInspectionUtil;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
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
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
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
                if (afterRevision == null) continue;

                VirtualFile vf = ReadAction.compute(() -> {
                    try { return afterRevision.getFile() != null ? afterRevision.getFile().getVirtualFile() : null; }
                    catch (Exception e) { LOG.error("獲取 VirtualFile 時發生錯誤", e); return null; }
                });

                if (vf == null || !vf.isValid() || vf.getFileType().isBinary() || !vf.getName().endsWith(".java")) continue;

                ReadAction.run(() -> {
                    PsiFile psiFile = psiManager.findFile(vf);
                    if (psiFile instanceof PsiJavaFile) relevantJavaFiles.add((PsiJavaFile) psiFile);
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
                            if (indicator.isCanceled()) return;
                            allProblems.addAll(CathayBkInspectionUtil.checkServiceClassDoc(aClass));
                        }
                        @Override
                        public void visitMethod(@NotNull PsiMethod method) {
                            super.visitMethod(method);
                            if (indicator.isCanceled()) return;
                            allProblems.addAll(CathayBkInspectionUtil.checkApiMethodDoc(method));
                            allProblems.addAll(CathayBkInspectionUtil.checkMethodNaming(method));
                        }
                        @Override
                        public void visitField(@NotNull PsiField field) {
                            super.visitField(field);
                            if (indicator.isCanceled()) return;
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
                    // 先確保工具窗口顯示內容
                    showProblemsInToolWindow(project, allProblems);

                    // --- 修改點：使用 Messages.showDialog ---
                    String title = "國泰規範檢查結果";
                    String message = "發現 " + allProblems.size() + " 個國泰規範問題。\n請查看「國泰規範檢查」工具窗口中的詳細信息。";
                    // 定義按鈕文字
                    String[] buttons = {"繼續提交 (Continue)", "取消提交 (Cancel)", "查看問題 (Review Issues)"};
                    // 顯示對話框
                    // 第三個參數是默認按鈕的索引 (0: 繼續, 1: 取消, 2: 查看)
                    // 第四個參數是焦點按鈕的索引
                    // 我們可以讓 "取消提交" 作為默認焦點
                    int choice = Messages.showDialog(
                            project, // 父組件
                            message, // 顯示訊息
                            title,   // 標題
                            buttons, // 按鈕文字陣列
                            1,       // 默認選中的按鈕索引 (取消提交)
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
                            // 再次確保工具窗口可見並激活 (showProblemsInToolWindow 應該已處理)
                            ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
                            if (toolWindow != null) {
                                toolWindow.activate(null, true); // 強制激活
                            }
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
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setDividerLocation(300);
        splitPane.setResizeWeight(0.7); // 讓樹佔更多空間

        Tree problemTree = createProblemTree(project, problems); // 傳遞 ProblemInfo
        JBScrollPane treeScrollPane = new JBScrollPane(problemTree);

        JEditorPane detailsPane = new JEditorPane();
        detailsPane.setEditable(false);
        detailsPane.setContentType("text/html"); // 設置為 HTML 類型
        detailsPane.setText("<html><body><h3>請選擇一個問題查看詳細信息</h3></body></html>");
        JBScrollPane detailsScrollPane = new JBScrollPane(detailsPane);

        problemTree.addTreeSelectionListener(e -> {
            TreePath path = e.getPath();
            if (path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                Object userObject = node.getUserObject();
                if (userObject instanceof ProblemInfoNode) {
                    ProblemInfoNode problemNode = (ProblemInfoNode) userObject;
                    updateDetailsPane(project, detailsPane, problemNode.getProblemInfo()); // 傳遞 ProblemInfo
                } else {
                    detailsPane.setText("<html><body><h3>請選擇一個問題查看詳細信息</h3></body></html>");
                }
            }
        });

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
                            navigateToProblem(project, problemNode.getProblemInfo()); // 傳遞 ProblemInfo
                        }
                    }
                }
            }
        });

        splitPane.setTopComponent(treeScrollPane);
        splitPane.setBottomComponent(detailsScrollPane);

        JLabel headerLabel = new JLabel("發現 " + problems.size() + " 個國泰規範問題。請雙擊問題以導航到相應代碼位置。");
        headerLabel.setBorder(JBUI.Borders.empty(5, 10));
        headerLabel.setIcon(AllIcons.General.Warning);

        mainPanel.add(headerLabel, BorderLayout.NORTH);
        mainPanel.add(splitPane, BorderLayout.CENTER);

        return mainPanel;
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

                    for (ProblemInfo problem : fileProblems) {
                        // 只添加有效元素的問題節點
                        if (problem.getElement() != null && problem.getElement().isValid()) {
                            DefaultMutableTreeNode problemNode = new DefaultMutableTreeNode(new ProblemInfoNode(problem));
                            fileNode.add(problemNode);
                        } else {
                            LOG.warn("跳過創建無效元素的樹節點: " + problem.getDescription());
                        }
                    }
                });

        Tree tree = new Tree(treeModel);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                                                          boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                Object userObject = ((DefaultMutableTreeNode) value).getUserObject();
                if (userObject instanceof ProblemInfoNode) {
                    ProblemInfoNode problemNode = (ProblemInfoNode) userObject;
                    setText(problemNode.getShortDescription());

                    ProblemHighlightType highlightType = problemNode.getProblemInfo().getHighlightType();
                    if (highlightType == ProblemHighlightType.ERROR) {
                        setIcon(AllIcons.General.Error);
                    } else if (highlightType == ProblemHighlightType.WARNING || highlightType == ProblemHighlightType.WEAK_WARNING) {
                        setIcon(AllIcons.General.Warning);
                    } else {
                        setIcon(AllIcons.General.Information); // 例如 INFO
                    }
                } else if (userObject instanceof String) {
                    setText(userObject.toString());
                    setIcon(AllIcons.Nodes.Folder);
                } else {
                    // 根節點
                    setText("規範問題"); // 確保根節點有文字
                    setIcon(AllIcons.General.InspectionsEye);
                }
                return this;
            }
        });

        TreeUtil.expandAll(tree);
        return tree;
    }

    /**
     * 更新詳細信息面板 (使用 ProblemInfo)
     * (簡化 CSS 樣式以避免渲染錯誤)
     */
    private void updateDetailsPane(Project project, JEditorPane detailsPane, ProblemInfo problem) {
        PsiElement element = problem.getElement();
        PsiFile file = (element != null && element.isValid()) ? element.getContainingFile() : null;

        StringBuilder html = new StringBuilder();
        html.append("<html><body style='font-family: sans-serif; margin: 10px;'>");
        html.append("<h2 style='color: #2c3e50;'>問題詳情</h2>");
        html.append("<p><b>描述：</b> ").append(escapeHtml(problem.getDescription())).append("</p>");

        if (file != null) {
            html.append("<p><b>文件：</b> ").append(escapeHtml(file.getName())).append("</p>");
            int lineNumber = getLineNumber(project, element);
            if (lineNumber > 0) {
                html.append("<p><b>行號：</b> ").append(lineNumber).append("</p>");
            }
        } else {
            html.append("<p><b>文件：</b> 無法確定 (元素可能已失效)</p>");
        }

        if (element != null && element.isValid()) {
            html.append("<p><b>代碼片段：</b></p>");
            try {
                String elementText = ReadAction.compute(() -> element.getText());
                // --- 修改點：簡化 <pre> 的 style ---
                html.append("<pre style='background-color:#f0f0f0; padding:8px; border:1px solid #ccc; white-space: pre-wrap;'>") // 移除了 border-radius, overflow, word-wrap
                        .append(escapeHtml(elementText))
                        .append("</pre>");
            } catch (Exception e) {
                html.append("<pre style='color:red;'>無法顯示代碼片段</pre>");
                LOG.warn("無法獲取問題元素的文本: " + e.getMessage());
            }
        } else {
            html.append("<p><b>代碼片段：</b> 無法顯示 (元素可能已失效)</p>");
        }

        html.append("<p><b>嚴重程度：</b> ");
        ProblemHighlightType highlightType = problem.getHighlightType();
        // (嚴重程度部分的 HTML 保持不變)
        if (highlightType == ProblemHighlightType.ERROR) {
            html.append("<span style='color:red; font-weight: bold;'>錯誤</span>");
        } else if (highlightType == ProblemHighlightType.WARNING || highlightType == ProblemHighlightType.WEAK_WARNING) {
            html.append("<span style='color:orange; font-weight: bold;'>警告</span>");
        } else {
            html.append("<span style='color:gray; font-weight: bold;'>資訊</span>");
        }
        html.append("</p>");

        // 建議部分
        if (problem.getSuggestionSource() != null || problem.getSuggestedValue() != null) {
            html.append("<h3 style='color: #2c3e50; margin-top: 20px;'>建議</h3>");
            if (problem.getSuggestionSource() != null) {
                html.append("<p><b>來源：</b> ").append(escapeHtml(problem.getSuggestionSource())).append("</p>");
            }
            if (problem.getSuggestedValue() != null) {
                // --- 修改點：簡化 <code> 的 style ---
                html.append("<p><b>建議值：</b><br><code style='background-color:#e0e0e0; padding: 2px 5px;'>") // 移除了 border-radius
                        .append(escapeHtml(problem.getSuggestedValue()))
                        .append("</code></p>");
            }
        } else {
            html.append("<h3 style='color: #2c3e50; margin-top: 20px;'>修復建議</h3>");
            html.append("<p>請根據問題描述手動修改代碼。</p>");
        }

        html.append("<p style='margin-top: 15px; font-style: italic;'>提示：雙擊問題可直接跳轉到對應代碼位置</p>");
        html.append("</body></html>");

        try {
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
            // 可以嘗試設置一個簡單的錯誤訊息
            detailsPane.setText("<html><body><p style='color:red'>顯示問題詳情時發生錯誤。</p></body></html>");
        }
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
                if (endIndex < 0) endIndex = 0; // 避免負數索引
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
                        LOG.warn("問題元素的偏移量超出文檔範圍: offset=" + offset + ", length=" + document.getTextLength() + ", elementText=" + element.getText());
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
        if (text == null) return "";
        // 注意替換的順序，& 必須第一個替換
        return text.replace("&", "&")
                .replace("<", "<")
                .replace(">", ">")
                .replace("'", "'");
    }
}