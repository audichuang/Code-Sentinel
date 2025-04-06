package com.cathaybk.codingassistant.utils;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.Query;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API 電文代號相關的工具類
 *
 * <p>本工具類用於處理與API電文代號相關的各種操作，包括：</p>
 * <ul>
 *     <li>檢查方法是否為API方法</li>
 *     <li>檢查類是否為Controller或Service</li>
 *     <li>驗證JavaDoc中的電文代號格式</li>
 *     <li>生成電文代號模板</li>
 *     <li>尋找Service類相關的Controller及其電文代號</li>
 * </ul>
 *
 * <p>電文代號格式規範為：XXX-X-XXXX，例如 SYS-T-USER_LOGIN，通常還會在後面跟隨描述文字</p>
 */
public class ApiMsgIdUtil {

    /**
     * 電文代號的正則表達式模式 - 匹配電文代號和說明文字
     *
     * <p>格式說明：</p>
     * <ul>
     *     <li>匹配格式：任意兩組以上的英文字母和數字，用連字符（-）連接，後面跟隨空格和描述文字</li>
     *     <li>例如：SYS-USER 使用者資訊</li>
     *     <li>或者：API-001-LOGIN 使用者登入</li>
     *     <li>或者：M-BANK-USR 行動銀行使用者查詢</li>
     * </ul>
     */
    public static final Pattern API_ID_PATTERN = Pattern.compile("([A-Za-z0-9]+-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*\\s+.+)");

    /**
     * 判斷方法是否為 API 方法（被Mapping註解標記的方法）
     *
     * <p>檢查方法是否有以下註解之一：</p>
     * <ul>
     *     <li>@RequestMapping</li>
     *     <li>@GetMapping</li>
     *     <li>@PostMapping</li>
     *     <li>@PutMapping</li>
     *     <li>@DeleteMapping</li>
     *     <li>@PatchMapping</li>
     * </ul>
     *
     * @param method 要檢查的方法
     * @return 如果是 API 方法則返回 true，否則返回 false
     */
    public static boolean isApiMethod(PsiMethod method) {
        // 首先檢查方法和其修飾符列表是否為空
        if (method == null || method.getModifierList() == null) {
            return false;
        }

        // 使用Java 8 Stream API檢查所有註解，尋找以"Mapping"結尾的註解
        return Arrays.stream(method.getModifierList().getAnnotations())
                .map(PsiAnnotation::getQualifiedName)     // 獲取註解的完整名稱
                .filter(StringUtils::isNotEmpty)          // 過濾掉空名稱
                .anyMatch(name -> StringUtils.endsWith(name, "Mapping"));  // 檢查是否以"Mapping"結尾
    }

    /**
     * 判斷一個類是否是 Controller 類
     *
     * <p>判斷依據：</p>
     * <ul>
     *     <li>類名是否以"Controller"結尾</li>
     *     <li>類所在包名是否包含".controller."或以".controller"結尾</li>
     *     <li>類是否有@Controller或@RestController註解</li>
     * </ul>
     *
     * @param psiClass 要檢查的類
     * @return 如果是 Controller 類則返回 true，否則返回 false
     */
    public static boolean isControllerClass(PsiClass psiClass) {
        if (psiClass == null) {
            return false;
        }

        // 檢查類名
        String className = psiClass.getName();
        if (StringUtils.isNotEmpty(className) && StringUtils.endsWith(className, "Controller")) {
            return true;  // 類名以"Controller"結尾
        }

        // 檢查包名
        String qualifiedName = psiClass.getQualifiedName();
        if (StringUtils.isNotEmpty(qualifiedName) &&
                (qualifiedName.contains(".controller.") || qualifiedName.endsWith(".controller"))) {
            return true;  // 類所在包含有"controller"
        }

        // 檢查是否有 Controller 相關註解
        return psiClass.hasAnnotation("org.springframework.stereotype.Controller") ||
                psiClass.hasAnnotation("org.springframework.web.bind.annotation.RestController");
    }

    /**
     * 判斷一個類是否是 Service 接口
     *
     * <p>判斷依據：</p>
     * <p>1. 是否為接口</p>
     * <p>2. 類名是否以 "Service" 或 "Svc" 結尾</p>
     *
     * @param psiClass 要檢查的類
     * @return 如果是 Service 接口則返回 true，否則返回 false
     */
    public static boolean isServiceInterface(PsiClass psiClass) {
        if (psiClass == null || !psiClass.isInterface()) {
            return false;
        }

        String className = psiClass.getName();
        return StringUtils.isNotEmpty(className) &&
                (StringUtils.endsWith(className, "Service") ||
                        StringUtils.endsWith(className, "Svc"));
    }

