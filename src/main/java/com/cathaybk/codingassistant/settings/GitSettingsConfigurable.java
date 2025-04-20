package com.cathaybk.codingassistant.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;

/**
 * 為 Git 設置提供配置界面
 * 
 * 此類實現了 IntelliJ IDEA 的 Configurable 接口，提供以下功能：
 * 1. Git 分支檢查設置 - 配置目標分支以便在提交前檢查
 * 2. Javadoc 生成設置 - 控制自動生成 Javadoc 的格式和內容
 * 
 * 界面使用 GridBagLayout 實現靈活的佈局，並遵循 IntelliJ IDEA 的 UI 設計指南
 */
public class GitSettingsConfigurable implements Configurable {
    /** 當前項目實例 */
    @Nullable
    private Project project;

    /** 主配置面板 */
    private JPanel myMainPanel;

    /** 目標分支輸入字段 */
    private JBTextField targetBranchesField;

    /** Javadoc 生成選項複選框 */
    private JCheckBox generateFullJavadocCheckbox;

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
        return "國泰 Git 設置";
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
        // Make main panel transparent
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
        JButton gitResetButton = createStyledButton("重置分支為預設值");
        gitResetButton.addActionListener(e -> {
            if (project != null) {
                GitSettings settings = GitSettings.getInstance(project);
                settings.setTargetBranchesFromString("dev");
                loadSettings();
            }
        });

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

        // 填充剩餘空間
        gbc.gridy++;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        JPanel filler = new JPanel();
        // Make filler transparent
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
     * 配置示例文本區域的外觀
     * 
     * @param textArea 要配置的文本區域
     */
    private void configureExampleTextArea(JTextArea textArea) {
        textArea.setEditable(false);
        // Use theme color for background
        textArea.setBackground(UIManager.getColor("EditorPane.background"));

        Font monoFont = new Font(Font.MONOSPACED, Font.PLAIN,
                UIManager.getFont("Label.font").getSize());
        textArea.setFont(monoFont);

        // Use theme color for border
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
            generateFullJavadocCheckbox.setSelected(true); // 預設勾選
            return;
        }

        GitSettings settings = GitSettings.getInstance(project);
        targetBranchesField.setText(settings.getTargetBranchesAsString());
        generateFullJavadocCheckbox.setSelected(settings.isGenerateFullJavadoc());
    }

    /**
     * 將所有設定重置為預設值
     * 注意：目前單獨按鈕處理分支重置，此方法僅作為備用
     */
    private void resetToDefaults() {
        // 這個方法現在可能不太需要，或者可以只重置所有設定為預設
        // 按鈕已經分離，單獨處理分支重置
        if (project == null) {
            targetBranchesField.setText("dev");
            generateFullJavadocCheckbox.setSelected(true);
            return;
        }

        GitSettings settings = GitSettings.getInstance(project);
        settings.setTargetBranchesFromString("dev");
        settings.setGenerateFullJavadoc(true);
        // 注意：調用 loadSettings 會覆蓋這裡的修改，所以要么不調用，要么只修改 settings
    }

    /**
     * 檢查設定是否已被修改
     * 
     * @return 如果任何設定已被修改則返回 true
     */
    @Override
    public boolean isModified() {
        if (project == null) {
            return !targetBranchesField.getText().equals("dev");
        }

        GitSettings settings = GitSettings.getInstance(project);
        boolean branchesModified = !targetBranchesField.getText().equals(settings.getTargetBranchesAsString());
        // 檢查 Javadoc 設定是否被修改
        boolean javadocModified = generateFullJavadocCheckbox.isSelected() != settings.isGenerateFullJavadoc();
        return branchesModified || javadocModified;
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

        // 檢查分支名稱是否有效
        String[] branches = branchesText.split(",");
        for (String branch : branches) {
            String trimmed = branch.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (trimmed.contains(" ") || trimmed.contains("\t")) {
                throw new ConfigurationException("分支名稱 '" + trimmed + "' 包含空格，這可能不是有效的分支名稱。");
            }
        }

        if (project == null) {
            Messages.showInfoMessage("無法保存設置：沒有開啟的項目。", "設置保存失敗");
            return;
        }

        GitSettings settings = GitSettings.getInstance(project);
        settings.setTargetBranchesFromString(branchesText);
        // 保存 Javadoc 設定
        settings.setGenerateFullJavadoc(generateFullJavadocCheckbox.isSelected());

        Messages.showInfoMessage(project, "設置已保存！分支檢查將使用這些目標分支: " + branchesText, "設置保存成功");
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
        myMainPanel = null;
        targetBranchesField = null;
        // 釋放 Javadoc UI 資源
        generateFullJavadocCheckbox = null;
    }
}