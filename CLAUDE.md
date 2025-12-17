# Code Sentinel - IntelliJ IDEA Plugin Project Context

## 專案概述
**Code Sentinel** 是一款 IntelliJ IDEA 插件，用於確保 Java 程式碼品質、維護團隊編碼規範，並在提交前自動檢查程式碼問題。

## 核心目標
- 🎯 即時檢查程式碼規範（API 電文代號、Javadoc）
- 🛡️ 提交前自動檢查，防止不符規範的程式碼進入版本庫
- 💡 提供智能快速修復建議
- 📊 優化大型專案的檢查效能
- 🔍 API 搜尋與依賴分析

## 技術棧
- **IDE**: IntelliJ IDEA 2025.2 Ultimate
- **Language**: Java 21 LTS
- **Build Tool**: Gradle 9.2.1 with IntelliJ Platform Plugin 2.10.5
- **Dependencies**: Lombok 1.18.38, Jackson 2.19.0
- **Plugin Version**: 1.0.0

## 主要功能模組

### 1. 即時檢查器 (Inspections)
- `ApiMsgIdInspection`: 檢查 Controller/Service 的 API 電文代號
- `InjectedFieldJavadocInspection`: 檢查 @Autowired/@Resource 欄位的 Javadoc

### 2. 提交前檢查 (VCS)
- `PreCommitInspectionHandler`: Git 提交前的主要檢查邏輯
- `PreCommitInspectionHandlerFactory`: 檢查處理器工廠
- `GitOperationHelper`: Git 操作輔助（fetch, 分支比較）
- `ProblemCollector`: 收集並分析程式碼問題
- `InspectionProblemsPanel`: 問題展示面板 UI

### 3. 快速修復 (Quick Fixes)
- `AddApiIdDocFix`: 添加 API ID 文檔
- `AddFieldJavadocFix`: 添加欄位 Javadoc
- `AddControllerApiIdFromServiceFix`: 從 Service 複製 API ID
- `AddServiceApiIdQuickFix`: Service 類別 API ID 修復
- `AddServiceClassApiIdDocFix`: Service 類別文檔修復

### 4. 效能優化 (Cache)
- `InspectionCacheManager`: 單例緩存管理器
  - 使用 SoftReference 防止 OOM
  - TTL 3分鐘自動過期
  - 低記憶體模式自動切換

### 5. 工具類 (Utils)
- `CodeInspectionUtil`: 核心檢查邏輯（API、Service、欄位檢查）
- `ApiMsgIdUtil`: API ID 相關工具
- `JavadocUtil`: Javadoc 生成工具
- `FullJavadocGenerator`: 完整 Javadoc 生成器

### 6. 使用者設定 (Settings)
- `GitSettings`: 插件設定管理
- `GitSettingsConfigurable`: 設定 UI 面板

### 7. API 複製與搜尋 (API Copy)
- `ApiIndexService`: API 索引服務，建立專案內 API 索引
- `ApiCopyService`: API 複製服務
- `ApiSearchToolWindowFactory`: API 搜尋工具視窗
- `ApiSearchEverywhereContributorFactory`: Search Everywhere 整合（Shift+Shift）
- `ApiDependencyAnalyzer`: API 依賴分析器
- `CopyFullApiAction`: 複製完整 API 程式碼
- `ServiceFinder`: Service 類別追蹤器

## 專案結構
```
CathayBank-JavaCodeQuality/
├── src/main/
│   ├── java/com/cathaybk/codingassistant/
│   │   ├── apicopy/              # API 複製功能
│   │   │   ├── action/           # Action 類別
│   │   │   ├── analysis/         # 依賴分析
│   │   │   ├── model/            # 資料模型
│   │   │   ├── searcheverywhere/ # Search Everywhere 整合
│   │   │   ├── service/          # 服務類別
│   │   │   └── ui/               # UI 元件
│   │   ├── cache/                # 緩存管理
│   │   ├── common/               # 共用類別
│   │   ├── dialog/               # UI 對話框
│   │   ├── fix/                  # Quick Fix 實作
│   │   ├── inspection/           # 程式碼檢查器
│   │   ├── intention/            # Intention Actions
│   │   ├── settings/             # 設定管理
│   │   ├── util/                 # 工具類
│   │   └── vcs/                  # 版本控制檢查
│   └── resources/
│       └── META-INF/
│           └── plugin.xml        # 插件配置
├── build.gradle.kts              # Gradle 建構檔
├── gradle.properties             # Gradle 屬性
├── README.md                     # 專案說明
├── CLAUDE.md                     # 本文件
└── GEMINI.md                     # Gemini 參考文件
```

## 最新變更 (v1.0.0)
**初始發布版本**，包含以下核心功能：

1. ✅ **即時程式碼檢查** - API MsgID 格式驗證、Javadoc 檢查
2. ✅ **提交前檢查守衛** - Git 分支狀態檢查、程式碼品質門檻
3. ✅ **智能快速修復** - 一鍵生成 API ID、自動添加 Javadoc
4. ✅ **API 搜尋與發現** - Search Everywhere 整合（Shift+Shift → APIs 分頁）
5. ✅ **API 依賴複製** - 複製完整 API 程式碼及其所有依賴
6. ✅ **高度可配置** - 可個別開關 Git 檢查、程式碼檢查、Javadoc 樣式

## 開發指南

### 建構專案
```bash
# 清理並建構
./gradlew clean build

# 運行測試 IDE
./gradlew runIde

# 建構插件分發包
./gradlew buildPlugin

# 開發模式（跳過耗時任務）
./gradlew runIde -Pdev.mode=true
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
   - PSI 存取必須包裝在 ReadAction 中

4. **執行緒安全**
   - UI 更新使用 `ApplicationManager.getApplication().invokeLater()`
   - 寫入操作使用 `WriteCommandAction.runWriteCommandAction()`
   - Modal Dialog 中使用 `ModalityState`

### 測試重點
- 大型專案效能測試（1000+ 檔案）
- 記憶體使用監控
- 併發提交場景
- Search Everywhere 整合測試

## 常見問題

### Q: 插件在大型專案中變慢？
A: 已實作檢查緩存機制和檔案變更檢測，優化大型專案效能

### Q: 記憶體使用過高？
A: 已實作 SoftReference 和低記憶體模式自動切換

### Q: 如何關閉某些檢查？
A: Settings → Tools → Code Sentinel 可個別開關功能

### Q: 如何使用 API 搜尋？
A: 按 Shift+Shift 開啟 Search Everywhere，切換到「APIs」分頁

## 聯絡資訊
- **Vendor**: CathayBk
- **Developer**: AudiChuang
- **Email**: audiapplication880208@gmail.com
- **GitHub**: https://github.com/audichuang

## 版本歷史
- v1.0.0 (2024) - 初始發布版本，包含即時檢查、提交前守衛、快速修復、API 搜尋等完整功能

---
*此文件供 Claude Code 參考，以快速理解專案架構與開發重點*
