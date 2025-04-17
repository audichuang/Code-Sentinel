package com.cathaybk.codingassistant.inspection;

import com.cathaybk.codingassistant.fix.AddApiIdDocFix;
import com.cathaybk.codingassistant.fix.AddControllerApiIdFromServiceFix;
import com.cathaybk.codingassistant.fix.AddServiceApiIdQuickFix;
import com.cathaybk.codingassistant.utils.ApiMsgIdUtil;
import com.intellij.codeInspection.*;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * API 電文代號檢查器。
 * <p>
 * 此檢查器用於驗證專案中的 Java 程式碼是否符合電文代號 (ApiMsgId) 的 Javadoc 註解規範。
 * 主要檢查兩種類型的元素：
 * </p>
 * <ol>
 *     <li><b>Controller API 方法：</b> 確保被 Spring Web Mapping 註解 (如 {@code @GetMapping}) 標記的方法，
 *         其 Javadoc 包含符合 {@link ApiMsgIdUtil#API_ID_PATTERN} 格式的電文代號。
 *         如果缺少，會嘗試從該方法內部使用的 Service 類別中尋找關聯的電文代號作為快速修復建議。</li>
 *     <li><b>Service 類別 (接口與實現)：</b> 確保被識別為 Service 的類別 (基於命名慣例、包結構或 {@code @Service} 註解)，
 *         其 Javadoc 包含有效的電文代號。如果缺少，會嘗試從使用該 Service 的 Controller API 方法、
 *         或其關聯的接口/實現類中尋找電文代號作為快速修復建議。</li>
 * </ol>
 * <p>
 * 電文代號格式範例： {@code API-ORDER-CREATE 建立訂單}
 * </p>
 */
public class ApiMsgIdInspection extends AbstractBaseJavaLocalInspectionTool {

    /**
     * @return 檢查器的短名稱，用於內部標識。
     */
    @NotNull
    @Override
    public String getShortName() {
        return "ApiMsgIdInspection";
    }

    /**
     * @return 顯示在 IntelliJ IDEA 設定和問題描述中的檢查器名稱。
     */
    @NotNull
    @Override
    public String getDisplayName() {
        return "API 電文代號檢查";
    }

    /**
     * @return 此檢查器所屬的分組名稱，顯示在 IntelliJ IDEA 設定中。
     */
    @NotNull
    @Override
    public String getGroupDisplayName() {
        return "CathayBk規範檢查";
    }

    /**
     * 建立並返回一個 {@link PsiElementVisitor} 實例，用於訪問和檢查 Java 程式碼元素。
     * <p>
     * 此 Visitor 會覆寫 {@code visitMethod} 和 {@code visitClass} 方法，
     * 分別處理 Controller API 方法和 Service 類別的檢查邏輯。
     * </p>
     *
     * @param holder     用於註冊檢查到的問題 (Problems) 的容器。
     * @param isOnTheFly 指示檢查是否在用戶輸入時即時運行。
     * @return 一個配置好的 {@link JavaElementVisitor}。
     */
    @NotNull
    @Override
    public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {

            /**
             * 訪問 Java 方法。主要用於檢查 Controller API 方法。
             *
             * @param method 當前訪問的 {@link PsiMethod}。
             */
            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                // 1. 判斷是否為我們關心的 API 方法
                if (!ApiMsgIdUtil.isApiMethod(method)) {
                    return; // 不是 API 方法，直接跳過
                }

                // 2. 檢查方法本身是否已有有效的電文代號 Javadoc
                if (ApiMsgIdUtil.hasValidApiMsgId(method.getDocComment())) {
                    return; // 已有有效 ID，無需處理
                }

                // --- 如果 API 方法缺少有效的電文代號 ---

                // 3. 嘗試查找此方法內部實際使用的 Service 及其關聯的電文代號
                Map<String, String> serviceApiIds = findAndSuggestApiIdsFromUsedServices(method);

                // --- 確定標記問題的位置 ---
                // 優先使用方法名稱的標識符 (PsiIdentifier)
                // 如果找不到標識符 (例如某些特殊構造的方法)，則將問題標記在方法元素本身
                PsiElement problemElement = method.getNameIdentifier();
                if (problemElement == null) {
                    problemElement = method; // <<--- 修改點：直接使用 PsiMethod
                }
                // 確保 problemElement 不為 null (理論上 PsiMethod 不會是 null)
                if (problemElement == null) return;


                // 4. 根據是否找到來源 ID，註冊不同的問題和快速修復
                if (!serviceApiIds.isEmpty()) {
                    // 4.1 找到了至少一個來自 Service 的建議 ID
                    LocalQuickFix[] fixes = serviceApiIds.entrySet().stream()
                            .map(entry -> new AddControllerApiIdFromServiceFix(entry.getKey(), entry.getValue()))
                            .toArray(LocalQuickFix[]::new);

                    holder.registerProblem(
                            problemElement, // <<--- 修改點：使用 problemElement
                            "API 方法缺少有效的電文代號註解。找到可能的來源：" + String.join(", ", serviceApiIds.keySet()),
                            ProblemHighlightType.WARNING,
                            fixes);
                } else {
                    // 4.2 未找到任何來自 Service 的建議 ID，提供預設的添加模板修復
                    holder.registerProblem(
                            problemElement, // <<--- 修改點：使用 problemElement
                            "API 方法缺少有效的電文代號註解 (格式: ID 描述)",
                            ProblemHighlightType.WARNING,
                            new AddApiIdDocFix());
                }
            }

            /**
             * 訪問 Java 類別。主要用於檢查 Service 接口和實現類。
             *
             * @param aClass 當前訪問的 {@link PsiClass}。
             */
            @Override
            public void visitClass(@NotNull PsiClass aClass) {
                // 1. 判斷是否為我們關心的 Service 類別
                if (!ApiMsgIdUtil.isServiceClass(aClass)) {
                    return; // 不是 Service 類，跳過
                }

                // 2. 檢查類別本身是否已有有效的電文代號 Javadoc
                if (ApiMsgIdUtil.hasValidApiMsgId(aClass.getDocComment())) {
                    return; // 已有有效 ID，無需處理
                }

                // --- 如果 Service 類缺少有效的電文代號 ---

                // 3. 查找與此 Service 關聯的最相關的電文代號來源
                Map<String, String> sourceApiIds = ApiMsgIdUtil.findApiIdsForServiceClass(aClass);

                // 4. 如果找到了來源 ID，則註冊問題和快速修復
                if (!sourceApiIds.isEmpty()) {
                    // --- 確定標記問題的位置 ---
                    PsiElement problemElement = aClass.getNameIdentifier();
                    if (problemElement == null) {
                        // 對於匿名類或特殊類，可能沒有名稱標識符
                        // 嘗試標記在 'class' 關鍵字或類本身
                        PsiKeyword classKeyword = PsiTreeUtil.getChildOfType(aClass, PsiKeyword.class);
                        problemElement = (classKeyword != null && PsiKeyword.CLASS.equals(classKeyword.getText())) ? classKeyword : aClass; // <<--- 修改點
                    }
                    // 確保 problemElement 不為 null
                    if (problemElement == null) return;

                    // 目前 findApiIdsForServiceClass 設計為只返回一個最相關的來源
                    Map.Entry<String, String> entry = sourceApiIds.entrySet().iterator().next();
                    String sourceName = entry.getKey();
                    String apiId = entry.getValue();

                    holder.registerProblem(
                            problemElement, // <<--- 修改點：使用 problemElement
                            "Service 類別缺少有效的電文代號註解。建議來源：" + sourceName,
                            ProblemHighlightType.WARNING,
                            new AddServiceApiIdQuickFix(sourceName, apiId));
                }
            }
        };
    }


    /**
     * 查找給定 Controller 方法內部實際使用的 Service 類別，並獲取這些 Service 關聯的電文代號。
     * <p>
     * 此方法通過 PSI 分析方法體，識別出對 Service 類別實例的方法調用，
     * 然後為每個識別出的 Service 類別調用 {@link ApiMsgIdUtil#findApiIdsForServiceClass}
     * 來查找其最相關的電文代號。
     * </p>
     *
     * @param controllerMethod 要分析的 Controller {@link PsiMethod}。
     * @return 一個 Map，Key 是找到的電文代號的來源名稱 (例如，"UserServiceImpl" 或 "anotherControllerMethod")，
     *         Value 是對應的電文代號字串。如果方法體內沒有使用 Service，或者使用的 Service 沒有關聯的電文代號，則返回空 Map。
     */
    @NotNull
    private Map<String, String> findAndSuggestApiIdsFromUsedServices(@NotNull PsiMethod controllerMethod) {
        Map<String, String> allFoundApiIds = new HashMap<>();
        PsiCodeBlock body = controllerMethod.getBody();
        if (body == null) {
            return allFoundApiIds; // 沒有方法體
        }

        // 用於記錄已經處理過的 Service 類，避免重複查找
        Set<PsiClass> processedServiceClasses = new HashSet<>();

        // 遍歷方法體內的元素
        body.accept(new JavaRecursiveElementWalkingVisitor() {
            /**
             * 訪問方法調用表達式，這是 Service 最常見的被使用方式。
             */
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression); // 繼續遍歷子節點

                // 獲取調用方法的對象 (qualifier)
                PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();
                if (qualifier == null) {
                    return; // 靜態調用或隱式 this
                }

                // 解析 qualifier 的類型
                PsiType qualifierType = qualifier.getType();
                if (qualifierType != null) {
                    PsiClass qualifierClass = PsiUtil.resolveClassInType(qualifierType);
                    // 檢查解析出的類型是否是一個 Service 類，並且尚未處理過
                    if (qualifierClass != null
                            && ApiMsgIdUtil.isServiceClass(qualifierClass)
                            && processedServiceClasses.add(qualifierClass)) { // add() 成功表示是第一次遇到

                        // 為這個找到的 Service 類查找關聯的電文代號
                        Map<String, String> apiIds = ApiMsgIdUtil.findApiIdsForServiceClass(qualifierClass);
                        // 將找到的結果合併到總結果中
                        allFoundApiIds.putAll(apiIds);
                    }
                } else if (qualifier instanceof PsiReferenceExpression) {
                    // getType() 為 null 時的後備檢查 (較少見)
                    PsiElement resolved = ((PsiReferenceExpression) qualifier).resolve();
                    if (resolved instanceof PsiVariable) {
                        PsiType variableType = ((PsiVariable) resolved).getType();
                        PsiClass variableClass = PsiUtil.resolveClassInType(variableType);
                        if (variableClass != null
                                && ApiMsgIdUtil.isServiceClass(variableClass)
                                && processedServiceClasses.add(variableClass)) {

                            Map<String, String> apiIds = ApiMsgIdUtil.findApiIdsForServiceClass(variableClass);
                            allFoundApiIds.putAll(apiIds);
                        }
                    }
                }
            }
        });

        return allFoundApiIds;
    }
}