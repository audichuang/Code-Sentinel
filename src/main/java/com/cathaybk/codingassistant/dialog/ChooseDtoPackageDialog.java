package com.cathaybk.codingassistant.dialog;

import com.intellij.ide.util.PackageChooserDialog;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiNameHelper;
import com.intellij.psi.PsiPackage;
import com.intellij.ui.RecentsManager;
import com.intellij.ui.components.JBLabel;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class ChooseDtoPackageDialog extends DialogWrapper {
    private static final @NonNls String RECENTS_KEY = "CreateDtoIntentionAction.RecentsKey";
    private final Project project;
    private final String initialPackageName;
    private JComboBox<String> packageComboBox;

    public ChooseDtoPackageDialog(@Nullable Project project, String title, String initialPackageName) {
        super(project, true);
        this.project = project;
        this.initialPackageName = initialPackageName;
        setTitle(title);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));

        JLabel label = new JBLabel("目標包:");
        packageComboBox = new ComboBox<>();
        packageComboBox.setEditable(true);

        // 填充最近和建議的包名
        RecentsManager recentsManager = RecentsManager.getInstance(project);
        List<String> recentPackages = recentsManager.getRecentEntries(RECENTS_KEY);
        if (recentPackages != null) {
            for (String recentPackage : recentPackages) {
                packageComboBox.addItem(recentPackage);
            }
        }
        // 將建議的包名放在最前面（如果不在 recent 裡）
        if (!initialPackageName.isEmpty() && (recentPackages == null || !recentPackages.contains(initialPackageName))) {
            packageComboBox.addItem(initialPackageName);
        }
        // 設置初始選中或輸入
        if (!initialPackageName.isEmpty()) {
            packageComboBox.setSelectedItem(initialPackageName);
        } else if (packageComboBox.getItemCount() > 0) {
            packageComboBox.setSelectedIndex(0);
        }

        label.setLabelFor(packageComboBox);
        panel.add(label, BorderLayout.WEST);
        panel.add(packageComboBox, BorderLayout.CENTER);

        JButton browseButton = new JButton("...");
        browseButton.addActionListener(e -> browsePackages());
        panel.add(browseButton, BorderLayout.EAST);

        return panel;
    }

    private void browsePackages() {
        PackageChooserDialog chooser = new PackageChooserDialog("選擇目標包", project);
        String currentSelection = getPackageName();
        PsiPackage currentPackage = JavaPsiFacade.getInstance(project).findPackage(currentSelection);
        if (currentPackage != null) {
            chooser.selectPackage(currentPackage.getQualifiedName());
        }

        if (chooser.showAndGet()) {
            PsiPackage selectedPackage = chooser.getSelectedPackage();
            if (selectedPackage != null) {
                packageComboBox.getEditor().setItem(selectedPackage.getQualifiedName());
            }
        }
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        // 強制轉換為 JComponent
        Component editorComponent = packageComboBox.getEditor().getEditorComponent();
        return (editorComponent instanceof JComponent) ? (JComponent) editorComponent : null;
    }

    public String getPackageName() {
        return ((JTextField) packageComboBox.getEditor().getEditorComponent()).getText().trim();
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String name = getPackageName();
        if (name.isEmpty()) {
            return new ValidationInfo("包名不能為空", packageComboBox);
        }
        if (!PsiNameHelper.getInstance(project).isQualifiedName(name)) {
            return new ValidationInfo("'" + name + "' 不是有效的包名", packageComboBox);
        }
        return super.doValidate();
    }

    @Override
    protected void doOKAction() {
        // 將選中的包名存儲到 RecentsManager
        RecentsManager.getInstance(project).registerRecentEntry(RECENTS_KEY, getPackageName());
        super.doOKAction();
    }
}