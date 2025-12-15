package com.cathaybk.codingassistant.apicopy.model;

/**
 * API 相關檔案類型枚舉
 * 用於標識不同類型的 API 依賴檔案
 */
public enum ApiFileType {
    CONTROLLER("Controller", "控制器"),
    SERVICE_INTERFACE("Service Interface", "服務介面"),
    SERVICE_IMPL("Service Impl", "服務實作"),
    REQUEST_DTO("Request DTO", "請求 DTO"),
    RESPONSE_DTO("Response DTO", "回應 DTO"),
    NESTED_DTO("Nested DTO", "嵌套 DTO"),
    REPOSITORY("Repository", "資料存取層"),
    ENTITY("Entity", "實體類別"),
    COMPOSITE_PK("Composite PK", "複合主鍵"),
    SQL_FILE("SQL File", "SQL 檔案");

    private final String displayName;
    private final String chineseName;

    ApiFileType(String displayName, String chineseName) {
        this.displayName = displayName;
        this.chineseName = chineseName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getChineseName() {
        return chineseName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