    /**
     * 判斷一個類是否是 Service 實現類
     *
     * <p>判斷依據：</p>
     * <p>1. 是否有 @Service 註解</p>
     * <p>2. 類名是否以 "Impl" 結尾</p>
     * <p>3. 是否實現了 Service 接口</p>
     *
     * @param psiClass 要檢查的類
     * @return 如果是 Service 實現類則返回 true，否則返回 false
     */
    public static boolean isServiceImpl(PsiClass psiClass) {
        if (psiClass == null || psiClass.isInterface()) {
            return false;
        }

        // 檢查是否有 @Service 註解
        if (psiClass.hasAnnotation("org.springframework.stereotype.Service")) {
            return true;
        }

        // 檢查類名是否以 Impl 結尾
        String className = psiClass.getName();
        if (StringUtils.isNotEmpty(className) && StringUtils.endsWith(className, "Impl")) {
            // 檢查是否實現了 Service 接口
            return Arrays.stream(psiClass.getInterfaces())
                    .anyMatch(ApiMsgIdUtil::isServiceInterface);
        }

        return false;
    }

    /**
     * 判斷一個類是否與 Service 相關（接口或實現類）
     *
     * @param psiClass 要檢查的類
     * @return 如果是 Service 接口或實現類則返回 true，否則返回 false
     */
    public static boolean isServiceClass(PsiClass psiClass) {
        return isServiceInterface(psiClass) || isServiceImpl(psiClass);
    }

    /**
     * 檢查文檔註解中是否包含正確格式的電文代號
     *
     * <p>使用API_ID_PATTERN正則表達式檢查文檔中是否包含符合電文代號格式的文字</p>
     * <p>例如：SYS-T-USER_LOGIN 使用者登入</p>
     *
     * @param docComment 要檢查的Javadoc註解，可為null
     * @return 如果包含符合格式的電文代號則返回true，否則返回false
     */
    public static boolean hasValidApiMsgId(@Nullable PsiDocComment docComment) {
        if (docComment == null) {
            return false;
        }

        String docText = docComment.getText();
        return API_ID_PATTERN.matcher(docText).find();
    }

    /**
     * 為Controller方法生成一個電文代號模板
     *
     * <p>根據Controller類名和方法名生成電文代號模板</p>
     * <p>例如，如果類是UserController，方法是login，則生成：API-USER_LOGIN</p>
     *
     * @param method Controller方法
     * @return 生成的電文代號模板
     */
    public static String generateApiMsgId(PsiMethod method) {
        String className = "";
        PsiClass containingClass = method.getContainingClass();
        if (containingClass != null) {
            className = containingClass.getName();
            if (className != null) {
                // 移除類名中的"CONTROLLER"字串
                className = className.toUpperCase().replace("CONTROLLER", "");
            }
        }

        // 方法名轉大寫
        String methodName = method.getName().toUpperCase();
        return "API-" + (className != null ? className : "") + "_" + methodName;
    }

    /**
     * 查找使用指定Service類的Controller方法及其電文代號
     *
     * <p>搜索過程：</p>
     * <ol>
     *     <li>首先搜索Service類的直接引用</li>
     *     <li>檢查引用中的Controller方法及其電文代號</li>
     *     <li>如果未找到引用，嘗試以字段名方式搜索</li>
     *     <li>最後嘗試搜索所有Controller方法</li>
     * </ol>
     *
     * @param serviceClass 要查找引用的Service類
     * @return 使用此Service的Controller方法及其電文代號的映射，鍵為方法名，值為電文代號
     */
    public static Map<String, String> findControllerApiIds(PsiClass serviceClass) {
        Map<String, String> result = new HashMap<>();
        Project project = serviceClass.getProject();

        try {
            // 使用ReferencesSearch尋找所有引用
            // ReferencesSearch用於尋找代碼中對特定元素的所有引用位置
            Collection<PsiReference> references = ReferencesSearch.search(serviceClass).findAll();
            // 檢查方法引用、變量引用和參數引用
            checkReferences(references, serviceClass, result);

        } catch (Exception e) {
            // 忽略搜索異常，繼續執行後續搜索邏輯
        }

        // 如果沒有找到引用，嘗試搜索Java字面量
        // 例如：將 UserService 轉換為 userService 進行搜索
        if (result.isEmpty() && serviceClass.getName() != null) {
            // 生成可能的字段名（首字母小寫的類名）
            // 例如：UserService -> userService
            String serviceFieldName = serviceClass.getName().substring(0, 1).toLowerCase()
                    + serviceClass.getName().substring(1);

            // 搜索Controller中的使用
            searchForServiceUsageInControllers(project, serviceFieldName, result);

            // 如果仍找不到，搜索所有Controller方法
            if (result.isEmpty()) {
                searchAllControllerMethods(project, serviceClass, result);
            }
        }

        return result;
    }

