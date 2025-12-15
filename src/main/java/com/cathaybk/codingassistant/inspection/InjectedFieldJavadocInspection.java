package com.cathaybk.codingassistant.inspection;

import com.cathaybk.codingassistant.common.ProblemInfo;
import com.cathaybk.codingassistant.fix.AddFieldJavadocFix;
import com.cathaybk.codingassistant.util.CathayBkInspectionUtil;
import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiField;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressManager;

import java.util.List;

/**
 * 檢查注入的欄位是否缺少 Javadoc 註解。(委託 CathayBkInspectionUtil 執行檢查)
 */
public class InjectedFieldJavadocInspection extends AbstractBaseJavaLocalInspectionTool {

    // private static final Logger LOG =
    // Logger.getInstance(InjectedFieldJavadocInspection.class); // Log 移走

    // 常量已移到 Util
    // private static final String[] COMPONENT_ANNOTATIONS = { ... };
    // private static final String[] INJECTION_ANNOTATIONS = { ... };

    @NotNull
    @Override
    public String getShortName() {
        return "InjectedFieldJavadocInspection";
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return "注入的欄位缺少 Javadoc";
    }

    @NotNull
    @Override
    public String getGroupDisplayName() {
        return "CathayBk規範檢查";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @NotNull
    @Override
    public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        // System.out.println 和 LOG 移到 Handler
        return new JavaElementVisitor() {
            @Override
            public void visitField(@NotNull PsiField field) {
                // 檢查取消狀態
                ProgressManager.checkCanceled();

                // 合併 ReadAction 調用以提高效能
                // 同時獲取問題列表和欄位類型名稱
                ReadAction.run(() -> {
                    List<ProblemInfo> problems = CathayBkInspectionUtil.checkInjectedFieldDoc(field);

                    if (problems.isEmpty()) {
                        return;
                    }

                    // 只獲取一次類型名稱（所有問題都是同一個欄位）
                    String fieldTypeName = field.getType().getPresentableText();

                    // 根據 Util 返回的問題註冊 ProblemDescriptor
                    for (ProblemInfo problem : problems) {
                        // 檢查問題是否仍然有效
                        if (!problem.isValid()) {
                            continue;
                        }
                        holder.registerProblem(
                                problem.getElement(),
                                problem.getDescription(),
                                problem.getHighlightType(),
                                new AddFieldJavadocFix(fieldTypeName) // QuickFix 保持不變
                        );
                    }
                });
            }
        };
    }

    // isLikelyInjectedField 方法已移到 CathayBkInspectionUtil
}