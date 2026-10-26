package com.local.yuanatlas;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.net.ssl.*;

/** 单向、单查看端的 TLS 长连接。只保留最新快照，慢设备不会累积无限消息队列。 */
final class RecorderLanTransport implements AutoCloseable {
    static final int MAX_BYTES = 1024 * 1024;
    private static final int SNAPSHOT = 1, ACK = 2, PING = 3, PONG = 4, END = 5;
    interface Listener { void changed(); }
    interface Binder { void bind(Socket socket) throws IOException; }
    final boolean host;
    private final Listener listener;
    private final Object lock = new Object();
    private volatile boolean closed;
    private volatile boolean ending;
    private volatile boolean snapshotReady;
    private volatile SSLSocket socket;
    private SSLServerSocket server;
    private volatile String phase = "starting", error = "";
    volatile Exception lastFailure;
    private String payload = "";
    private long sequence, acknowledged;

    RecorderLanTransport(boolean host, Listener listener) { this.host = host; this.listener = listener; }
    synchronized long sequence() { return sequence; }
    synchronized long acknowledged() { return acknowledged; }
    synchronized String payload() { return payload; }
    static final class Snapshot{final String payload;final long sequence;Snapshot(String payload,long sequence){this.payload=payload;this.sequence=sequence;}}
    // 内容和版本必须一起读取，避免接收线程恰好换包时确认了尚未显示的新版本。
    synchronized Snapshot snapshot(){return new Snapshot(payload,sequence);}
    String phase() { return phase; }
    String error() { return error; }
    boolean ready(){return snapshotReady&&"connected".equals(phase)&&!closed;}
    private void state(String value, String message) {
        if (closed) return;
        phase = value; error = message; listener.changed();
    }
    static void background(Runnable task) { Thread thread = new Thread(task, "yuan-recorder-lan"); thread.setDaemon(true); thread.start(); }

    int listen(SSLContext tls, InetAddress address, String token) throws IOException {
        server = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket();
        server.bind(new InetSocketAddress(address, 0), 1);
        state("waiting", "");
        background(() -> {
            while (!closed && !ending) {
                try (SSLSocket peer = (SSLSocket) server.accept()) {
                    socket = peer; peer.setSoTimeout(8000); peer.setTcpNoDelay(true); peer.startHandshake();
                    DataInputStream input = new DataInputStream(peer.getInputStream());
                    DataOutputStream output = new DataOutputStream(peer.getOutputStream());
                    // 只有持有本次随机连接码的设备才能读取表格，失败不回传任何快照。
                    if (!MessageDigest.isEqual(input.readUTF().getBytes(StandardCharsets.UTF_8), ("YRL1:" + token).getBytes(StandardCharsets.UTF_8))) throw new IOException("配对失败");
                    output.writeUTF("YRL1"); output.flush();
                    synchronized (this) { acknowledged = 0; }
                    state("connected", "");
                    long sent = 0;
                    while (!closed) {
                        String next; long seq;
                        synchronized (this) { next = payload; seq = sequence; }
                        if (ending && seq <= sent) { write(output, END, 0, ""); return; }
                        if (seq > sent && !next.isEmpty()) {
                            write(output, SNAPSHOT, seq, next);
                            Frame reply = read(input);
                            if (reply.type != ACK || reply.sequence != seq) throw new IOException("同步确认无效");
                            synchronized (this) { acknowledged = seq; }
                            sent = seq; listener.changed();
                        } else {
                            write(output, PING, 0, "");
                            if (read(input).type != PONG) throw new IOException("连接确认无效");
                            synchronized (lock) { if (!closed) lock.wait(1000); }
                        }
                    }
                } catch (Exception failure) {
                    lastFailure = failure;
                    if (!closed) state("waiting", "连接已断开，等待查看设备重新连接");
                } finally { socket = null; synchronized (this) { acknowledged = 0; } }
            }
        });
        return server.getLocalPort();
    }

