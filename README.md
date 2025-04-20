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

- IntelliJ IDEA 2023.1 或更高版本
- JDK 11 或更高版本

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

- **1.3.0 (最新)**:
  - 插件更名為 "Code Sentinel"。
  - 新增可配置的 Git 分支檢查開關。
  - 新增可配置的程式碼規範檢查開關。
  - 新增可配置的 Javadoc 生成方式 (完整/最小)。
  - 大幅優化設定頁面 UI/UX，增加說明範例。
  - 增強分支名稱驗證。
  - 移除冗餘設定成功提示。
  - 修復 Javadoc 生成的換行符及排版問題。
  - (包含之前 1.3.0-dev 的改動：問題面板優化等)
- 1.2.0: 增加對 25.1 版本的支持，新增 bean 加入註解功能。
- 1.1.0: 優化效能，修正已知問題，改善使用者體驗。
- 1.0.0: 初始版本，提供 API 註解檢查和 Service 關聯功能。

## 貢獻指南

歡迎提交問題報告和改進建議。如果你想貢獻代碼，請遵循以下步驟：

1. Fork 本專案
2. 創建你的特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交你的更改 (`git commit -m 'Add some amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 打開 Pull Request
