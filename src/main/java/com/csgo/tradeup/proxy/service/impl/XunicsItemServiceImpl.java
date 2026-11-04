package com.csgo.tradeup.proxy.service.impl;

import com.csgo.tradeup.proxy.controller.xunics.param.*;
import com.csgo.tradeup.proxy.controller.xunics.resp.*;

import com.csgo.tradeup.proxy.service.XunicsItemService;
import com.csgo.tradeup.proxy.integration.xunics.XunicsClient;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;
import javax.annotation.Resource;

@Service
public class XunicsItemServiceImpl implements XunicsItemService {
    @Resource
    private XunicsClient client;

    @Override
    public XunicsPageResult<ItemQuoteInfo> list(ItemQueryParam param) {
        return client.get("/v1/items", param, new TypeReference<XunicsPageResult<ItemQuoteInfo>>() { });
    }

    @Override
    public ItemQuoteInfo quote(String quoteId) {
        return client.get("/v1/quotes/" + UriUtils.encodePathSegment(quoteId, "UTF-8"), null, new TypeReference<ItemQuoteInfo>() { });
    }

}
