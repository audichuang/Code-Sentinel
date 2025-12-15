package com.cathaybk.codingassistant.utils;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 智能檔案變更檢測器。
 * <p>
 * 只處理真正有變更的 Java 檔案，大幅減少不必要的檢查。
 * 使用 Project 級別的 Service 確保資源正確管理。
 * </p>
 */
@Service(Service.Level.PROJECT)
public final class FileChangeDetector implements Disposable {
    private static final Logger LOG = Logger.getInstance(FileChangeDetector.class);

    // 最大快取條目數，防止記憶體無限增長
    private static final int MAX_CACHE_SIZE = 2000;

    // TTL 時間 (毫秒)，超過此時間的條目會被清理
    private static final long CACHE_TTL_MS = 600_000; // 10 分鐘

    // 記錄已知的檔案修改時間戳和記錄時間
    private final Map<String, CacheEntry> lastKnownModificationStamps = new ConcurrentHashMap<>();

    // 快取檔案類型檢查結果
    private final Map<String, Boolean> fileTypeCache = new ConcurrentHashMap<>();

    /**
     * 快取條目，包含修改時間戳和記錄時間
     */
    private static class CacheEntry {
        final long modificationStamp;
        final long recordTime;

        CacheEntry(long modificationStamp) {
            this.modificationStamp = modificationStamp;
            this.recordTime = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - recordTime > CACHE_TTL_MS;
        }
    }

    /**
     * 獲取專案級別的單例實例
     */
    public static FileChangeDetector getInstance(@NotNull Project project) {
        return project.getService(FileChangeDetector.class);
    }

    public FileChangeDetector(@NotNull Project project) {
        // 註冊為 Disposable
        Disposer.register(project, this);
        LOG.info("FileChangeDetector 初始化完成");
    }

    /**
     * 檢查檔案是否已變更
     * 
     * @param file 要檢查的檔案
     * @return true 如果檔案已變更或第一次檢查
     */
    public boolean hasFileChanged(@NotNull VirtualFile file) {
        String path = file.getPath();
        long currentStamp = file.getModificationStamp();
        CacheEntry lastKnown = lastKnownModificationStamps.get(path);

        if (lastKnown == null || lastKnown.modificationStamp != currentStamp || lastKnown.isExpired()) {
            // 檢查是否需要清理快取
            if (lastKnownModificationStamps.size() >= MAX_CACHE_SIZE) {
                performCleanup();
            }
            lastKnownModificationStamps.put(path, new CacheEntry(currentStamp));
            return true;
        }
        return false;
    }

    /**
     * 執行快取清理，移除過期條目和最舊的條目
     */
    private void performCleanup() {
        int beforeSize = lastKnownModificationStamps.size();

        // 移除過期條目
        Iterator<Map.Entry<String, CacheEntry>> iterator = lastKnownModificationStamps.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CacheEntry> entry = iterator.next();
            if (entry.getValue().isExpired()) {
                iterator.remove();
            }
        }

        // 如果仍然超過限制，移除最舊的條目
        if (lastKnownModificationStamps.size() >= MAX_CACHE_SIZE) {
            // 簡單的 LRU：移除前 20% 的條目
            int toRemove = MAX_CACHE_SIZE / 5;
            Iterator<String> keyIterator = lastKnownModificationStamps.keySet().iterator();
            while (keyIterator.hasNext() && toRemove > 0) {
                keyIterator.next();
                keyIterator.remove();
                toRemove--;
            }
        }

        // 同時清理類型快取
        if (fileTypeCache.size() > MAX_CACHE_SIZE) {
            fileTypeCache.clear();
        }

        int afterSize = lastKnownModificationStamps.size();
        if (beforeSize != afterSize) {
            LOG.debug("快取清理：從 " + beforeSize + " 減少到 " + afterSize + " 條目");
        }
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

        // 對於小集合使用普通 stream，避免 parallelStream 的開銷
        // parallelStream 只在大量檔案時才有效益
        Set<VirtualFile> changedJavaFiles = (changes.size() > 50 ? changes.parallelStream() : changes.stream())
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
        // 對於小集合使用普通 stream，避免 parallelStream 的開銷
        return (changes.size() > 50 ? changes.parallelStream() : changes.stream())
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

    @Override
    public void dispose() {
        LOG.info("FileChangeDetector 正在釋放資源");
        clearCaches();
        LOG.info("FileChangeDetector 資源釋放完成");
    }
}