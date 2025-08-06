package com.cathaybk.codingassistant.utils;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.Query;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
// import java.util.stream.Collectors; // 目前未使用 Collectors

/**
 * API 電文代號相關的工具類 (已優化，移除不可靠的文本搜索)
 *
 * <p>本工具類提供一系列靜態方法，用於處理與 API 電文代號相關的各種 PSI 分析與操作，主要包括：</p>
 * <ul>
 *     <li>識別 Controller、Service、API 方法等特定類型的程式碼元素。</li>
 *     <li>驗證 Javadoc 中是否存在符合規範格式的電文代號。</li>
 *     <li>提取或生成電文代號字串。</li>
 *     <li>基於 PSI 分析，查找 Service 類與使用它的 Controller API 方法之間的關聯，並提取 Controller 的電文代號。</li>
 *     <li>為 Service 類查找最相關的電文代號來源（自身、實現類、接口或關聯的 Controller）。</li>
 * </ul>
 * <p>所有查找和關聯分析均基於 IntelliJ Platform 的 PSI API，避免了不可靠的文本搜索。</p>
 */
public class ApiMsgIdUtil {

    /**
     * 電文代號的正規表示式模式。
     * <p>用於匹配 Javadoc 中符合 "ID 描述" 格式的字串。</p>
     * <p>格式要求：</p>
     * <ul>
     *     <li>ID 部分由至少一個字母數字區段構成。</li>
     *     <li>後續可以跟隨一個或多個由連字號'-'連接的字母數字區段。</li>
     *     <li>ID 部分之後必須跟隨至少一個空白字符。</li>
     *     <li>空白字符之後必須跟隨至少一個任意字符作為描述文字。</li>
     * </ul>
     * <p>範例：</p>
     * <ul>
     *     <li>`API-LOGIN 使用者登入`</li>
     *     <li>`SYS-AUTH-VALIDATE 權限驗證`</li>
     * </ul>
     * <p>捕獲組 1 包含完整的 "ID 描述" 字串。</p>
     */
    public static final Pattern API_ID_PATTERN = Pattern.compile("([A-Za-z0-9]+(?:-[A-Za-z0-9]+)+\\s+.+)");

    /**
     * 判斷一個方法是否為 Spring Web API 方法。
     * <p>檢查方法是否帶有任何以 "Mapping" 結尾的標準 Spring Web 註解
     * (例如 `@RequestMapping`, `@GetMapping` 等)。</p>
     *
     * @param method 要檢查的 {@link PsiMethod} 物件。
     * @return 如果方法被識別為 API 方法，則返回 {@code true}；否則返回 {@code false}。
     */
    public static boolean isApiMethod(@Nullable PsiMethod method) {
        if (method == null || method.getModifierList() == null) {
            return false;
        }
        return Arrays.stream(method.getModifierList().getAnnotations())
                .map(PsiAnnotation::getQualifiedName)
                .filter(StringUtils::isNotEmpty)
                .anyMatch(name -> StringUtils.endsWith(name, "Mapping"));
    }

