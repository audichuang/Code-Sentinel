package com.cathaybk.codingassistant.cache;

import com.cathaybk.codingassistant.common.ProblemInfo;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.LowMemoryWatcher;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.lang.ref.SoftReference;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 單例模式的檢查緩存管理器，提供集中的緩存管理和記憶體優化
 * 使用 IntelliJ 的 Service 架構確保單例
 */
@Service(Service.Level.PROJECT)
public final class InspectionCacheManager implements Disposable {
    private static final Logger LOG = Logger.getInstance(InspectionCacheManager.class);
    
    // 使用更小的初始容量，根據需要動態增長
    private static final int INITIAL_CAPACITY = 100;
    private static final int MAX_CACHE_SIZE = 500; // 降低最大緩存大小
    private static final long CACHE_TTL_MS = 180_000; // 縮短到 3 分鐘
    private static final long CLEANUP_INTERVAL_MS = 60_000; // 每分鐘清理一次
    
    // 使用 SoftReference 允許 GC 在記憶體不足時回收
    private final Map<String, SoftReference<CachedInspectionResult>> cache;
    
    // 統計資訊
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    
    // 定期清理任務
    private final ScheduledExecutorService cleanupExecutor;
    
    // 記憶體壓力監聽器的 Disposable
    private final Disposable lowMemoryWatcherDisposable;
    
    // 使用 AtomicBoolean 避免 TOCTOU 競態條件
    private final AtomicBoolean isLowMemoryMode = new AtomicBoolean(false);

    // 防止並行清理的互斥鎖
    private final AtomicBoolean cleanupInProgress = new AtomicBoolean(false);
    
    /**
     * 獲取專案級別的單例實例
     */
    public static InspectionCacheManager getInstance(@NotNull Project project) {
        return project.getService(InspectionCacheManager.class);
    }
    
    public InspectionCacheManager(@NotNull Project project) {
        this.cache = new ConcurrentHashMap<>(INITIAL_CAPACITY);
        
        // 註冊 Disposable
        Disposer.register(project, this);
        
        // 設置定期清理任務
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "InspectionCacheManager-Cleanup");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        
        cleanupExecutor.scheduleWithFixedDelay(
            this::performCleanup,
            CLEANUP_INTERVAL_MS,
            CLEANUP_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
        
        // 設置記憶體壓力監聽器
        // LowMemoryWatcher.register 返回 void，所以我們創建一個 Disposable 來管理它
        this.lowMemoryWatcherDisposable = new Disposable() {
            @Override
            public void dispose() {
                // 當這個 Disposable 被釋放時，監聽器也會被移除
            }
        };
        
        LowMemoryWatcher.register(() -> {
            LOG.info("檢測到記憶體壓力，清理緩存");
            isLowMemoryMode.set(true);
            clearCache();
        }, lowMemoryWatcherDisposable);
        
        // 確保在 manager dispose 時清理監聽器
        Disposer.register(this, lowMemoryWatcherDisposable);
        
        LOG.info("InspectionCacheManager 初始化完成");
    }
    
    /**
     * 緩存的檢查結果（內部類）
     */
    private static class CachedInspectionResult {
        final List<ProblemInfo> problems;
        final long timestamp;
        final long fileModificationStamp;
        
