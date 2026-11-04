package com.csgo.tradeup.proxy.controller.xunics;

import com.csgo.tradeup.proxy.controller.xunics.param.*;
import com.csgo.tradeup.proxy.controller.xunics.resp.*;

import com.csgo.tradeup.proxy.controller.template.ResultHelper;
import com.csgo.tradeup.proxy.controller.template.ResultInfo;
import com.csgo.tradeup.proxy.service.XunicsOrderService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import javax.validation.Valid;

@RestController
@RequestMapping("/api/public/v1/orders")
@Api(tags = "Xunics-采购订单")
public class XunicsOrderController extends ResultHelper {
    @Resource
    private XunicsOrderService service;

    @ApiOperation("按报价下单")
    @PostMapping("")
    public ResultInfo<OrderInfo> create(@Valid @RequestBody CreateOrderParam param) {
        return successResultInfo(service.create(param));
    }

    @ApiOperation("按名称快速下单")
    @PostMapping("/quick")
    public ResultInfo<OrderInfo> quick(@Valid @RequestBody QuickOrderParam param) {
        return successResultInfo(service.quick(param));
    }

    @ApiOperation("批量快速下单")
    @PostMapping("/quick/batch")
    public ResultInfo<BatchOrderInfo> batch(@Valid @RequestBody BatchQuickOrderParam param) {
        return successResultInfo(service.batch(param));
    }

    @ApiOperation("分页查询订单")
    @GetMapping("")
    public ResultInfo<XunicsPageResult<OrderInfo>> list(@Valid @ModelAttribute OrderQueryParam param) {
        return successResultInfo(service.list(param));
    }

    @ApiOperation("查询订单详情")
    @GetMapping("/{order_no}")
    public ResultInfo<OrderInfo> detail(@PathVariable("order_no") String orderNo) {
        return successResultInfo(service.detail(orderNo));
    }

}
