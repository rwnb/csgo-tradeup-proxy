package com.csgo.tradeup.proxy.service;

import com.csgo.tradeup.proxy.controller.xunics.param.*;
import com.csgo.tradeup.proxy.controller.xunics.resp.*;

public interface XunicsItemService {
    XunicsPageResult<ItemQuoteInfo> list(ItemQueryParam param);
    ItemQuoteInfo quote(String quoteId);
}