    /**
     * 判斷一個類別是否為 Controller 類別。
     * <p>判斷依據按以下優先順序（滿足其一即可）：</p>
     * <ol>
     *     <li>是否帶有標準 Spring 註解 {@code @Controller} 或 {@code @RestController} (最可靠)。</li>
     *     <li>類別名稱是否以 "Controller" 結尾 (忽略大小寫，較可靠的命名慣例)。</li>
     *     <li>類別所在的套件名稱是否包含精確的 "controller" 區段 (較不可靠的包結構慣例)。</li>
     * </ol>
     * <p>注意：此方法不檢查接口、註解類型或枚舉。</p>
     *
     * @param psiClass 要檢查的 {@link PsiClass} 物件。
     * @return 如果類別被識別為 Controller，則返回 {@code true}；否則返回 {@code false}。
     */
    public static boolean isControllerClass(@Nullable PsiClass psiClass) {
        // 1. 基本類型過濾
        if (psiClass == null || psiClass.isInterface() || psiClass.isAnnotationType() || psiClass.isEnum()) {
            return false;
        }

        // 2. 檢查標準註解
        if (psiClass.hasAnnotation("org.springframework.stereotype.Controller") ||
                psiClass.hasAnnotation("org.springframework.web.bind.annotation.RestController")) {
            return true;
        }
        // TODO: 可以考慮檢查元註解，以支持自訂 Controller 註解

        // 3. 檢查類名後綴
        String className = psiClass.getName();
        if (StringUtils.isNotEmpty(className) && StringUtils.endsWithIgnoreCase(className, "Controller")) {
            return true;
        }

        // 4. 檢查包名區段 (較低優先級，可能不準確)
        String qualifiedName = psiClass.getQualifiedName();
        if (StringUtils.isNotEmpty(qualifiedName)) {
            int lastDotIndex = qualifiedName.lastIndexOf('.');
            if (lastDotIndex != -1) {
                String packageName = qualifiedName.substring(0, lastDotIndex);
                // 使用 split 檢查包名段是否精確匹配 "controller" (忽略大小寫)
                if (Arrays.stream(packageName.split("\\.")).anyMatch("controller"::equalsIgnoreCase)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判斷一個類別是否為 Service 介面。
     * <p>判斷依據按以下優先順序（滿足其一即可）：</p>
     * <ol>
     *     <li>是否為介面。</li>
     *     <li>類別名稱是否以 "Service" 或 "Svc" 結尾 (忽略大小寫，較可靠的命名慣例)。</li>
     *     <li>類別所在的套件名稱是否包含精確的 "service" 或 "svc" 區段 (較可靠的包結構慣例)。</li>
     * </ol>
     *
     * @param psiClass 要檢查的 {@link PsiClass} 物件。
     * @return 如果類別被識別為 Service 介面，則返回 {@code true}；否則返回 {@code false}。
     */
    public static boolean isServiceInterface(@Nullable PsiClass psiClass) {
        // 1. 必須是介面
        if (psiClass == null || !psiClass.isInterface()) {
            return false;
        }

        // 2. 檢查類名後綴
        String className = psiClass.getName();
        if (StringUtils.isNotEmpty(className)) {
            String upperClassName = className.toUpperCase();
            if (upperClassName.endsWith("SERVICE") || upperClassName.endsWith("SVC")) {
                return true;
            }
        }

        // 3. 檢查包名區段
        String qualifiedName = psiClass.getQualifiedName();
        if (StringUtils.isNotEmpty(qualifiedName)) {
            int lastDotIndex = qualifiedName.lastIndexOf('.');
            if (lastDotIndex != -1) {
                String packageName = qualifiedName.substring(0, lastDotIndex);
                // 檢查是否有任何一個包名區段等於 "service" 或 "svc" (忽略大小寫)
                if (Arrays.stream(packageName.split("\\."))
                        .anyMatch(segment -> "service".equalsIgnoreCase(segment) || "svc".equalsIgnoreCase(segment))) {
                    return true;
                }
            }
        }
        return false;
    }


    /**
     * 判斷一個類別是否為 Service 實現類別。
     * <p>判斷依據按以下優先順序（滿足其一即可）：</p>
     * <ol>
     *     <li>是否帶有標準 Spring 註解 {@code @Service} (最可靠)。</li>
     *     <li>類別名稱是否以 "Impl" 結尾 (忽略大小寫)，並且它直接實現了至少一個被 {@link #isServiceInterface} 識別的介面。</li>
     * </ol>
     * <p>注意：此方法不檢查接口、註解類型或枚舉。</p>
     *
     * @param psiClass 要檢查的 {@link PsiClass} 物件。
     * @return 如果類別被識別為 Service 實現類，則返回 {@code true}；否則返回 {@code false}。
     */
    public static boolean isServiceImpl(@Nullable PsiClass psiClass) {
        // 1. 基本類型過濾
        if (psiClass == null || psiClass.isInterface() || psiClass.isAnnotationType() || psiClass.isEnum()) {
            return false;
        }

        // 2. 檢查 @Service 註解
        if (psiClass.hasAnnotation("org.springframework.stereotype.Service")) {
            // TODO: 可以考慮檢查元註解 @Service
            return true;
        }

        // 3. 檢查命名慣例 + 實現的接口
        String className = psiClass.getName();
        if (StringUtils.isNotEmpty(className) && StringUtils.endsWithIgnoreCase(className, "Impl")) {
            // getInterfaces() 只返回直接實現的接口
            return Arrays.stream(psiClass.getInterfaces())
                    .anyMatch(ApiMsgIdUtil::isServiceInterface);
        }

        return false;
    }

    /**
     * 判斷一個類別是否與 Service 相關（是 Service 接口或 Service 實現類）。
     *
     * @param psiClass 要檢查的 {@link PsiClass} 物件。
     * @return 如果是 Service 接口或實現類，則返回 {@code true}；否則返回 {@code false}。
     */
    public static boolean isServiceClass(@Nullable PsiClass psiClass) {
        // 稍微優化，避免重複計算 isServiceInterface
        if (psiClass == null) return false;
        // isServiceInterface 內部已經判斷了是否為接口，isServiceImpl 判斷了不是接口
        return isServiceInterface(psiClass) || isServiceImpl(psiClass);
    }

    /**
     * 檢查指定的 Javadoc 註解中是否包含符合 {@link #API_ID_PATTERN} 格式的電文代號。
     * <p>此方法會檢查 Javadoc 的完整文本內容。</p>
     *
     * @param docComment 要檢查的 {@link PsiDocComment} 物件，可以為 {@code null}。
     * @return 如果 Javadoc 不為 {@code null} 且包含有效的電文代號格式，則返回 {@code true}；否則返回 {@code false}。
     */
    public static boolean hasValidApiMsgId(@Nullable PsiDocComment docComment) {
        if (docComment == null) {
            return false;
        }
        String docText = docComment.getText();
        return API_ID_PATTERN.matcher(docText).find();
    }

    /**
     * 從給定的 Javadoc 註解中提取第一個匹配 {@link #API_ID_PATTERN} 的電文代號字串。
     * <p>提取的結果包含 ID 和後續的描述文字，並去除首尾空白。</p>
     *
     * @param docComment 要從中提取的 {@link PsiDocComment} 物件，可以為 {@code null}。
     * @return 如果找到匹配的電文代號字串，則返回該字串；否則返回 {@code null}。
     */
    @Nullable
    public static String extractApiMsgId(@Nullable PsiDocComment docComment) {
        if (docComment == null) {
            return null;
        }
        String docText = docComment.getText();
        Matcher matcher = API_ID_PATTERN.matcher(docText);
        if (matcher.find()) {
            // group(1) 包含 ID 和描述
            return matcher.group(1).trim();
        }
        return null;
    }


    /**
     * 為指定的 Controller API 方法生成一個建議的電文代號模板字串。
     * <p>模板格式為 "API-<類名簡寫>_<方法名大寫>"。</p>
     * <p>類名簡寫會移除 "Controller" 字樣（不區分大小寫）並轉為大寫。</p>
     * <p>例如：`UserController` 的 `login` 方法會生成 "API-USER_LOGIN"。</p>
     *
     * @param method 要為其生成模板的 Controller {@link PsiMethod} 物件。
     * @return 生成的電文代號模板字串。
     */
    @NotNull
    public static String generateApiMsgId(@NotNull PsiMethod method) {
        String classNameAbbr = "";
        String suffix = "";
        PsiClass containingClass = method.getContainingClass();
        if (containingClass != null) {
            String className = containingClass.getName();
            if (className != null) {
                // 簡化判斷邏輯
                if (containingClass.isInterface() && isServiceInterface(containingClass)) {
                    // Service 介面，移除 "Service"，加上 "Svc" 後綴
                    classNameAbbr = className.replaceAll("(?i)Service", "").toUpperCase();
                    suffix = "Svc";
                } else if (!containingClass.isInterface() && 
                           (containingClass.hasAnnotation("org.springframework.stereotype.Service") ||
                            containingClass.hasAnnotation("javax.inject.Named") ||
                            containingClass.hasAnnotation("jakarta.inject.Named"))) {
                    // 實作類且有 Service 相關註解，加上 "SvcImpl" 後綴
                    classNameAbbr = className.replaceAll("(?i)(Service)?Impl", "").toUpperCase();
                    suffix = "SvcImpl";
                } else if (isControllerClass(containingClass)) {
                    // Controller 類別，移除 "Controller"
                    classNameAbbr = className.replaceAll("(?i)Controller", "").toUpperCase();
                } else {
                    // 其他類別，保持原類名
                    classNameAbbr = className.toUpperCase();
                }
            }
        }

        String methodNameUpper = method.getName().toUpperCase();
        // 處理類名簡寫為空的情況
        String baseId = "API-" + (StringUtils.isEmpty(classNameAbbr) ? "UNKNOWN" : classNameAbbr) + "_" + methodNameUpper;
        
        // 加上 Service 後綴（如果有的話）
        String result = StringUtils.isEmpty(suffix) ? baseId : baseId + "_" + suffix;
        
        // 輸出調試訊息到 IntelliJ 的 Event Log
        if (containingClass != null) {
            String debugMsg = "[ApiMsgIdUtil] Class: " + containingClass.getName() + 
                               ", isInterface: " + containingClass.isInterface() + 
                               ", hasServiceAnnotation: " + containingClass.hasAnnotation("org.springframework.stereotype.Service") +
                               ", suffix: " + suffix +
                               ", result: " + result;
            System.out.println(debugMsg);
            // 同時寫入到錯誤日誌，這樣更容易看到
            System.err.println(debugMsg);
        }
        
        return result;
    }

    /**
     * 為 Service 類別的方法生成專用的 API 電文代號。
     * 根據 Service 類別的類型自動加上相應的後綴：
     * - Service 介面：加上 "Svc" 後綴
     * - Service 實現類：加上 "SvcImpl" 後綴
     * 
     * @param method Service 類別中的方法
     * @return 生成的 API 電文代號，格式為 "API-{ClassName}_{MethodName}_{Suffix}"
     */
    @NotNull
    public static String generateServiceApiMsgId(@NotNull PsiMethod method) {
        return generateApiMsgId(method); // 現在 generateApiMsgId 已經支援 Service 後綴
    }
    
    /**
     * 查找所有使用了指定 Service 類別或其接口的 Controller API 方法，並提取這些 Controller 方法的電文代號。
     * <p>此方法完全基於 PSI 分析，不再依賴不可靠的文本搜索。</p>
     * <p>搜索過程：</p>
     * <ol>
     *     <li>使用 {@link ReferencesSearch} 在 {@code serviceOrInterfaceClass} 的使用範圍 (use scope) 內查找所有直接引用。</li>
     *     <li>透過 {@link #checkReferences} 檢查每個引用，判斷其上下文。</li>
     *     <li>如果引用最終指向了一個 Controller 類別中的 API 方法，並且該方法體內確實有對 {@code serviceOrInterfaceClass} 兼容類型實例的使用 (透過 {@link #checkMethodBodyForServiceUsage} 驗證)，則嘗試從該 Controller 方法的 Javadoc 中提取電文代號。</li>
     * </ol>
     *
     * @param serviceOrInterfaceClass 要查找其使用的 Service 類別或接口 ({@link PsiClass})。
     * @return 一個 Map，其中 Key 是找到的 Controller 方法的名稱 (String)，Value 是從該方法 Javadoc 中提取的電文代號字串 (String)。如果找不到任何符合條件的 Controller 方法或它們沒有電文代號，則返回空的 Map。
     */
    @NotNull
    public static Map<String, String> findControllerApiIds(@NotNull PsiClass serviceOrInterfaceClass) {
        Map<String, String> result = new HashMap<>();
        Project project = serviceOrInterfaceClass.getProject();

        try {
            // 嘗試使用 serviceOrInterfaceClass 的 use scope 作為搜索範圍
            SearchScope scope = serviceOrInterfaceClass.getUseScope();

            // 查找 serviceOrInterfaceClass 的所有引用
            Collection<PsiReference> references = ReferencesSearch.search(serviceOrInterfaceClass, scope).findAll();

            // 檢查這些引用是否關聯到符合條件的 Controller 方法
            // 第二個參數傳遞 serviceOrInterfaceClass，用於後續的類型使用檢查
            checkReferences(references, serviceOrInterfaceClass, result);

        } catch (Exception e) {
            // 實際應用中應使用日誌框架
            System.err.println("查找 Controller API ID 時出錯，類: " + serviceOrInterfaceClass.getName() + ", 錯誤: " + e.getMessage());
        }
        return result;
    }

    /**
     * 輔助方法：檢查給定的類別引用集合，找出其中與 Controller API 方法相關聯的引用。
     * <p>對每個引用，判斷其上下文：</p>
     * <ol>
     *     <li>如果引用直接位於某方法內，檢查該方法。</li>
     *     <li>如果引用指向一個變數，則查找該變數的所有使用點，並檢查使用點所在的方法。</li>
     * </ol>
     * <p>使用 {@code checkedMethods} 避免重複處理同一個方法。</p>
     *
     * @param references  對 {@code targetClass} 的引用集合。
     * @param targetClass 被引用的目標類別 (接口或實現類)，傳遞給 {@link #checkControllerMethod} 用於 usage 檢查。
     * @param result      用於存放結果的 Map (方法名 -> 電文代號)。
     */
    private static void checkReferences(@NotNull Collection<PsiReference> references, @NotNull PsiClass targetClass,
                                        @NotNull Map<String, String> result) {
        Set<PsiMethod> checkedMethods = new HashSet<>();

        for (PsiReference reference : references) {
            PsiElement element = reference.getElement();
            if (element == null) continue;

            // 情況 A: 引用直接出現在方法體內
            PsiMethod containingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
            if (containingMethod != null && checkedMethods.add(containingMethod)) {
                // 傳遞 targetClass 給 checkControllerMethod 進行使用檢查
                checkControllerMethod(containingMethod, targetClass, result);
            }

            // 情況 B: 引用指向一個變數 (欄位、參數、局部變數)
            // 注意：這裡的 element 可能是變數類型，也可能是變數名
            // 我們需要找到包含它的 PsiVariable
            PsiVariable variable = PsiTreeUtil.getParentOfType(element, PsiVariable.class, false);
            // 有時引用可能直接指向變數名標識符，其父級才是 PsiVariable
            if (variable == null && element instanceof PsiIdentifier && element.getParent() instanceof PsiVariable) {
                variable = (PsiVariable) element.getParent();
            }

            if (variable != null) {
                // 查找這個 *變數* 在哪裡被 *使用* 了
                Collection<PsiReference> variableReferences = ReferencesSearch.search(variable, variable.getUseScope()).findAll();
                for (PsiReference varRef : variableReferences) {
                    PsiElement usageElement = varRef.getElement();
                    if (usageElement == null) continue;
                    // 查找變數使用點所在的方法
                    PsiMethod usageMethod = PsiTreeUtil.getParentOfType(usageElement, PsiMethod.class, false);
                    if (usageMethod != null && checkedMethods.add(usageMethod)) {
                        // 檢查這個使用了變數的方法，判斷它是否使用了 targetClass 兼容類型的實例
                        checkControllerMethod(usageMethod, targetClass, result);
                    }
                }
            }
        }
    }

    /**
     * 輔助方法：檢查給定的方法是否滿足 Controller API 方法的條件、是否實際使用了目標 Service 類型，
     * 並提取其電文代號。
     *
     * @param method                  要檢查的 {@link PsiMethod}。
     * @param serviceOrInterfaceClass 目標 Service 類型 (接口或實現類)，用於 {@link #checkMethodBodyForServiceUsage}。
     * @param result                  用於存放結果的 Map (方法名 -> 電文代號)。
     */
    private static void checkControllerMethod(@NotNull PsiMethod method, @NotNull PsiClass serviceOrInterfaceClass,
                                              @NotNull Map<String, String> result) {
        // 1. 驗證方法是否為 Controller 中的 API 方法
        if (!isApiMethod(method) || !isControllerClass(method.getContainingClass())) {
            return;
        }

        // 2. 驗證方法體內是否實際使用了 serviceOrInterfaceClass 的實例
        //    即使 Controller 注入的是接口，如果實際運行時使用的是實現類，這個檢查也能通過
        if (!checkMethodBodyForServiceUsage(method, serviceOrInterfaceClass)) {
            return;
        }

        // 3. 如果滿足以上條件，提取電文代號
        String apiId = extractApiMsgId(method.getDocComment());
        if (apiId != null) {
            result.putIfAbsent(method.getName(), apiId);
        }
    }

    /**
     * 核心檢查邏輯：使用 PSI Visitor 遍歷方法體，判斷是否實際使用了指定 {@code targetClass} 兼容類型的實例。
     * <p>主要檢查方法體內是否存在對 {@code targetClass} 或其子類/實現類實例的方法調用。</p>
     *
     * @param method      要檢查的 {@link PsiMethod}。
     * @param targetClass 要檢查是否被使用的目標類 (接口或實現類) {@link PsiClass}。
     * @return 如果方法體內找到對 {@code targetClass} 兼容類型實例的有效使用（通常是方法調用），則返回 {@code true}；否則返回 {@code false}。
     */
    private static boolean checkMethodBodyForServiceUsage(@NotNull PsiMethod method, @NotNull PsiClass targetClass) {
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            return false;
        }
        final AtomicBoolean usageFound = new AtomicBoolean(false);

        body.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                if (usageFound.get()) {
                    stopWalking();
                    return;
                }
                super.visitMethodCallExpression(expression);
                PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();
                checkQualifier(qualifier);
            }

            private void checkQualifier(@Nullable PsiExpression qualifier) {
                if (usageFound.get() || qualifier == null) {
                    return;
                }
                PsiType qualifierType = qualifier.getType();
                if (qualifierType != null) {
                    PsiClass qualifierClass = PsiUtil.resolveClassInType(qualifierType);
                    // 檢查 qualifier 的類型是否是 targetClass 或其子類/實現類
                    if (qualifierClass != null && InheritanceUtil.isInheritorOrSelf(qualifierClass, targetClass, true)) {
                        usageFound.set(true);
                        stopWalking();
                    }
                } else if (qualifier instanceof PsiReferenceExpression) {
                    PsiElement resolved = ((PsiReferenceExpression) qualifier).resolve();
                    if (resolved instanceof PsiVariable) {
                        PsiType variableType = ((PsiVariable) resolved).getType();
                        PsiClass variableClass = PsiUtil.resolveClassInType(variableType);
                        if (variableClass != null && InheritanceUtil.isInheritorOrSelf(variableClass, targetClass, true)) {
                            usageFound.set(true);
                            stopWalking();
                        }
                    }
                }
            }
        });
        return usageFound.get();
    }


