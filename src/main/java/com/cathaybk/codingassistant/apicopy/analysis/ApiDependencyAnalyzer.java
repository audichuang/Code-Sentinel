package com.cathaybk.codingassistant.apicopy.analysis;

import com.cathaybk.codingassistant.apicopy.analysis.finder.*;
import com.cathaybk.codingassistant.apicopy.model.ApiFileType;
import com.cathaybk.codingassistant.apicopy.model.ApiInfo;
import com.cathaybk.codingassistant.apicopy.model.DependencyGraph;
import com.cathaybk.codingassistant.apicopy.model.DependencyGraph.DependencyNode;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * API 依賴分析器
 * 協調各個 Finder 來構建完整的依賴圖
 */
public class ApiDependencyAnalyzer {
    private static final Logger LOG = Logger.getInstance(ApiDependencyAnalyzer.class);

    // 效能限制
    private static final int MAX_DEPTH = 5;
    private static final int MAX_NODES = 500;

    private final Project project;
    private final String projectPackagePrefix;

    // 子分析器 (非 final 以便重新初始化)
    private ServiceFinder serviceFinder;
    private DtoFinder dtoFinder;
    private RepositoryFinder repositoryFinder;
    private EntityFinder entityFinder;
    private final SqlFileFinder sqlFileFinder;
    private final GenericTypeResolver typeResolver;

    // 防止循環依賴
    private final Set<String> visitedClasses = new HashSet<>();
    private int nodeCount = 0;

    public ApiDependencyAnalyzer(@NotNull Project project, @NotNull String projectPackagePrefix) {
        this.project = project;
        this.projectPackagePrefix = projectPackagePrefix;

        this.typeResolver = new GenericTypeResolver();
        this.serviceFinder = new ServiceFinder(project, projectPackagePrefix);
        this.dtoFinder = new DtoFinder(project, projectPackagePrefix, typeResolver);
        this.repositoryFinder = new RepositoryFinder(project, projectPackagePrefix);
        this.entityFinder = new EntityFinder(project, projectPackagePrefix);
        this.sqlFileFinder = new SqlFileFinder(project);
    }

    /**
     * 分析 Controller 方法的完整依賴圖
     *
     * @param apiInfo API 資訊
     * @return 依賴圖，如果分析失敗則返回 null
     */
    @Nullable
    public DependencyGraph analyze(@NotNull ApiInfo apiInfo) {
        return ReadAction.compute(() -> {
            try {
                // 重置狀態
                reset();

                PsiMethod method = apiInfo.getMethod();
                PsiClass controller = apiInfo.getController();

                if (method == null || controller == null || !method.isValid() || !controller.isValid()) {
                    LOG.warn("API 方法或控制器無效: " + apiInfo.getMsgId());
                    return null;
                }

                // 自動推斷專案套件前綴
                String detectedPrefix = detectPackagePrefix(controller);
                LOG.info("偵測到的套件前綴: " + detectedPrefix);

                // 使用偵測到的前綴重新初始化 Finders
                reinitializeFinders(detectedPrefix);

                DependencyGraph graph = new DependencyGraph(apiInfo);

                // 1. 添加 Controller
                addClassNode(graph, controller, ApiFileType.CONTROLLER);

                // 2. 分析方法的 DTO（請求和回應）
                analyzeMethodDtos(graph, method);

                // 3. 分析 Service 依賴
                analyzeServiceDependencies(graph, method);

                LOG.info("完成依賴分析: " + apiInfo.getMsgId() + ", 節點數: " + graph.getTotalFileCount());
                return graph;

            } catch (Exception e) {
                LOG.error("分析依賴時發生錯誤: " + apiInfo.getMsgId(), e);
                return null;
            }
        });
    }

    /**
     * 從 Controller 類別推斷專案套件前綴
     */
    @NotNull
    private String detectPackagePrefix(@NotNull PsiClass controller) {
        String qName = controller.getQualifiedName();
        if (qName == null) {
            return projectPackagePrefix;
        }

        // 從完整類名提取套件前綴（取前三層或到 controller/service/dto 之前）
        String[] parts = qName.split("\\.");
        StringBuilder prefix = new StringBuilder();

        for (int i = 0; i < parts.length && i < 4; i++) {
            String part = parts[i].toLowerCase();
            // 停止在常見的子套件名稱
            if (part.equals("controller") || part.equals("service") ||
                part.equals("dto") || part.equals("repository") ||
                part.equals("entity") || part.equals("api") ||
                part.equals("web") || part.equals("rest")) {
                break;
            }
            if (prefix.length() > 0) {
                prefix.append(".");
            }
            prefix.append(parts[i]);
        }

        String result = prefix.toString();
        return result.isEmpty() ? projectPackagePrefix : result;
    }

