package com.cathaybk.codingassistant.apicopy.model;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * API 依賴圖結構
 * 用於儲存和管理 API 的所有依賴關係
 */
public class DependencyGraph {

    /**
     * 依賴節點，代表一個檔案及其類型
     */
    public static class DependencyNode {
        private final ApiFileType fileType;
        private final SmartPsiElementPointer<PsiFile> filePointer;
        private final SmartPsiElementPointer<PsiClass> classPointer;
        private final String displayName;
        private final String filePath;
        private boolean selected;

        public DependencyNode(@NotNull ApiFileType fileType,
                              @NotNull PsiFile file,
                              @Nullable PsiClass psiClass) {
            this.fileType = fileType;
            this.filePointer = SmartPointerManager.getInstance(file.getProject())
                    .createSmartPsiElementPointer(file);
            this.classPointer = psiClass != null
                    ? SmartPointerManager.getInstance(psiClass.getProject())
                            .createSmartPsiElementPointer(psiClass)
                    : null;
            this.displayName = file.getName();
            VirtualFile vf = file.getVirtualFile();
            this.filePath = vf != null ? vf.getPath() : file.getName();
            this.selected = true;
        }

        /**
         * 建立 SQL 檔案的節點
         */
        public static DependencyNode createSqlNode(@NotNull String sqlPath,
                                                    @NotNull PsiFile sqlFile) {
            return new DependencyNode(ApiFileType.SQL_FILE, sqlFile, null);
        }

        @NotNull
        public ApiFileType getFileType() {
            return fileType;
        }

        @Nullable
        public PsiFile getFile() {
            return filePointer.getElement();
        }

        @Nullable
        public PsiClass getPsiClass() {
            return classPointer != null ? classPointer.getElement() : null;
        }

        @NotNull
        public String getDisplayName() {
            return displayName;
        }

        @NotNull
        public String getFilePath() {
            return filePath;
        }

        public boolean isSelected() {
            return selected;
        }

        public void setSelected(boolean selected) {
            this.selected = selected;
        }

        /**
         * 檢查節點是否有效
         */
        public boolean isValid() {
            PsiFile file = filePointer.getElement();
            return file != null && file.isValid();
        }

        /**
         * 取得檔案內容
         */
        @Nullable
        public String getContent() {
            PsiFile file = filePointer.getElement();
            return file != null ? file.getText() : null;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DependencyNode that = (DependencyNode) o;
            return filePath.equals(that.filePath);
        }

        @Override
        public int hashCode() {
            return filePath.hashCode();
        }

        @Override
        public String toString() {
            return "[" + fileType.getDisplayName() + "] " + displayName;
        }
    }

    private final ApiInfo apiInfo;
    private final List<DependencyNode> nodes;
    private final Map<ApiFileType, List<DependencyNode>> nodesByType;
    private int totalFileCount;
    private int totalLineCount;

    public DependencyGraph(@NotNull ApiInfo apiInfo) {
        this.apiInfo = apiInfo;
        this.nodes = new ArrayList<>();
        this.nodesByType = new LinkedHashMap<>();
        this.totalFileCount = 0;
        this.totalLineCount = 0;
    }

    /**
     * 添加依賴節點
     */
    public void addNode(@NotNull DependencyNode node) {
        if (!nodes.contains(node)) {
            nodes.add(node);
            nodesByType.computeIfAbsent(node.getFileType(), k -> new ArrayList<>()).add(node);
            totalFileCount++;

            // 計算行數
            String content = node.getContent();
            if (content != null) {
                totalLineCount += content.split("\n").length;
            }
        }
    }

    /**
     * 取得所有節點
     */
    @NotNull
    public List<DependencyNode> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    /**
     * 取得被選中的節點
     */
    @NotNull
    public List<DependencyNode> getSelectedNodes() {
        List<DependencyNode> selected = new ArrayList<>();
        for (DependencyNode node : nodes) {
            if (node.isSelected() && node.isValid()) {
                selected.add(node);
            }
        }
        return selected;
    }

    /**
     * 依類型取得節點
     */
    @NotNull
    public List<DependencyNode> getNodesByType(@NotNull ApiFileType type) {
        return nodesByType.getOrDefault(type, Collections.emptyList());
    }

    /**
     * 取得依賴的所有檔案類型
     */
    @NotNull
    public Set<ApiFileType> getFileTypes() {
        return nodesByType.keySet();
    }

    @NotNull
    public ApiInfo getApiInfo() {
        return apiInfo;
    }

    public int getTotalFileCount() {
        return totalFileCount;
    }

    public int getTotalLineCount() {
        return totalLineCount;
    }

    /**
     * 生成所有選中檔案的合併內容
     */
    @NotNull
    public String generateMergedContent() {
        StringBuilder sb = new StringBuilder();

        sb.append("// ============================================================\n");
        sb.append("// API: ").append(apiInfo.getMsgId()).append("\n");
        if (apiInfo.getDescription() != null) {
            sb.append("// Description: ").append(apiInfo.getDescription()).append("\n");
        }
        sb.append("// Total Files: ").append(getSelectedNodes().size()).append("\n");
        sb.append("// ============================================================\n\n");

        // 按類型分組輸出
        for (ApiFileType type : ApiFileType.values()) {
            List<DependencyNode> typeNodes = getNodesByType(type);
            for (DependencyNode node : typeNodes) {
                if (node.isSelected() && node.isValid()) {
                    sb.append("// ").append("=".repeat(60)).append("\n");
                    sb.append("// File: ").append(node.getDisplayName()).append("\n");
                    sb.append("// Type: ").append(type.getChineseName()).append("\n");
                    sb.append("// Path: ").append(node.getFilePath()).append("\n");
                    sb.append("// ").append("=".repeat(60)).append("\n\n");

                    String content = node.getContent();
                    if (content != null) {
                        sb.append(content);
                    }
                    sb.append("\n\n");
                }
            }
        }

        return sb.toString();
    }

    /**
     * 取得摘要資訊
     */
    @NotNull
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("API: ").append(apiInfo.getMsgId());
        if (apiInfo.getDescription() != null) {
            sb.append(" - ").append(apiInfo.getDescription());
        }
        sb.append("\n");
        sb.append("檔案數: ").append(totalFileCount);
        sb.append(", 行數: ").append(totalLineCount).append("\n");

        for (ApiFileType type : ApiFileType.values()) {
            List<DependencyNode> typeNodes = getNodesByType(type);
            if (!typeNodes.isEmpty()) {
                sb.append("  - ").append(type.getChineseName()).append(": ")
                  .append(typeNodes.size()).append(" 個\n");
            }
        }

        return sb.toString();
    }

    @Override
    public String toString() {
        return "DependencyGraph{" +
                "apiInfo=" + apiInfo +
                ", nodeCount=" + nodes.size() +
                '}';
    }
}
