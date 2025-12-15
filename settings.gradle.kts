rootProject.name = "CathayBkCodingAssistant"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// 啟用類型安全的專案存取器
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// 依賴管理配置 - 確保所有專案使用相同版本
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
    }
} 