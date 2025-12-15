package com.cathaybk.codingassistant.dialog;

import com.intellij.ide.util.PackageChooserDialog;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiNameHelper;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.RecentsManager;
import com.intellij.ui.TextFieldWithAutoCompletion;
import com.intellij.ui.components.JBLabel;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class ChooseDtoPackageDialog extends DialogWrapper {
    private static final @NonNls String RECENTS_KEY = "CreateDtoIntentionAction.RecentsKey";
    private final Project project;
    private final String initialPackageName;
    private TextFieldWithAutoCompletion<String> packageField;

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

        // 創建包名稱建議列表
        List<String> packageSuggestions = createPackageSuggestions();

        // 使用 TextFieldWithAutoCompletion 替代 ComboBox
        packageField = TextFieldWithAutoCompletion.create(
                project,
                packageSuggestions,
                true,
                initialPackageName);

        label.setLabelFor(packageField);
        panel.add(label, BorderLayout.WEST);
        panel.add(packageField, BorderLayout.CENTER);

        JButton browseButton = new JButton("...");
        browseButton.addActionListener(e -> browsePackages());
        panel.add(browseButton, BorderLayout.EAST);

        return panel;
    }

    /**
     * 創建包名建議列表，包括最近使用的包名和項目中存在的包名
     */
    private List<String> createPackageSuggestions() {
        List<String> suggestions = new ArrayList<>();

        // 添加最近使用的包名
        RecentsManager recentsManager = RecentsManager.getInstance(project);
        List<String> recentPackages = recentsManager.getRecentEntries(RECENTS_KEY);
        if (recentPackages != null) {
            suggestions.addAll(recentPackages);
        }

        // 添加初始建議的包名
        if (!initialPackageName.isEmpty() && !suggestions.contains(initialPackageName)) {
            suggestions.add(initialPackageName);
        }

        // 添加項目中存在的包名
        PsiPackage rootPackage = JavaPsiFacade.getInstance(project).findPackage("");
        if (rootPackage != null) {
            collectSubPackages(rootPackage, suggestions);
        }

        return suggestions.stream().distinct().collect(Collectors.toList());
    }

    /**
     * 遞歸收集所有子包名
     */
    private void collectSubPackages(PsiPackage psiPackage, List<String> suggestions) {
        String qualifiedName = psiPackage.getQualifiedName();
        if (!qualifiedName.isEmpty()) {
            suggestions.add(qualifiedName);
        }

        for (PsiPackage subPackage : psiPackage.getSubPackages(GlobalSearchScope.projectScope(project))) {
            collectSubPackages(subPackage, suggestions);
        }
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
                packageField.setText(selectedPackage.getQualifiedName());
            }
        }
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return packageField;
    }

    public String getPackageName() {
        return packageField.getText().trim();
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String name = getPackageName();
        if (name.isEmpty()) {
            return new ValidationInfo("包名不能為空", packageField);
        }
        if (!PsiNameHelper.getInstance(project).isQualifiedName(name)) {
            return new ValidationInfo("'" + name + "' 不是有效的包名", packageField);
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