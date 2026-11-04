package com.csgo.tradeup.proxy.controller.xunics;

import com.csgo.tradeup.proxy.controller.xunics.param.*;
import com.csgo.tradeup.proxy.controller.xunics.resp.*;

import com.csgo.tradeup.proxy.controller.template.ResultHelper;
import com.csgo.tradeup.proxy.controller.template.ResultInfo;
import com.csgo.tradeup.proxy.service.XunicsWalletService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import javax.validation.Valid;

@RestController
@RequestMapping("/api/public/v1/wallets/me")
@Api(tags = "Xunics-钱包")
public class XunicsWalletController extends ResultHelper {
    @Resource
    private XunicsWalletService service;

    @ApiOperation("查询钱包余额")
    @GetMapping("")
    public ResultInfo<WalletBalanceInfo> balance() {
        return successResultInfo(service.balance());
    }

    @ApiOperation("查询资金流水")
    @GetMapping("/ledger")
    public ResultInfo<XunicsPageResult<WalletLedgerInfo>> ledger(@Valid @ModelAttribute WalletLedgerQueryParam param) {
        return successResultInfo(service.ledger(param));
    }

    @ApiOperation("查询订单冻结资金")
    @GetMapping("/holds")
    public ResultInfo<XunicsPageResult<WalletHoldInfo>> holds(@Valid @ModelAttribute WalletHoldQueryParam param) {
        return successResultInfo(service.holds(param));
    }

}
