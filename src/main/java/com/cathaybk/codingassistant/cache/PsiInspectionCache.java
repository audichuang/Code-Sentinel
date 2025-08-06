package com.cathaybk.codingassistant.cache;

import com.cathaybk.codingassistant.common.ProblemInfo;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PSI 檢查結果緩存，避免重複檢查未修改的檔案
 * 這是效能優化的關鍵組件，可以大幅提升重複檢查的速度
 */
public class PsiInspectionCache {
    private static final Logger LOG = Logger.getInstance(PsiInspectionCache.class);
    
    // 緩存大小限制，防止記憶體過度使用
    private static final int MAX_CACHE_SIZE = 1000;
    
    // 緩存存活時間：5分鐘，平衡效能與資料新鮮度
    private static final long CACHE_TTL_MS = 300_000;
    
    // 使用 ConcurrentHashMap 確保線程安全
    private final Map<String, CachedInspectionResult> cache = new ConcurrentHashMap<>(MAX_CACHE_SIZE);
    
    /**
     * 緩存的檢查結果
     */
    private static class CachedInspectionResult {
        final List<ProblemInfo> problems;
        final long timestamp;
        final long fileModificationStamp;
        
        CachedInspectionResult(@NotNull List<ProblemInfo> problems, long fileModificationStamp) {
            // 創建不可變副本，避免外部修改影響緩存
            this.problems = List.copyOf(problems);
            this.timestamp = System.currentTimeMillis();
            this.fileModificationStamp = fileModificationStamp;
        }
        
        boolean isValid() {
            return System.currentTimeMillis() - timestamp < CACHE_TTL_MS;
        }
        
        boolean isFileUnchanged(long currentModificationStamp) {
            return this.fileModificationStamp == currentModificationStamp;
        }
    }
    
    /**
     * 獲取緩存的檢查結果
     * 
     * @param file 要檢查的檔案
     * @return 緩存的問題列表，如果沒有有效緩存則返回 empty
     */
    public Optional<List<ProblemInfo>> getCachedResult(@NotNull PsiFile file) {
        if (file.getVirtualFile() == null) {
            return Optional.empty();
        }
        
        String key = file.getVirtualFile().getPath();
        CachedInspectionResult cached = cache.get(key);
        
        if (cached != null && cached.isValid() && cached.isFileUnchanged(file.getModificationStamp())) {
            LOG.debug("使用緩存的檢查結果: " + file.getName());
            return Optional.of(cached.problems);
        }
        
        // 移除過期或無效的緩存項目
        if (cached != null) {
            cache.remove(key);
            LOG.debug("移除過期緩存: " + file.getName());
        }
        
        return Optional.empty();
    }
    
    /**
     * 緩存檢查結果
     * 
     * @param file 檢查的檔案
     * @param problems 發現的問題列表
     */
    public void cacheResult(@NotNull PsiFile file, @NotNull List<ProblemInfo> problems) {
        if (file.getVirtualFile() == null) {
            return;
        }
        
        String key = file.getVirtualFile().getPath();
        CachedInspectionResult result = new CachedInspectionResult(problems, file.getModificationStamp());
        
        cache.put(key, result);
        LOG.debug("緩存檢查結果: " + file.getName() + ", 問題數量: " + problems.size());
        
        // LRU 式清理，防止記憶體洩漏
        if (cache.size() > MAX_CACHE_SIZE) {
            evictOldEntries();
        }
    }
    
    /**
     * 清理過期的緩存項目
     */
    private void evictOldEntries() {
        int beforeSize = cache.size();
        cache.entrySet().removeIf(entry -> !entry.getValue().isValid());
        int afterSize = cache.size();
        
        if (beforeSize != afterSize) {
            LOG.debug("清理過期緩存項目: " + (beforeSize - afterSize) + " 個");
        }
        
        // 如果清理後仍然超過限制，移除最舊的項目
        if (cache.size() > MAX_CACHE_SIZE) {
            // 這是一個簡化的 LRU 實作，實際上可以使用更複雜的策略
            cache.entrySet().stream()
                .sorted((e1, e2) -> Long.compare(e1.getValue().timestamp, e2.getValue().timestamp))
                .limit(cache.size() - MAX_CACHE_SIZE + 100) // 多清理一些，減少頻繁清理
                .forEach(entry -> cache.remove(entry.getKey()));
            
            LOG.debug("清理最舊緩存項目，當前緩存大小: " + cache.size());
        }
    }
    
    /**
     * 清除所有緩存
     */
    public void clearCache() {
        cache.clear();
        LOG.info("已清除所有緩存");
    }
    
    /**
     * 獲取緩存統計資訊
     */
    public String getCacheStats() {
        int validEntries = (int) cache.values().stream()
            .mapToLong(result -> result.isValid() ? 1 : 0)
            .sum();
        
        return String.format("緩存統計 - 總項目: %d, 有效項目: %d, 記憶體使用: %d KB", 
            cache.size(), validEntries, estimateMemoryUsage() / 1024);
    }
    
    /**
     * 估算緩存記憶體使用量（粗略估算）
     */
    private long estimateMemoryUsage() {
        // 粗略估算：每個緩存項目約 1KB（包括字串、物件開銷等）
        return cache.size() * 1024L;
    }
}