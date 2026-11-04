package com.csgo.tradeup.proxy.controller.xunics;

import com.csgo.tradeup.proxy.controller.xunics.param.*;
import com.csgo.tradeup.proxy.controller.xunics.resp.*;

import com.csgo.tradeup.proxy.controller.template.ResultHelper;
import com.csgo.tradeup.proxy.controller.template.ResultInfo;
import com.csgo.tradeup.proxy.service.XunicsItemService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import javax.validation.Valid;

@RestController
@RequestMapping("/api/public/v1/quotes")
@Api(tags = "Xunics-报价查询")
public class XunicsQuoteController extends ResultHelper {
    @Resource
    private XunicsItemService service;

    @ApiOperation("查询有效报价")
    @GetMapping("/{quote_id}")
    public ResultInfo<ItemQuoteInfo> detail(@PathVariable("quote_id") String quoteId) {
        return successResultInfo(service.quote(quoteId));
    }

}
