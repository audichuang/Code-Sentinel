import java.util.EnumSet

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.10.5"
    id("io.freefair.lombok") version "8.6"
}

// 使用 Gradle Properties 支援動態配置
val pluginVersion = providers.gradleProperty("plugin.version").orElse("1.5.0")
val pluginGroup = providers.gradleProperty("plugin.group").orElse("com.cathaybk")

group = pluginGroup.get()
version = pluginVersion.get()

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
    
    // 使用 IntelliJ IDEA Ultimate - 支援動態配置
    val intellijTypeProperty = providers.gradleProperty("intellijPlatform.type").orElse("IU")
    val intellijVersionProperty = providers.gradleProperty("intellijPlatform.version").orElse("2024.3")
    
    intellijPlatform {
        // 使用統一的 intellijIdea() API (2.9.0+ 推薦)
        // IU/IC 已合併為單一 IntellijIdea 類型
        when (intellijTypeProperty.get()) {
            "IU", "IC" -> intellijIdea(intellijVersionProperty.get())
            else -> create(intellijTypeProperty.get(), intellijVersionProperty.get())
        }
        
        bundledPlugin("com.intellij.java")
        
        // 插件驗證器
        pluginVerifier()
        
        // 測試框架支援
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        
        // Instrumentation tools - 2.7.0+ 不再需要顯式調用
        // instrumentationTools() 已經被自動包含
    }
}

tasks {
    // 配置 Java 版本 - 升級到 Java 21 LTS
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
        options.release.set(21)
        options.encoding = "UTF-8"
        // 啟用預覽功能（如需要）
        // options.compilerArgs.add("--enable-preview")
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
    
    // 開發模式下跳過某些耗時任務
    val isDevMode = providers.gradleProperty("dev.mode").map { it.toBoolean() }.orElse(false).get()
    if (isDevMode) {
        named("buildSearchableOptions") {
            enabled = false
        }
        
        // 開發時也可跳過驗證
        named("verifyPlugin") {
            enabled = !isDevMode
        }
    }
    
    // 優化 runIde 任務
    named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
        // 增加記憶體配置
        maxHeapSize = "2048m"
        
        // 開發時的 JVM 參數優化
        jvmArgs(
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:+IgnoreUnrecognizedVMOptions",
            "-Xverify:none",
            "-XX:TieredStopAtLevel=1",
            "-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader"
        )
        
        // 系統屬性
        systemProperty("idea.platform.prefix", "idea")
        systemProperty("idea.is.internal", "true") // 開發者模式
        systemProperty("idea.debug.mode", providers.gradleProperty("debug.mode").orElse("false").get())
    }
    
    // 優化測試任務
    test {
        useJUnitPlatform()
        maxHeapSize = "1024m"
        
        // 並行測試
        maxParallelForks = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
        
        // 測試輸出
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = providers.gradleProperty("test.showOutput").map { it.toBoolean() }.orElse(false).get()
        }
    }
}

// 配置 IntelliJ Platform 插件（使用 2.7.0 新特性）
val buildSearchableOptionsProperty = providers.gradleProperty("buildSearchableOptions")
    .map { it.toBoolean() }
    .orElse(false)

val instrumentCodeProperty = providers.gradleProperty("instrumentCode")
    .map { it.toBoolean() }
    .orElse(false)