    /**
     * 為給定的 Service 類別（接口或實現類）查找最相關的一個電文代號來源。
     * <p>查找遵循以下固定順序，找到第一個有效的電文代號即返回：</p>
     * <ol>
     *     <li>檢查 {@code serviceClass} 自身的 Javadoc。</li>
     *     <li>如果是接口 (Interface)，則查找其所有**直接或間接實現類**中，第一個帶有有效電文代號的 Service 實現類 ({@link #isServiceImpl}) 的 Javadoc。</li>
     *     <li>如果是實現類 (Impl)，則查找其所有**直接實現的接口**中，第一個帶有有效電文代號的 Service 接口 ({@link #isServiceInterface}) 的 Javadoc。</li>
     *     <li>**針對 Service 實現類：** 如果前序步驟未找到 ID，則遍歷其實現的 Service 接口，對**每個接口**呼叫 {@link #findControllerApiIds(PsiClass)} 查找使用該**接口**的 Controller，找到第一個即返回。</li>
     *     <li>如果 {@code serviceClass} 是接口且前序步驟未找到，**或者** {@code serviceClass} 是實現類且以上所有步驟都未找到，則最後嘗試基於 {@code serviceClass} 本身查找使用它的 Controller。</li>
     * </ol>
     * <p><b>注意:</b> 此方法設計為只返回單個最相關的來源。</p>
     *
     * @param serviceClass 要查找電文代號來源的 Service {@link PsiClass}。
     * @return 一個 Map，其中 Key 是找到的電文代號的來源名稱 (類名或方法名)，Value 是電文代號字串。如果按上述順序未找到任何來源，則返回空的 Map。返回的 Map 最多只包含一個條目。
     */
    @NotNull
    public static Map<String, String> findApiIdsForServiceClass(@NotNull PsiClass serviceClass) {
        Map<String, String> result = new HashMap<>();

        // 1. 檢查自身 Javadoc
        String selfApiId = extractApiMsgId(serviceClass.getDocComment());
        if (selfApiId != null) {
            result.put(serviceClass.getName(), selfApiId);
            return result;
        }

        // 2. 如果是接口 -> 查找實現類的 Javadoc (找第一個)
        if (isServiceInterface(serviceClass)) {
            SearchScope scope = serviceClass.getResolveScope();
            // 查找直接或間接繼承者 (true)
            Query<PsiClass> implementations = ClassInheritorsSearch.search(serviceClass, scope, true);
            for (PsiClass implClass : implementations) {
                if (isServiceImpl(implClass)) {
                    String implApiId = extractApiMsgId(implClass.getDocComment());
                    if (implApiId != null) {
                        result.put(implClass.getName(), implApiId);
                        return result; // 返回第一個找到的實現類 ID
                    }
                }
            }
        }
        // 3. 如果是實現類 -> 查找接口的 Javadoc (找第一個 Service 接口)
        else if (isServiceImpl(serviceClass)) {
            boolean foundIdInInterfaceDoc = false;
            for (PsiClassType interfaceType : serviceClass.getImplementsListTypes()) {
                PsiClass interfaceClass = interfaceType.resolve();
                if (isServiceInterface(interfaceClass)) {
                    String interfaceApiId = extractApiMsgId(interfaceClass.getDocComment());
                    if (interfaceApiId != null) {
                        result.put(interfaceClass.getName(), interfaceApiId);
                        foundIdInInterfaceDoc = true;
                        break; // 找到接口 Javadoc ID，停止查找接口 Javadoc
                    }
                }
            }
            // 如果從接口 Javadoc 找到，直接返回
            if (foundIdInInterfaceDoc) {
                return result;
            }

            // 4. ServiceImpl 未在自身或接口 Javadoc 找到 ID -> 嘗試通過實現的 *接口* 去查找 Controller
            for (PsiClassType interfaceType : serviceClass.getImplementsListTypes()) {
                PsiClass interfaceClass = interfaceType.resolve();
                if (interfaceClass != null && isServiceInterface(interfaceClass)) {
                    // 查找使用 *這個接口* 的 Controller
                    Map<String, String> controllerApiIds = findControllerApiIds(interfaceClass);
                    if (!controllerApiIds.isEmpty()) {
                        Map.Entry<String, String> firstEntry = controllerApiIds.entrySet().iterator().next();
                        result.put(firstEntry.getKey(), firstEntry.getValue());
                        return result; // 找到關聯 Controller，立即返回
                    }
                }
            }
            // 如果通過接口查找 Controller 也失敗了，會繼續執行步驟 5
        }

        // 5. 最後手段：如果前面都沒找到 ID，嘗試基於 serviceClass 本身查找 Controller
        //    (對接口來說是主要查找 Controller 路徑，對實現類是最後補救)
        Map<String, String> controllerApiIds = findControllerApiIds(serviceClass);
        if (!controllerApiIds.isEmpty()) {
            Map.Entry<String, String> firstEntry = controllerApiIds.entrySet().iterator().next();
            result.put(firstEntry.getKey(), firstEntry.getValue());
        }

        return result; // 可能返回空 Map
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
     * Value 是對應的電文代號字串。如果方法體內沒有使用 Service，或者使用的 Service 沒有關聯的電文代號，則返回空 Map。
     */
    @NotNull
    public static Map<String, String> findAndSuggestApiIdsFromUsedServices(@NotNull PsiMethod controllerMethod) {
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
                    // 可能是 this.method() 或 靜態方法調用，或者方法在同一個類中
                    // 如果需要處理 this.service.method() 的情況，需要進一步分析 qualifier
                    // 如果是 this 調用，嘗試獲取 containing class
                    if (expression.getMethodExpression().getQualifier() == null || expression.getMethodExpression().getQualifier() instanceof PsiThisExpression) {
                        PsiMethod resolvedMethod = expression.resolveMethod();
                        if (resolvedMethod != null) {
                            PsiClass containingClass = resolvedMethod.getContainingClass();
                            // 我們關心的是調用 Service 的方法，不是調用 Controller 內部其他方法
                            // 所以這裡的邏輯可能需要調整，看是否要處理 this 調用
                        }
                    }
                    return;
                }

                // 解析 qualifier 的類型
                PsiType qualifierType = qualifier.getType();
                if (qualifierType != null) {
                    PsiClass qualifierClass = PsiUtil.resolveClassInType(qualifierType);
                    // 檢查解析出的類型是否是一個 Service 類，並且尚未處理過
                    if (qualifierClass != null
                            && ApiMsgIdUtil.isServiceClass(qualifierClass) // 使用 ApiMsgIdUtil 的方法
                            && processedServiceClasses.add(qualifierClass)) { // add() 成功表示是第一次遇到

                        // 為這個找到的 Service 類查找關聯的電文代號
                        Map<String, String> apiIds = ApiMsgIdUtil.findApiIdsForServiceClass(qualifierClass); // 使用 ApiMsgIdUtil 的方法
                        // 將找到的結果合併到總結果中
                        allFoundApiIds.putAll(apiIds);
                    }
                } else if (qualifier instanceof PsiReferenceExpression) {
                    // getType() 為 null 時的後備檢查 (例如，對字段的引用)
                    PsiElement resolved = ((PsiReferenceExpression) qualifier).resolve();
                    if (resolved instanceof PsiVariable) { // 檢查是否解析為一個變數 (字段、局部變數等)
                        PsiType variableType = ((PsiVariable) resolved).getType();
                        PsiClass variableClass = PsiUtil.resolveClassInType(variableType);
                        if (variableClass != null
                                && ApiMsgIdUtil.isServiceClass(variableClass) // 使用 ApiMsgIdUtil 的方法
                                && processedServiceClasses.add(variableClass)) {

                            Map<String, String> apiIds = ApiMsgIdUtil.findApiIdsForServiceClass(variableClass); // 使用 ApiMsgIdUtil 的方法
                            allFoundApiIds.putAll(apiIds);
                        }
                    }
                    // 這裡可以添加對解析為 PsiMethod 等其他情況的處理 (如果需要)
                }
            }

