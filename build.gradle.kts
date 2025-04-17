plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.5.0"
    id("io.freefair.lombok") version "8.4"
}

group = "com.cathaybk"
version = "1.1.0"

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
        force("com.fasterxml.jackson.core:jackson-core:2.15.2")
        force("com.fasterxml.jackson.core:jackson-databind:2.15.2")
        force("com.fasterxml.jackson.core:jackson-annotations:2.15.2")
    }
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
    
    // 添加 lombok 依賴
    compileOnly("org.projectlombok:lombok:1.18.36")
    annotationProcessor("org.projectlombok:lombok:1.18.36")
    
    // 添加 Jackson 依賴（明確指定版本）
    implementation("com.fasterxml.jackson.core:jackson-core:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.15.2")
    
    // 指定 IntelliJ Platform 依賴
    intellijPlatform {
        create("IC", "2024.3")
        bundledPlugin("com.intellij.java")
    }
}

tasks {
    // 配置 Java 版本
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
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
        // 分配更多記憶體
        jvmArgs("-Xmx1g")
    }
}

// 配置 IntelliJ Platform 插件
intellijPlatform {
    pluginConfiguration {
        id.set("com.cathaybk.codingassistant")
        name.set("CathayBk Coding Assistant")
        version.set(project.version.toString())
        
        vendor {
            name.set("CathayBk")
            email.set("support@cathaybk.com")
            url.set("https://www.cathaybk.com")
        }
        
        description.set("""
            國泰銀行程式碼助手，用於協助開發人員遵循公司的編碼標準和規範。
            主要功能：
            - 檢查Controller層API方法是否添加了msgID註解
            - 自動關聯Controller的API方法與對應的Service實現
            - 提供程式碼規範檢查並顯示警告
        """)

        ideaVersion {
            sinceBuild.set("231")
            untilBuild.set("251.*")
        }
        
        changeNotes.set("""
            <ul>
                <li>1.1.0: 優化效能，修正已知問題，改善使用者體驗</li>
                <li>1.0.0: 初始版本，提供API註解檢查和Service關聯功能</li>
            </ul>
        """)
    }
} 