    /**
     * 分析方法的 DTO 類型
     */
    private void analyzeMethodDtos(@NotNull DependencyGraph graph, @NotNull PsiMethod method) {
        checkProgress();
        LOG.debug("[ApiDependencyAnalyzer] 開始分析方法的 DTO: " + method.getName());

        // 分析請求參數
        Set<PsiClass> requestDtos = dtoFinder.findRequestDtos(method);
        LOG.debug("[ApiDependencyAnalyzer] 找到 Request DTO 數量: " + requestDtos.size());
        for (PsiClass dto : requestDtos) {
            LOG.debug("[ApiDependencyAnalyzer] Request DTO: " + dto.getQualifiedName());
            if (addClassNode(graph, dto, ApiFileType.REQUEST_DTO)) {
                analyzeNestedDtos(graph, dto);
            }
        }

        // 分析回應類型
        Set<PsiClass> responseDtos = dtoFinder.findResponseDtos(method);
        LOG.debug("[ApiDependencyAnalyzer] 找到 Response DTO 數量: " + responseDtos.size());
        for (PsiClass dto : responseDtos) {
            LOG.debug("[ApiDependencyAnalyzer] Response DTO: " + dto.getQualifiedName());
            if (addClassNode(graph, dto, ApiFileType.RESPONSE_DTO)) {
                analyzeNestedDtos(graph, dto);
            }
        }
    }

    /**
     * 分析嵌套的 DTO
     */
    private void analyzeNestedDtos(@NotNull DependencyGraph graph, @NotNull PsiClass dto) {
        if (!checkLimits()) return;
        checkProgress();

        Set<PsiClass> nestedDtos = dtoFinder.findNestedDtos(dto);
        for (PsiClass nested : nestedDtos) {
            if (addClassNode(graph, nested, ApiFileType.NESTED_DTO)) {
                analyzeNestedDtos(graph, nested);
            }
        }
    }

    /**
     * 分析 Service 依賴
     */
    private void analyzeServiceDependencies(@NotNull DependencyGraph graph, @NotNull PsiMethod controllerMethod) {
        checkProgress();
        LOG.debug("[ApiDependencyAnalyzer] 開始分析 Service 依賴: " + controllerMethod.getName());

        // 找到方法調用的 Service
        Set<PsiClass> services = serviceFinder.findCalledServices(controllerMethod);
        LOG.debug("[ApiDependencyAnalyzer] 找到 Service 數量: " + services.size());

        for (PsiClass service : services) {
            if (!checkLimits()) return;
            checkProgress();

            LOG.debug("[ApiDependencyAnalyzer] 處理 Service: " + service.getQualifiedName() + ", isInterface: " + service.isInterface());

            // 判斷是介面還是實作類
            ApiFileType serviceType = service.isInterface()
                    ? ApiFileType.SERVICE_INTERFACE
                    : ApiFileType.SERVICE_IMPL;

            if (addClassNode(graph, service, serviceType)) {
                // 如果是介面，找實作類
                if (service.isInterface()) {
                    Set<PsiClass> impls = serviceFinder.findImplementations(service);
                    LOG.debug("[ApiDependencyAnalyzer] 找到 Service 實作類數量: " + impls.size());
                    for (PsiClass impl : impls) {
                        LOG.debug("[ApiDependencyAnalyzer] Service 實作: " + impl.getQualifiedName());
                        if (addClassNode(graph, impl, ApiFileType.SERVICE_IMPL)) {
                            analyzeServiceImpl(graph, impl);
                        }
                    }
                } else {
                    analyzeServiceImpl(graph, service);
                }
            }
        }
    }

    /**
     * 分析 Service 實作類
     */
    private void analyzeServiceImpl(@NotNull DependencyGraph graph, @NotNull PsiClass serviceImpl) {
        analyzeServiceImpl(graph, serviceImpl, false);
    }

