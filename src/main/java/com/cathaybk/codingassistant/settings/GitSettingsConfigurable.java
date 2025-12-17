package com.cathaybk.codingassistant.settings;

import com.cathaybk.codingassistant.apicopy.service.ApiIndexService;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 為 Git 設置提供配置界面
 *
 * 此類實現了 IntelliJ IDEA 的 Configurable 接口，提供以下功能：
 * 1. Git 分支檢查設置 - 配置目標分支以便在提交前檢查
 * 2. Javadoc 生成設置 - 控制自動生成 Javadoc 的格式和內容
 *
 * 界面使用 GridBagLayout 實現靈活的佈局，並遵循 IntelliJ IDEA 的 UI 設計指南
 */
public class GitSettingsConfigurable implements SearchableConfigurable {
    /** 當前項目實例 */
    @Nullable
    private Project project;
    
    /**
     * 取得唯一識別 ID（用於搜尋功能）
     * @return 搜尋用的唯一 ID
     */
    @Override
    @NonNls
    @NotNull
    public String getId() {
        return "com.cathaybk.codingassistant.settings";
    }

    /** 主配置面板 */
    private JPanel myMainPanel;

    /** 目標分支輸入字段 */
    private JBTextField targetBranchesField;

    /** Javadoc 生成選項複選框 */
    private JCheckBox generateFullJavadocCheckbox;

    /** Git 檢查開關複選框 */
    private JCheckBox checkGitBranchCheckbox;

    /** 程式碼規範檢查開關複選框 */
    private JCheckBox checkCodeQualityCheckbox;

    /** 全 Javadoc 選項複選框 */
    private JRadioButton fullJavadocRadioButton;

    /** 簡化 Javadoc 選項複選框 */
    private JRadioButton minimalJavadocRadioButton;

    /** 參數 DTO 後綴輸入字段 */
    private JTextField parameterDtoSuffixField;

    /** 返回 DTO 後綴輸入字段 */
    private JTextField returnTypeDtoSuffixField;

    /** Git 重置按鈕 */
    private JButton gitResetButton;

    /** Git 重置按鈕的 ActionListener */
    private ActionListener gitResetListener;

    /** Module 過濾啟用複選框 */
    private JCheckBox enableModuleFilteringCheckbox;

    /** Module 選擇列表面板 */
    private JPanel moduleListPanel;

    /** Module 複選框映射 */
    private Map<String, JCheckBox> moduleCheckBoxes = new HashMap<>();

    /** Git 分支名稱的非法字符正則表達式 */
    private static final Pattern INVALID_BRANCH_CHARS = Pattern.compile(".*[~^:?*\\[\\\\].*");

    /** Git 分支名稱的各種驗證規則 */
    private static final BranchValidationRule[] BRANCH_VALIDATION_RULES = {
            new BranchValidationRule(
                    name -> name.contains(" ") || name.contains("\t"),
                    "包含空格"),
            new BranchValidationRule(
                    name -> name.startsWith(".") || name.startsWith("/"),
                    "不能以 '.' 或 '/' 開頭"),
            new BranchValidationRule(
                    name -> name.endsWith(".") || name.endsWith("/"),
                    "不能以 '.' 或 '/' 結尾"),
            new BranchValidationRule(
                    name -> name.contains(".."),
                    "不能包含 '..'"),
            new BranchValidationRule(
                    name -> name.contains("//"),
                    "不能包含 '//'"),
            new BranchValidationRule(
                    name -> name.contains("@{"),
                    "不能包含 '@{'"),
            new BranchValidationRule(
                    name -> name.endsWith(".lock"),
                    "不能以 '.lock' 結尾"),
            new BranchValidationRule(
                    name -> name.equals("@"),
                    "分支名稱不能是 '@'"),
            new BranchValidationRule(
                    name -> INVALID_BRANCH_CHARS.matcher(name).matches(),
                    "包含無效字符 (例如 ~^:?*[\\)")
    };

    /**
     * 默認構造函數，嘗試獲取當前活動項目
     * 當作為應用程序級配置使用時使用此構造函數
     */
    public GitSettingsConfigurable() {
        // 當作為應用程序級配置使用時，嘗試獲取當前活動項目
        Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
        if (openProjects.length > 0) {
            this.project = openProjects[0];
        }
    }