            // 可以考慮也訪問字段引用、變數聲明等，以處理更複雜的 Service 使用方式
            // 例如： private final UserService userService; (注入的字段)
            //        ...
            //        userService.doSomething();
            // 或者： UserService localService = getService(); (方法返回 Service)
            //        localService.doAnother();

            // 訪問變數聲明，檢查類型是否為 Service
            @Override
            public void visitDeclarationStatement(@NotNull PsiDeclarationStatement statement) {
                super.visitDeclarationStatement(statement);
                for (PsiElement declaredElement : statement.getDeclaredElements()) {
                    if (declaredElement instanceof PsiLocalVariable) {
                        PsiLocalVariable localVar = (PsiLocalVariable) declaredElement;
                        PsiType varType = localVar.getType();
                        PsiClass varClass = PsiUtil.resolveClassInType(varType);
                        if (varClass != null && ApiMsgIdUtil.isServiceClass(varClass) && processedServiceClasses.add(varClass)) {
                            // 這個 Service 類被聲明了，我們也查找它的 ID
                            // 這可能有點過度查找，因為聲明不代表一定被使用
                            // 但如果 Controller 方法很複雜，這可能是一種補充
                            // Map<String, String> apiIds = ApiMsgIdUtil.findApiIdsForServiceClass(varClass);
                            // allFoundApiIds.putAll(apiIds);
                        }
                    }
                }
            }
        });

        return allFoundApiIds;
    }
}