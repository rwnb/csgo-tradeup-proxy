package com.csgo.tradeup.proxy.steam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** A received upstream offer ID is necessary but not sufficient: never give items away. */
@Service
class SteamTradeService {
    private static final BigInteger STEAM_BASE = new BigInteger("76561197960265728");
    private final SteamTradeTransport transport;
    private final CommunityClient community;
    private final ObjectMapper mapper;
    private final Object[] locks = new Object[256];
    // A second POST in this process is forbidden while the first outcome remains uncertain.
    // The caller must also persist a fence across process restarts and multiple instances.
    private final Set<String> attempted = ConcurrentHashMap.newKeySet();
    SteamTradeService(SteamTradeTransport transport, CommunityClient community, ObjectMapper mapper) {
        this.transport=transport; this.community=community; this.mapper=mapper;
        Arrays.setAll(locks, n -> new Object());
    }
    static class Input { public String steamId, accessToken, offerId, marketHashName, tradeId; }
    static class Result {
        public String state,tradeId,message;
        public List<String> receivedAssetIds = Collections.emptyList();
        Result(String state,String tradeId,String message) {this.state=state;this.tradeId=tradeId;this.message=message;}
    }
    private static class Snapshot {
        Result result; String partner, classId, instanceId, assetId;
        Snapshot(Result result) {this.result=result;}
    }
    Result inspect(Input input) {
        try {
            Result checked=inspectSnapshot(input).result;
            if ("ACCEPTED".equals(checked.state)) attempted.remove(input.steamId+":"+input.offerId);
            return checked;
        } catch (Exception e) { return result("UNKNOWN", null, "Steam报价暂不可验证"); }
    }
    Result accept(Input input) {
        if (!validInput(input)) return result("INVALID",null,"报价参数或凭据账号无效");
        String key=input.steamId+":"+input.offerId;
        synchronized (locks[(key.hashCode() & 0x7fffffff) % locks.length]) {
            try {
                Snapshot snapshot=inspectSnapshot(input);
                if (!"ACTIVE".equals(snapshot.result.state)) {
                    if ("ACCEPTED".equals(snapshot.result.state)) attempted.remove(key);
                    return snapshot.result;
                }
                synchronized (attempted) {
                    if (attempted.contains(key) || attempted.size() >= 10000)
                        return result("UNKNOWN",null,"报价接收结果待核对，请勿重复提交");
                    attempted.add(key);
                }
                JsonNode posted=transport.accept(input.steamId,input.accessToken,input.offerId,snapshot.partner);
                if (posted.path("needs_mobile_confirmation").asBoolean(false) || posted.path("needs_email_confirmation").asBoolean(false))
                    return result("WAITING_CONFIRMATION",null,"等待Steam交易确认");
                Snapshot after=inspectSnapshot(input);
                if ("ACCEPTED".equals(after.result.state)) attempted.remove(key);
                if ("ACCEPTED".equals(after.result.state) || "WAITING_CONFIRMATION".equals(after.result.state) || "INVALID".equals(after.result.state)) return after.result;
                return result("UNKNOWN",null,"报价接收结果待核对，请勿重复提交");
            } catch (Exception e) { return result("UNKNOWN",null,"报价接收结果待核对，请勿重复提交"); }
        }
    }
    private Snapshot inspectSnapshot(Input in) throws Exception {
        if (!validInput(in)) return new Snapshot(result("INVALID",null,"报价参数或凭据账号无效"));
        JsonNode root=transport.offer(in.accessToken,in.offerId).path("response"); JsonNode offer=root.path("offer");
        if (!offer.isObject() || !offer.path("tradeofferid").isValueNode() || !in.offerId.equals(offer.path("tradeofferid").asText())
                || !offer.has("is_our_offer") || !offer.get("is_our_offer").isBoolean() || offer.get("is_our_offer").asBoolean())
            return new Snapshot(result("UNKNOWN",null,"Steam报价身份无法验证"));
        if (!offer.path("trade_offer_state").canConvertToInt()) return new Snapshot(result("UNKNOWN",null,"Steam报价状态不可用"));
        int state=offer.path("trade_offer_state").asInt();
        if (state!=2 && state!=3 && state!=9 && state!=11) return new Snapshot(result("INVALID",null,"Steam报价不再有效"));
        JsonNode give=offer.get("items_to_give"), receive=offer.get("items_to_receive");
        if (give==null || !give.isArray() || give.size()!=0
                || !noGivenCurrency(offer) || !receiveIsValid(receive)) return new Snapshot(result("INVALID",null,"报价包含非预期物品或我方付出"));
        JsonNode item=receive.get(0); String classId=item.path("classid").asText(),instanceId=item.path("instanceid").asText();
        if (!numeric(classId) || !numeric(instanceId) || !matchesDescription(root.path("descriptions"),classId,instanceId,in.marketHashName))
            return new Snapshot(result("UNKNOWN",null,"无法核对报价中的物品名称"));
        String other=offer.path("accountid_other").asText();
        if (!numeric(other)) return new Snapshot(result("UNKNOWN",null,"无法核对报价对方"));
        BigInteger account=new BigInteger(other); if (account.signum()<=0 || account.bitLength()>32) return new Snapshot(result("INVALID",null,"报价对方无效"));
        String tradeId=offer.path("tradeid").asText(null);
        if (tradeId!=null && !numeric(tradeId)) return new Snapshot(result("UNKNOWN",null,"Steam交易编号无效"));
        // Steam may return 0 before a trade exists; it is not a usable transaction ID.
        if (tradeId!=null && new BigInteger(tradeId).signum()==0) tradeId=null;
        String mapped=state==2?"ACTIVE":state==3?"ACCEPTED":state==9?"WAITING_CONFIRMATION":"UNKNOWN";
        Snapshot out=new Snapshot(result(mapped,tradeId,state==2?"报价已核验":state==3?"报价已接受，待核验到账":state==9?"等待Steam交易确认":"报价处于Steam托管状态"));
        out.partner=STEAM_BASE.add(account).toString(); out.classId=classId;out.instanceId=instanceId;out.assetId=item.path("assetid").asText(); return out;
    }
    Result receipt(Input in) {
        if (!validInput(in) || (in.tradeId!=null && !numeric(in.tradeId))) return result("INVALID",null,"交易参数或凭据账号无效");
        String requestedTradeId=in.tradeId;
        if (requestedTradeId!=null && new BigInteger(requestedTradeId).signum()==0) requestedTradeId=null;
        // No new request starts after 60 seconds; each outbound request has a 25 second maximum.
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        try {
            Snapshot offer=inspectSnapshot(in);
            if ("ACCEPTED".equals(offer.result.state)) attempted.remove(in.steamId+":"+in.offerId);
            if (!"ACCEPTED".equals(offer.result.state)) {
                if ("INVALID".equals(offer.result.state)) return offer.result;
                return result("RECEIPT_PENDING",offer.result.tradeId,"报价尚未完成或无法核验");
            }
            String tradeId=offer.result.tradeId;
            if (!numeric(tradeId) || (requestedTradeId!=null && !requestedTradeId.equals(tradeId))) return result("RECEIPT_PENDING",null,"交易编号尚未核对");
            JsonNode trade=locateTrade(in.accessToken,tradeId,deadline);
            if (trade==null) return result("RECEIPT_PENDING",tradeId,"未找到唯一交易回执，请人工核验");
            if (!trade.path("status").canConvertToInt() || trade.path("status").asInt()!=3 || !offer.partner.equals(trade.path("steamid_other").asText()))
                return result("RECEIPT_PENDING",tradeId,"Steam交易尚未完成或交易对方不匹配");
            JsonNode assets=trade.path("assets_received");
            if (!assets.isArray() || assets.size()!=1) return result("RECEIPT_PENDING",tradeId,"无法确定收到的物品");
            JsonNode item=assets.get(0);
            if (!"730".equals(item.path("appid").asText()) || !"2".equals(item.path("contextid").asText())
                    || !"2".equals(item.path("new_contextid").asText()) || !"1".equals(item.path("amount").asText())
                    || !offer.assetId.equals(item.path("assetid").asText())
                    || !offer.classId.equals(item.path("classid").asText()) || !offer.instanceId.equals(item.path("instanceid").asText())
                    || !positiveId(item.path("new_assetid").asText())) return result("RECEIPT_PENDING",tradeId,"回执物品无法核对");
            String newAsset=item.path("new_assetid").asText();
            if (!inventoryContains(in,newAsset,offer.classId,offer.instanceId,deadline)) return result("RECEIPT_PENDING",tradeId,"回执存在，库存到账待核验");
            Result ok=result("RECEIVED",tradeId,"交易回执与库存物品已核对");ok.receivedAssetIds=Collections.singletonList(newAsset);return ok;
        } catch (Exception e) { return result("RECEIPT_PENDING",null,"交易回执暂不可用"); }
    }
    /** Steam's documented cursor consists of the last trade's time_init and tradeid. */
    private JsonNode locateTrade(String token,String tradeId,long deadline) throws Exception {
        String afterTime="0",afterId="0";Set<String> cursors=new HashSet<>();
        for(int page=0;page<10;page++) {
            if (System.nanoTime()>=deadline) return null;
            JsonNode response=transport.history(token,afterTime,afterId).path("response");JsonNode trades=response.path("trades");
            if (!trades.isArray()) return null;
            JsonNode found=null;
            for(JsonNode trade:trades) if(tradeId.equals(trade.path("tradeid").asText())) {
                if(found!=null) return null; found=trade;
            }
            if(found!=null) return found;
            if (!response.path("more").asBoolean(false) || trades.size()==0) return null;
            JsonNode last=trades.get(trades.size()-1);afterTime=last.path("time_init").asText();afterId=last.path("tradeid").asText();
            if (!numeric(afterTime) || !numeric(afterId) || !cursors.add(afterTime+":"+afterId)) return null;
        }
        return null;
    }
    private boolean inventoryContains(Input in,String assetId,String classId,String instanceId,long deadline) throws Exception {
        String cursor=null; Set<String> seen=new HashSet<>();
        for (int page=0;page<20;page++) {
            if (System.nanoTime()>=deadline) return false;
            JsonNode result=mapper.readTree(community.inventoryPage(in.steamId,in.accessToken,730,2,cursor));
            if (!result.path("success").asBoolean(false) || !result.path("assets").isArray()) return false;
            for(JsonNode asset:result.path("assets")) if(assetId.equals(asset.path("assetid").asText())
                    && "730".equals(asset.path("appid").asText()) && "2".equals(asset.path("contextid").asText())
                    && classId.equals(asset.path("classid").asText()) && instanceId.equals(asset.path("instanceid").asText())
                    && "1".equals(asset.path("amount").asText())) return true;
            if (!result.path("more_items").asBoolean(false)) return false;
            cursor=result.path("last_assetid").asText(); if (!numeric(cursor) || !seen.add(cursor)) return false;
        }
        return false;
    }
    private boolean validInput(Input i) {
        if (i==null || !positiveId(i.steamId) || i.steamId.length()<15 || !positiveId(i.offerId) || i.marketHashName==null
                || i.marketHashName.trim().isEmpty() || i.marketHashName.length()>255 || i.accessToken==null || i.accessToken.length()>16000) return false;
        try {
            String[] parts=i.accessToken.split("\\.",-1); if(parts.length!=3) return false;
            JsonNode claims=mapper.readTree(new String(Base64.getUrlDecoder().decode(parts[1]),StandardCharsets.UTF_8));
            // The Steam API authenticates the token; this additional check binds its account subject to the requested receiver.
            return claims.path("sub").isTextual() && i.steamId.equals(claims.path("sub").asText());
        } catch(Exception e){return false;}
    }
    private static boolean receiveIsValid(JsonNode items) {
        if (items==null || !items.isArray() || items.size()!=1) return false;
        JsonNode item=items.get(0);return item.isObject() && "730".equals(item.path("appid").asText()) && "2".equals(item.path("contextid").asText())
                && "1".equals(item.path("amount").asText()) && positiveId(item.path("assetid").asText())
                && !item.hasNonNull("currencyid") && !item.path("is_currency").asBoolean(false);
    }
    /** Any present currency-give field must be an explicitly empty array or numeric zero. */
    private static boolean noGivenCurrency(JsonNode offer) {
        Iterator<Map.Entry<String,JsonNode>> fields=offer.fields();
        while(fields.hasNext()) {
            Map.Entry<String,JsonNode> field=fields.next();String name=field.getKey().toLowerCase(Locale.ROOT);
            if (!name.contains("currenc") || !name.contains("give")) continue;
            JsonNode value=field.getValue();
            if (value==null || value.isNull()) return false;
            if (value.isArray()) { if (!value.isEmpty()) return false; }
            else if (value.isNumber()) { if (value.decimalValue().signum()!=0) return false; }
            else return false;
        }
        return true;
    }
    private static boolean matchesDescription(JsonNode descriptions,String classId,String instanceId,String expected) {
        if (!descriptions.isArray()) return false;int count=0;
        for(JsonNode d:descriptions) if ("730".equals(d.path("appid").asText()) && classId.equals(d.path("classid").asText())
                && instanceId.equals(d.path("instanceid").asText()) && expected.equals(d.path("market_hash_name").asText())) count++;
        return count==1;
    }
    private static boolean numeric(String id) {return id!=null && id.matches("[0-9]{1,20}");}
    private static boolean positiveId(String id) {return numeric(id) && new BigInteger(id).signum()>0;}
    private static Result result(String state,String tradeId,String message){return new Result(state,tradeId,message);}
}
