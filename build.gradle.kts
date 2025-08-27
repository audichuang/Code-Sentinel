import java.util.EnumSet

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.7.0"
    id("io.freefair.lombok") version "8.6"
}

group = "com.cathaybk"
version = "1.5.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// 強制使用特定版本的依賴項，解決衝突
configurations.all {
    resolutionStrategy {
        // 強制使用指定版本的 Jackson
        force("com.fasterxml.jackson.core:jackson-core:2.19.0")
        force("com.fasterxml.jackson.core:jackson-databind:2.19.0")
        force("com.fasterxml.jackson.core:jackson-annotations:2.19.0")

    }
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    
    // 添加 lombok 依賴
    compileOnly("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok:1.18.38")
    
    // 添加 Jackson 依賴（明確指定版本）
    implementation("com.fasterxml.jackson.core:jackson-core:2.19.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.0")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.19.0")
    
    // 使用 IntelliJ IDEA Ultimate 2024.3
    intellijPlatform {
        intellijIdeaUltimate("2024.3")
        bundledPlugin("com.intellij.java")
        
        // 插件驗證器
        pluginVerifier()
        
        // 測試框架支援
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

tasks {
    // 配置 Java 版本
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.release.set(17)
    }
    
    test {
        useJUnitPlatform()
    }
    
    // 解決重複文件的問題
    withType<Copy> {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    
    processResources {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    
    prepareSandbox {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    
    // 跳過 buildSearchableOptions 任務
    named("buildSearchableOptions") {
        enabled = false
    }
    
    // 設置執行 IDE 的選項
    named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
        // 設置系統屬性
        systemProperty("idea.platform.prefix", "idea")
    }
}

// 配置 IntelliJ Platform 插件（使用 2.7.0 新特性）
intellijPlatform {
    // 啟用自動重載功能（開發時很有用）
    autoReload.set(true)
    
    // 插件配置
    pluginConfiguration {
        id.set("com.cathaybk.codingassistant")
        name.set("Code Sentinel")
        version.set(project.version.toString())
        
        vendor {
            name.set("CathayBk")
            email.set("support@cathaybk.com")
            url.set("https://www.cathaybk.com")
        }
        
        description.set("""
            <html><body>
            <h2>Code Sentinel: 您的智能編碼與提交守衛</h2>
            <p>Code Sentinel 是一款專為 IntelliJ IDEA 設計的開發者助手插件，旨在通過<b>即時程式碼分析</b>和<b>提交前檢查</b>機制，全方位保障您的程式碼品質、統一團隊規範，並顯著提升開發與提交流程的效率。</p>
            <hr/>
            <h3>核心功能</h3>
            <b>🚀 即時檢查與快速修復:</b> <ul><li>規範哨兵: 即時捕捉不合規代碼。</li><li>智能修復: 提供 Quick Fix 建議。</li></ul>
            <b>🛡️ 提交前守衛 (可選):</b> <ul><li>Git 分支檢查: 防過時提交。</li><li>程式碼品質門禁: 掃描變更。</li><li>問題看板: 清晰列出問題。</li><li>批量修復 ("Fix All"): 一鍵修正。</li></ul>
            <b>💡 智能輔助:</b> <ul><li>Javadoc 生成器: 自動生成完整或最小 Javadoc。</li></ul>
            <hr/>
            <h3>高度可配置:</h3> <p>可在設定中開關 Git/程式碼檢查、選擇 Javadoc 風格、定義目標分支。</p>
            <p>讓 Code Sentinel 成為您編碼過程中的得力助手和品質守護者！</p>
            </body></html>
        """)

        ideaVersion {
            sinceBuild.set("231")
            // 使用本地版本時，不設定 untilBuild 以支援最新版本
            // untilBuild 留空表示沒有版本上限
        }
        
        changeNotes.set("""
            <b>v1.5.0</b>
            <ul>
                <li>新增 Service 類別與方法的電文代號檢查功能</li>
                <li>支援 Service 介面與實現類的自動識別與後綴生成（Svc/SvcImpl）</li>
                <li>優化檔案變更檢測器，提升大型專案的檢查效能</li>
                <li>新增 PSI 檢查結果緩存機制，避免重複檢查</li>
                <li>改進資源管理，實作 Disposable 介面防止記憶體洩漏</li>
                <li>修正 Service API ID 生成時的格式問題</li>
                <li>支援最新版 IntelliJ IDEA 2024.3</li>
            </ul>
            <br/>
            <b>v1.4.0</b>
            <ul>
                <li>優化記憶體資源使用，減少資源洩漏風險。</li>
                <li>改善 PSI 元素處理邏輯，提高穩定性。</li>
                <li>為主要工具類實現批次處理和緩存機制。</li>
                <li>強化資源釋放機制，避免長時間運行時效能衰退。</li>
                <li>低記憶體環境下自動調整工作模式，提高適應性。</li>
            </ul>
            <br/>
            <b>v1.3.0</b>
            <ul>
                <li>插件更名為 "Code Sentinel"。</li>
                <li>新增設定選項：可開關 Git 分支落後檢查。</li>
                <li>新增設定選項：可開關程式碼規範檢查。</li>
                <li>新增設定選項：可配置 Javadoc 生成方式 (完整/最小)。</li>
                <li>設定頁面 UI 優化，分區顯示並加入範例說明。</li>
                <li>增強設定中目標分支名稱的驗證規則。</li>
                <li>移除設定成功時的冗餘提示彈窗。</li>
                <li>修復 Javadoc 生成中的換行符問題。</li>
                <li>(舊) 問題面板界面優化，移除搜尋框，修復 HTML 顯示問題，提高檢查執行效率。</li> 
            </ul>
            <br/>
            <b>v1.2.0</b>
            <ul>
                <li>增加對25.1版本的支持，新增bean加入註解功能</li>
            </ul>
             <br/>
            <b>v1.1.0</b>
            <ul>
                 <li>優化效能，修正已知問題，改善使用者體驗</li>
            </ul>
             <br/>
            <b>v1.0.0</b>
            <ul>
                 <li>初始版本，提供API註解檢查和Service關聯功能</li>
            </ul>
        """)
    }
    
    // 插件驗證配置（2.7.0 新功能）
    pluginVerification {
        // 驗證的 IDE 版本
        ides {
            recommended()
        }
        
        // 失敗級別配置 - 只檢查嚴重問題
        failureLevel.set(
            EnumSet.of(
                org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INVALID_PLUGIN
            )
        )
    }
    
    // 簽名配置（如果需要）
    if (file("chain.crt").exists() && file("private.pem").exists()) {
        signing {
            certificateChainFile.set(file("chain.crt"))
            privateKeyFile.set(file("private.pem"))
            // password.set(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
        }
    }
} 