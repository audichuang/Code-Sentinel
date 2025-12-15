package com.cathaybk.codingassistant.apicopy.analysis.finder;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Repository 追蹤器
 * 用於找到 Service 中使用的 Repository
 */
public class RepositoryFinder {
    private static final Logger LOG = Logger.getInstance(RepositoryFinder.class);

    private static final Set<String> REPOSITORY_ANNOTATIONS = Set.of(
            "org.springframework.stereotype.Repository"
    );

    private static final Set<String> REPOSITORY_INTERFACES = Set.of(
            "org.springframework.data.repository.Repository",
            "org.springframework.data.jpa.repository.JpaRepository",
            "org.springframework.data.repository.CrudRepository",
            "org.springframework.data.repository.PagingAndSortingRepository"
    );

    private static final Set<String> INJECT_ANNOTATIONS = Set.of(
            "org.springframework.beans.factory.annotation.Autowired",
            "jakarta.inject.Inject",
            "javax.inject.Inject",
            "jakarta.annotation.Resource",
            "javax.annotation.Resource"
    );

    private final Project project;
    private final String projectPackagePrefix;

    public RepositoryFinder(@NotNull Project project, @NotNull String projectPackagePrefix) {
        this.project = project;
        this.projectPackagePrefix = projectPackagePrefix;
    }

    /**
     * 找到類別中使用的所有 Repository
     */
    @NotNull
    public Set<PsiClass> findUsedRepositories(@NotNull PsiClass serviceClass) {
        Set<PsiClass> repositories = new LinkedHashSet<>();

        // 找到所有注入的 Repository 欄位
        for (PsiField field : serviceClass.getFields()) {
            if (isInjectedField(field)) {
                PsiType type = field.getType();
                if (type instanceof PsiClassType) {
                    PsiClass fieldClass = ((PsiClassType) type).resolve();
                    if (fieldClass != null && isRepositoryClass(fieldClass)) {
                        repositories.add(fieldClass);
                    }
                }
            }
        }

        // 檢查建構函數參數（支援建構函數注入）
        for (PsiMethod constructor : serviceClass.getConstructors()) {
            for (PsiParameter parameter : constructor.getParameterList().getParameters()) {
                PsiType type = parameter.getType();
                if (type instanceof PsiClassType) {
                    PsiClass paramClass = ((PsiClassType) type).resolve();
                    if (paramClass != null && isRepositoryClass(paramClass)) {
                        repositories.add(paramClass);
                    }
                }
            }
        }

        LOG.debug("找到 Repository 數量: " + repositories.size());
        return repositories;
    }

    /**
     * 判斷是否為注入欄位
     */
    private boolean isInjectedField(@NotNull PsiField field) {
        for (String annotation : INJECT_ANNOTATIONS) {
            if (field.hasAnnotation(annotation)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判斷是否為 Repository 類別
     */
    private boolean isRepositoryClass(@NotNull PsiClass psiClass) {
        // 檢查是否有 @Repository 注解
        for (String annotation : REPOSITORY_ANNOTATIONS) {
            if (psiClass.hasAnnotation(annotation)) {
                return true;
            }
        }

        // 檢查是否繼承自 Spring Data Repository 介面
        if (psiClass.isInterface()) {
            for (PsiClassType superType : psiClass.getSuperTypes()) {
                PsiClass superClass = superType.resolve();
                if (superClass != null) {
                    String qName = superClass.getQualifiedName();
                    if (qName != null && REPOSITORY_INTERFACES.contains(qName)) {
                        return true;
                    }
                    // 遞迴檢查父介面
                    if (isRepositoryClass(superClass)) {
                        return true;
                    }
                }
            }
        }

        // 檢查命名規則
        String name = psiClass.getName();
        if (name != null && (name.endsWith("Repository") || name.endsWith("Dao"))) {
            return isProjectClass(psiClass);
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
