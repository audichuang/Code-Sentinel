package com.cathaybk.codingassistant.apicopy.analysis;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * 泛型類型解析器
 * 用於從泛型類型中提取實際的類型參數
 */
public class GenericTypeResolver {
    private static final Logger LOG = Logger.getInstance(GenericTypeResolver.class);

    private static final int MAX_DEPTH = 5;
    private final Set<String> visited = new HashSet<>();

    /**
     * 從泛型類型中解析所有專案類型
     *
     * @param type    要解析的類型
     * @param project 專案類型判斷用
     * @return 所有解析出的專案類別集合
     */
    @NotNull
    public Set<PsiClass> resolveProjectTypes(@NotNull PsiType type, @NotNull String projectPackagePrefix) {
        Set<PsiClass> result = new HashSet<>();
        resolveTypeRecursively(type, result, projectPackagePrefix, 0);
        return result;
    }

    private void resolveTypeRecursively(@NotNull PsiType type,
                                        @NotNull Set<PsiClass> result,
                                        @NotNull String projectPackagePrefix,
                                        int depth) {
        if (depth > MAX_DEPTH) {
            LOG.debug("達到最大遞迴深度，停止解析: " + type.getCanonicalText());
            return;
        }

        // 處理泛型類型
        if (type instanceof PsiClassType) {
            PsiClassType classType = (PsiClassType) type;

            // 解析主類型
            PsiClass resolvedClass = classType.resolve();
            if (resolvedClass != null && isProjectClass(resolvedClass, projectPackagePrefix)) {
                String qName = resolvedClass.getQualifiedName();
                if (qName != null && !visited.contains(qName)) {
                    visited.add(qName);
                    result.add(resolvedClass);
                }
            }

            // 解析泛型參數
            PsiType[] typeParameters = classType.getParameters();
            for (PsiType param : typeParameters) {
                resolveTypeRecursively(param, result, projectPackagePrefix, depth + 1);
            }
        }
        // 處理陣列類型
        else if (type instanceof PsiArrayType) {
            PsiArrayType arrayType = (PsiArrayType) type;
            resolveTypeRecursively(arrayType.getComponentType(), result, projectPackagePrefix, depth + 1);
        }
        // 處理萬用字元類型
        else if (type instanceof PsiWildcardType) {
            PsiWildcardType wildcardType = (PsiWildcardType) type;
            PsiType bound = wildcardType.getBound();
            if (bound != null) {
                resolveTypeRecursively(bound, result, projectPackagePrefix, depth + 1);
            }
        }
    }

    /**
     * 判斷是否為專案內的類別
     */
    private boolean isProjectClass(@NotNull PsiClass psiClass, @NotNull String projectPackagePrefix) {
        String qualifiedName = psiClass.getQualifiedName();
        if (qualifiedName == null) {
            return false;
        }

        // 排除 JDK 和常見第三方套件
        if (qualifiedName.startsWith("java.") ||
            qualifiedName.startsWith("javax.") ||
            qualifiedName.startsWith("jakarta.") ||
            qualifiedName.startsWith("org.springframework.") ||
            qualifiedName.startsWith("com.google.") ||
            qualifiedName.startsWith("org.apache.") ||
            qualifiedName.startsWith("lombok.")) {
            return false;
        }

        // 檢查是否為專案套件
        return qualifiedName.startsWith(projectPackagePrefix);
    }

    /**
     * 從方法的參數和返回值解析專案類型
     */
    @NotNull
    public Set<PsiClass> resolveMethodTypes(@NotNull PsiMethod method, @NotNull String projectPackagePrefix) {
        Set<PsiClass> result = new HashSet<>();

        // 解析返回類型
        PsiType returnType = method.getReturnType();
        if (returnType != null) {
            result.addAll(resolveProjectTypes(returnType, projectPackagePrefix));
        }

        // 解析參數類型
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            result.addAll(resolveProjectTypes(parameter.getType(), projectPackagePrefix));
        }

        return result;
    }

    /**
     * 從欄位類型解析專案類型
     */
    @NotNull
    public Set<PsiClass> resolveFieldTypes(@NotNull PsiField field, @NotNull String projectPackagePrefix) {
        return resolveProjectTypes(field.getType(), projectPackagePrefix);
    }

    /**
     * 解析 List、Set、Map 等集合的元素類型
     */
    @Nullable
    public PsiClass resolveCollectionElementType(@NotNull PsiType type) {
        if (type instanceof PsiClassType) {
            PsiClassType classType = (PsiClassType) type;
            PsiClass resolvedClass = classType.resolve();

            if (resolvedClass != null) {
                String qName = resolvedClass.getQualifiedName();
                if (qName != null) {
                    // 處理 List, Set, Collection
                    if (qName.equals("java.util.List") ||
                        qName.equals("java.util.Set") ||
                        qName.equals("java.util.Collection")) {
                        PsiType[] params = classType.getParameters();
                        if (params.length > 0) {
                            return PsiUtil.resolveClassInType(params[0]);
                        }
                    }
                    // 處理 Map 的 value 類型
                    else if (qName.equals("java.util.Map")) {
                        PsiType[] params = classType.getParameters();
                        if (params.length > 1) {
                            return PsiUtil.resolveClassInType(params[1]);
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 重置訪問記錄
     */
    public void reset() {
        visited.clear();
    }
}
