package com.csgo.tradeup.proxy.controller.common;

import com.csgo.tradeup.proxy.controller.template.ResultHelper;
import com.csgo.tradeup.proxy.controller.template.ResultInfo;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Collections;

@RestController
@Api(tags = "存活检查")
public class HealthController {
    @GetMapping("/health")
    @ApiOperation("进程存活检查，不检测数据库或 Redis 连接")
    public ResultInfo health() {
        return ResultHelper.successResultInfo(Collections.singletonMap("application", "csgo-tradeup-proxy"));
    }
}