intellijPlatform {
    // 啟用自動重載功能（開發時很有用）
    autoReload.set(providers.gradleProperty("dev.mode").map { it.toBoolean() }.orElse(true).get())
    
    // 搜尋選項建構（基於屬性）
    buildSearchableOptions.set(buildSearchableOptionsProperty.get())
    
    // 程式碼 instrumentation（優化建構速度）
    instrumentCode.set(instrumentCodeProperty.get())
    
    // 插件配置
    pluginConfiguration {
        id.set("com.cathaybk.codingassistant")
        name.set("Code Sentinel")
        version.set(project.version.toString())
        
        vendor {
            name.set("AudiChuang")
            email.set("audiapplication880208@gmail.com")
            url.set("https://github.com/audichuang")
        }
        
        description.set("""
            Code Sentinel - Intelligent coding assistant and pre-commit guard for IntelliJ IDEA. Ensures code quality through real-time inspections, pre-commit checks, and smart quick fixes.

            <h2>Code Sentinel: Your Intelligent Coding and Commit Guard</h2>
            <p>Code Sentinel is a developer assistant plugin designed for IntelliJ IDEA, providing <b>real-time code analysis</b> and <b>pre-commit checks</b> to ensure code quality, maintain team coding standards, and significantly improve development workflow efficiency.</p>
            <hr/>
            <h3>Core Features</h3>
            <b>Real-time Inspection and Quick Fixes:</b>
            <ul>
                <li><b>Code Standards Sentinel:</b> Instantly catches code that doesn't comply with predefined standards (e.g., API MsgID format, missing Javadoc).</li>
                <li><b>Smart Fixes:</b> Provides context-aware quick fix suggestions (via Alt+Enter / Option+Enter).</li>
            </ul>
            <b>Pre-commit Guard (Optional):</b>
            <ul>
                <li><b>Git Branch Check:</b> Prevents commits based on outdated branches.</li>
                <li><b>Code Quality Gate:</b> Scans pending changes for compliance.</li>
                <li><b>Issue Dashboard:</b> Lists all detected issues clearly.</li>
                <li><b>Batch Fix ("Fix All"):</b> One-click fix for all issues.</li>
            </ul>
            <b>Smart Assistance:</b>
            <ul>
                <li><b>Javadoc Generator:</b> Auto-generates complete or minimal Javadoc.</li>
                <li><b>API Search:</b> Search APIs via Search Everywhere (Shift+Shift).</li>
                <li><b>API Copy:</b> Copy complete API code with dependencies.</li>
            </ul>
            <hr/>
            <h3>Highly Configurable</h3>
            <p>Enable/disable Git checks, code inspections, choose Javadoc style, and define target branches in Settings.</p>
            <p>Let Code Sentinel be your reliable assistant and quality guardian!</p>
        """)

        ideaVersion {
            sinceBuild.set(providers.gradleProperty("plugin.sinceBuild").orElse("231"))
            // 明確不設定 untilBuild，支援所有未來版本
            untilBuild.set(provider { null })
        }
        
        changeNotes.set("""
            <b>v1.6.0 - Search Everywhere API 整合與穩定性提升</b>
            <ul>
                <li><b>新功能：Search Everywhere API 搜尋整合</b> - 在 Shift+Shift 對話框中新增「APIs」分頁，支援 MSGID、路徑、描述的模糊搜尋</li>
                <li><b>新功能：API 依賴預覽增強</b> - 區分直接依賴與遞迴依賴檔案，改進依賴分析視覺化</li>
                <li><b>效能與穩定性</b> - 修正執行緒安全問題，改善 Modal Dialog UI 更新</li>
                <li><b>記憶體洩漏修復</b> - 強化 disposed 狀態檢查，優化快取清理機制</li>
                <li><b>建構系統</b> - 升級 Gradle 9.2.1，IntelliJ Platform Plugin 2.10.5</li>
                <li><b>程式碼品質</b> - 重構內部類別名稱，移除未使用的 import</li>
            </ul>
            <br/>
            <b>v1.5.0 - Service 電文代號支援與效能優化</b>
            <ul>
                <li>新增 Service 類別與方法的電文代號檢查功能</li>
                <li>支援 Service 介面與實現類的自動識別與後綴生成（Svc/SvcImpl）</li>
                <li>優化檔案變更檢測器，提升大型專案的檢查效能</li>
                <li>新增 PSI 檢查結果緩存機制，避免重複檢查</li>
                <li>改進資源管理，實作 Disposable 介面防止記憶體洩漏</li>
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