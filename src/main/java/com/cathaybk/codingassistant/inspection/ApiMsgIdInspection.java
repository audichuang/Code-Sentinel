package com.cathaybk.codingassistant.inspection;

import com.cathaybk.codingassistant.utils.AddControllerApiIdFromServiceFix;
import com.cathaybk.codingassistant.utils.AddServiceApiIdQuickFix;
import com.cathaybk.codingassistant.utils.ApiMsgIdUtil;
import com.intellij.codeInspection.*;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.Query;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * 檢查API方法和Service類的電文代號註解
 *
 * <p>本檢查器用於確保代碼符合以下規範：</p>
 * <ol>
 *     <li>所有API方法（被RequestMapping等註解標記的方法）都有正確格式的電文代號註解</li>
 *     <li>所有Service類都有關聯的Controller電文代號註解</li>
 * </ol>
 *
 * <p>電文代號格式: XXX-X-XXXX 說明文字，例如 SYS-T-USER_LOGIN 使用者登入</p>
 *
 * <p>檢查過程：</p>
 * <ul>
 *     <li>對於API方法：檢查Javadoc是否包含電文代號</li>
 *     <li>對於Service類：查找使用此Service的Controller，並將其電文代號關聯到Service</li>
 * </ul>
 */
public class ApiMsgIdInspection extends AbstractBaseJavaLocalInspectionTool {

    /**
     * 取得檢查器的簡稱
     *
     * @return 檢查器簡稱
     */
    @NotNull
    @Override
    public String getShortName() {
        return "ApiMsgIdInspection";
    }

    /**
     * 取得檢查器的顯示名稱
     *
     * @return 檢查器顯示名稱
     */
    @NotNull
    @Override
    public String getDisplayName() {
        return "API 電文代號檢查";
    }

    /**
     * 取得檢查器所屬的分組名稱
     *
     * @return 分組名稱
     */
    @NotNull
    @Override
    public String getGroupDisplayName() {
        return "CathayBk規範檢查";
    }

