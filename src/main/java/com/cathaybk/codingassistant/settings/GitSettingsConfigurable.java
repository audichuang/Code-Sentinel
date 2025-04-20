package com.cathaybk.codingassistant.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * 為 Git 設置提供配置界面
 */
public class GitSettingsConfigurable implements Configurable {
    @Nullable
    private Project project;
    private JPanel myMainPanel;
    private JBTextField targetBranchesField;

    public GitSettingsConfigurable() {
        // 當作為應用程序級配置使用時，嘗試獲取當前活動項目
        Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
        if (openProjects.length > 0) {
            this.project = openProjects[0];
        }
    }

    public GitSettingsConfigurable(Project project) {
        this.project = project;
    }

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "國泰 Git 設置";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        // 創建主面板，使用 BorderLayout
        myMainPanel = new JPanel(new BorderLayout());

        // 頂部說明面板
        JPanel topPanel = new JPanel(new BorderLayout());
        JTextArea helpText = new JTextArea(
                "設置要檢查的目標分支，當您的分支落後於這些分支時，系統會提醒您。\n" +
                        "多個分支請用逗號分隔，例如：master,dev,main\n" +
                        "預設值為：dev");
        helpText.setEditable(false);
        helpText.setBackground(myMainPanel.getBackground());
        helpText.setFont(UIManager.getFont("Label.font"));
        helpText.setWrapStyleWord(true);
        helpText.setLineWrap(true);
        helpText.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));
        topPanel.add(helpText, BorderLayout.CENTER);

        // 中央輸入面板
        JPanel centerPanel = new JPanel(new BorderLayout());
        targetBranchesField = new JBTextField();
        targetBranchesField.setToolTipText("輸入要檢查的目標分支，多個分支用逗號分隔（例如：master,dev,main）");

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));
        JLabel label = new JBLabel("目標分支（逗號分隔）: ");
        label.setLabelFor(targetBranchesField);

        inputPanel.add(label, BorderLayout.WEST);
        inputPanel.add(targetBranchesField, BorderLayout.CENTER);
        centerPanel.add(inputPanel, BorderLayout.NORTH);

        // 底部按鈕面板
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton resetButton = new JButton("重置為預設值");
        resetButton.addActionListener(e -> {
            resetToDefaults();
            loadSettings();
        });
        bottomPanel.add(resetButton);

        // 添加到主面板
        myMainPanel.add(topPanel, BorderLayout.NORTH);
        myMainPanel.add(centerPanel, BorderLayout.CENTER);
        myMainPanel.add(bottomPanel, BorderLayout.SOUTH);

        // 加載設置
        loadSettings();

        return myMainPanel;
    }

    private void loadSettings() {
        if (project == null) {
            targetBranchesField.setText("dev");
            return;
        }

        GitSettings settings = GitSettings.getInstance(project);
        targetBranchesField.setText(settings.getTargetBranchesAsString());
    }

    private void resetToDefaults() {
        if (project == null) {
            targetBranchesField.setText("dev");
            return;
        }

        GitSettings settings = GitSettings.getInstance(project);
        settings.resetToDefaults();
    }

    @Override
    public boolean isModified() {
        if (project == null) {
            return !targetBranchesField.getText().equals("dev");
        }

        GitSettings settings = GitSettings.getInstance(project);
        return !targetBranchesField.getText().equals(settings.getTargetBranchesAsString());
    }

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

        Messages.showInfoMessage(project, "設置已保存！分支檢查將使用這些目標分支: " + branchesText, "設置保存成功");
    }

    @Override
    public void reset() {
        loadSettings();
    }

    @Override
    public void disposeUIResources() {
        myMainPanel = null;
        targetBranchesField = null;
    }
}