package com.cathaybk.codingassistant.apicopy.analysis.finder;

import com.cathaybk.codingassistant.apicopy.analysis.GenericTypeResolver;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * DTO 追蹤器
 * 用於找到方法的請求/回應 DTO 及其嵌套類型
 */
public class DtoFinder {
    private static final Logger LOG = Logger.getInstance(DtoFinder.class);

    private static final Set<String> REQUEST_BODY_ANNOTATIONS = Set.of(
            "org.springframework.web.bind.annotation.RequestBody"
    );


    private final Project project;
    private final String projectPackagePrefix;
    private final GenericTypeResolver typeResolver;

    public DtoFinder(@NotNull Project project,
                     @NotNull String projectPackagePrefix,
                     @NotNull GenericTypeResolver typeResolver) {
        this.project = project;
        this.projectPackagePrefix = projectPackagePrefix;
        this.typeResolver = typeResolver;
    }

    /**
     * 找到方法的請求 DTO
     */
    @NotNull
    public Set<PsiClass> findRequestDtos(@NotNull PsiMethod method) {
        Set<PsiClass> dtos = new LinkedHashSet<>();
        LOG.info("[DtoFinder] 分析請求 DTO, 方法: " + method.getName() + ", 參數數量: " + method.getParameterList().getParametersCount());

        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            LOG.info("[DtoFinder] 檢查參數: " + parameter.getName() + ", 類型: " + parameter.getType().getCanonicalText());

            // 檢查是否有 @RequestBody 注解
            boolean isRequestBody = false;
            for (String annotation : REQUEST_BODY_ANNOTATIONS) {
                if (parameter.hasAnnotation(annotation)) {
                    isRequestBody = true;
                    break;
                }
            }

            LOG.info("[DtoFinder] 參數 " + parameter.getName() + " 是否有 @RequestBody: " + isRequestBody);

            if (isRequestBody) {
                PsiType type = parameter.getType();
                extractDtoClasses(type, dtos);
            }
        }

