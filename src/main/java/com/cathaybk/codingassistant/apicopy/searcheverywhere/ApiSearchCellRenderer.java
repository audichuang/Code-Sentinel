package com.cathaybk.codingassistant.apicopy.searcheverywhere;

import com.cathaybk.codingassistant.apicopy.model.ApiInfo;
import com.intellij.icons.AllIcons;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.speedSearch.SpeedSearchUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * API 搜尋結果的自訂渲染器
 * 顯示格式：[圖示] MSGID  描述  HTTP方法 路徑
 */
public class ApiSearchCellRenderer extends ColoredListCellRenderer<ApiInfo> {

    @Override
    protected void customizeCellRenderer(@NotNull JList<? extends ApiInfo> list,
                                         ApiInfo value,
                                         int index,
                                         boolean selected,
                                         boolean hasFocus) {
        if (value == null) {
            return;
        }

        // 設定圖示
        setIcon(AllIcons.Nodes.Method);

        // MSGID（主要文字）
        append(value.getMsgId(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES);

        // 描述（次要文字）
        String description = value.getDescription();
        if (description != null && !description.isEmpty()) {
            // 限制描述長度
            if (description.length() > 40) {
                description = description.substring(0, 37) + "...";
            }
            append(description, SimpleTextAttributes.REGULAR_ATTRIBUTES);
            append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES);
        }

        // HTTP 方法（灰色）
        String httpMethod = value.getHttpMethod();
        if (httpMethod != null) {
            append(httpMethod, SimpleTextAttributes.GRAYED_ATTRIBUTES);
            append(" ", SimpleTextAttributes.REGULAR_ATTRIBUTES);
        }

        // 路徑（灰色斜體）
        String path = value.getPath();
        if (path != null && !path.isEmpty()) {
            append(path, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
        }

        // 支援速度搜尋高亮
        SpeedSearchUtil.applySpeedSearchHighlighting(list, this, true, selected);
    }
}
