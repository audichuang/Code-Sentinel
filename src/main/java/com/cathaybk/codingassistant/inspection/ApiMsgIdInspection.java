package com.cathaybk.codingassistant.inspection;

import com.cathaybk.codingassistant.common.ProblemInfo;
import com.cathaybk.codingassistant.fix.AddApiIdDocFix;
import com.cathaybk.codingassistant.fix.AddControllerApiIdFromServiceFix;
import com.cathaybk.codingassistant.fix.AddServiceApiIdQuickFix;
import com.cathaybk.codingassistant.util.CathayBkInspectionUtil;
import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;

import java.util.List;
// import java.util.*; // 可能不再需要

/**
 * API 電文代號檢查器。 (委託 CathayBkInspectionUtil 執行檢查)
 * <p>
 * 此檢查器用於驗證專案中的 Java 程式碼是否符合電文代號 (ApiMsgId) 的 Javadoc 註解規範。
 * </p>
 */
public class ApiMsgIdInspection extends AbstractBaseJavaLocalInspectionTool {

    @NotNull
    @Override
    public String getShortName() {
        return "ApiMsgIdInspection";
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return "API 電文代號檢查";
    }

    @NotNull
    @Override
    public String getGroupDisplayName() {
        return "CathayBk規範檢查";
    }

    @NotNull
    @Override
    public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {

            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                // 委託給 Util 進行檢查
                List<ProblemInfo> problems = CathayBkInspectionUtil.checkApiMethodDoc(method);

                // 根據 Util 返回的問題註冊 ProblemDescriptor
                for (ProblemInfo problem : problems) {
                    LocalQuickFix fix;
                    // 根據是否有建議值選擇不同的 QuickFix
                    if (problem.getSuggestionSource() != null && problem.getSuggestedValue() != null) {
                        fix = new AddControllerApiIdFromServiceFix(problem.getSuggestionSource(), problem.getSuggestedValue());
                    } else {
                        fix = new AddApiIdDocFix(); // 預設修復
                    }
                    holder.registerProblem(
                            problem.getElement(),
                            problem.getDescription(),
                            problem.getHighlightType(),
                            fix);
                }
            }

            @Override
            public void visitClass(@NotNull PsiClass aClass) {
                // 委託給 Util 進行檢查
                List<ProblemInfo> problems = CathayBkInspectionUtil.checkServiceClassDoc(aClass);

                // 根據 Util 返回的問題註冊 ProblemDescriptor
                for (ProblemInfo problem : problems) {
                    LocalQuickFix fix;
                    if (problem.getSuggestionSource() != null && problem.getSuggestedValue() != null) {
                        fix = new AddServiceApiIdQuickFix(problem.getSuggestionSource(), problem.getSuggestedValue());
                    } else {
                        // Service 類如果沒建議，也給一個基礎的添加 Fix
                        fix = new AddApiIdDocFix(); // 或者創建一個專門的 AddServiceApiIdDocFix
                    }
                    holder.registerProblem(
                            problem.getElement(),
                            problem.getDescription(),
                            problem.getHighlightType(),
                            fix);
                }
            }
        };
    }

    // findAndSuggestApiIdsFromUsedServices 方法現在移到了 ApiMsgIdUtil，這裡不再需要
}