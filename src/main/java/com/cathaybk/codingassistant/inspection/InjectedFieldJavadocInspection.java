package com.cathaybk.codingassistant.inspection;

import com.cathaybk.codingassistant.fix.AddFieldJavadocFix; // 等一下會建立這個類別
import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;

/**
 * 檢查注入的欄位是否缺少 Javadoc 註解。
 * <p>
 * 此檢查器會尋找 Spring 元件 (如 @Service, @Component 等) 中
 * 使用 final 修飾符 (通常透過 Lombok @RequiredArgsConstructor 注入)
 * 或使用 @Autowired/@Inject 註解的欄位，
 * 如果這些欄位缺少 Javadoc，則會提出警告並提供快速修復。
 * </p>
 */
public class InjectedFieldJavadocInspection extends AbstractBaseJavaLocalInspectionTool {

    // 常見的 Spring 元件註解 (也可以考慮 Jakarta EE/CDI 的 @Inject 等)
    private static final String[] COMPONENT_ANNOTATIONS = {
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController",
            "org.springframework.context.annotation.Configuration"
            // 可以根據需要添加更多
    };

    private static final String[] INJECTION_ANNOTATIONS = {
            "org.springframework.beans.factory.annotation.Autowired",
            "javax.inject.Inject", // 如果使用 JSR-330
            "jakarta.inject.Inject" // 如果使用 Jakarta EE 9+
            // 可以根據需要添加更多
    };

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
        return "CathayBk規範檢查"; // 與之前的檢查放在同一個群組
    }

    @Override
    public boolean isEnabledByDefault() {
        return true; // 預設啟用此檢查
    }

    @NotNull
    @Override
    public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitField(@NotNull PsiField field) {
                super.visitField(field);

                // 1. 檢查欄位是否已經有 Javadoc
                if (field.getDocComment() != null) {
                    return; // 已有 Javadoc，無需處理
                }

                // 2. 檢查欄位是否可能是注入的
                if (isLikelyInjectedField(field)) {
                    // 3. 獲取欄位名稱和類型名稱，用於提示和修復
                    String fieldName = field.getName();
                    String fieldTypeName = field.getType().getPresentableText(); // 獲取簡潔的類型名稱

                    // 4. 註冊問題，並提供快速修復
                    PsiIdentifier nameIdentifier = field.getNameIdentifier();
                    if (nameIdentifier != null) {
                        holder.registerProblem(
                                nameIdentifier, // 將問題標記在欄位名稱上
                                "注入的欄位 '" + fieldName + "' 缺少 Javadoc 註解",
                                ProblemHighlightType.WEAK_WARNING, // 使用較弱的警告等級
                                new AddFieldJavadocFix(fieldTypeName) // 傳遞類型名稱給 QuickFix
                        );
                    }
                }
            }
        };
    }

    /**
     * 判斷一個欄位是否可能是依賴注入的。
     *
     * @param field 要檢查的欄位
     * @return 如果欄位可能是注入的，返回 true
     */
    private boolean isLikelyInjectedField(@NotNull PsiField field) {
        PsiClass containingClass = field.getContainingClass();
        if (containingClass == null || containingClass.isInterface() || containingClass.isAnnotationType()) {
            return false; // 不檢查介面或註解中的欄位
        }

        // 檢查標準注入註解 (@Autowired, @Inject)
        for (String annotationFqn : INJECTION_ANNOTATIONS) {
            if (field.hasAnnotation(annotationFqn)) {
                return true;
            }
        }

        // 檢查是否為 final 欄位，且其所在的類別是 Spring 元件
        // (這通常暗示著使用 Lombok 的 @RequiredArgsConstructor 進行建構子注入)
        if (field.hasModifierProperty(PsiModifier.FINAL)) {
            for (String componentAnnotation : COMPONENT_ANNOTATIONS) {
                if (containingClass.hasAnnotation(componentAnnotation)) {
                    // TODO: 更精確的檢查可以看是否有 @RequiredArgsConstructor
                    // 或檢查是否有建構子確實注入了這個 final 欄位，但目前這樣已涵蓋大部分 Lombok 情況
                    return true;
                }
            }
        }

        return false;
    }
}