    /**
     * 項目特定構造函數
     *
     * @param project 要為其配置設置的項目
     */
    public GitSettingsConfigurable(Project project) {
        this.project = project;
    }

    /**
     * 返回配置頁面的顯示名稱
     *
     * @return 配置頁面標題
     */
    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Code Sentinel 設定";
    }

    /**
     * 創建配置界面組件
     * 此方法創建並返回完整的設置界面
     *
     * @return 包含所有配置選項的 Swing 組件
     */
    @Nullable
    @Override
    public JComponent createComponent() {
        // --- Main Panel ---
        myMainPanel = new JPanel();
        myMainPanel.setLayout(new GridBagLayout());
        // 使面板透明
        myMainPanel.setOpaque(false);

        myMainPanel.setBorder(JBUI.Borders.empty(12));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(6); // 使用 JBUI 縮放的邊距，適合高 DPI 顯示器
        gbc.weightx = 1.0;
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.NORTHWEST;

        // --- Git 分支檢查設定區塊 ---
        JPanel gitPanel = createPanelWithBorder("Git 分支檢查設定");
        gitPanel.setLayout(new GridBagLayout());
        GridBagConstraints gitGbc = new GridBagConstraints();
        gitGbc.fill = GridBagConstraints.HORIZONTAL;
        gitGbc.weightx = 1.0;
        gitGbc.gridx = 0;
        gitGbc.gridy = GridBagConstraints.RELATIVE;
        gitGbc.insets = JBUI.insets(4);
        gitGbc.anchor = GridBagConstraints.WEST;

        // 新增：Git 檢查開關
        checkGitBranchCheckbox = new JCheckBox("啟用 Git 分支落後檢查 (提交前)");
        checkGitBranchCheckbox.setOpaque(false);
        checkGitBranchCheckbox.setToolTipText("若關閉，提交前將不執行 Git fetch 和分支比較。");
        checkGitBranchCheckbox.setBorder(JBUI.Borders.emptyBottom(8));
        gitPanel.add(checkGitBranchCheckbox, gitGbc);

        // 使用 JTextPane 替代 JTextArea 以獲得更好的文字渲染
        JTextPane gitHelpText = new JTextPane();
        gitHelpText.setContentType("text/html");
        gitHelpText.setText("<html><body style='font-family: sans-serif; font-size: 12pt;'>" +
                "當您的分支落後於以下設定的目標分支時，提交前會提醒您。<br>" +
                "多個分支請用逗號分隔 (例如：master,dev)。預設值：dev</body></html>");
        gitHelpText.setEditable(false);
        gitHelpText.setOpaque(false);
        gitHelpText.setBorder(JBUI.Borders.emptyBottom(8));
        gitPanel.add(gitHelpText, gitGbc);

        // 目標分支輸入區
        JPanel gitInputRow = new JPanel(new BorderLayout(8, 0));
        gitInputRow.setOpaque(false);
        gitInputRow.setBorder(JBUI.Borders.emptyBottom(8));

        // 使用更寬的文字字段
        targetBranchesField = new JBTextField();
        targetBranchesField.setToolTipText("輸入要檢查的目標分支，多個分支用逗號分隔");
        targetBranchesField.setPreferredSize(new Dimension(200, targetBranchesField.getPreferredSize().height));

        JLabel gitLabel = new JBLabel("目標分支:");
        gitLabel.setLabelFor(targetBranchesField);
        gitLabel.setPreferredSize(new Dimension(80, gitLabel.getPreferredSize().height));

        gitInputRow.add(gitLabel, BorderLayout.WEST);
        gitInputRow.add(targetBranchesField, BorderLayout.CENTER);
        gitPanel.add(gitInputRow, gitGbc);

        // 重置按鈕使用更現代的樣式
        gitResetButton = createStyledButton("重置分支為預設值");
        // 儲存 ActionListener 參考以便後續清理
        gitResetListener = e -> {
            if (project != null) {
                GitSettings settings = GitSettings.getInstance(project);
                settings.setTargetBranchesFromString("dev");
                loadSettings();
            }
        };
        gitResetButton.addActionListener(gitResetListener);

        JPanel gitButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        gitButtonPanel.setOpaque(false);
        gitButtonPanel.add(gitResetButton);
        gitPanel.add(gitButtonPanel, gitGbc);

        // 將 Git 面板添加到主面板
        myMainPanel.add(gitPanel, gbc);
        gbc.gridy++;

        // 面板之間添加間距
        gbc.insets = JBUI.insets(12, 6, 6, 6);

        // --- Javadoc 生成設定區塊 ---
        JPanel javadocPanel = createPanelWithBorder("Javadoc 生成設定");
        javadocPanel.setLayout(new GridBagLayout());
        GridBagConstraints javadocGbc = new GridBagConstraints();
        javadocGbc.fill = GridBagConstraints.HORIZONTAL;
        javadocGbc.weightx = 1.0;
        javadocGbc.gridx = 0;
        javadocGbc.gridy = GridBagConstraints.RELATIVE;
        javadocGbc.insets = JBUI.insets(4);
        javadocGbc.anchor = GridBagConstraints.WEST;

        // 使用更醒目的複選框
        generateFullJavadocCheckbox = new JCheckBox("為新註解生成完整 Javadoc 結構 (含 @param, @return 等)");
        generateFullJavadocCheckbox.setOpaque(false);
        generateFullJavadocCheckbox.setToolTipText("若取消勾選，則只添加必要文字 (如 API ID)，不生成額外標籤。");
        generateFullJavadocCheckbox.setBorder(JBUI.Borders.emptyBottom(8));
        javadocPanel.add(generateFullJavadocCheckbox, javadocGbc);

        // --- 改進範例顯示 ---
        JPanel examplePanel = new JPanel();
        examplePanel.setLayout(new GridBagLayout());
        examplePanel.setOpaque(false);
        examplePanel.setBorder(JBUI.Borders.empty(4, 0, 0, 0));

        GridBagConstraints exGbc = new GridBagConstraints();
        exGbc.fill = GridBagConstraints.HORIZONTAL;
        exGbc.weightx = 1.0;
        exGbc.gridx = 0;
        exGbc.gridy = GridBagConstraints.RELATIVE;
        exGbc.insets = JBUI.insets(2);

        String exampleMethodSignature = "public String getUser(String userId)";
        String exampleApiId = "API-USER_GETUSER [描述]";

        // 完整 Javadoc 示例
        JLabel fullExampleLabel = new JLabel("<html><b>啟用此選項時 (預設):</b></html>");
        examplePanel.add(fullExampleLabel, exGbc);

        JTextArea fullExampleText = new JTextArea(
                "/**\n" +
                        " * " + exampleApiId + "\n" +
                        " * \n" +
                        " * @param userId 使用者 ID\n" +
                        " * @return 使用者名稱\n" +
                        " */\n" +
                        exampleMethodSignature + " { ... }");
        configureExampleTextArea(fullExampleText);
        examplePanel.add(fullExampleText, exGbc);

        // 簡化 Javadoc 示例
        exGbc.insets = JBUI.insets(12, 2, 2, 2); // 為第二個示例添加上方間距
        JLabel minimalExampleLabel = new JLabel("<html><b>未啟用此選項時:</b></html>");
        examplePanel.add(minimalExampleLabel, exGbc);

        exGbc.insets = JBUI.insets(2);
        JTextArea minimalExampleText = new JTextArea(
                "/**\n" +
                        " * " + exampleApiId + "\n" +
                        " */\n" +
                        exampleMethodSignature + " { ... }");
        configureExampleTextArea(minimalExampleText);
        examplePanel.add(minimalExampleText, exGbc);

        javadocPanel.add(examplePanel, javadocGbc);

        // 添加 Javadoc 面板到主面板
        myMainPanel.add(javadocPanel, gbc);
        gbc.gridy++;

        // --- 程式碼規範檢查設定區塊 ---
        JPanel codeQualityPanel = createPanelWithBorder("程式碼規範檢查設定");
        codeQualityPanel.setLayout(new BorderLayout());

        checkCodeQualityCheckbox = new JCheckBox("啟用程式碼規範檢查 (提交前)");
        checkCodeQualityCheckbox.setOpaque(false);
        checkCodeQualityCheckbox.setToolTipText("若關閉，提交前將不執行自訂的程式碼規範檢查。警告：可能導致不合規的代碼被提交。");
        codeQualityPanel.add(checkCodeQualityCheckbox, BorderLayout.NORTH);

        // Add codeQualityPanel to the main panel
        myMainPanel.add(codeQualityPanel, gbc);
        gbc.gridy++;

        // --- API Module 過濾設定區塊 ---
        JPanel moduleFilterPanel = createModuleFilterPanel();
        myMainPanel.add(moduleFilterPanel, gbc);
        gbc.gridy++;

        // --- DTO 後綴設定區塊 ---
        JPanel dtoSuffixPanel = new JPanel(new GridBagLayout());
        dtoSuffixPanel.setBorder(IdeBorderFactory.createTitledBorder("DTO 電文代號後綴設定", true));
        GridBagConstraints dtoGbc = new GridBagConstraints(); // Use new constraints for inner layout
        dtoGbc.gridx = 0;
        dtoGbc.gridy = 0;
        dtoGbc.anchor = GridBagConstraints.WEST;
        dtoGbc.insets = JBUI.insets(2, 5);
        dtoSuffixPanel.add(new JLabel("參數 DTO 後綴:"), dtoGbc);

        dtoGbc.gridx = 1;
        dtoGbc.fill = GridBagConstraints.HORIZONTAL;
        dtoGbc.weightx = 1.0;
        parameterDtoSuffixField = new JTextField();
        dtoSuffixPanel.add(parameterDtoSuffixField, dtoGbc);

        dtoGbc.gridx = 0;
        dtoGbc.gridy = 1;
        dtoGbc.fill = GridBagConstraints.NONE;
        dtoGbc.weightx = 0;
        dtoSuffixPanel.add(new JLabel("返回 DTO 後綴:"), dtoGbc);

        dtoGbc.gridx = 1;
        dtoGbc.fill = GridBagConstraints.HORIZONTAL;
        dtoGbc.weightx = 1.0;
        returnTypeDtoSuffixField = new JTextField();
        dtoSuffixPanel.add(returnTypeDtoSuffixField, dtoGbc);

        // Add dtoSuffixPanel using the main panel's constraints
        myMainPanel.add(dtoSuffixPanel, gbc);
        gbc.gridy++; // Increment gridy

        // --- Filler --- (Push everything up)
        gbc.weighty = 1.0; // Give remaining vertical space to filler
        gbc.fill = GridBagConstraints.BOTH;
        JPanel filler = new JPanel();
        filler.setOpaque(false);
        myMainPanel.add(filler, gbc);

        // 加載設定
        loadSettings();

        return myMainPanel;
    }

    /**
     * 創建帶有美觀邊框的面板
     *
     * @param title 面板標題
     * @return 帶有標題邊框的配置面板
     */
    private JPanel createPanelWithBorder(String title) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);

        // 使用更現代的 IntelliJ 風格邊框
        TitledBorder titledBorder = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor"), 1, true),
                title);
        titledBorder.setTitleFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD));
        panel.setBorder(BorderFactory.createCompoundBorder(
                titledBorder,
                JBUI.Borders.empty(10, 12, 12, 12)));

        return panel;
    }

    /**
     * 創建風格化按鈕
     *
     * @param text 按鈕文字
     * @return 風格化的按鈕
     */
    private JButton createStyledButton(String text) {
        JButton button = new JButton(text);
        // 使按鈕看起來更現代
        button.setFocusPainted(false);
        button.setBorderPainted(true);
        return button;
    }

    /**
     * 創建 Module 過濾設定面板
     *
     * @return Module 過濾設定面板
     */
    private JPanel createModuleFilterPanel() {
        JPanel panel = createPanelWithBorder("API Module 過濾設定");
        panel.setLayout(new BorderLayout());

        // 啟用複選框
        enableModuleFilteringCheckbox = new JCheckBox("啟用 API 索引 Module 過濾");
        enableModuleFilteringCheckbox.setOpaque(false);
        enableModuleFilteringCheckbox.setToolTipText("若啟用，只索引選中的 Module 中的 API。");
        enableModuleFilteringCheckbox.setBorder(JBUI.Borders.emptyBottom(8));
        panel.add(enableModuleFilteringCheckbox, BorderLayout.NORTH);

        // Module 列表
        moduleListPanel = new JPanel();
        moduleListPanel.setLayout(new BoxLayout(moduleListPanel, BoxLayout.Y_AXIS));
        moduleListPanel.setOpaque(false);

        // 載入 Module 列表
        refreshModuleList();

        JBScrollPane scrollPane = new JBScrollPane(moduleListPanel);
        scrollPane.setPreferredSize(new Dimension(300, 120));
        scrollPane.setBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")));

        JPanel moduleContainer = new JPanel(new BorderLayout());
        moduleContainer.setOpaque(false);
        moduleContainer.add(new JLabel("選擇要索引的 Module:"), BorderLayout.NORTH);
        moduleContainer.add(scrollPane, BorderLayout.CENTER);
        moduleContainer.setBorder(JBUI.Borders.emptyTop(4));

        panel.add(moduleContainer, BorderLayout.CENTER);

        // 啟用/禁用聯動
        enableModuleFilteringCheckbox.addActionListener(e -> {
            boolean enabled = enableModuleFilteringCheckbox.isSelected();
            for (JCheckBox cb : moduleCheckBoxes.values()) {
                cb.setEnabled(enabled);
            }
        });

        return panel;
    }

    /**
     * 重新整理 Module 列表
     */
    private void refreshModuleList() {
        moduleListPanel.removeAll();
        moduleCheckBoxes.clear();

        if (project != null) {
            ApiIndexService indexService = ApiIndexService.getInstance(project);
            List<String> modules = indexService.getAvailableModules();

            for (String moduleName : modules) {
                JCheckBox cb = new JCheckBox(moduleName, true);
                cb.setOpaque(false);
                moduleCheckBoxes.put(moduleName, cb);
                moduleListPanel.add(cb);
            }

            if (modules.isEmpty()) {
                JLabel noModulesLabel = new JLabel("（未偵測到 Module）");
                noModulesLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
                moduleListPanel.add(noModulesLabel);
            }
        } else {
            JLabel noProjectLabel = new JLabel("（無專案）");
            noProjectLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            moduleListPanel.add(noProjectLabel);
        }

        moduleListPanel.revalidate();
        moduleListPanel.repaint();
    }

    /**
     * 配置示例文本區域的外觀
     *
     * @param textArea 要配置的文本區域
     */
    private void configureExampleTextArea(JTextArea textArea) {
        textArea.setEditable(false);
        // 使用主題顏色作為背景
        textArea.setBackground(UIManager.getColor("EditorPane.background"));

        Font monoFont = new Font(Font.MONOSPACED, Font.PLAIN,
                UIManager.getFont("Label.font").getSize());
        textArea.setFont(monoFont);

        // 使用主題顏色作為邊框
        textArea.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(8, 12, 8, 12) // 增加內部填充
        ));

        textArea.setLineWrap(false);
    }

    /**
     * 從設定加載保存的值到 UI 組件
     * 若沒有項目，則使用預設值
     */
    private void loadSettings() {
        if (project == null) {
            targetBranchesField.setText("dev");
            generateFullJavadocCheckbox.setSelected(true);
            checkGitBranchCheckbox.setSelected(true);
            checkCodeQualityCheckbox.setSelected(true); // 預設勾選
            enableModuleFilteringCheckbox.setSelected(false);
            return;
        }

        GitSettings settings = GitSettings.getInstance(project);
        targetBranchesField.setText(settings.getTargetBranchesAsString());
        generateFullJavadocCheckbox.setSelected(settings.isGenerateFullJavadoc());
        checkGitBranchCheckbox.setSelected(settings.isCheckGitBranch());
        // 加載程式碼檢查開關設定
        checkCodeQualityCheckbox.setSelected(settings.isCheckCodeQuality());
        // 新增 DTO 後綴重置
        parameterDtoSuffixField.setText(settings.getParameterDtoSuffix());
        returnTypeDtoSuffixField.setText(settings.getReturnTypeDtoSuffix());

        // 載入 Module 過濾設定
        enableModuleFilteringCheckbox.setSelected(settings.isEnableModuleFiltering());
        Set<String> indexedModules = settings.getIndexedModules();
        for (Map.Entry<String, JCheckBox> entry : moduleCheckBoxes.entrySet()) {
            // 若 indexedModules 為空，預設全選；否則按設定值選擇
            boolean selected = indexedModules.isEmpty() || indexedModules.contains(entry.getKey());
            entry.getValue().setSelected(selected);
            entry.getValue().setEnabled(settings.isEnableModuleFiltering());
        }
    }

    /**
     * 檢查設定是否已被修改
     *
     * @return 如果任何設定已被修改則返回 true
     */
    @Override
    public boolean isModified() {
        if (project == null) {
            boolean branchesModified = !targetBranchesField.getText().equals("dev");
            boolean javadocModified = generateFullJavadocCheckbox.isSelected() != true;
            boolean gitCheckModified = checkGitBranchCheckbox.isSelected() != true;
            boolean codeQualityModified = checkCodeQualityCheckbox.isSelected() != true;
            boolean moduleFilterModified = enableModuleFilteringCheckbox.isSelected() != false;
            return branchesModified || javadocModified || gitCheckModified || codeQualityModified || moduleFilterModified;
        }

        GitSettings settings = GitSettings.getInstance(project);
        boolean branchesModified = !targetBranchesField.getText().equals(settings.getTargetBranchesAsString());
        boolean javadocModified = generateFullJavadocCheckbox.isSelected() != settings.isGenerateFullJavadoc();
        boolean gitCheckModified = checkGitBranchCheckbox.isSelected() != settings.isCheckGitBranch();
        // 檢查程式碼檢查開關是否被修改
        boolean codeQualityModified = checkCodeQualityCheckbox.isSelected() != settings.isCheckCodeQuality();
        boolean dtoSuffixModified = !Objects.equals(settings.getParameterDtoSuffix(), parameterDtoSuffixField.getText())
                ||
                !Objects.equals(settings.getReturnTypeDtoSuffix(), returnTypeDtoSuffixField.getText());

        // 檢查 Module 過濾設定是否被修改
        boolean moduleFilterModified = enableModuleFilteringCheckbox.isSelected() != settings.isEnableModuleFiltering();
        boolean moduleSelectionModified = isModuleSelectionModified(settings);

        return branchesModified || javadocModified || gitCheckModified || codeQualityModified
                || dtoSuffixModified || moduleFilterModified || moduleSelectionModified;
    }

    /**
     * 檢查 Module 選擇是否被修改
     */
    private boolean isModuleSelectionModified(GitSettings settings) {
        Set<String> savedModules = settings.getIndexedModules();
        Set<String> currentModules = getSelectedModules();

        // 若 savedModules 為空，表示預設全選
        if (savedModules.isEmpty()) {
            // 只有當有 Module 未選中時才算修改
            return currentModules.size() != moduleCheckBoxes.size();
        }

        return !savedModules.equals(currentModules);
    }

    /**
     * 取得目前選中的 Module
     */
    private Set<String> getSelectedModules() {
        Set<String> selected = new HashSet<>();
        for (Map.Entry<String, JCheckBox> entry : moduleCheckBoxes.entrySet()) {
            if (entry.getValue().isSelected()) {
                selected.add(entry.getKey());
            }
        }
        return selected;
    }

    /**
     * 驗證分支名稱
     *
     * @param branchName 要驗證的分支名稱
     * @return 驗證錯誤信息，如果合法則返回 null
     */
    @Nullable
    private String validateBranchName(String branchName) {
        if (branchName.isEmpty()) {
            return null; // 空字符串會在外層處理
        }

        for (BranchValidationRule rule : BRANCH_VALIDATION_RULES) {
            if (rule.test(branchName)) {
                return rule.getErrorMessage();
            }
        }

        return null; // 通過所有驗證
    }

    /**
     * 應用並保存設定
     * 在用戶點擊 "應用" 或 "確定" 按鈕時調用
     *
     * @throws ConfigurationException 如果設定輸入無效
     */
    @Override
    public void apply() throws ConfigurationException {
        String branchesText = targetBranchesField.getText().trim();
        if (branchesText.isEmpty()) {
            throw new ConfigurationException("目標分支不能為空，請至少提供一個分支。");
        }

        // 驗證所有分支名稱
        String[] branches = branchesText.split(",");
        List<String> validBranches = new ArrayList<>();

        for (String branch : branches) {
            String trimmed = branch.trim();
            if (trimmed.isEmpty()) {
                continue; // 忽略空的條目
            }

            validBranches.add(trimmed);

            // 驗證分支名稱
            String errorMessage = validateBranchName(trimmed);
            if (errorMessage != null) {
                throw new ConfigurationException("分支名稱 \"" + trimmed + "\" 無效：" + errorMessage);
            }
        }

        if (validBranches.isEmpty()) {
            throw new ConfigurationException("目標分支不能為空，請至少提供一個有效分支。");
        }

        if (project == null) {
            Messages.showInfoMessage("無法保存設置：沒有開啟的項目。", "設置保存失敗");
            return;
        }

        GitSettings settings = GitSettings.getInstance(project);
        settings.setTargetBranchesFromString(validBranches.isEmpty() ? "" : String.join(",", validBranches));
        settings.setGenerateFullJavadoc(generateFullJavadocCheckbox.isSelected());
        settings.setCheckGitBranch(checkGitBranchCheckbox.isSelected());
        // 保存程式碼檢查開關設定
        settings.setCheckCodeQuality(checkCodeQualityCheckbox.isSelected());
        // 新增 DTO 後綴保存
        settings.setParameterDtoSuffix(parameterDtoSuffixField.getText());
        settings.setReturnTypeDtoSuffix(returnTypeDtoSuffixField.getText());

        // 保存 Module 過濾設定
        settings.setEnableModuleFiltering(enableModuleFilteringCheckbox.isSelected());
        settings.setIndexedModules(getSelectedModules());
    }

    /**
     * 重置 UI 組件到當前保存的設定值
     * 在用戶點擊 "重置" 按鈕時調用
     */
    @Override
    public void reset() {
        loadSettings();
    }

    /**
     * 釋放 UI 資源
     * 在關閉配置頁面時調用
     */
    @Override
    public void disposeUIResources() {
        // 移除 ActionListener 以防止記憶體洩漏
        if (gitResetButton != null && gitResetListener != null) {
            gitResetButton.removeActionListener(gitResetListener);
            gitResetListener = null;
        }

        // 清理所有 UI 元件參考
        myMainPanel = null;
        targetBranchesField = null;
        generateFullJavadocCheckbox = null;
        checkGitBranchCheckbox = null;
        // 釋放程式碼檢查開關 UI 資源
        checkCodeQualityCheckbox = null;
        fullJavadocRadioButton = null;
        minimalJavadocRadioButton = null;
        parameterDtoSuffixField = null;
        returnTypeDtoSuffixField = null;
        gitResetButton = null;
        // 釋放 Module 過濾 UI 資源
        enableModuleFilteringCheckbox = null;
        moduleListPanel = null;
        moduleCheckBoxes.clear();
    }

    /**
     * 分支名稱驗證規則類
     * 用於封裝驗證邏輯和錯誤訊息
     */
    private static class BranchValidationRule {
        private final BranchValidator validator;
        private final String errorMessage;

        /**
         * 建立驗證規則
         *
         * @param validator    驗證函數
         * @param errorMessage 錯誤訊息
         */
        public BranchValidationRule(BranchValidator validator, String errorMessage) {
            this.validator = validator;
            this.errorMessage = errorMessage;
        }

        /**
         * 測試分支名稱是否違反此規則
         *
         * @param branchName 要測試的分支名稱
         * @return 若違反則返回 true
         */
        public boolean test(String branchName) {
            return validator.isInvalid(branchName);
        }

        /**
         * 獲取錯誤訊息
         *
         * @return 錯誤訊息
         */
        public String getErrorMessage() {
            return errorMessage;
        }
    }

    /**
     * 分支驗證器函數式接口
     */
    @FunctionalInterface
    private interface BranchValidator {
        /**
         * 檢查分支名稱是否無效
         *
         * @param branchName 要檢查的分支名稱
         * @return 若無效則返回 true
         */
        boolean isInvalid(String branchName);
    }
}