    /**
     * 分析 Service 實作類（內部方法）
     * @param isRecursive 是否為遞迴依賴（從其他 Service 注入的）
     */
    private void analyzeServiceImpl(@NotNull DependencyGraph graph, @NotNull PsiClass serviceImpl, boolean isRecursive) {
        if (!checkLimits()) return;
        checkProgress();

        // 1. 分析 SQL 檔案
        Set<String> sqlPaths = sqlFileFinder.findSqlFiles(serviceImpl);
        for (String sqlPath : sqlPaths) {
            PsiFile sqlFile = sqlFileFinder.resolveSqlFile(sqlPath);
            if (sqlFile != null) {
                if (isRecursive) {
                    addRecursiveSqlNode(graph, sqlFile);
                } else {
                    addSqlNode(graph, sqlFile);
                }
            }
        }

        // 2. 分析 Repository 依賴
        Set<PsiClass> repositories = repositoryFinder.findUsedRepositories(serviceImpl);
        for (PsiClass repository : repositories) {
            if (!checkLimits()) return;

            if (isRecursive) {
                if (addRecursiveClassNode(graph, repository, ApiFileType.REPOSITORY)) {
                    analyzeRepository(graph, repository, true);
                }
            } else {
                if (addClassNode(graph, repository, ApiFileType.REPOSITORY)) {
                    analyzeRepository(graph, repository, false);
                }
            }
        }

        // 3. 分析注入的 Service 依賴（遞迴）
        Map<String, PsiClass> injectedServices = serviceFinder.findInjectedServices(serviceImpl);
        LOG.debug("[ApiDependencyAnalyzer] Service " + serviceImpl.getName() + " 注入的 Service 數量: " + injectedServices.size());

        for (PsiClass injectedService : injectedServices.values()) {
            if (!checkLimits()) return;
            checkProgress();

            String qName = injectedService.getQualifiedName();
            if (qName == null || visitedClasses.contains(qName)) {
                continue;  // 避免循環依賴
            }

            LOG.debug("[ApiDependencyAnalyzer] 分析注入的 Service: " + qName);

            // 判斷是介面還是實作類
            ApiFileType serviceType = injectedService.isInterface()
                    ? ApiFileType.SERVICE_INTERFACE
                    : ApiFileType.SERVICE_IMPL;

            // 注入的 Service 及其依賴都是遞迴依賴
            if (addRecursiveClassNode(graph, injectedService, serviceType)) {
                // 如果是介面，找實作類
                if (injectedService.isInterface()) {
                    Set<PsiClass> impls = serviceFinder.findImplementations(injectedService);
                    LOG.debug("[ApiDependencyAnalyzer] 找到 " + injectedService.getName() + " 的實作類數量: " + impls.size());
                    for (PsiClass impl : impls) {
                        if (addRecursiveClassNode(graph, impl, ApiFileType.SERVICE_IMPL)) {
                            analyzeServiceImpl(graph, impl, true);  // 遞迴分析實作類
                        }
                    }
                } else {
                    analyzeServiceImpl(graph, injectedService, true);  // 遞迴分析
                }
            }
        }
    }

    /**
     * 分析 Repository
     */
    private void analyzeRepository(@NotNull DependencyGraph graph, @NotNull PsiClass repository, boolean isRecursive) {
        if (!checkLimits()) return;
        checkProgress();

        // 找到 Repository 使用的 Entity
        Set<PsiClass> entities = entityFinder.findEntitiesFromRepository(repository);
        for (PsiClass entity : entities) {
            if (isRecursive) {
                if (addRecursiveClassNode(graph, entity, ApiFileType.ENTITY)) {
                    analyzeEntity(graph, entity, true);
                }
            } else {
                if (addClassNode(graph, entity, ApiFileType.ENTITY)) {
                    analyzeEntity(graph, entity, false);
                }
            }
        }
    }

    /**
     * 分析 Entity
     */
    private void analyzeEntity(@NotNull DependencyGraph graph, @NotNull PsiClass entity, boolean isRecursive) {
        if (!checkLimits()) return;
        checkProgress();

        // 找到複合主鍵
        Set<PsiClass> compositePKs = entityFinder.findCompositePK(entity);
        for (PsiClass pk : compositePKs) {
            if (isRecursive) {
                addRecursiveClassNode(graph, pk, ApiFileType.COMPOSITE_PK);
            } else {
                addClassNode(graph, pk, ApiFileType.COMPOSITE_PK);
            }
        }
    }

