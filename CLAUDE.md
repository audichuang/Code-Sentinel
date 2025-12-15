# Code Sentinel - IntelliJ IDEA Plugin Project Context

## 專案概述
這是一個為 **國泰世華銀行（CathayBK）** 開發的 IntelliJ IDEA 插件，名為 **Code Sentinel**。主要用於確保 Java 程式碼品質、維護團隊編碼規範，並在提交前自動檢查程式碼問題。

## 核心目標
- 🎯 即時檢查程式碼規範（API 電文代號、Javadoc）
- 🛡️ 提交前自動檢查，防止不符規範的程式碼進入版本庫
- 💡 提供智能快速修復建議
- 📊 優化大型專案的檢查效能

## 技術棧
- **IDE**: IntelliJ IDEA 2024.3 Ultimate
- **Language**: Java 17
- **Build Tool**: Gradle 7.x with IntelliJ Platform Plugin 2.7.0
- **Dependencies**: Lombok 1.18.38, Jackson 2.19.0
- **Plugin Version**: 1.5.0

## 主要功能模組

### 1. 即時檢查器 (Inspections)
- `ApiMsgIdInspection`: 檢查 Controller/Service 的 API 電文代號
- `InjectedFieldJavadocInspection`: 檢查 @Autowired/@Resource 欄位的 Javadoc
- `MethodJavadocInspection`: 檢查方法的 Javadoc 完整性

### 2. 提交前檢查 (VCS)
- `CathayBkCheckinHandler`: Git 提交前的主要檢查邏輯
- `GitOperationHelper`: Git 操作輔助（fetch, 分支比較）
- `ProblemCollector`: 收集並分析程式碼問題
- `CathayBkProblemsPanel`: 問題展示面板 UI

### 3. 快速修復 (Quick Fixes)
- `AddApiIdDocFix`: 添加 API ID 文檔
- `AddFieldJavadocFix`: 添加欄位 Javadoc
- `AddMethodJavadocFix`: 添加方法 Javadoc
- `AddControllerApiIdFromServiceFix`: 從 Service 複製 API ID
- `AddServiceApiIdQuickFix`: Service 類別 API ID 修復

### 4. 效能優化 (Cache)
- `InspectionCacheManager`: 單例緩存管理器
  - 使用 SoftReference 防止 OOM
  - TTL 3分鐘自動過期
  - 低記憶體模式自動切換
- `PsiInspectionCache`: PSI 元素檢查結果緩存
- `FileChangeDetector`: 檔案變更檢測優化

### 5. 工具類 (Utils)
- `CathayBkInspectionUtil`: 核心檢查邏輯
- `ApiMsgIdUtil`: API ID 相關工具
- `JavadocUtil`: Javadoc 生成工具
- `FullJavadocGenerator`: 完整 Javadoc 生成器

### 6. 使用者設定 (Settings)
- `GitSettings`: 插件設定管理
- `GitSettingsConfigurable`: 設定 UI 面板

## 專案結構
```
CathayBank-JavaCodeQuality/
├── src/main/
│   ├── java/com/cathaybk/codingassistant/
│   │   ├── cache/               # 緩存管理（v1.5.0 新增）
│   │   ├── common/              # 共用類別
│   │   ├── dialog/              # UI 對話框
│   │   ├── fix/                 # Quick Fix 實作
│   │   ├── inspection/          # 程式碼檢查器
│   │   ├── intention/           # Intention Actions
│   │   ├── settings/            # 設定管理
│   │   ├── util/                # 舊版工具類
│   │   ├── utils/               # 新版工具類
│   │   └── vcs/                 # 版本控制檢查
│   └── resources/
│       └── META-INF/
│           └── plugin.xml       # 插件配置
├── build.gradle.kts             # Gradle 建構檔
├── gradle.properties            # Gradle 屬性
├── README.md                    # 專案說明
└── CLAUDE.md                    # 本文件
```

## 最新變更 (v1.5.0)
1. ✅ 新增 Service 類別與方法的電文代號檢查功能
2. ✅ 支援 Service 介面與實現類的自動識別（Svc/SvcImpl）
3. ✅ 實作 PSI 檢查結果緩存機制（InspectionCacheManager）
4. ✅ 添加記憶體壓力監聽和自動調整機制
5. ✅ 所有主要類別實作 Disposable 介面防止記憶體洩漏
6. ✅ **升級至 Java 21 LTS**
7. ✅ **實作 SearchableConfigurable 提供搜尋功能**
8. ✅ **加入 ReadAction 和 ProgressManager 優化**
9. ✅ **使用 WriteAction 和 CommandProcessor 確保線程安全**
10. ✅ **動態配置支援（Gradle Properties）**

## 開發指南

### 建構專案
```bash
# 清理並建構
./gradlew clean build

# 運行測試 IDE
./gradlew runIde

# 建構插件分發包
./gradlew buildPlugin
```

### 程式碼規範
1. **命名規則**
   - Service 介面: `XxxSvc`
   - Service 實作: `XxxSvcImpl`
   - API ID 格式: `MSGID-XXXX-XXXX`

2. **Javadoc 要求**
   - 所有 public 方法必須有 Javadoc
   - @Autowired/@Resource 欄位必須有說明
   - Controller/Service 必須有 API ID

3. **資源管理**
   - 實作 Disposable 介面
   - 使用 try-with-resources
   - 避免長時間持有 PSI 元素

### 測試重點
- 大型專案效能測試（1000+ 檔案）
- 記憶體使用監控
- 併發提交場景
- 低記憶體環境適應性

## 常見問題

### Q: 插件在大型專案中變慢？
A: v1.5.0 已優化，檢查緩存機制和檔案變更檢測

### Q: 記憶體使用過高？
A: 已實作 SoftReference 和低記憶體模式自動切換

### Q: 如何關閉某些檢查？
A: Settings → Tools → Code Sentinel 可個別開關功能

## 聯絡資訊
- **Vendor**: CathayBk
- **Developer**: AudiChuang
- **Email**: audiapplication880208@gmail.com
- **GitHub**: https://github.com/audichuang

## 版本歷史
- v1.5.0 (2024) - Service 電文代號支援與效能優化
- v1.4.0 - 記憶體優化與資源管理改進
- v1.3.0 - 插件更名為 Code Sentinel，新增可配置選項
- v1.2.0 - Bean 註解功能支援
- v1.1.0 - 效能優化
- v1.0.0 - 初始版本

---
*此文件供 Claude Code 參考，以快速理解專案架構與開發重點*