    void join(SSLContext tls, String address, int port, String token, Binder binder) {
        background(() -> {
            while (!closed) {
                state("connecting", "正在连接录入设备");
                try (SSLSocket peer = (SSLSocket) tls.getSocketFactory().createSocket()) {
                    socket = peer; binder.bind(peer); peer.connect(new InetSocketAddress(address, port), 5000);
                    peer.setSoTimeout(8000); peer.setTcpNoDelay(true); peer.startHandshake();
                    DataInputStream input = new DataInputStream(peer.getInputStream());
                    DataOutputStream output = new DataOutputStream(peer.getOutputStream());
                    output.writeUTF("YRL1:" + token); output.flush();
                    if (!"YRL1".equals(input.readUTF())) throw new IOException("连接版本不兼容");
                    state("connected", "");
                    while (!closed) {
                        Frame frame = read(input);
                        if (frame.type == END) { state("ended", "录入设备已结束同步"); return; }
                        if (frame.type == PING) { write(output, PONG, 0, ""); continue; }
                        if (frame.type != SNAPSHOT || frame.sequence <= 0) throw new IOException("同步消息无效");
                        synchronized (this) { payload = frame.payload; sequence = frame.sequence; acknowledged = 0;snapshotReady=true; }
                        listener.changed();
                        // 页面完成校验和渲染后才确认；仅写入 socket 不代表对方已经看到。
                        long deadline = System.nanoTime() + 7_000_000_000L;
                        synchronized (lock) {
                            while (!closed && acknowledged() != frame.sequence && System.nanoTime() < deadline) lock.wait(200);
                        }
                        if (closed) return;
                        if (acknowledged() != frame.sequence) throw new IOException("查看页面尚未确认数据");
                        write(output, ACK, frame.sequence, "");
                    }
                } catch (Exception failure) {
                    lastFailure = failure;
                    snapshotReady=false;
                    if (!closed) state("reconnecting", "连接已断开，正在重连；请检查同一 Wi-Fi、连接码及设备隔离设置");
                } finally { socket = null; }
                synchronized (lock) { try { if (!closed) lock.wait(2000); } catch (InterruptedException ignored) { return; } }
            }
        });
    }

    void publish(String text) {
        if (!host || closed) throw new IllegalStateException("当前不是录入设备");
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("操作表过大，请拆分后同步");
        synchronized (this) { if (text.equals(payload)) return; payload = text; sequence++; }
        synchronized (lock) { lock.notifyAll(); }
        listener.changed();
    }
    void acknowledge(long seq) {
        synchronized (this) { if (host || closed || seq != sequence) return; acknowledged = seq; }
        synchronized (lock) { lock.notifyAll(); }
    }
    private static final class Frame {
        int type; long sequence; String payload;
        Frame(int type, long seq, String text) { this.type = type; sequence = seq; payload = text; }
    }
    private static void write(DataOutputStream output, int type, long seq, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        synchronized (output) { output.writeByte(type); output.writeLong(seq); output.writeInt(bytes.length); output.write(bytes); output.flush(); }
    }
    private static Frame read(DataInputStream input) throws IOException {
        int type = input.readUnsignedByte(); long seq = input.readLong(); int length = input.readInt();
        if (length < 0 || length > MAX_BYTES || (type != SNAPSHOT && length != 0)) throw new IOException("同步消息过大或无效");
        byte[] bytes = new byte[length]; input.readFully(bytes);
        return new Frame(type, seq, new String(bytes, StandardCharsets.UTF_8));
    }
    void finish() {
        if (!host) { close(); return; }
        ending = true;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        synchronized (lock) { lock.notifyAll(); }
        // 正常连接先发送结束通知；失联时最多等待 1.5 秒就释放资源。
        background(() -> { try { Thread.sleep(1500); } catch (InterruptedException ignored) { } close(); });
    }
    @Override public void close() {
        closed = true;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        // 关闭 socket 会解除握手/读写等待，避免离开页面后残留连接线程。
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        synchronized (lock) { lock.notifyAll(); }
    }
}
