package com.csgo.tradeup.proxy.steam;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** These operations inherit InternalAuthFilter's /internal/steam/ protection. */
@RestController
@RequestMapping("/internal/steam/trade")
class InternalSteamTradeController {
    private final SteamTradeService service;
    InternalSteamTradeController(SteamTradeService service) { this.service=service; }
    @PostMapping("/inspect") SteamTradeService.Result inspect(@RequestBody SteamTradeService.Input input) { return service.inspect(input); }
    @PostMapping("/accept") SteamTradeService.Result accept(@RequestBody SteamTradeService.Input input) { return service.accept(input); }
    @PostMapping("/receipt") SteamTradeService.Result receipt(@RequestBody SteamTradeService.Input input) { return service.receipt(input); }
}
