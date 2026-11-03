package com.csgo.tradeup.proxy.steam;

import com.csgo.tradeup.proxy.config.exception.BizException;
import com.csgo.tradeup.proxy.controller.template.ResultCode;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

/** Internal API: events are retained until acknowledged; an abandoned session expires. */
@RestController
@RequestMapping("/internal/steam")
public class InternalSteamController {
    private static final Logger log = LoggerFactory.getLogger(InternalSteamController.class);
    private final SteamGateway gateway;
    private final CommunityClient community;
    private final ConcurrentMap<String, Holder> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor();
    private final ThreadPoolExecutor starts;
    @Autowired
    public InternalSteamController(SteamGateway gateway, CommunityClient community) {
        this(gateway, community, 4, 100);
    }
    InternalSteamController(SteamGateway gateway, CommunityClient community, int threads, int queueSize) {
        this.gateway = gateway; this.community = community;
        starts = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueSize), r -> { Thread t = new Thread(r, "steam-session-start"); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.AbortPolicy());
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
        SteamGateway.Input input = new SteamGateway.Input(request.username, request.password, request.refreshToken, request.guardData, request.deadline);
        request.username = null; request.password = null; request.refreshToken = null; request.guardData = null;
        h.pending(input);
        try { h.setFuture(starts.submit(() -> {
            if (!h.begin()) return;
            try { h.attach(gateway.open(input, h.listener())); }
            catch (RuntimeException e) {
                input.clear(); log.warn("Steam会话初始化失败，异常类型={}", e.getClass().getSimpleName());
                h.failure("Steam连接初始化失败，请稍后重试");
            }
        })); }
        catch (RejectedExecutionException e) {
            sessions.remove(id, h); h.close();
            throw new BizException(ResultCode.TOO_MANY_REQUESTS, "Steam连接队列已满，请稍后重试");
        }
        return Collections.singletonMap("id", id);
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
    public void code(@PathVariable String id, @RequestBody CodeRequest request) {
        SteamGateway.Session session = find(id).getSession();
        if (session == null) throw new BizException(ResultCode.CONFLICT, "Steam连接尚未就绪");
        session.submitCode(request.code);
    }
    @DeleteMapping("/sessions/{id}")
    public void close(@PathVariable String id) { Holder h = sessions.remove(id); if (h != null) h.close(); }
    @PostMapping("/inventory")
    public Object inventory(@RequestBody InventoryRequest request) throws Exception {
        try {
            com.fasterxml.jackson.databind.JsonNode page = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    community.inventoryPage(request.steamId, request.accessToken, request.appId, request.contextId, request.cursor));
            if (!page.path("success").asBoolean(false)) throw new BizException(ResultCode.UNAVAILABLE, "Steam inventory unavailable");
            return page;
        } catch (Exception e) {
            log.warn("Steam库存读取失败：{}", CommunityClient.diagnostic(e));
            throw e;
        }
    }
    private Holder find(String id) { Holder h = sessions.get(id); if (h == null) throw new BizException(ResultCode.NOT_FOUND, "Session expired"); return h; }
    @PreDestroy public void shutdown() { cleanup.shutdownNow(); starts.shutdownNow(); sessions.values().forEach(Holder::close); sessions.clear(); }
    public static class OpenRequest { public String username, password, refreshToken, guardData; public long deadline; }
    public static class CodeRequest { public String code; }
    public static class InventoryRequest { public String steamId, accessToken, cursor; public long appId, contextId; }
    public static class Event { public long seq; public String type, status, message, steamId, refreshToken, guardData, accessToken, tradeUrl; }
    private class Holder {
        volatile long lastSeen = System.currentTimeMillis(); SteamGateway.Session session;
        SteamGateway.Input input;
        Future<?> future;
        boolean closed;
        final List<Event> events = new ArrayList<>(); long seq;
        synchronized boolean add(Event event) { if (closed || events.size() >= 128) return false; event.seq = ++seq; events.add(event); return true; }
        synchronized void pending(SteamGateway.Input value) { if (closed) value.clear(); else input = value; }
        synchronized boolean begin() { return !closed; }
        synchronized void setFuture(Future<?> value) {
            if (closed) { value.cancel(true); if (value instanceof Runnable) starts.remove((Runnable) value); }
            else future = value;
        }
        synchronized SteamGateway.Session getSession() { return closed ? null : session; }
        void attach(SteamGateway.Session value) {
            boolean close;
            synchronized (this) { input = null; future = null; close = closed; if (!close) session = value; }
            if (close) value.close();
        }
        void failure(String message) {
            Event e = new Event(); e.type = "state"; e.status = "FAILED"; e.message = message;
            synchronized (this) { input = null; future = null; add(e); }
        }
        SteamGateway.Listener listener() { return new SteamGateway.Listener() {
            public boolean state(String status, String message) { Event e = new Event(); e.type = "state"; e.status = status; e.message = message; return add(e); }
            public boolean success(String id, String refresh, String guard) { Event e = new Event(); e.type = "success"; e.steamId = id; e.refreshToken = refresh; e.guardData = guard; return add(e); }
            public void webCredentials(String access, String trade, String refresh) { Event e = new Event(); e.type = "credentials"; e.accessToken = access; e.tradeUrl = trade; e.refreshToken = refresh; add(e); }
        }; }
        void close() {
            SteamGateway.Session s; Future<?> f; SteamGateway.Input in;
            synchronized (this) { closed = true; s = session; session = null; f = future; future = null; in = input; input = null; events.clear(); }
            if (in != null) in.clear();
            if (f != null) { f.cancel(true); if (f instanceof Runnable) starts.remove((Runnable) f); }
            if (s != null) s.close();
        }
    }
}