    /**
     * 添加類別節點到圖中
     *
     * @return 如果成功添加則返回 true，如果已存在或無效則返回 false
     */
    private boolean addClassNode(@NotNull DependencyGraph graph,
                                  @NotNull PsiClass psiClass,
                                  @NotNull ApiFileType fileType) {
        return addClassNode(graph, psiClass, fileType, false);
    }

    /**
     * 添加遞迴依賴的類別節點到圖中
     *
     * @return 如果成功添加則返回 true，如果已存在或無效則返回 false
     */
    private boolean addRecursiveClassNode(@NotNull DependencyGraph graph,
                                           @NotNull PsiClass psiClass,
                                           @NotNull ApiFileType fileType) {
        return addClassNode(graph, psiClass, fileType, true);
    }

    /**
     * 添加類別節點到圖中（內部方法）
     *
     * @param isRecursive 是否為遞迴依賴
     * @return 如果成功添加則返回 true，如果已存在或無效則返回 false
     */
    private boolean addClassNode(@NotNull DependencyGraph graph,
                                  @NotNull PsiClass psiClass,
                                  @NotNull ApiFileType fileType,
                                  boolean isRecursive) {
        String qName = psiClass.getQualifiedName();
        if (qName == null || visitedClasses.contains(qName)) {
            return false;
        }

        PsiFile file = psiClass.getContainingFile();
        if (file == null) {
            return false;
        }

        visitedClasses.add(qName);
        nodeCount++;

        DependencyNode node = new DependencyNode(fileType, file, psiClass);
        if (isRecursive) {
            graph.addRecursiveNode(node);
        } else {
            graph.addNode(node);
        }

        LOG.debug("添加節點: [" + fileType + "] " + qName + (isRecursive ? " (遞迴)" : ""));
        return true;
    }

    /**
     * 添加 SQL 檔案節點
     */
    private void addSqlNode(@NotNull DependencyGraph graph, @NotNull PsiFile sqlFile) {
        addSqlNode(graph, sqlFile, false);
    }

    /**
     * 添加遞迴依賴的 SQL 檔案節點
     */
    private void addRecursiveSqlNode(@NotNull DependencyGraph graph, @NotNull PsiFile sqlFile) {
        addSqlNode(graph, sqlFile, true);
    }

    /**
     * 添加 SQL 檔案節點（內部方法）
     */
    private void addSqlNode(@NotNull DependencyGraph graph, @NotNull PsiFile sqlFile, boolean isRecursive) {
        String path = sqlFile.getVirtualFile() != null
                ? sqlFile.getVirtualFile().getPath()
                : sqlFile.getName();

        if (visitedClasses.contains(path)) {
            return;
        }

        visitedClasses.add(path);
        nodeCount++;

        DependencyNode node = DependencyNode.createSqlNode(path, sqlFile);
        if (isRecursive) {
            graph.addRecursiveNode(node);
        } else {
            graph.addNode(node);
        }

        LOG.debug("添加 SQL 節點: " + path + (isRecursive ? " (遞迴)" : ""));
    }

    /**
     * 檢查是否超過限制
     */
    private boolean checkLimits() {
        if (nodeCount >= MAX_NODES) {
            LOG.warn("達到最大節點數限制: " + MAX_NODES);
            return false;
        }
        return true;
    }

    /**
     * 檢查進度並允許取消
     */
    private void checkProgress() {
        ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
        if (indicator != null) {
            indicator.checkCanceled();
        }
    }

    /**
     * 重置分析器狀態
     */
    private void reset() {
        visitedClasses.clear();
        nodeCount = 0;
        typeResolver.reset();
    }

    /**
     * 使用新的套件前綴重新初始化 Finders
     */
    private void reinitializeFinders(@NotNull String packagePrefix) {
        this.serviceFinder = new ServiceFinder(project, packagePrefix);
        this.dtoFinder = new DtoFinder(project, packagePrefix, typeResolver);
        this.repositoryFinder = new RepositoryFinder(project, packagePrefix);
        this.entityFinder = new EntityFinder(project, packagePrefix);
        LOG.debug("Finders 已使用套件前綴重新初始化: " + packagePrefix);
    }

    /**
     * 取得專案套件前綴
     */
    @NotNull
    public String getProjectPackagePrefix() {
        return projectPackagePrefix;
    }
}
