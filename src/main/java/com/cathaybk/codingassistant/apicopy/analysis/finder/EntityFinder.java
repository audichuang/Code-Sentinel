package com.cathaybk.codingassistant.apicopy.analysis.finder;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Entity 追蹤器
 * 用於找到 Repository 使用的 Entity 和複合主鍵
 */
public class EntityFinder {
    private static final Logger LOG = Logger.getInstance(EntityFinder.class);

    private static final Set<String> ENTITY_ANNOTATIONS = Set.of(
            "jakarta.persistence.Entity",
            "javax.persistence.Entity"
    );

    private static final String ID_CLASS_ANNOTATION = "jakarta.persistence.IdClass";
    private static final String ID_CLASS_ANNOTATION_JAVAX = "javax.persistence.IdClass";
    private static final String EMBEDDED_ID_ANNOTATION = "jakarta.persistence.EmbeddedId";
    private static final String EMBEDDED_ID_ANNOTATION_JAVAX = "javax.persistence.EmbeddedId";

    private final Project project;
    private final String projectPackagePrefix;

    public EntityFinder(@NotNull Project project, @NotNull String projectPackagePrefix) {
        this.project = project;
        this.projectPackagePrefix = projectPackagePrefix;
    }

    /**
     * 從 Repository 找到使用的 Entity
     * Repository 通常繼承自 JpaRepository<Entity, ID>
     */
    @NotNull
    public Set<PsiClass> findEntitiesFromRepository(@NotNull PsiClass repository) {
        Set<PsiClass> entities = new LinkedHashSet<>();

        if (!repository.isInterface()) {
            return entities;
        }

        // 檢查繼承的泛型介面
        for (PsiClassType superType : repository.getSuperTypes()) {
            PsiClass superClass = superType.resolve();
            if (superClass != null) {
                String qName = superClass.getQualifiedName();
                if (qName != null && isSpringDataRepository(qName)) {
                    // 提取第一個泛型參數（Entity 類型）
                    PsiType[] typeParams = superType.getParameters();
                    if (typeParams.length > 0) {
                        PsiClass entityClass = PsiUtil.resolveClassInType(typeParams[0]);
                        if (entityClass != null && isEntityClass(entityClass)) {
                            entities.add(entityClass);
                        }
                    }
                }
            }
        }

        // 如果沒找到，嘗試從方法返回類型推斷
        if (entities.isEmpty()) {
            for (PsiMethod method : repository.getMethods()) {
                PsiType returnType = method.getReturnType();
                if (returnType != null) {
                    PsiClass returnClass = PsiUtil.resolveClassInType(returnType);
                    if (returnClass != null && isEntityClass(returnClass)) {
                        entities.add(returnClass);
                    }
                }
            }
        }

        LOG.debug("從 Repository 找到 Entity 數量: " + entities.size());
        return entities;
    }

    /**
     * 找到 Entity 的複合主鍵
     */
    @NotNull
    public Set<PsiClass> findCompositePK(@NotNull PsiClass entity) {
        Set<PsiClass> pks = new LinkedHashSet<>();

        // 方式 1: @IdClass(XxxPK.class)
        PsiAnnotation idClassAnnotation = entity.getAnnotation(ID_CLASS_ANNOTATION);
        if (idClassAnnotation == null) {
            idClassAnnotation = entity.getAnnotation(ID_CLASS_ANNOTATION_JAVAX);
        }

        if (idClassAnnotation != null) {
            PsiAnnotationMemberValue value = idClassAnnotation.findAttributeValue("value");
            if (value instanceof PsiClassObjectAccessExpression) {
                PsiType type = ((PsiClassObjectAccessExpression) value).getOperand().getType();
                PsiClass pkClass = PsiUtil.resolveClassInType(type);
                if (pkClass != null && isProjectClass(pkClass)) {
                    pks.add(pkClass);
                    LOG.debug("找到 @IdClass 複合主鍵: " + pkClass.getQualifiedName());
                }
            }
        }

        // 方式 2: @EmbeddedId 欄位
        for (PsiField field : entity.getFields()) {
            boolean hasEmbeddedId = field.hasAnnotation(EMBEDDED_ID_ANNOTATION) ||
                                    field.hasAnnotation(EMBEDDED_ID_ANNOTATION_JAVAX);

            if (hasEmbeddedId) {
                PsiType type = field.getType();
                PsiClass pkClass = PsiUtil.resolveClassInType(type);
                if (pkClass != null && isProjectClass(pkClass)) {
                    pks.add(pkClass);
                    LOG.debug("找到 @EmbeddedId 複合主鍵: " + pkClass.getQualifiedName());
                }
            }
        }

        return pks;
    }

    /**
     * 判斷是否為 Spring Data Repository 介面
     */
    private boolean isSpringDataRepository(@NotNull String qualifiedName) {
        return qualifiedName.equals("org.springframework.data.repository.Repository") ||
               qualifiedName.equals("org.springframework.data.jpa.repository.JpaRepository") ||
               qualifiedName.equals("org.springframework.data.repository.CrudRepository") ||
               qualifiedName.equals("org.springframework.data.repository.PagingAndSortingRepository");
    }

    /**
     * 判斷是否為 Entity 類別
     */
    private boolean isEntityClass(@NotNull PsiClass psiClass) {
        for (String annotation : ENTITY_ANNOTATIONS) {
            if (psiClass.hasAnnotation(annotation)) {
                return true;
            }
        }

        // 檢查命名規則
        String name = psiClass.getName();
        if (name != null && name.endsWith("Entity")) {
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
