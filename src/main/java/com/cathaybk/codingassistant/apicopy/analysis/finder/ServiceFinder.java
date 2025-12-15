package com.cathaybk.codingassistant.apicopy.analysis.finder;

import com.cathaybk.codingassistant.util.CodeInspectionUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Service 追蹤器
 * 用於找到方法調用的 Service 類別及其實作
 */
public class ServiceFinder {
    private static final Logger LOG = Logger.getInstance(ServiceFinder.class);

    private static final Set<String> SERVICE_ANNOTATIONS = Set.of(
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Component",
            "jakarta.inject.Named",
            "javax.inject.Named"
    );

    private final Project project;
    private final String projectPackagePrefix;

    public ServiceFinder(@NotNull Project project, @NotNull String projectPackagePrefix) {
        this.project = project;
        this.projectPackagePrefix = projectPackagePrefix;
    }

    /**
     * 找到方法中調用的所有 Service
     */
    @NotNull
    public Set<PsiClass> findCalledServices(@NotNull PsiMethod method) {
        Set<PsiClass> services = new LinkedHashSet<>();
        PsiClass containingClass = method.getContainingClass();

        if (containingClass == null) {
            LOG.info("[ServiceFinder] 方法沒有包含類別");
            return services;
        }

        LOG.info("[ServiceFinder] 分析方法: " + method.getName() + " 在類別: " + containingClass.getQualifiedName());

        // 找到類別中所有注入的 Service 欄位
        Map<String, PsiClass> injectedServices = findInjectedServices(containingClass);
        LOG.info("[ServiceFinder] 找到注入的 Service 欄位數量: " + injectedServices.size());

        for (Map.Entry<String, PsiClass> entry : injectedServices.entrySet()) {
            LOG.info("[ServiceFinder] 注入欄位: " + entry.getKey() + " -> " +
                    (entry.getValue().getQualifiedName() != null ? entry.getValue().getQualifiedName() : entry.getValue().getName()));
        }

        // 分析方法體中使用的欄位
        PsiCodeBlock body = method.getBody();
        if (body != null) {
            LOG.info("[ServiceFinder] 開始分析方法體...");
            body.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitReferenceExpression(@NotNull PsiReferenceExpression expression) {
                    super.visitReferenceExpression(expression);

                    PsiElement resolved = expression.resolve();
                    if (resolved instanceof PsiField) {
                        PsiField field = (PsiField) resolved;
                        String fieldName = field.getName();

                        if (injectedServices.containsKey(fieldName)) {
                            PsiClass serviceClass = injectedServices.get(fieldName);
                            services.add(serviceClass);
                            LOG.info("[ServiceFinder] 方法使用了 Service: " + fieldName + " -> " + serviceClass.getQualifiedName());
                        }
                    }
                }
            });
        } else {
            LOG.info("[ServiceFinder] 方法沒有方法體（可能是抽象方法）");
        }

        LOG.info("[ServiceFinder] 最終找到方法調用的 Service 數量: " + services.size());
        return services;
    }

    /**
     * 找到類別中所有注入的 Service 欄位
     */
    @NotNull
    public Map<String, PsiClass> findInjectedServices(@NotNull PsiClass psiClass) {
        Map<String, PsiClass> services = new LinkedHashMap<>();

        LOG.info("[ServiceFinder] 檢查類別欄位: " + psiClass.getQualifiedName() + ", 欄位數量: " + psiClass.getFields().length);

        for (PsiField field : psiClass.getFields()) {
            boolean isInjected = isInjectedField(field);
            LOG.info("[ServiceFinder] 欄位: " + field.getName() + ", 類型: " + field.getType().getCanonicalText() + ", 是否注入: " + isInjected);

            if (isInjected) {
                PsiType type = field.getType();
                if (type instanceof PsiClassType) {
                    PsiClass fieldClass = ((PsiClassType) type).resolve();
                    if (fieldClass != null) {
                        boolean isService = isServiceClass(fieldClass);
                        LOG.info("[ServiceFinder] 欄位類別: " + fieldClass.getQualifiedName() + ", 是否為 Service: " + isService);

                        if (isService) {
                            services.put(field.getName(), fieldClass);
                        }
                    } else {
                        LOG.info("[ServiceFinder] 無法解析欄位類型: " + type.getCanonicalText());
                    }
                }
            }
        }

        return services;
    }

    /**
     * 找到 Service 介面的實作類
     */
    @NotNull
    public Set<PsiClass> findImplementations(@NotNull PsiClass serviceInterface) {
        Set<PsiClass> implementations = new LinkedHashSet<>();

        if (!serviceInterface.isInterface()) {
            return implementations;
        }

        // 如果在 dumb mode，跳過索引搜索，只用命名規則
        if (DumbService.isDumb(project)) {
            LOG.info("[ServiceFinder] 專案在 dumb mode，跳過繼承搜索，使用命名規則");
            return findImplementationsByNaming(serviceInterface);
        }

        // 使用 IntelliJ 的繼承搜索
        try {
            GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
            ClassInheritorsSearch.search(serviceInterface, scope, false)
                    .forEach(impl -> {
                        if (isProjectClass(impl) && !impl.isInterface()) {
                            implementations.add(impl);
                            LOG.info("[ServiceFinder] 找到實作類: " + impl.getQualifiedName());
                        }
                        return true;
                    });
        } catch (Exception e) {
            LOG.warn("[ServiceFinder] 繼承搜索失敗: " + e.getMessage());
        }

        // 如果沒找到，嘗試按命名規則找（XxxSvc -> XxxSvcImpl）
        if (implementations.isEmpty()) {
            implementations.addAll(findImplementationsByNaming(serviceInterface));
        }

        return implementations;
    }

    /**
     * 按命名規則找實作類
     */
    @NotNull
    private Set<PsiClass> findImplementationsByNaming(@NotNull PsiClass serviceInterface) {
        Set<PsiClass> implementations = new LinkedHashSet<>();
        String interfaceName = serviceInterface.getQualifiedName();

        if (interfaceName != null) {
            GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
            String implName = interfaceName + "Impl";
            PsiClass implClass = JavaPsiFacade.getInstance(project).findClass(implName, scope);
            if (implClass != null) {
                implementations.add(implClass);
                LOG.info("[ServiceFinder] 通過命名規則找到實作類: " + implClass.getQualifiedName());
            }
        }

        return implementations;
    }

    /**
     * 判斷欄位是否為注入欄位
     * 支援 @Autowired, @Inject, @Resource 以及 Lombok @RequiredArgsConstructor
     */
    private boolean isInjectedField(@NotNull PsiField field) {
        // 使用共用的工具方法，支援多種注入方式
        return CodeInspectionUtil.isLikelyInjectedField(field);
    }

    /**
     * 判斷是否為 Service 類別
     */
    private boolean isServiceClass(@NotNull PsiClass psiClass) {
        // 檢查是否有 Service 相關注解
        for (String annotation : SERVICE_ANNOTATIONS) {
            if (psiClass.hasAnnotation(annotation)) {
                return true;
            }
        }

        // 檢查是否為介面（Service 介面）
        if (psiClass.isInterface()) {
            String name = psiClass.getName();
            // 常見的 Service 命名模式
            if (name != null && (name.endsWith("Service") || name.endsWith("Svc"))) {
                return true;
            }
        }

        // 檢查實作類
        String name = psiClass.getName();
        if (name != null && (name.endsWith("ServiceImpl") || name.endsWith("SvcImpl"))) {
            return true;
        }

        return false;
    }

    /**
     * 判斷是否為專案內的類別
     */
    private boolean isProjectClass(@NotNull PsiClass psiClass) {
        String qualifiedName = psiClass.getQualifiedName();
        return qualifiedName != null && qualifiedName.startsWith(projectPackagePrefix);
    }
}
