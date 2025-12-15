package com.cathaybk.codingassistant.apicopy.ui;

import com.cathaybk.codingassistant.apicopy.model.ApiInfo;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * API 列表表格模型
 */
public class ApiListTableModel extends AbstractTableModel {

    private static final String[] COLUMN_NAMES = {"MSGID", "描述", "HTTP 方法", "路徑"};
    private static final int COL_MSG_ID = 0;
    private static final int COL_DESCRIPTION = 1;
    private static final int COL_HTTP_METHOD = 2;
    private static final int COL_PATH = 3;

    private final List<ApiInfo> apis = new ArrayList<>();

    @Override
    public int getRowCount() {
        return apis.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMN_NAMES.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMN_NAMES[column];
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return String.class;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        if (rowIndex < 0 || rowIndex >= apis.size()) {
            return "";
        }

        ApiInfo api = apis.get(rowIndex);
        switch (columnIndex) {
            case COL_MSG_ID:
                return api.getMsgId();
            case COL_DESCRIPTION:
                String desc = api.getDescription();
                return desc != null ? truncate(desc, 50) : "";
            case COL_HTTP_METHOD:
                return api.getHttpMethod() != null ? api.getHttpMethod() : "";
            case COL_PATH:
                return api.getPath() != null ? api.getPath() : "";
            default:
                return "";
        }
    }

    /**
     * 取得指定行的 API
     */
    public ApiInfo getApiAt(int rowIndex) {
        if (rowIndex >= 0 && rowIndex < apis.size()) {
            return apis.get(rowIndex);
        }
        return null;
    }

    /**
     * 設定 API 列表
     */
    public void setApis(List<ApiInfo> newApis) {
        apis.clear();
        if (newApis != null) {
            apis.addAll(newApis);
        }
        fireTableDataChanged();
    }

    /**
     * 清空列表
     */
    public void clear() {
        apis.clear();
        fireTableDataChanged();
    }

    /**
     * 取得 API 數量
     */
    public int getApiCount() {
        return apis.size();
    }

    /**
     * 截斷過長的文字
     */
    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }
}
