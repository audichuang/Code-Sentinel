# Code Sentinel 優化計畫

基於官方 IntelliJ Platform Gradle Plugin 2.7.2 範例的優化建議

## 🎯 短期優化（立即可做）

### 1. 建構效能優化
```kotlin
// build.gradle.kts 加入
intellijPlatform {
    instrumentCode = false  // 不需要字節碼修改時關閉
    buildSearchableOptions = false  // 開發時關閉
}
```

### 2. 改進 Configurable 實作
- 將 `GitSettingsConfigurable` 改為實作 `SearchableConfigurable`
- 加入 `getId()` 方法提供搜尋功能
- 使用 `FormBuilder` 替代手動 GridBagLayout

### 3. 註解安全性
```java
// 所有公開方法加入
@NotNull / @Nullable
@Override
public @NotNull String getDisplayName() { ... }
```

## 🚀 中期優化（一週內）

### 1. PSI 處理優化
```java
// 避免長時間持有 PSI 元素
ReadAction.compute(() -> {
    // PSI 操作
    return result;
});

// 使用 Smart Pointers
SmartPsiElementPointer<PsiClass> pointer = 
    SmartPointerManager.getInstance(project)
        .createSmartPsiElementPointer(psiClass);
```

### 2. 批次處理機制
```java
// 對多個檔案的檢查使用批次處理
List<PsiFile> files = ...;
ProgressManager.getInstance().runProcess(() -> {
    for (int i = 0; i < files.size(); i += BATCH_SIZE) {
        List<PsiFile> batch = files.subList(i, 
            Math.min(i + BATCH_SIZE, files.size()));
        processBatch(batch);
        
        // 檢查取消
        ProgressManager.checkCanceled();
    }
}, indicator);
```

### 3. 依賴鎖定
```kotlin
// build.gradle.kts
dependencyLocking {
    lockAllConfigurations()
    lockFile.set(file("gradle/dependency-locks/gradle.lockfile"))
}
```

## 📈 長期優化（一個月內）

### 1. 測試架構建立
參考官方的 `IntelliJPlatformIntegrationTestBase`：
```kotlin
class CathayBkInspectionTest : IntelliJPlatformIntegrationTestBase() {
    @Test
    fun testApiMsgIdInspection() {
        // 測試檢查器
    }
}
```

### 2. 多模組支援
```
project/
├── core/           # 核心功能
├── inspections/    # 檢查器模組
├── quickfixes/     # Quick Fix 模組
└── ui/            # UI 元件
```

### 3. 效能監控
```java
// 加入效能指標
public class PerformanceMonitor {
    private final AtomicLong inspectionTime = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    
    public void recordInspectionTime(long millis) {
        inspectionTime.addAndGet(millis);
    }
    
    public String getStats() {
        return String.format("Inspection: %dms, Cache hits: %d",
            inspectionTime.get(), cacheHits.get());
    }
}
```

## 📋 檢查清單

### 程式碼品質
- [ ] 所有 public 方法都有 JavaDoc
- [ ] 使用 @NotNull/@Nullable 註解
- [ ] 實作 Disposable 介面
- [ ] 使用 try-with-resources

### 效能
- [ ] PSI 操作在 ReadAction 中
- [ ] 使用 Smart Pointers
- [ ] 實作緩存機制
- [ ] 批次處理大量檔案

### 測試
- [ ] 單元測試覆蓋率 > 70%
- [ ] 整合測試
- [ ] 效能測試
- [ ] 記憶體洩漏測試

## 🔧 工具建議

### 開發工具
- 使用 IntelliJ IDEA 2024.3 Ultimate
- 安裝 Plugin DevKit 插件
- 使用 Gradle 8.6+

### 分析工具
- Memory Profiler 檢查記憶體使用
- CPU Profiler 找出效能瓶頸
- Coverage 工具檢查測試覆蓋率

## 📚 參考資源

1. [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/)
2. [官方範例](https://github.com/JetBrains/intellij-platform-plugin-template)
3. [Gradle Plugin 文檔](https://github.com/JetBrains/intellij-platform-gradle-plugin)

## 優先順序

1. **高優先**：效能優化、記憶體管理
2. **中優先**：程式碼品質、測試覆蓋
3. **低優先**：新功能、UI 改進

---
*更新日期：2024-08-27*