        CachedInspectionResult(@NotNull List<ProblemInfo> problems, long fileModificationStamp) {
            // 使用 ArrayList 的精確大小，避免浪費空間
            this.problems = new ArrayList<>(problems);
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
     */
    public Optional<List<ProblemInfo>> getCachedResult(@NotNull PsiFile file) {
        if (isLowMemoryMode.get()) {
            // 低記憶體模式下不使用緩存
            return Optional.empty();
        }
        
        if (file.getVirtualFile() == null) {
            return Optional.empty();
        }
        
        String key = file.getVirtualFile().getPath();
        SoftReference<CachedInspectionResult> ref = cache.get(key);
        
        if (ref != null) {
            CachedInspectionResult cached = ref.get();
            if (cached != null && cached.isValid() && 
                cached.isFileUnchanged(file.getModificationStamp())) {
                cacheHits.incrementAndGet();
                LOG.debug("緩存命中: " + file.getName());
                return Optional.of(new ArrayList<>(cached.problems));
            } else {
                // 移除無效的緩存
                cache.remove(key);
            }
        }
        
        cacheMisses.incrementAndGet();
        return Optional.empty();
    }
    
    /**
     * 緩存檢查結果
     */
    public void cacheResult(@NotNull PsiFile file, @NotNull List<ProblemInfo> problems) {
        if (isLowMemoryMode.get() || file.getVirtualFile() == null) {
            return;
        }

        String key = file.getVirtualFile().getPath();
        CachedInspectionResult result = new CachedInspectionResult(problems, file.getModificationStamp());

        // 使用 compute 確保原子操作
        cache.compute(key, (k, existingRef) -> {
            // 如果緩存過大，先觸發異步清理（不阻塞當前操作）
            if (cache.size() >= MAX_CACHE_SIZE && existingRef == null) {
                // 只在新增項目時觸發清理，更新現有項目不觸發
                scheduleAsyncCleanup();
            }
            return new SoftReference<>(result);
        });

        LOG.debug("緩存結果: " + file.getName() + ", 問題數: " + problems.size());
    }

    /**
     * 安排異步清理（避免阻塞調用線程）
     * 互斥鎖檢查已移至 performCleanup 內部，確保所有調用路徑都被保護
     */
    private void scheduleAsyncCleanup() {
        // 直接提交任務，互斥鎖檢查在 performCleanup 內部進行
        ApplicationManager.getApplication().executeOnPooledThread(this::performCleanup);
    }

    /**
     * 執行清理操作（直接執行，不再嵌套 pooled thread）
     * 互斥鎖在此處檢查，確保排程任務和異步清理都能正確互斥
     */
    private void performCleanup() {
        // 互斥鎖檢查移至此處，確保所有調用路徑都被保護
        if (!cleanupInProgress.compareAndSet(false, true)) {
            LOG.debug("清理已在進行中，跳過本次清理");
            return;
        }

        try {
            performCleanupInternal();
        } finally {
            cleanupInProgress.set(false);
        }
    }

    /**
     * 實際的清理邏輯（內部方法，假設已持有互斥鎖）
     */
    private void performCleanupInternal() {
        int removedCount = 0;
        Iterator<Map.Entry<String, SoftReference<CachedInspectionResult>>> iterator =
            cache.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, SoftReference<CachedInspectionResult>> entry = iterator.next();
            SoftReference<CachedInspectionResult> ref = entry.getValue();
            CachedInspectionResult result = ref.get();

            if (result == null || !result.isValid()) {
                iterator.remove();
                removedCount++;
            }
        }

        if (removedCount > 0) {
            LOG.debug("清理過期緩存: " + removedCount + " 項");
        }

        // 如果仍然超過大小限制，移除最舊的項目
        if (cache.size() > MAX_CACHE_SIZE) {
            List<Map.Entry<String, SoftReference<CachedInspectionResult>>> entries =
                new ArrayList<>(cache.entrySet());

            entries.sort((e1, e2) -> {
                CachedInspectionResult r1 = e1.getValue().get();
                CachedInspectionResult r2 = e2.getValue().get();
                if (r1 == null) return -1;
                if (r2 == null) return 1;
                return Long.compare(r1.timestamp, r2.timestamp);
            });

            int toRemove = cache.size() - MAX_CACHE_SIZE + 50; // 多移除一些
            for (int i = 0; i < toRemove && i < entries.size(); i++) {
                cache.remove(entries.get(i).getKey());
            }

            LOG.debug("LRU 清理: " + toRemove + " 項");
        }

        // 檢查是否可以退出低記憶體模式
        if (isLowMemoryMode.get()) {
            Runtime runtime = Runtime.getRuntime();
            long freeMemory = runtime.freeMemory();
            long totalMemory = runtime.totalMemory();
            long maxMemory = runtime.maxMemory();
            double memoryUsage = (double)(totalMemory - freeMemory) / maxMemory;

            if (memoryUsage < 0.7) { // 記憶體使用率低於 70%
                isLowMemoryMode.set(false);
                LOG.info("退出低記憶體模式");
            }
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
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        double hitRate = (hits + misses) > 0 ? (double)hits / (hits + misses) * 100 : 0;
        
        int validEntries = (int) cache.values().stream()
            .filter(ref -> {
                CachedInspectionResult result = ref.get();
                return result != null && result.isValid();
            })
            .count();
        
        return String.format(
            "緩存統計 - 總項目: %d, 有效: %d, 命中率: %.1f%% (%d/%d), 低記憶體模式: %s",
            cache.size(), validEntries, hitRate, hits, misses, isLowMemoryMode.get()
        );
    }
    
    @Override
    public void dispose() {
        LOG.info("InspectionCacheManager 正在釋放資源");
        
        // 停止清理任務
        if (cleanupExecutor != null && !cleanupExecutor.isShutdown()) {
            cleanupExecutor.shutdown();
            try {
                if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        // 清理緩存
        clearCache();
        
        LOG.info("InspectionCacheManager 資源釋放完成");
    }
}