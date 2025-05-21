package com.cathaybk.codingassistant.vcs;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vcs.CheckinProjectPanel;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.CommitContext;
import com.intellij.openapi.vcs.checkin.CheckinHandler;
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Factory for creating CathayBkCheckinHandler instances.
 */
public class CathayBkCheckinHandlerFactory extends CheckinHandlerFactory {
    private static final Logger LOG = Logger.getInstance(CathayBkCheckinHandlerFactory.class);

    @NotNull
    @Override
    public CheckinHandler createHandler(@NotNull CheckinProjectPanel panel, @NotNull CommitContext commitContext) {
        LOG.info("CathayBk Checkin Handler Factory 被呼叫！項目：" + panel.getProject().getName() +
                ", 變更數量：" + panel.getSelectedChanges().size());

        // Create and return the handler for the commit process
        CathayBkCheckinHandler handler = new CathayBkCheckinHandler(panel);
        LOG.debug("成功創建 CathayBkCheckinHandler");
        return handler;
    }
}