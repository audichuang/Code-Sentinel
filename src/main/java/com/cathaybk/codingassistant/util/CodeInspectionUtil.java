package com.cathaybk.codingassistant.util; // 你的包路徑

import com.cathaybk.codingassistant.common.ProblemInfo;
import com.cathaybk.codingassistant.utils.ApiMsgIdUtil;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 提供程式碼規範核心檢查邏輯的靜態輔助方法。
 * 這些方法被 LocalInspectionTool 和 CheckinHandler 共用。
 * (已移除無意義方法名檢查邏輯)
 */
public class CodeInspectionUtil {

    // --- 常量區 ---

    // InjectedFieldJavadocInspection 的常量
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
            "jakarta.inject.Inject", // 如果使用 Jakarta EE 9+
            "org.springframework.beans.factory.annotation.Qualifier", // Qualifier 通常也表示注入
            "javax.annotation.Resource", // JSR-250
            "jakarta.annotation.Resource" // Jakarta EE
    };

    // MethodNamingInspection 的常量 (只保留駝峰命名檢查)
    private static final Pattern CAMEL_CASE_PATTERN = Pattern.compile("^[a-z][a-zA-Z0-9]*$");
    // private static final Pattern MEANINGLESS_NAME_PATTERN = Pattern // <-- 已移除
    // .compile(".*(?:asdasd|qwerty|test\\d+|temp\\d*|foo|bar|baz|dummy).*",
    // Pattern.CASE_INSENSITIVE);

    // --- 檢查方法 ---

    /**
     * 檢查 Controller API 方法的電文代號 Javadoc。
     *
     * @param method 要檢查的 PsiMethod。
     * @return 包含問題資訊的 List，如果沒有問題則為空 List。
     */
    @NotNull
    public static List<ProblemInfo> checkApiMethodDoc(@NotNull PsiMethod method) {
        List<ProblemInfo> problems = new ArrayList<>();

        if (!ApiMsgIdUtil.isApiMethod(method)) {
            return problems; // 不是 API 方法，不檢查
        }

        if (ApiMsgIdUtil.hasValidApiMsgId(method.getDocComment())) {
            return problems; // 已有有效 ID，不檢查
        }

        // --- 如果 API 方法缺少有效的電文代號 ---
        Map<String, String> serviceApiIds = ApiMsgIdUtil.findAndSuggestApiIdsFromUsedServices(method);

        PsiElement problemElement = method.getNameIdentifier() != null ? method.getNameIdentifier() : method;

        if (!serviceApiIds.isEmpty()) {
            Map.Entry<String, String> firstSuggestion = serviceApiIds.entrySet().iterator().next();
            problems.add(new ProblemInfo(
                    problemElement,
                    "API 方法缺少有效的電文代號註解。找到可能的來源：" + String.join(", ", serviceApiIds.keySet()),
                    ProblemHighlightType.WARNING,
                    firstSuggestion.getKey(), // 來源名稱
                    firstSuggestion.getValue() // 建議的 ID
            ));
        } else {
            problems.add(new ProblemInfo(
                    problemElement,
                    "API 方法缺少有效的電文代號註解 (格式: ID 描述)",
                    ProblemHighlightType.WARNING));
        }
        return problems;
    }

    /**
     * 檢查 Service 方法的電文代號 Javadoc。
     * 這是新增的功能，為 Service 介面和實現類中的方法檢查電文代號。
     *
     * @param method 要檢查的 Service 方法。
     * @return 包含問題資訊的 List，如果沒有問題則為空 List。
     */
    @NotNull
    public static List<ProblemInfo> checkServiceMethodDoc(@NotNull PsiMethod method) {
        List<ProblemInfo> problems = new ArrayList<>();

        // 檢查是否為 Service 類別中的方法
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null || !ApiMsgIdUtil.isServiceClass(containingClass)) {
            return problems; // 不是 Service 類別中的方法，不檢查
        }

        // 排除一些不需要檢查的方法
        if (method.isConstructor() ||
                method.hasModifierProperty(PsiModifier.PRIVATE) ||
                method.hasModifierProperty(PsiModifier.STATIC) ||
                "toString".equals(method.getName()) ||
                "equals".equals(method.getName()) ||
                "hashCode".equals(method.getName()) ||
                "clone".equals(method.getName())) {
            return problems; // 跳過不需要檢查的方法
        }

        if (ApiMsgIdUtil.hasValidApiMsgId(method.getDocComment())) {
            return problems; // 已有有效 ID，不檢查
        }

        // --- 如果 Service 方法缺少有效的電文代號 ---
        PsiElement problemElement = method.getNameIdentifier() != null ? method.getNameIdentifier() : method;
        String serviceType = ApiMsgIdUtil.isServiceInterface(containingClass) ? "Service 介面" : "Service 實現類";

        problems.add(new ProblemInfo(
                problemElement,
                serviceType + "方法缺少有效的電文代號註解 (格式: ID 描述)",
                ProblemHighlightType.WARNING));

        return problems;
    }

    /**
     * 檢查 Service 類別的電文代號 Javadoc。
     *
     * @param aClass 要檢查的 PsiClass。
     * @return 包含問題資訊的 List，如果沒有問題則為空 List。
     */
    @NotNull
    public static List<ProblemInfo> checkServiceClassDoc(@NotNull PsiClass aClass) {
        List<ProblemInfo> problems = new ArrayList<>();

        if (!ApiMsgIdUtil.isServiceClass(aClass)) {
            return problems; // 不是 Service 類，不檢查
        }

        if (ApiMsgIdUtil.hasValidApiMsgId(aClass.getDocComment())) {
            return problems; // 已有有效 ID，不檢查
        }

        // --- 如果 Service 類缺少有效的電文代號 ---
        Map<String, String> sourceApiIds = ApiMsgIdUtil.findApiIdsForServiceClass(aClass);

        if (!sourceApiIds.isEmpty()) {
            PsiElement problemElement = aClass.getNameIdentifier();
            if (problemElement == null) {
                PsiKeyword classKeyword = PsiTreeUtil.getChildOfType(aClass, PsiKeyword.class);
                problemElement = (classKeyword != null && PsiKeyword.CLASS.equals(classKeyword.getText()))
                        ? classKeyword
                        : aClass;
            }
            if (problemElement == null)
                return problems; // 無法定位問題元素

            Map.Entry<String, String> entry = sourceApiIds.entrySet().iterator().next();
            String sourceName = entry.getKey();
            String apiId = entry.getValue();

            problems.add(new ProblemInfo(
                    problemElement,
                    "Service 類別缺少有效的電文代號註解。建議來源：" + sourceName,
                    ProblemHighlightType.WARNING,
                    sourceName, // 來源
                    apiId // 建議值
            ));
        } else {
            PsiElement problemElement = aClass.getNameIdentifier() != null ? aClass.getNameIdentifier() : aClass;
            problems.add(new ProblemInfo(
                    problemElement,
                    "Service 類別缺少有效的電文代號註解 (格式: ID 描述)",
                    ProblemHighlightType.WARNING));
        }
        return problems;
    }

    /**
     * 檢查注入的欄位是否缺少 Javadoc 註解。
     *
     * @param field 要檢查的 PsiField。
     * @return 包含問題資訊的 List，如果沒有問題則為空 List。
     */
    @NotNull
    public static List<ProblemInfo> checkInjectedFieldDoc(@NotNull PsiField field) {
        List<ProblemInfo> problems = new ArrayList<>();

        PsiDocComment docComment = field.getDocComment();
        if (docComment != null) {
            return problems; // 已有 Javadoc，無需處理
        }

        if (isLikelyInjectedField(field)) {
            String fieldName = field.getName();
            PsiIdentifier nameIdentifier = field.getNameIdentifier();
            if (nameIdentifier != null) {
                problems.add(new ProblemInfo(
                        nameIdentifier,
                        "注入的欄位 '" + fieldName + "' 缺少 Javadoc 註解",
                        ProblemHighlightType.WEAK_WARNING // 使用較弱的警告等級
                ));
            }
        }
        return problems;
    }

    /**
     * 檢查方法命名是否符合駝峰命名法。
     * (已移除無意義名稱檢查)
     *
     * @param method 要檢查的 PsiMethod。
     * @return 包含問題資訊的 List，如果沒有問題則為空 List。
     */
    @NotNull
    public static List<ProblemInfo> checkMethodNaming(@NotNull PsiMethod method) {
        List<ProblemInfo> problems = new ArrayList<>();

        if (method.isConstructor()) {
            return problems; // 跳過構造函數
        }

        String methodName = method.getName();
        PsiElement nameIdentifier = method.getNameIdentifier();
        if (nameIdentifier == null) {
            return problems; // 無法定位問題元素
        }

        // 檢查駝峰命名
        if (!CAMEL_CASE_PATTERN.matcher(methodName).matches()) {
            problems.add(new ProblemInfo(
                    nameIdentifier,
                    "方法名稱必須符合駝峰式命名法（小駝峰式）",
                    ProblemHighlightType.WARNING));
        }

        // -- 無意義名稱檢查邏輯已移除 --
        // if (MEANINGLESS_NAME_PATTERN.matcher(methodName).matches()) { ... }

        return problems;
    }

    // --- 內部輔助方法 ---

    /**
     * 判斷一個欄位是否可能是依賴注入的。
     * 支援：
     * - @Autowired, @Inject, @Resource, @Qualifier 注解
     * - Lombok @RequiredArgsConstructor 搭配 final 欄位
     * - Spring 元件中的 final 欄位
     *
     * @param field 要檢查的欄位
     * @return 如果欄位可能是注入的，返回 true
     */
    public static boolean isLikelyInjectedField(@NotNull PsiField field) {
        PsiClass containingClass = field.getContainingClass();
        if (containingClass == null || containingClass.isInterface() || containingClass.isAnnotationType()) {
            return false; // 不檢查介面或註解中的欄位
        }

        // 檢查標準注入註解 (@Autowired, @Inject, @Resource, @Qualifier)
        for (String annotationFqn : INJECTION_ANNOTATIONS) {
            if (field.hasAnnotation(annotationFqn)) {
                return true;
            }
        }
        // 檢查簡寫 (例如 import 後只寫 @Autowired)
        for (PsiAnnotation annotation : field.getAnnotations()) {
            String shortName = annotation.getQualifiedName(); // 可能就是簡寫
            if (shortName != null) {
                if ("Autowired".equals(shortName) || "Inject".equals(shortName) || "Resource".equals(shortName)
                        || "Qualifier".equals(shortName)) {
                    return true;
                }
                // 檢查更深層次的 FQN
                PsiJavaCodeReferenceElement ref = annotation.getNameReferenceElement();
                if (ref != null) {
                    String qualifiedName = ref.getQualifiedName();
                    for (String annotationFqn : INJECTION_ANNOTATIONS) {
                        if (annotationFqn.equals(qualifiedName)) {
                            return true;
                        }
                    }
                }
            }
        }

        // 檢查是否為 final 欄位，且其所在的類別是 Spring 元件
        // (這通常暗示著使用 Lombok 的 @RequiredArgsConstructor 進行建構子注入)
        boolean isFinal = field.hasModifierProperty(PsiModifier.FINAL);
        if (isFinal) {
            for (String componentAnnotation : COMPONENT_ANNOTATIONS) {
                if (containingClass.hasAnnotation(componentAnnotation)) {
                    return true;
                }
            }
            // 檢查 Lombok @RequiredArgsConstructor
            if (containingClass.hasAnnotation("lombok.RequiredArgsConstructor")) {
                return true;
            }
        }

        return false;
    }
}