        LOG.info("[DtoFinder] 找到請求 DTO 數量: " + dtos.size());
        return dtos;
    }

    /**
     * 找到方法的回應 DTO
     */
    @NotNull
    public Set<PsiClass> findResponseDtos(@NotNull PsiMethod method) {
        Set<PsiClass> dtos = new LinkedHashSet<>();

        PsiType returnType = method.getReturnType();
        LOG.info("[DtoFinder] 分析回應 DTO, 方法: " + method.getName() + ", 返回類型: " + (returnType != null ? returnType.getCanonicalText() : "void"));

        if (returnType != null) {
            extractDtoClasses(returnType, dtos);
        }

        LOG.info("[DtoFinder] 找到回應 DTO 數量: " + dtos.size());
        for (PsiClass dto : dtos) {
            LOG.info("[DtoFinder] 回應 DTO: " + dto.getQualifiedName());
        }
        return dtos;
    }

    /**
     * 找到 DTO 的嵌套類型（欄位類型和內部類別）
     */
    @NotNull
    public Set<PsiClass> findNestedDtos(@NotNull PsiClass dto) {
        return findNestedDtos(dto, new HashSet<>());
    }

    /**
     * 遞迴找到 DTO 的嵌套類型
     */
    @NotNull
    private Set<PsiClass> findNestedDtos(@NotNull PsiClass dto, @NotNull Set<String> visited) {
        Set<PsiClass> result = new LinkedHashSet<>();

        String qName = dto.getQualifiedName();
        if (qName == null || visited.contains(qName)) {
            return result;
        }
        visited.add(qName);

        // 1. 追蹤欄位類型
        for (PsiField field : dto.getFields()) {
            // 跳過靜態欄位和常量
            if (field.hasModifierProperty(PsiModifier.STATIC)) {
                continue;
            }

            Set<PsiClass> fieldTypes = typeResolver.resolveProjectTypes(
                    field.getType(), projectPackagePrefix);

            for (PsiClass fieldType : fieldTypes) {
                if (!visited.contains(fieldType.getQualifiedName())) {
                    result.add(fieldType);
                    // 遞迴追蹤
                    result.addAll(findNestedDtos(fieldType, visited));
                }
            }
        }

        // 2. 追蹤內部類別（包括 Record）
        for (PsiClass inner : dto.getInnerClasses()) {
            if (isProjectClass(inner) && !visited.contains(inner.getQualifiedName())) {
                result.add(inner);
                result.addAll(findNestedDtos(inner, visited));
            }
        }

        // 3. 追蹤 getter 方法的返回類型（用於 Record 類型）
        if (dto.isRecord()) {
            for (PsiMethod method : dto.getMethods()) {
                // Record 的 accessor 方法
                if (method.getParameterList().isEmpty()) {
                    PsiType returnType = method.getReturnType();
                    if (returnType != null) {
                        Set<PsiClass> returnTypes = typeResolver.resolveProjectTypes(
                                returnType, projectPackagePrefix);
                        for (PsiClass returnClass : returnTypes) {
                            if (!visited.contains(returnClass.getQualifiedName())) {
                                result.add(returnClass);
                                result.addAll(findNestedDtos(returnClass, visited));
                            }
                        }
                    }
                }
            }
        }

        return result;
    }

    /**
     * 從類型中提取 DTO 類別
     * 會遞迴提取所有泛型參數中的專案類別
     */
    private void extractDtoClasses(@NotNull PsiType type, @NotNull Set<PsiClass> dtos) {
        LOG.info("[DtoFinder] extractDtoClasses: " + type.getCanonicalText());

        if (type instanceof PsiClassType) {
            PsiClassType classType = (PsiClassType) type;
            PsiClass resolvedClass = classType.resolve();

            if (resolvedClass != null) {
                String qName = resolvedClass.getQualifiedName();
                LOG.info("[DtoFinder] 解析到類別: " + qName);

                // 如果是專案類別，添加到結果
                if (isProjectClass(resolvedClass)) {
                    LOG.info("[DtoFinder] " + qName + " 是專案類別，添加到 DTO 結果");
                    dtos.add(resolvedClass);
                }

                // 不論是否為專案類別，都嘗試提取泛型參數
                // （處理 Page<Dto>, ResponseEntity<Dto>, List<Dto>, ApiResponse<Dto> 等）
                PsiType[] typeParams = classType.getParameters();
                if (typeParams.length > 0) {
                    LOG.info("[DtoFinder] " + qName + " 有 " + typeParams.length + " 個泛型參數，逐一提取");
                    for (PsiType param : typeParams) {
                        extractDtoClasses(param, dtos);
                    }
                }
            } else {
                LOG.info("[DtoFinder] 無法解析類型: " + type.getCanonicalText());
            }
        } else if (type instanceof PsiArrayType) {
            // 處理陣列類型
            PsiArrayType arrayType = (PsiArrayType) type;
            extractDtoClasses(arrayType.getComponentType(), dtos);
        } else if (type instanceof PsiWildcardType) {
            // 處理萬用字元類型 (? extends SomeDto, ? super SomeDto)
            PsiWildcardType wildcardType = (PsiWildcardType) type;
            PsiType bound = wildcardType.getBound();
            if (bound != null) {
                LOG.info("[DtoFinder] 處理萬用字元類型，bound: " + bound.getCanonicalText());
                extractDtoClasses(bound, dtos);
            }
        }
    }

    /**
     * 判斷是否為專案內的類別
     */
    private boolean isProjectClass(@NotNull PsiClass psiClass) {
        String qualifiedName = psiClass.getQualifiedName();
        if (qualifiedName == null) {
            LOG.info("[DtoFinder] isProjectClass: qualifiedName 為 null");
            return false;
        }

        // 排除 JDK 和常見第三方套件
        if (qualifiedName.startsWith("java.") ||
            qualifiedName.startsWith("javax.") ||
            qualifiedName.startsWith("jakarta.") ||
            qualifiedName.startsWith("org.springframework.") ||
            qualifiedName.startsWith("lombok.")) {
            LOG.info("[DtoFinder] isProjectClass: " + qualifiedName + " 是第三方套件，排除");
            return false;
        }

        boolean result = qualifiedName.startsWith(projectPackagePrefix);
        LOG.info("[DtoFinder] isProjectClass: " + qualifiedName + " 是否以 " + projectPackagePrefix + " 開頭: " + result);
        return result;
    }
}
