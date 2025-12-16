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
        private boolean recursiveDependency;  // 是否為遞迴依賴（非直接依賴）

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
         * 是否為遞迴依賴（非直接依賴）
         */
        public boolean isRecursiveDependency() {
            return recursiveDependency;
        }

        /**
         * 設定是否為遞迴依賴
         */
        public void setRecursiveDependency(boolean recursiveDependency) {
            this.recursiveDependency = recursiveDependency;
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
     * 添加遞迴依賴節點（標記為非直接依賴，預設不選中）
     */
    public void addRecursiveNode(@NotNull DependencyNode node) {
        node.setRecursiveDependency(true);
        node.setSelected(false);  // 遞迴依賴預設不選中
        addNode(node);
    }

    /**
     * 取得所有節點
     */
    @NotNull
    public List<DependencyNode> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    /**
     * 取得直接依賴節點（非遞迴依賴）
     */
    @NotNull
    public List<DependencyNode> getDirectDependencyNodes() {
        List<DependencyNode> direct = new ArrayList<>();
        for (DependencyNode node : nodes) {
            if (!node.isRecursiveDependency()) {
                direct.add(node);
            }
        }
        return direct;
    }

    /**
     * 取得遞迴依賴節點
     */
    @NotNull
    public List<DependencyNode> getRecursiveDependencyNodes() {
        List<DependencyNode> recursive = new ArrayList<>();
        for (DependencyNode node : nodes) {
            if (node.isRecursiveDependency()) {
                recursive.add(node);
            }
        }
        return recursive;
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
     * 使用 ClipCode 兼容格式，支援 Paste and Restore Files 功能
     */
    @NotNull
    public String generateMergedContent() {
        StringBuilder sb = new StringBuilder();

        boolean firstFile = true;
        for (ApiFileType type : ApiFileType.values()) {
            List<DependencyNode> typeNodes = getNodesByType(type);
            for (DependencyNode node : typeNodes) {
                if (node.isSelected() && node.isValid()) {
                    // 檔案之間加空行
                    if (!firstFile) {
                        sb.append("\n");
                    }
                    firstFile = false;

                    // ClipCode 格式: // file: <相對路徑>
                    String relativePath = getRelativePath(node.getFilePath());
                    sb.append("// file: ").append(relativePath).append("\n");

                    String content = node.getContent();
                    if (content != null) {
                        sb.append(content);
                        // 確保內容以換行結尾
                        if (!content.endsWith("\n")) {
                            sb.append("\n");
                        }
                    }
                }
            }
        }

        return sb.toString();
    }

    /**
     * 將絕對路徑轉換為相對路徑（ClipCode 兼容格式）
     */
    @NotNull
    private String getRelativePath(@NotNull String absolutePath) {
        // 統一使用正斜杠
        String normalizedPath = absolutePath.replace('\\', '/');

        // 嘗試找出專案相對路徑起點
        String[] markers = {
            "src/main/java/",
            "src/main/resources/",
            "src/test/java/",
            "src/test/resources/",
            "src/"
        };

        for (String marker : markers) {
            int index = normalizedPath.indexOf(marker);
            if (index >= 0) {
                return normalizedPath.substring(index);
            }
        }

        // 如果找不到標記，返回檔案名
        int lastSlash = normalizedPath.lastIndexOf('/');
        return lastSlash >= 0 ? normalizedPath.substring(lastSlash + 1) : normalizedPath;
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
