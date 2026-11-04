package com.csgo.tradeup.proxy.service;

import com.csgo.tradeup.proxy.controller.xunics.param.*;
import com.csgo.tradeup.proxy.controller.xunics.resp.*;

public interface XunicsOrderService {
    OrderInfo create(CreateOrderParam param);
    OrderInfo quick(QuickOrderParam param);
    BatchOrderInfo batch(BatchQuickOrderParam param);
    XunicsPageResult<OrderInfo> list(OrderQueryParam param);
    OrderInfo detail(String orderNo);
}