    /**
     * 檢查所有引用（方法、變量、參數）
     *
     * <p>對於每個引用，檢查：</p>
     * <ol>
     *     <li>方法引用 - 引用位於某個方法內</li>
     *     <li>變量引用 - 引用是一個變量的定義或使用</li>
     * </ol>
     *
     * @param references   Service類的所有引用
     * @param serviceClass Service類
     * @param result       結果映射，用於儲存找到的電文代號
     */
    private static void checkReferences(Collection<PsiReference> references, PsiClass serviceClass,
                                        Map<String, String> result) {
        for (PsiReference reference : references) {
            PsiElement element = reference.getElement();

            // 檢查方法引用 - 尋找包含此引用的方法
            PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
            if (method != null) {
                // 檢查所在方法是否是Controller方法
                checkControllerMethod(method, serviceClass, result);
                continue;
            }

            // 檢查變量引用 - 尋找使用此變量的所有方法
            PsiVariable variable = PsiTreeUtil.getParentOfType(element, PsiVariable.class);
            if (variable != null) {
                // 查找使用此變量的所有引用
                Collection<PsiReference> variableReferences = ReferencesSearch.search(variable).findAll();
                for (PsiReference varRef : variableReferences) {
                    PsiElement varElement = varRef.getElement();
                    // 尋找變量使用所在的方法
                    method = PsiTreeUtil.getParentOfType(varElement, PsiMethod.class);
                    if (method != null) {
                        // 檢查所在方法是否是Controller方法
                        checkControllerMethod(method, serviceClass, result);
                    }
                }
            }
        }
    }

    /**
     * 檢查方法是否是Controller方法並含有電文代號
     *
     * <p>檢查步驟：</p>
     * <ol>
     *     <li>檢查方法所在類是否是Controller</li>
     *     <li>檢查方法文本中是否使用了service（通過字段名）</li>
     *     <li>如果都滿足，檢查方法Javadoc中是否有電文代號</li>
     * </ol>
     *
     * @param method       要檢查的方法
     * @param serviceClass Service類
     * @param result       結果映射，用於儲存找到的電文代號
     */
    private static void checkControllerMethod(PsiMethod method, PsiClass serviceClass, Map<String, String> result) {
        // 檢查方法是否在Controller中
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null || !isControllerClass(containingClass)) {
            return;  // 不是Controller類，跳過
        }

        // 檢查方法中使用了service
        // 將Service類名首字母小寫作為可能的字段名
        // 例如：UserService -> userService
        String methodText = method.getText();
        String serviceFieldName = serviceClass.getName().substring(0, 1).toLowerCase() +
                serviceClass.getName().substring(1);
        if (!methodText.contains(serviceFieldName)) {
            return;  // 方法中未使用Service，跳過
        }

