# Code Sentinel

一個提供即時程式碼分析、提交前檢查及輔助功能的 IntelliJ IDEA 插件，旨在幫助開發人員遵循編碼標準、提升程式碼品質並優化提交流程。

## 主要功能

- **即時程式碼檢查與快速修復**:
  - 在編寫程式碼時，即時檢查 API MsgID、注入欄位的 Javadoc 等規範。
  - 對檢測到的問題提供快速修復 (Quick Fix) 選項 (例如，自動添加 Javadoc 或電文代號)。
- **提交前檢查 (可選)**:
  - **(可選) Git 分支檢查**: 檢查當前分支是否落後於設定的目標分支。
  - **(可選) 程式碼規範檢查**: 檢查待提交的程式碼變更是否符合自訂規範。
  - **問題列表展示**: 在工具視窗中清晰列出所有檢查發現的問題。
  - **一鍵修復**: 提供 "Fix All" 按鈕，嘗試自動修復所有可修復的問題。
- **(可選) Javadoc 生成**: 自動為缺少 Javadoc 的方法或類別生成註解 (可配置生成完整或最小結構)。

## 安裝方法

1. 在 IntelliJ IDEA 中，打開 `Settings` -> `Plugins` -> `Marketplace`
2. 搜索 "Code Sentinel" (注意: 如果尚未發布，可能搜索不到)
3. 點擊 "Install" 並重啟 IDE

或者你可以從 releases 頁面下載最新的插件 ZIP 檔案，然後通過 `Settings` -> `Plugins` -> `⚙️` -> `Install Plugin from Disk...` 安裝。

## 使用方法

- **即時檢查**: 插件會在您編寫 Java 程式碼時自動運行檢查，並在有問題的程式碼處顯示警告及快速修復建議 (可按 Alt+Enter 或 Option+Enter 觸發)。
- **提交前檢查**: 在您執行 Git 提交操作時，插件會自動觸發提交前檢查流程 (如果相關選項已在設定中啟用)。檢查結果會顯示在 "Code Sentinel 檢查" 工具視窗中。
- **設定**: 所有可配置選項 (Git 檢查開關、程式碼檢查開關、Javadoc 生成方式等) 均可在 `Settings` -> `Tools` -> `Code Sentinel 設定` 中調整。

## 開發環境配置

本專案使用 Gradle 構建，可以直接在 IntelliJ IDEA 中打開。

### 開發要求

- IntelliJ IDEA 2024.3 或更高版本
- JDK 21 或更高版本

### 構建步驟

```bash
# 構建專案
./gradlew build

# 運行IDE測試
./gradlew runIde

# 構建插件分發包
./gradlew buildPlugin
```

## 版本歷史

- **1.6.0 (最新)**:
  - **新功能：Search Everywhere API 搜尋整合** - 在 Shift+Shift 對話框中新增「APIs」分頁，支援 MSGID、路徑、描述的模糊搜尋
  - **新功能：API 依賴預覽增強** - 區分直接依賴與遞迴依賴檔案，改進依賴分析視覺化
  - **效能與穩定性** - 修正執行緒安全問題，改善 Modal Dialog UI 更新
  - **記憶體洩漏修復** - 強化 disposed 狀態檢查，優化快取清理機制
  - **建構系統** - 升級 Gradle 9.2.1，IntelliJ Platform Plugin 2.10.5
  - **程式碼品質** - 重構內部類別名稱，移除未使用的 import
- **1.5.0**:
  - 新增 Service 類別與方法的電文代號檢查功能
  - 支援 Service 介面與實現類的自動識別（Svc/SvcImpl）
  - 實作 PSI 檢查結果緩存機制（InspectionCacheManager）
  - 添加記憶體壓力監聽和自動調整機制
  - 所有主要類別實作 Disposable 介面防止記憶體洩漏
- **1.4.0**:
  - 優化記憶體資源使用，減少資源洩漏風險
  - 改善 PSI 元素處理邏輯，提高穩定性
  - 為主要工具類實現批次處理和緩存機制
  - 強化資源釋放機制，避免長時間運行時效能衰退
  - 低記憶體環境下自動調整工作模式
- **1.3.0**:
  - 插件更名為 "Code Sentinel"
  - 新增可配置的 Git 分支檢查開關
  - 新增可配置的程式碼規範檢查開關
  - 新增可配置的 Javadoc 生成方式 (完整/最小)
  - 大幅優化設定頁面 UI/UX，增加說明範例
- **1.2.0**: 增加對 25.1 版本的支持，新增 bean 加入註解功能
- **1.1.0**: 優化效能，修正已知問題，改善使用者體驗
- **1.0.0**: 初始版本，提供 API 註解檢查和 Service 關聯功能

## 貢獻指南

歡迎提交問題報告和改進建議。如果你想貢獻代碼，請遵循以下步驟：

1. Fork 本專案
2. 創建你的特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交你的更改 (`git commit -m 'Add some amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 打開 Pull Request
