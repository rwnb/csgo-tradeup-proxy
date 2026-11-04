package com.csgo.tradeup.proxy.controller.xunics;

import com.csgo.tradeup.proxy.integration.xunics.XunicsProxyService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping(XunicsProxyService.PREFIX + "/v1")
@Api(tags = "Xunics 原协议代理")
public class XunicsProxyController {
    private final XunicsProxyService proxy;

    public XunicsProxyController(XunicsProxyService proxy) { this.proxy = proxy; }

    @GetMapping({"/items", "/quotes/{quote_id}", "/orders", "/orders/{order_no}",
            "/wallets/me", "/wallets/me/ledger", "/wallets/me/holds"})
    @ApiOperation("查询商品、报价、订单和钱包；参数与响应遵循 Xunics OpenAPI")
    public ResponseEntity<byte[]> read(HttpServletRequest request) { return proxy.forward(request); }

    @PostMapping({"/orders", "/orders/quick", "/orders/quick/batch"})
    @ApiOperation("原文转发下单请求；保持 external_order_no 和全部参数不变")
    public ResponseEntity<byte[]> order(HttpServletRequest request) { return proxy.forward(request); }
}