    /**
     * 構建訪問者模式，用於檢查類
     *
     * <p>這個方法用於檢查Service類是否有相關的電文代號註解</p>
     * <p>檢查過程：</p>
     * <ol>
     *     <li>確認是否為Service類</li>
     *     <li>檢查類的Javadoc中是否已包含電文代號</li>
     *     <li>如果沒有，查找使用此Service的Controller及其電文代號</li>
     *     <li>如果是實現類，嘗試檢查其接口</li>
     * </ol>
     *
     * @param holder 用於註冊問題的容器
     * @param isOnTheFly 是否是即時檢查
     * @return PsiElementVisitor實例
     */
    @NotNull
    @Override
    public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {

            @Override
            public void visitMethod(PsiMethod method) {
                // 檢查是否是API方法
                if (!ApiMsgIdUtil.isApiMethod(method)) {
                    return;  // 不是API方法，跳過
                }

                // 檢查方法是否有正確的電文代號
                if (!ApiMsgIdUtil.hasValidApiMsgId(method.getDocComment())) {
                    // 沒有電文代號，檢查方法內使用的 Service
                    Map<String, String> serviceApiIds = findServiceApiIdsFromMethod(method);

                    if (!serviceApiIds.isEmpty()) {
                        // 找到了相關 Service 的電文代號，提供快速修復
                        holder.registerProblem(
                                method.getNameIdentifier(),
                                "API方法缺少正確的電文代號註解，可從使用的Service獲取",
                                new AddControllerApiIdFromServiceFix(serviceApiIds));  // 使用專門為 Controller 方法設計的修復類
                    } else {
                        // 未找到相關 Service 的電文代號，提供默認快速修復
                        holder.registerProblem(
                                method.getNameIdentifier(),
                                "API方法缺少正確的電文代號註解，格式應為: 電文代號 說明文字（如 API-001 登入服務）",
                                new AddApiIdDocFix());
                    }
                }
            }

            @Override
            public void visitClass(PsiClass aClass) {
                // 1. 判斷類型 - 是否為 Service 類，若不是則跳過
                if (!ApiMsgIdUtil.isServiceClass(aClass)) {
                    return;
                }

                // 2. 如果已有電文代號，則跳過
                if (ApiMsgIdUtil.hasValidApiMsgId(aClass.getDocComment())) {
                    return;
                }

                // 3. 根據是 Service 接口還是實現類採取不同策略
                Map<String, String> controllerApiIds = new HashMap<>();

                if (ApiMsgIdUtil.isServiceInterface(aClass)) {
                    // 3.1 處理 Service 接口
                    processServiceInterface(aClass, controllerApiIds);
                } else if (ApiMsgIdUtil.isServiceImpl(aClass)) {
                    // 3.2 處理 Service 實現類
                    processServiceImplementation(aClass, controllerApiIds);
                }

                // 4. 如果找到電文代號，則註冊問題
                if (!controllerApiIds.isEmpty()) {
                    holder.registerProblem(
                            aClass.getNameIdentifier() != null ? aClass.getNameIdentifier() : aClass,
                            "Service類可能需要添加來自Controller的電文代號註解",
                            new AddServiceApiIdQuickFix(controllerApiIds));
                }
            }
        };
    }

    /**
     * 處理 Service 接口的電文代號查找
     *
     * @param interfaceClass Service 接口
     * @param result 結果映射
     */
    private void processServiceInterface(PsiClass interfaceClass, Map<String, String> result) {
        // 1. 直接查找使用此接口的 Controller 方法
        Map<String, String> directApiIds = ApiMsgIdUtil.findControllerApiIds(interfaceClass);
        result.putAll(directApiIds);

        // 2. 如果找不到，檢查實現此接口的所有實現類
        if (result.isEmpty()) {
            searchImplementationClasses(interfaceClass, result);
        }
    }

    /**
     * 處理 Service 實現類的電文代號查找
     *
     * @param implClass Service 實現類
     * @param result 結果映射
     */
    private void processServiceImplementation(PsiClass implClass, Map<String, String> result) {
        // 1. 直接查找使用此實現類的 Controller 方法
        Map<String, String> directApiIds = ApiMsgIdUtil.findControllerApiIds(implClass);
        result.putAll(directApiIds);

        // 2. 如果找不到，查找其實現的所有接口
        if (result.isEmpty()) {
            for (PsiClassType interfaceType : implClass.getImplementsListTypes()) {
                PsiClass interfaceClass = interfaceType.resolve();
                if (interfaceClass != null && ApiMsgIdUtil.isServiceInterface(interfaceClass)) {
                    // 2.1 查找接口關聯的 Controller 電文代號
                    Map<String, String> interfaceApiIds = ApiMsgIdUtil.findControllerApiIds(interfaceClass);
                    result.putAll(interfaceApiIds);

                    // 2.2 檢查接口本身的註解
                    if (result.isEmpty() && ApiMsgIdUtil.hasValidApiMsgId(interfaceClass.getDocComment())) {
                        String interfaceDocText = interfaceClass.getDocComment().getText();
                        Matcher matcher = ApiMsgIdUtil.API_ID_PATTERN.matcher(interfaceDocText);
                        if (matcher.find()) {
                            String apiId = matcher.group(1);
                            result.put(interfaceClass.getName(), apiId);
                        }
                    }

                    if (!result.isEmpty()) {
                        break;
                    }
                }
            }
        }
    }

    /**
     * 查找接口的所有實現類並檢查它們是否已有電文代號註解
     *
     * @param interfaceClass Service 接口
     * @param result 結果映射
     */
    private void searchImplementationClasses(PsiClass interfaceClass, Map<String, String> result) {
        Project project = interfaceClass.getProject();

        // 查找所有實現此接口的類
        Query<PsiClass> implementations = ClassInheritorsSearch.search(interfaceClass, true);
        for (PsiClass implClass : implementations) {
            if (ApiMsgIdUtil.isServiceImpl(implClass)) {
                // 檢查實現類是否已經有電文代號註解
                PsiDocComment docComment = implClass.getDocComment();
                if (ApiMsgIdUtil.hasValidApiMsgId(docComment)) {
                    // 提取實現類的電文代號並添加到結果中
                    String docText = docComment.getText();
                    Matcher matcher = ApiMsgIdUtil.API_ID_PATTERN.matcher(docText);
                    if (matcher.find()) {
                        String apiId = matcher.group(1);
                        result.put(implClass.getName(), apiId);
                        break;
                    }
                }
            }
        }
    }

    /**
     * 建立Controller方法的問題描述
     *
     * <p>創建一個警告級別的問題，並關聯AddApiIdDocFix快速修復</p>
     *
     * @param method 有問題的方法
     * @param manager InspectionManager實例
     * @param isOnTheFly 是否是即時檢查
     * @return 問題描述
     */
    private ProblemDescriptor createControllerProblemDescriptor(PsiMethod method, InspectionManager manager, boolean isOnTheFly) {
        return manager.createProblemDescriptor(
                method.getNameIdentifier(),
                "API方法缺少正確的電文代號註解，格式應為: 電文代號 說明文字（如 API-001 登入服務）",
                new AddApiIdDocFix(),
                ProblemHighlightType.WARNING,
                isOnTheFly);
    }


    /**
     * Controller方法的快速修復實現
     *
     * <p>提供為API方法添加電文代號Javadoc的快速修復功能</p>
     */
    private static class AddApiIdDocFix implements LocalQuickFix {
        /**
         * 取得修復的名稱
         *
         * @return 修復名稱
         */
        @NotNull
        @Override
        public String getName() {
            return "添加電文代號註解";
        }

        /**
         * 取得修復的族名稱
         *
         * @return 族名稱
         */
        @NotNull
        @Override
        public String getFamilyName() {
            return getName();
        }

        /**
         * 應用修復邏輯
         *
         * <p>為方法添加或更新包含電文代號的Javadoc註解</p>
         *
         * @param project 當前項目
         * @param descriptor 問題描述
         */
        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            try {
                PsiElement element = descriptor.getPsiElement();
                if (element == null) return;  // 安全檢查

                // 獲取方法元素
                PsiMethod method = (PsiMethod) element.getParent();

                // 創建Javadoc註解
                PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

                // 使用工具類生成電文代號模板
                String apiId = ApiMsgIdUtil.generateApiMsgId(method);

                // 創建包含電文代號的Javadoc註解
                PsiDocComment newDocComment = factory.createDocCommentFromText(
                        "/**\n * " + apiId + " [請填寫API描述]\n */");

                // 更新或添加註解：如果已存在就替換，否則添加新的
                PsiDocComment existingComment = method.getDocComment();
                if (existingComment != null) {
                    // 替換現有註解
                    existingComment.replace(newDocComment);
                } else {
                    // 在方法修飾符前添加新註解
                    method.addBefore(newDocComment, method.getModifierList());
                }
            } catch (IncorrectOperationException e) {
                // 忽略操作異常
            }
        }
    }

    /**
     * 查找Controller方法中使用的Service及其電文代號
     * 改進：保留具體的Service類名而非簡單的方法名，以便標示來源
     *
     * @param method Controller方法
     * @return Service類名及其電文代號的映射
     */
    private Map<String, String> findServiceApiIdsFromMethod(PsiMethod method) {
        Map<String, String> result = new HashMap<>();

        // 檢查方法體是否存在
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            return result;
        }

        // 查找方法中的所有字段引用表達式
        Collection<PsiReferenceExpression> fieldRefs = PsiTreeUtil.findChildrenOfType(body, PsiReferenceExpression.class);

        for (PsiReferenceExpression fieldRef : fieldRefs) {
            PsiElement resolved = fieldRef.resolve();
            if (resolved instanceof PsiField) {
                PsiField field = (PsiField) resolved;
                PsiType fieldType = field.getType();

                if (fieldType instanceof PsiClassType) {
                    PsiClass fieldClass = ((PsiClassType) fieldType).resolve();
                    if (fieldClass != null &&
                            (ApiMsgIdUtil.isServiceInterface(fieldClass) || ApiMsgIdUtil.isServiceImpl(fieldClass))) {
                        // 獲取完整的類名，以便在註解中標明來源
                        String qualifiedName = fieldClass.getQualifiedName();

                        // 使用通用方法查找電文代號
                        Map<String, String> serviceApiIds = ApiMsgIdUtil.findApiIdsForServiceClass(fieldClass);

                        // 將結果重新映射，使用完整的類名作為key
                        for (String key : serviceApiIds.keySet()) {
                            result.put(key, serviceApiIds.get(key));
                        }

                        if (!result.isEmpty()) {
                            break;  // 找到一個電文代號就可以了
                        }
                    }
                }
            }
        }

        return result;
    }
}