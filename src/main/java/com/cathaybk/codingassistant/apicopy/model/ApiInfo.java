package com.cathaybk.codingassistant.apicopy.model;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * API 資訊類別
 * 儲存 API 的基本資訊，包括 MSGID、描述、路徑等
 */
public class ApiInfo {
    private final String msgId;
    private final String description;
    private final String httpMethod;
    private final String path;
    private final SmartPsiElementPointer<PsiMethod> methodPointer;
    private final SmartPsiElementPointer<PsiClass> controllerPointer;

    public ApiInfo(@NotNull String msgId,
                   @Nullable String description,
                   @Nullable String httpMethod,
                   @Nullable String path,
                   @NotNull PsiMethod method,
                   @NotNull PsiClass controller) {
        this.msgId = msgId;
        this.description = description;
        this.httpMethod = httpMethod;
        this.path = path;
        this.methodPointer = SmartPointerManager.getInstance(method.getProject())
                .createSmartPsiElementPointer(method);
        this.controllerPointer = SmartPointerManager.getInstance(controller.getProject())
                .createSmartPsiElementPointer(controller);
    }

    @NotNull
    public String getMsgId() {
        return msgId;
    }

    @Nullable
    public String getDescription() {
        return description;
    }

    @Nullable
    public String getHttpMethod() {
        return httpMethod;
    }

    @Nullable
    public String getPath() {
        return path;
    }

    @Nullable
    public PsiMethod getMethod() {
        return methodPointer.getElement();
    }

    @Nullable
    public PsiClass getController() {
        return controllerPointer.getElement();
    }

    /**
     * 檢查此 API 資訊是否仍然有效
     */
    public boolean isValid() {
        PsiMethod method = methodPointer.getElement();
        PsiClass controller = controllerPointer.getElement();
        return method != null && method.isValid() &&
               controller != null && controller.isValid();
    }

    /**
     * 取得顯示名稱（用於 UI）
     */
    @NotNull
    public String getDisplayName() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(msgId).append("] ");
        if (description != null && !description.isEmpty()) {
            sb.append(description);
        } else {
            PsiMethod method = methodPointer.getElement();
            if (method != null) {
                sb.append(method.getName());
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "ApiInfo{" +
                "msgId='" + msgId + '\'' +
                ", description='" + description + '\'' +
                ", httpMethod='" + httpMethod + '\'' +
                ", path='" + path + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ApiInfo apiInfo = (ApiInfo) o;
        return msgId.equals(apiInfo.msgId);
    }

    @Override
    public int hashCode() {
        return msgId.hashCode();
    }
}
