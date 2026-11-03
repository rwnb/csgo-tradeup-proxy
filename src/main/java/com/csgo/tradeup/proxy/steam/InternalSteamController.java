package com.csgo.tradeup.proxy.steam;

import com.csgo.tradeup.proxy.config.exception.BizException;
import com.csgo.tradeup.proxy.controller.template.ResultCode;
import org.springframework.web.bind.annotation.*;
import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

/** Internal API: events are retained until acknowledged; an abandoned session expires. */
@RestController
@RequestMapping("/internal/steam")
public class InternalSteamController {
    private final SteamGateway gateway;
    private final CommunityClient community;
    private final ConcurrentMap<String, Holder> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor();
    public InternalSteamController(SteamGateway gateway, CommunityClient community) {
        this.gateway = gateway; this.community = community;
        cleanup.scheduleWithFixedDelay(() -> sessions.forEach((id, h) -> {
            if (System.currentTimeMillis() - h.lastSeen > 45000 && sessions.remove(id, h)) h.close();
        }), 5, 5, TimeUnit.SECONDS);
    }
    @PostMapping("/sessions/{id}")
    public Map<String, Object> open(@PathVariable String id, @RequestBody OpenRequest request) {
        if (!id.matches("[a-f0-9-]{36}") || request.username == null || request.username.isEmpty()
                || request.deadline <= System.currentTimeMillis() || request.deadline > System.currentTimeMillis() + 900000)
            throw new BizException("Invalid session request");
        Holder h = new Holder(); Holder previous = sessions.putIfAbsent(id, h);
        if (previous != null) return Collections.singletonMap("id", id);
        if (sessions.size() > 100) { sessions.remove(id, h); throw new BizException(ResultCode.TOO_MANY_REQUESTS, "Session capacity reached"); }
        try {
            SteamGateway.Input input = new SteamGateway.Input(request.username, request.password, request.refreshToken, request.guardData, request.deadline);
            h.attach(gateway.open(input, h.listener()));
            return Collections.singletonMap("id", id);
        } catch (RuntimeException e) { sessions.remove(id, h); h.close(); throw e; }
    }
    @GetMapping("/sessions/{id}/events")
    public List<Event> events(@PathVariable String id, @RequestParam(defaultValue = "0") long after) {
        Holder h = find(id); synchronized (h) {
            if (after < 0 || after > h.seq) throw new BizException("Invalid acknowledgement");
            h.lastSeen = System.currentTimeMillis();
            h.events.removeIf(event -> event.seq <= after);
            return new ArrayList<>(h.events);
        }
    }
    @PostMapping("/sessions/{id}/code")
    public void code(@PathVariable String id, @RequestBody CodeRequest request) { find(id).session.submitCode(request.code); }
    @DeleteMapping("/sessions/{id}")
    public void close(@PathVariable String id) { Holder h = sessions.remove(id); if (h != null) h.close(); }
    @PostMapping("/inventory")
    public Object inventory(@RequestBody InventoryRequest request) throws Exception {
        com.fasterxml.jackson.databind.JsonNode page = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                community.inventoryPage(request.steamId, request.accessToken, request.appId, request.contextId, request.cursor));
        if (!page.path("success").asBoolean(false)) throw new BizException(ResultCode.UNAVAILABLE, "Steam inventory unavailable");
        return page;
    }
    private Holder find(String id) { Holder h = sessions.get(id); if (h == null) throw new BizException(ResultCode.NOT_FOUND, "Session expired"); return h; }
    @PreDestroy public void shutdown() { cleanup.shutdownNow(); sessions.values().forEach(Holder::close); sessions.clear(); }
    public static class OpenRequest { public String username, password, refreshToken, guardData; public long deadline; }
    public static class CodeRequest { public String code; }
    public static class InventoryRequest { public String steamId, accessToken, cursor; public long appId, contextId; }
    public static class Event { public long seq; public String type, status, message, steamId, refreshToken, guardData, accessToken, tradeUrl; }
    private static class Holder {
        volatile long lastSeen = System.currentTimeMillis(); volatile SteamGateway.Session session;
        boolean closed;
        final List<Event> events = new ArrayList<>(); long seq;
        synchronized boolean add(Event event) { if (closed || events.size() >= 128) return false; event.seq = ++seq; events.add(event); return true; }
        synchronized void attach(SteamGateway.Session value) { if (closed) value.close(); else session = value; }
        SteamGateway.Listener listener() { return new SteamGateway.Listener() {
            public boolean state(String status, String message) { Event e = new Event(); e.type = "state"; e.status = status; e.message = message; return add(e); }
            public boolean success(String id, String refresh, String guard) { Event e = new Event(); e.type = "success"; e.steamId = id; e.refreshToken = refresh; e.guardData = guard; return add(e); }
            public void webCredentials(String access, String trade, String refresh) { Event e = new Event(); e.type = "credentials"; e.accessToken = access; e.tradeUrl = trade; e.refreshToken = refresh; add(e); }
        }; }
        void close() { SteamGateway.Session s; synchronized (this) { closed = true; s = session; events.clear(); } if (s != null) s.close(); }
    }
}
