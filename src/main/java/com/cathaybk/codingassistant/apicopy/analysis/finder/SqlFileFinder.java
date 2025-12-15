package com.cathaybk.codingassistant.apicopy.analysis.finder;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 檔案追蹤器
 * 用於找到 Service 中使用的 SQL 檔案（支援直接字串和常量引用）
 */
public class SqlFileFinder {
    private static final Logger LOG = Logger.getInstance(SqlFileFinder.class);

    // 策略 1: 直接解析 .sqlFile("xxx") 字串
    private static final Pattern SQL_FILE_PATTERN =
            Pattern.compile("\\.sqlFile\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)");

    // 策略 2: 解析常量引用 .sqlFile(SQL_CONSTANT)
    private static final Pattern SQL_CONSTANT_PATTERN =
            Pattern.compile("\\.sqlFile\\s*\\(\\s*([A-Z][A-Z0-9_]*)\\s*\\)");

    // 策略 3: 其他可能的 SQL 路徑模式
    private static final Pattern SQL_PATH_PATTERN =
            Pattern.compile("[\"']([^\"']*\\.sql)[\"']");

    private final Project project;

    public SqlFileFinder(@NotNull Project project) {
        this.project = project;
    }

    /**
     * 找到類別中引用的所有 SQL 檔案路徑
     */
    @NotNull
    public Set<String> findSqlFiles(@NotNull PsiClass serviceClass) {
        Set<String> sqlPaths = new LinkedHashSet<>();

        // 首先掃描靜態常量欄位，建立常量映射
        Map<String, String> sqlConstants = scanSqlConstants(serviceClass);

        // 取得類別的原始碼文字
        String classText = serviceClass.getText();

        // 策略 1: 尋找直接的 SQL 檔案字串
        Matcher directMatcher = SQL_FILE_PATTERN.matcher(classText);
        while (directMatcher.find()) {
            String sqlPath = directMatcher.group(1);
            sqlPaths.add(sqlPath);
            LOG.debug("找到直接 SQL 路徑: " + sqlPath);
        }

        // 策略 2: 尋找常量引用
        Matcher constantMatcher = SQL_CONSTANT_PATTERN.matcher(classText);
        while (constantMatcher.find()) {
            String constantName = constantMatcher.group(1);
            String sqlPath = sqlConstants.get(constantName);
            if (sqlPath != null) {
                sqlPaths.add(sqlPath);
                LOG.debug("找到常量 SQL 路徑: " + constantName + " -> " + sqlPath);
            }
        }

        // 策略 3: 尋找其他 SQL 路徑模式（作為備用）
        if (sqlPaths.isEmpty()) {
            Matcher pathMatcher = SQL_PATH_PATTERN.matcher(classText);
            while (pathMatcher.find()) {
                String sqlPath = pathMatcher.group(1);
                // 只添加看起來像資源路徑的
                if (sqlPath.contains("/") && !sqlPath.startsWith("http")) {
                    sqlPaths.add(sqlPath);
                    LOG.debug("找到通用 SQL 路徑: " + sqlPath);
                }
            }
        }

        LOG.debug("共找到 SQL 檔案數量: " + sqlPaths.size());
        return sqlPaths;
    }

    /**
     * 掃描類別中的 SQL 常量定義
     * 例如: private static final String SQL_XXX = "oracle/xxx.sql"
     */
    @NotNull
    private Map<String, String> scanSqlConstants(@NotNull PsiClass psiClass) {
        Map<String, String> constants = new HashMap<>();

        for (PsiField field : psiClass.getFields()) {
            // 只處理 static final String 欄位
            if (field.hasModifierProperty(PsiModifier.STATIC) &&
                field.hasModifierProperty(PsiModifier.FINAL) &&
                field.getType().equalsToText("java.lang.String")) {

                String fieldName = field.getName();
                if (fieldName == null) continue;

                PsiExpression initializer = field.getInitializer();
                if (initializer instanceof PsiLiteralExpression) {
                    Object value = ((PsiLiteralExpression) initializer).getValue();
                    if (value instanceof String) {
                        String strValue = (String) value;
                        // 檢查是否為 SQL 檔案路徑
                        if (strValue.endsWith(".sql")) {
                            constants.put(fieldName, strValue);
                            LOG.debug("找到 SQL 常量: " + fieldName + " = " + strValue);
                        }
                    }
                }
            }
        }

        return constants;
    }

    /**
     * 解析 SQL 檔案路徑為 PsiFile
     */
    @Nullable
    public PsiFile resolveSqlFile(@NotNull String sqlPath) {
        // 嘗試多種資源路徑
        List<String> possiblePaths = new ArrayList<>();
        possiblePaths.add(sqlPath);

        // 如果路徑不以 resources/ 開頭，嘗試添加前綴
        if (!sqlPath.startsWith("resources/") && !sqlPath.startsWith("/")) {
            possiblePaths.add("sql/" + sqlPath);
            possiblePaths.add("resources/sql/" + sqlPath);
        }

        // 從檔案名稱搜索
        String fileName = sqlPath.substring(sqlPath.lastIndexOf('/') + 1);
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

        Collection<VirtualFile> files = FilenameIndex.getVirtualFilesByName(
                fileName, scope);

        for (VirtualFile vf : files) {
            String path = vf.getPath();
            // 檢查路徑是否匹配
            for (String possiblePath : possiblePaths) {
                if (path.endsWith(possiblePath)) {
                    PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                    if (psiFile != null) {
                        LOG.debug("解析 SQL 檔案成功: " + path);
                        return psiFile;
                    }
                }
            }
        }

        // 如果精確匹配失敗，返回第一個匹配的檔案
        if (!files.isEmpty()) {
            VirtualFile firstFile = files.iterator().next();
            PsiFile psiFile = PsiManager.getInstance(project).findFile(firstFile);
            if (psiFile != null) {
                LOG.debug("解析 SQL 檔案（模糊匹配）: " + firstFile.getPath());
                return psiFile;
            }
        }

        LOG.warn("無法解析 SQL 檔案: " + sqlPath);
        return null;
    }
}
