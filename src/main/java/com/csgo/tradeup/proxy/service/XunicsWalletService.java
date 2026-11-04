package com.csgo.tradeup.proxy.service;

import com.csgo.tradeup.proxy.controller.xunics.param.*;
import com.csgo.tradeup.proxy.controller.xunics.resp.*;

public interface XunicsWalletService {
    WalletBalanceInfo balance();
    XunicsPageResult<WalletLedgerInfo> ledger(WalletLedgerQueryParam param);
    XunicsPageResult<WalletHoldInfo> holds(WalletHoldQueryParam param);
}
