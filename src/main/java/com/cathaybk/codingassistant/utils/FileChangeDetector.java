package com.cathaybk.codingassistant.utils;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 智能檔案變更檢測器
 * 只處理真正有變更的 Java 檔案，大幅減少不必要的檢查
 */
public class FileChangeDetector {
    private static final Logger LOG = Logger.getInstance(FileChangeDetector.class);
    
    // 記錄已知的檔案修改時間戳
    private final Map<String, Long> lastKnownModificationStamps = new ConcurrentHashMap<>();
    
    // 快取檔案類型檢查結果
    private final Map<String, Boolean> fileTypeCache = new ConcurrentHashMap<>();
    
    /**
     * 檢查檔案是否已變更
     * 
     * @param file 要檢查的檔案
     * @return true 如果檔案已變更或第一次檢查
     */
    public boolean hasFileChanged(@NotNull VirtualFile file) {
        String path = file.getPath();
        long currentStamp = file.getModificationStamp();
        Long lastKnown = lastKnownModificationStamps.get(path);
        
        if (lastKnown == null || lastKnown != currentStamp) {
            lastKnownModificationStamps.put(path, currentStamp);
            return true;
        }
        return false;
    }
    
    /**
     * 從變更集合中篩選出真正有變更的 Java 檔案
     * 這是效能優化的關鍵：只檢查實際變更的檔案
     * 
     * @param changes VCS 變更集合
     * @return 已變更的 Java 檔案集合
     */
    public Set<VirtualFile> filterChangedJavaFiles(@NotNull Collection<Change> changes) {
        if (changes.isEmpty()) {
            LOG.debug("沒有變更，跳過檔案篩選");
            return Set.of();
        }
        
        Set<VirtualFile> changedJavaFiles = changes.parallelStream()
            .map(this::getAfterRevisionFile)
            .filter(Objects::nonNull)
            .filter(this::isJavaFile)
            .filter(this::hasFileChanged)
            .collect(Collectors.toSet());
        
        LOG.info("篩選結果：" + changes.size() + " 個變更中有 " + changedJavaFiles.size() + " 個是新的 Java 檔案");
        
        return changedJavaFiles;
    }
    
    /**
     * 只篩選 Java 檔案，不檢查變更狀態
     * 用於首次掃描或強制檢查
     */
    public Set<VirtualFile> filterJavaFiles(@NotNull Collection<Change> changes) {
        return changes.parallelStream()
            .map(this::getAfterRevisionFile)
            .filter(Objects::nonNull)
            .filter(this::isJavaFile)
            .collect(Collectors.toSet());
    }
    
    /**
     * 從 Change 物件中安全地獲取變更後的檔案
     */
    private VirtualFile getAfterRevisionFile(@NotNull Change change) {
        try {
            ContentRevision afterRevision = change.getAfterRevision();
            if (afterRevision == null) {
                // 檔案被刪除，不需要檢查
                return null;
            }
            
            FilePath filePath = afterRevision.getFile();
            return filePath != null ? filePath.getVirtualFile() : null;
        } catch (Exception e) {
            LOG.warn("獲取變更檔案時出錯", e);
            return null;
        }
    }
    
    /**
     * 檢查是否為有效的 Java 檔案
     * 使用快取提高效能
     */
    private boolean isJavaFile(@NotNull VirtualFile file) {
        String path = file.getPath();
        
        // 使用快取避免重複檢查
        return fileTypeCache.computeIfAbsent(path, k -> {
            try {
                // 基本檢查
                if (!file.isValid() || file.isDirectory()) {
                    return false;
                }
                
                // 副檔名檢查
                String extension = file.getExtension();
                if (!"java".equals(extension)) {
                    return false;
                }
                
                // 檔案類型檢查
                if (file.getFileType().isBinary()) {
                    return false;
                }
                
                // 排除測試檔案路徑（可選，根據需求調整）
                String name = file.getName();
                if (name.endsWith("Test.java") || name.endsWith("Tests.java")) {
                    LOG.debug("跳過測試檔案: " + name);
                    return false;
                }
                
                // 排除生成的檔案
                if (path.contains("/target/") || path.contains("/build/") || 
                    path.contains("/generated/") || path.contains("/.gradle/")) {
                    LOG.debug("跳過生成檔案: " + path);
                    return false;
                }
                
                return true;
            } catch (Exception e) {
                LOG.warn("檢查檔案類型時出錯: " + path, e);
                return false;
            }
        });
    }
    
    /**
     * 清除快取，釋放記憶體
     * 建議在專案關閉或長時間不使用時調用
     */
    public void clearCaches() {
        int stampCacheSize = lastKnownModificationStamps.size();
        int typeCacheSize = fileTypeCache.size();
        
        lastKnownModificationStamps.clear();
        fileTypeCache.clear();
        
        LOG.info("已清除檔案變更檢測快取：時間戳快取 " + stampCacheSize + " 項，類型快取 " + typeCacheSize + " 項");
    }
    
    /**
     * 獲取快取統計資訊
     */
    public String getCacheStats() {
        return "檔案檢測快取 - 時間戳: " + lastKnownModificationStamps.size() + " 項，類型: " + fileTypeCache.size() + " 項";
    }
    
    /**
     * 強制標記檔案為已變更
     * 用於清除特定檔案的快取
     */
    public void markFileAsChanged(@NotNull VirtualFile file) {
        String path = file.getPath();
        lastKnownModificationStamps.remove(path);
        fileTypeCache.remove(path);
        LOG.debug("強制標記檔案為已變更: " + file.getName());
    }
    
    /**
     * 批次標記多個檔案為已變更
     */
    public void markFilesAsChanged(@NotNull Collection<VirtualFile> files) {
        files.forEach(this::markFileAsChanged);
        LOG.info("批次標記 " + files.size() + " 個檔案為已變更");
    }
}