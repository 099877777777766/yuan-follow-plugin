package com.local.yuanatlas;

import android.app.Activity;
import android.content.Context;
import android.net.*;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import java.net.*;
import java.security.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** 网页仅接触操作表快照，网络端没有数据库写入或远程指令执行接口。 */
final class RecorderLanBridge {
    private Activity activity;
    private WebView web;
    private final Context context;
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private static RecorderLanBridge retained;
    private volatile boolean following,followPending;
    private String followError="",followPayload="";
    private long followSequence;
    private final AtomicBoolean posted = new AtomicBoolean();
    private RecorderLanTransport transport;
    private long generation;
    private String mode = "idle", invitation = "", startupError = "";
    RecorderLanBridge(Activity activity, WebView web) { this.activity = activity; this.web = web;context=activity.getApplicationContext(); }
    static synchronized RecorderLanBridge attach(Activity activity,WebView web){
        if(retained==null)return new RecorderLanBridge(activity,web);
        retained.activity=activity;retained.web=web;return retained;
    }
    private String ok() { return "{\"ok\":true}"; }
    private String failure(Exception e) {
        try { return new JSONObject().put("ok", false).put("error", e.getMessage() == null ? "局域网连接失败" : e.getMessage()).toString(); }
        catch (Exception ignored) { return "{\"ok\":false}"; }
    }
    private void notifyPage() {
        if (!posted.compareAndSet(false, true)) return;
        handler.post(() -> {
            posted.set(false);
            refreshFollow();
            if (activity!=null&&web!=null&&!activity.isFinishing() && !activity.isDestroyed()) web.evaluateJavascript("window.dispatchEvent(new Event('yuan-recorder-lan'))", null);
        });
    }
    private Network wifi() throws Exception {
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return network;
        }
        throw new IllegalStateException("请先连接 Wi-Fi，再开启局域网同步");
    }
    private InetAddress address(Network network) throws Exception {
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        LinkProperties properties = connectivity.getLinkProperties(network);
        if (properties != null) for (LinkAddress link : properties.getLinkAddresses()) {
            InetAddress ip = link.getAddress();
            if (ip instanceof Inet4Address && ip.isSiteLocalAddress()) return ip;
        }
        throw new IllegalStateException("当前 Wi-Fi 没有可用的局域网 IPv4 地址");
    }
    private synchronized long reset(String nextMode) {
        following=false;followPending=false;followPayload="";followSequence=0;followError="";
        if(retained==this)retained=null;
        handler.post(()->{RuYuanCaptureService service=RuYuanCaptureService.instance;if(service!=null&&service.ownsRecorderLive(this))service.closeRecorderRounds();});
        generation++; if (transport != null) transport.finish(); transport = null;
        mode = nextMode; invitation = ""; startupError = ""; return generation;
    }
    @JavascriptInterface public String start(String text) {
        try {
            validateSnapshot(text);
            long ticket = reset("host"); notifyPage();
            RecorderLanTransport.background(() -> {
                RecorderLanTransport channel = new RecorderLanTransport(true, this::notifyPage);
                try {
                    Network network = wifi(); InetAddress ip = address(network);
                    KeyStore keys = RecorderLanTls.identity();
                    byte[] bytes = new byte[16]; new SecureRandom().nextBytes(bytes);
                    StringBuilder token = new StringBuilder(); for (byte b : bytes) token.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                    channel.publish(text);
                    int port = channel.listen(RecorderLanTls.server(keys), ip, token.toString());
                    synchronized (this) {
                        if (generation != ticket) { channel.close(); return; }
                        transport = channel;
                        invitation = "YRL1|" + ip.getHostAddress() + "|" + port + "|" + RecorderLanTls.fingerprint(keys) + "|" + token;
                    }
                    notifyPage();
                } catch (Exception e) { channel.close(); startupFailed(ticket, e); }
            });
            return ok();
        } catch (Exception e) { return failure(e); }
    }
    private synchronized void startupFailed(long ticket, Exception e) {
        if (generation != ticket) return;
        startupError = e.getMessage() == null ? "无法建立局域网连接" : e.getMessage(); notifyPage();
    }
    static String[] parseInvitation(String code) throws Exception {
        if (code == null || code.length() > 256) throw new IllegalArgumentException("连接码无效");
        String[] parts = code.trim().split("\\|", -1);
        if (parts.length != 5 || !parts[0].equals("YRL1") || !parts[1].matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}") || !parts[2].matches("[0-9]{1,5}") || !parts[3].matches("[a-f0-9]{64}") || !parts[4].matches("[a-f0-9]{32}")) throw new IllegalArgumentException("请使用录入设备生成的完整连接码");
        for (String octet : parts[1].split("\\.")) if (Integer.parseInt(octet) > 255) throw new IllegalArgumentException("地址无效");
        InetAddress ip = InetAddress.getByName(parts[1]); int port = Integer.parseInt(parts[2]);
        if (!(ip instanceof Inet4Address) || !ip.isSiteLocalAddress() || port < 1 || port > 65535) throw new IllegalArgumentException("只支持局域网地址");
        return parts;
    }
    @JavascriptInterface public String join(String code) {
        try {
            String[] parts = parseInvitation(code); Network network = wifi();
            long ticket = reset("viewer");
            RecorderLanTransport channel = new RecorderLanTransport(false, this::notifyPage);
            synchronized (this) { if (generation != ticket) return ok(); transport = channel; }
            channel.join(RecorderLanTls.client(parts[3]), parts[1], Integer.parseInt(parts[2]), parts[4], network::bindSocket);
            notifyPage(); return ok();
        } catch (Exception e) { return failure(e); }
    }
    private static void validateSnapshot(String text) throws Exception {
        if (text == null || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > RecorderLanTransport.MAX_BYTES) throw new IllegalArgumentException("操作表过大，请拆分后同步");
        JSONObject value = new JSONObject(text);
        if (!"yuan-recorder-live".equals(value.optString("type")) || value.optInt("version") != 1 || !value.has("state")) throw new IllegalArgumentException("同步数据无效");
    }
    @JavascriptInterface public synchronized String publish(String text) {
        try { validateSnapshot(text); if (!"host".equals(mode) || transport == null) throw new IllegalStateException("同步尚未就绪"); transport.publish(text); return ok(); }
        catch (Exception e) { return failure(e); }
    }
    @JavascriptInterface public synchronized String acknowledge(long seq) {
        // 绑定悬浮窗后由原生渲染确认；后台 WebView 的迟到 ACK 不能提前确认新队列。
        if (!following&&transport != null && "viewer".equals(mode)) transport.acknowledge(seq); return ok();
    }
    @JavascriptInterface public synchronized String read() {
        try {
            JSONObject value = new JSONObject().put("mode", mode).put("invitation", invitation)
                .put("following",following).put("followPending",followPending).put("followError",followError).put("followSequence",followSequence)
                .put("phase", !startupError.isEmpty() ? "error" : transport == null ? "idle".equals(mode) ? "idle" : "starting" : transport.phase())
                .put("error", !startupError.isEmpty() ? startupError : transport == null ? "" : transport.error());
            if (transport != null){RecorderLanTransport.Snapshot snapshot=transport.snapshot();value.put("sequence", snapshot.sequence).put("acknowledged", transport.acknowledged())
                .put("payload", "viewer".equals(mode) ? snapshot.payload : "");}
            return new JSONObject().put("ok", true).put("value", value).toString();
        } catch (Exception e) { return failure(e); }
    }
    @JavascriptInterface public synchronized String stop() { reset("idle"); notifyPage(); return ok(); }
    /** 只接受本机点击的载入请求；远端数据不具备打开浮窗或启动手势的能力。 */
    @JavascriptInterface public synchronized String openFollow(){
        try{
            if(!"viewer".equals(mode)||transport==null||!transport.ready())throw new IllegalStateException("请先连接并收到操作表");
            if(followPending||following)return ok();
            followPending=true;long ticket=generation;
            handler.post(()->{
                try{synchronized(this){
                    if(ticket!=generation)return;
                    RuYuanCaptureService service=RuYuanCaptureService.instance;
                    if(service==null)throw new IllegalStateException("请先开启鸢家百宝袋无障碍服务");
                    if(service.running())throw new IllegalStateException("请先结束当前游戏采集");
                    RecorderLanTransport channel=transport;
                    if(channel==null||!channel.ready())throw new IllegalStateException("连接已断开，请重连后载入");
                    RecorderLanTransport.Snapshot snapshot=channel.snapshot();String payload=snapshot.payload;RecorderFollowTable table=RecorderFollowTable.parseLive(payload);
                    service.showRecorderLive(table,this);following=true;retained=this;followError="";
                    followPayload=payload;followSequence=snapshot.sequence;
                    service.recorderLiveAvailable(new JSONObject(payload).getBoolean("active"),"录入设备暂离编辑页");
                    channel.acknowledge(followSequence);
                }}catch(Exception error){followError=error.getMessage()==null?"打开悬浮窗失败":error.getMessage();}
                finally{followPending=false;notifyPage();}
            });return ok();
        }catch(Exception error){return failure(error);}
    }
    /** 在 Android 主线程刷新浮窗，游戏前台时不依赖网页定时器或网页渲染。 */
    private synchronized void refreshFollow(){
        if(!following)return;
        RuYuanCaptureService service=RuYuanCaptureService.instance;RecorderLanTransport channel=transport;
        if(service==null||!service.ownsRecorderLive(this)){overlayClosed();return;}
        if(channel==null||!channel.ready()){
            service.recorderLiveAvailable(false,channel!=null&&"ended".equals(channel.phase())?"录入端已结束同步":"同步断开，等待重新连接");return;
        }
        try{
            RecorderLanTransport.Snapshot snapshot=channel.snapshot();String payload=snapshot.payload;long seq=snapshot.sequence;
            if(!payload.equals(followPayload)){
                RecorderFollowTable table=RecorderFollowTable.parseLive(payload);
                service.updateRecorderLive(table);followPayload=payload;
            }
            service.recorderLiveAvailable(new JSONObject(payload).getBoolean("active"),"录入设备暂离编辑页");
            followSequence=seq;followError="";channel.acknowledge(seq);
        }catch(Exception error){followError=error.getMessage()==null?"实时表无法读取":error.getMessage();service.recorderLiveAvailable(false,"同步数据无效："+followError);}
    }
    synchronized void overlayClosed(){following=false;followPayload="";followSequence=0;if(retained==this)retained=null;if(web==null)destroy();else notifyPage();}
    /** 离开 App 到游戏不销毁连接；Activity 被回收时仅保留无界面会话，不持有旧 Activity。 */
    synchronized void detach(Activity owner){if(activity!=owner)return;activity=null;web=null;if(!following)destroy();}
    synchronized void destroy(){generation++;following=false;followPending=false;if(retained==this)retained=null;if(transport!=null)transport.close();transport=null;handler.post(()->{RuYuanCaptureService service=RuYuanCaptureService.instance;if(service!=null&&service.ownsRecorderLive(this))service.closeRecorderRounds();});}
}