        // 檢查電文代號
        PsiDocComment docComment = method.getDocComment();
        if (docComment != null) {
            String docText = docComment.getText();
            Matcher matcher = API_ID_PATTERN.matcher(docText);
            if (matcher.find()) {
                // 找到電文代號，添加到結果映射中
                String apiId = matcher.group(1);
                result.put(method.getName(), apiId);
            }
        }
    }

    /**
     * 搜索使用Service的Controller
     *
     * <p>嘗試在項目中找到使用特定Service字段的所有Controller</p>
     * <p>搜索策略：</p>
     * <ol>
     *     <li>首先搜索controller包中的類</li>
     *     <li>如果未找到，搜索名稱中包含Controller的類</li>
     *     <li>檢查這些類的文本是否包含service字段名</li>
     *     <li>檢查含有API映射的方法</li>
     * </ol>
     *
     * @param project          IntelliJ項目
     * @param serviceFieldName Service字段名
     * @param result           結果映射，用於儲存找到的電文代號
     */
    private static void searchForServiceUsageInControllers(Project project, String serviceFieldName,
                                                           Map<String, String> result) {
        try {
            JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
            GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

            // 搜索Controller - 首先嘗試搜索controller包下的類
            PsiClass[] controllers = psiFacade.findClasses("*.controller.*", scope);
            if (controllers.length == 0) {
                // 如果找不到，嘗試直接按命名規則搜索
                controllers = psiFacade.findClasses("*Controller", scope);
            }

            // 檢查每個Controller
            for (PsiClass controller : controllers) {
                String classText = controller.getText();
                // 檢查類文本中是否包含service字段名
                if (classText.contains(serviceFieldName)) {
                    // 檢查所有方法
                    for (PsiMethod method : controller.getMethods()) {
                        // 檢查是否是API方法且使用了service
                        if (isApiMethod(method) && method.getText().contains(serviceFieldName)) {
                            extractApiId(method, result);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 忽略搜索異常
        }
    }

    /**
     * 直接搜索所有Controller方法
     *
     * <p>這是最後一種嘗試，直接搜索所有Controller類中的所有方法，檢查是否使用了Service</p>
     * <p>會檢查方法文本中是否包含Service類名或其字段名</p>
     *
     * @param project      IntelliJ項目
     * @param serviceClass Service類
     * @param result       結果映射，用於儲存找到的電文代號
     */
    private static void searchAllControllerMethods(Project project, PsiClass serviceClass, Map<String, String> result) {
        try {
            JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
            GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

            // 搜索所有Controller類
            PsiClass[] controllers = psiFacade.findClasses("*Controller", scope);

            String serviceClassName = serviceClass.getName();
            // 服務類名的首字母小寫版本 (如AbcService -> abcService)
            String serviceFieldName = serviceClassName.substring(0, 1).toLowerCase() +
                    serviceClassName.substring(1);

            // 檢查每個Controller的每個方法
            for (PsiClass controller : controllers) {
                for (PsiMethod method : controller.getMethods()) {
                    // 檢查是否是API方法且方法文本中使用了Service（類名或字段名）
                    if (isApiMethod(method) &&
                            (method.getText().contains(serviceFieldName) ||
                                    method.getText().contains(serviceClassName))) {
                        extractApiId(method, result);
                    }
                }
            }
        } catch (Exception e) {
            // 忽略搜索異常
        }
    }

    /**
     * 從方法註解中提取電文代號
     *
     * <p>檢查方法的Javadoc註解，使用正則表達式提取電文代號</p>
     *
     * @param method 要檢查的方法
     * @param result 結果映射，用於儲存找到的電文代號
     */
    private static void extractApiId(PsiMethod method, Map<String, String> result) {
        PsiDocComment docComment = method.getDocComment();
        if (docComment != null) {
            String docText = docComment.getText();
            // 使用正則表達式匹配電文代號
            Matcher matcher = API_ID_PATTERN.matcher(docText);
            if (matcher.find()) {
                // 找到匹配的電文代號，添加到結果中
                String apiId = matcher.group(1);
                result.put(method.getName(), apiId);
            }
        }
    }

    /**
     * 從 Service 類（接口或實現類）查找電文代號
     * 查找順序：本身 -> 關聯類（接口/實現類）-> Controller 方法
     *
     * @param serviceClass Service 類
     * @return 電文代號映射
     */
    public static Map<String, String> findApiIdsForServiceClass(PsiClass serviceClass) {
        Map<String, String> result = new HashMap<>();

        // 1. 檢查類本身是否有電文代號
        if (hasValidApiMsgId(serviceClass.getDocComment())) {
            String docText = serviceClass.getDocComment().getText();
            Matcher matcher = API_ID_PATTERN.matcher(docText);
            if (matcher.find()) {
                String apiId = matcher.group(1);
                result.put(serviceClass.getName(), apiId);
                return result;
            }
        }

        // 2. 如果是接口，檢查其所有實現類
        if (isServiceInterface(serviceClass)) {
            Query<PsiClass> implementations = ClassInheritorsSearch.search(serviceClass, true);
            for (PsiClass implClass : implementations) {
                if (isServiceImpl(implClass) && hasValidApiMsgId(implClass.getDocComment())) {
                    String docText = implClass.getDocComment().getText();
                    Matcher matcher = API_ID_PATTERN.matcher(docText);
                    if (matcher.find()) {
                        String apiId = matcher.group(1);
                        result.put(implClass.getName(), apiId);
                        return result;
                    }
                }
            }
        }

        // 3. 如果是實現類，檢查其所有接口
        if (isServiceImpl(serviceClass)) {
            for (PsiClassType interfaceType : serviceClass.getImplementsListTypes()) {
                PsiClass interfaceClass = interfaceType.resolve();
                if (interfaceClass != null && isServiceInterface(interfaceClass) &&
                        hasValidApiMsgId(interfaceClass.getDocComment())) {
                    String docText = interfaceClass.getDocComment().getText();
                    Matcher matcher = API_ID_PATTERN.matcher(docText);
                    if (matcher.find()) {
                        String apiId = matcher.group(1);
                        result.put(interfaceClass.getName(), apiId);
                        return result;
                    }
                }
            }
        }

        // 4. 如果以上都沒有找到，嘗試查找相關的 Controller 方法
        if (result.isEmpty()) {
            result.putAll(findControllerApiIds(serviceClass));
        }

        return result;
    }
}