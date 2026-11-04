package com.csgo.tradeup.proxy.service.impl;

import com.csgo.tradeup.proxy.controller.xunics.param.*;
import com.csgo.tradeup.proxy.controller.xunics.resp.*;

import com.csgo.tradeup.proxy.service.XunicsWalletService;
import com.csgo.tradeup.proxy.integration.xunics.XunicsClient;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;
import javax.annotation.Resource;

@Service
public class XunicsWalletServiceImpl implements XunicsWalletService {
    @Resource
    private XunicsClient client;

    @Override
    public WalletBalanceInfo balance() {
        return client.get("/v1/wallets/me", null, new TypeReference<WalletBalanceInfo>() { });
    }

    @Override
    public XunicsPageResult<WalletLedgerInfo> ledger(WalletLedgerQueryParam param) {
        return client.get("/v1/wallets/me/ledger", param, new TypeReference<XunicsPageResult<WalletLedgerInfo>>() { });
    }

    @Override
    public XunicsPageResult<WalletHoldInfo> holds(WalletHoldQueryParam param) {
        return client.get("/v1/wallets/me/holds", param, new TypeReference<XunicsPageResult<WalletHoldInfo>>() { });
    }

}
