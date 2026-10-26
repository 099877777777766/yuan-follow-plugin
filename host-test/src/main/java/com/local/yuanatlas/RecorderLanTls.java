package com.local.yuanatlas;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.math.BigInteger;
import java.net.Socket;
import java.security.*;
import java.security.cert.*;
import java.util.Date;
import javax.net.ssl.*;
import javax.security.auth.x500.X500Principal;

/** 证书私钥仅在本机 Keystore；连接码绑定证书指纹，不依赖互联网 CA。 */
final class RecorderLanTls {
    private static final String ALIAS = "yuan-recorder-lan-tls-v1";
    static KeyStore identity() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                // Conscrypt 在 TLS 内先计算摘要，再调用 NONEwithECDSA 签名，必须授权 NONE。
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(new X500Principal("CN=Yuan Recorder LAN"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(new Date(0)).setCertificateNotAfter(new Date(4102444800000L)).build());
            generator.generateKeyPair();
        }
        return store;
    }
    static String fingerprint(java.security.cert.Certificate certificate) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder value = new StringBuilder(); for (byte b : hash) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
    static SSLContext server(KeyStore store) throws Exception {
        X509Certificate[] chain = { (X509Certificate) store.getCertificate(ALIAS) };
        PrivateKey key = (PrivateKey) store.getKey(ALIAS, null);
        X509KeyManager manager = new X509KeyManager() {
            public String[] getClientAliases(String type, Principal[] issuers) { return null; }
            public String chooseClientAlias(String[] types, Principal[] issuers, Socket socket) { return null; }
            public String[] getServerAliases(String type, Principal[] issuers) { return "EC".equals(type) ? new String[]{ALIAS} : null; }
            public String chooseServerAlias(String type, Principal[] issuers, Socket socket) { return "EC".equals(type) ? ALIAS : null; }
            public X509Certificate[] getCertificateChain(String alias) { return ALIAS.equals(alias) ? chain : null; }
            public PrivateKey getPrivateKey(String alias) { return ALIAS.equals(alias) ? key : null; }
        };
        SSLContext tls = SSLContext.getInstance("TLS"); tls.init(new KeyManager[]{manager}, null, new SecureRandom()); return tls;
    }
    static String fingerprint(KeyStore store) throws Exception { return fingerprint(store.getCertificate(ALIAS)); }
    static SSLContext client(String expected) throws Exception {
        X509TrustManager trust = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String type) throws CertificateException { throw new CertificateException("不接受客户端证书"); }
            public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
                try { if (chain.length == 0 || !MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII), fingerprint(chain[0]).getBytes(java.nio.charset.StandardCharsets.US_ASCII))) throw new CertificateException("录入设备身份不匹配，请重新配对"); }
                catch (CertificateException e) { throw e; } catch (Exception e) { throw new CertificateException(e); }
            }
        };
        SSLContext tls = SSLContext.getInstance("TLS"); tls.init(null, new TrustManager[]{trust}, new SecureRandom()); return tls;
    }
}
