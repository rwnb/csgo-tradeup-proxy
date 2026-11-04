package com.csgo.tradeup.proxy.service.impl;

import com.csgo.tradeup.proxy.controller.xunics.param.*;
import com.csgo.tradeup.proxy.controller.xunics.resp.*;

import com.csgo.tradeup.proxy.service.XunicsOrderService;
import com.csgo.tradeup.proxy.integration.xunics.XunicsClient;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;
import javax.annotation.Resource;

@Service
public class XunicsOrderServiceImpl implements XunicsOrderService {
    @Resource
    private XunicsClient client;

    @Override
    public OrderInfo create(CreateOrderParam param) {
        return client.post("/v1/orders", param, new TypeReference<OrderInfo>() { });
    }

    @Override
    public OrderInfo quick(QuickOrderParam param) {
        return client.post("/v1/orders/quick", param, new TypeReference<OrderInfo>() { });
    }

    @Override
    public BatchOrderInfo batch(BatchQuickOrderParam param) {
        return client.post("/v1/orders/quick/batch", param, new TypeReference<BatchOrderInfo>() { });
    }

    @Override
    public XunicsPageResult<OrderInfo> list(OrderQueryParam param) {
        return client.get("/v1/orders", param, new TypeReference<XunicsPageResult<OrderInfo>>() { });
    }

    @Override
    public OrderInfo detail(String orderNo) {
        return client.get("/v1/orders/" + UriUtils.encodePathSegment(orderNo, "UTF-8"), null, new TypeReference<OrderInfo>() { });
    }

}
