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
@RequestMapping("/api/public/v1/items")
@Api(tags = "Xunics-商品查询")
public class XunicsItemController extends ResultHelper {
    @Resource
    private XunicsItemService service;

    @ApiOperation("查询在售商品")
    @GetMapping("")
    public ResultInfo<XunicsPageResult<ItemQuoteInfo>> list(@Valid @ModelAttribute ItemQueryParam param) {
        return successResultInfo(service.list(param));
    }

}
