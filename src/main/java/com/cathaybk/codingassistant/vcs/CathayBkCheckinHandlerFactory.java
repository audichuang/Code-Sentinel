package com.cathaybk.codingassistant.vcs;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vcs.CheckinProjectPanel;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.CommitContext;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.checkin.CheckinHandler;
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory for creating CathayBkCheckinHandler instances.
 */
public class CathayBkCheckinHandlerFactory extends CheckinHandlerFactory {
    private static final Logger LOG = Logger.getInstance(CathayBkCheckinHandlerFactory.class);

    @NotNull
    @Override
    public CheckinHandler createHandler(@NotNull CheckinProjectPanel panel, @NotNull CommitContext commitContext) {
        System.out.println("===========================================");
        System.out.println("CathayBk Checkin Handler Factory 被呼叫！");
        System.out.println("項目名稱：" + panel.getProject().getName());
        System.out.println("已選變更數量：" + panel.getSelectedChanges().size());
        System.out.println("===========================================");

        LOG.info("CathayBk Checkin Handler Factory 被呼叫！項目：" + panel.getProject().getName() + ", 變更數量："
                + panel.getSelectedChanges().size());

        // 檢查 panel 是否包含 Java 文件
        List<String> javaFilePaths = new ArrayList<>();
        boolean hasJavaFiles = panel.getSelectedChanges().stream()
                .filter(change -> change.getAfterRevision() != null)
                .anyMatch(change -> {
                    String path = change.getAfterRevision().getFile().getPath();
                    boolean isJava = path != null && path.endsWith(".java");
                    if (isJava) {
                        System.out.println("發現 Java 文件：" + path);
                        javaFilePaths.add(path);
                    }
                    return isJava;
                });

        System.out.println("提交中包含 Java 文件：" + hasJavaFiles);
        if (hasJavaFiles) {
            System.out.println("Java 文件清單：");
            for (String path : javaFilePaths) {
                System.out.println(" - " + path);
            }
        }
        LOG.info("提交中包含 Java 文件：" + hasJavaFiles);

        // 檢查文件內容可訪問性
        for (Change change : panel.getSelectedChanges()) {
            ContentRevision afterRevision = change.getAfterRevision();
            if (afterRevision != null) {
                String path = afterRevision.getFile().getPath();
                if (path != null && path.endsWith(".java")) {
                    VirtualFile vf = afterRevision.getFile().getVirtualFile();
                    if (vf != null) {
                        System.out.println("檢查 Java 文件可訪問性: " + path);
                        System.out.println(" - 文件存在: " + vf.exists());
                        System.out.println(" - 文件有效: " + vf.isValid());
                        System.out.println(" - 是目錄: " + vf.isDirectory());
                        System.out.println(" - 文件類型: " + vf.getFileType().getName());
                        System.out.println(" - 文件路徑: " + vf.getPath());
                    } else {
                        System.out.println("無法獲取 VirtualFile: " + path);
                    }
                }
            }
        }

        // Create and return the handler for the commit process
        CathayBkCheckinHandler handler = new CathayBkCheckinHandler(panel);
        System.out.println("成功創建 CathayBkCheckinHandler: " + handler);
        return handler;
    }

    // Optional: Override createSystemReadyHandler if needed for non-modal commit
    // interface
    // @Override
    // public CheckinHandler createSystemReadyHandler(@NotNull Project project) {
    // // Return a handler instance suitable for the non-modal commit UI
    // // For now, let's return null or a basic handler if not fully implemented
    // return null; // Or implement a handler for non-modal commit
    // }
}