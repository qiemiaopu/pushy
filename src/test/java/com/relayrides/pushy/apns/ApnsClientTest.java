package com.relayrides.pushy.apns;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Date;
import java.util.Random;
import javax.net.ssl.SSLException;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import com.relayrides.pushy.apns.util.SimpleApnsPushNotification;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.util.concurrent.Future;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;

public class ApnsClientTest {

    private static NioEventLoopGroup EVENT_LOOP_GROUP;

    private static File SINGLE_TOPIC_CLIENT_CERTIFICATE;
    private static File SINGLE_TOPIC_CLIENT_PRIVATE_KEY;

    private static File MULTI_TOPIC_CLIENT_CERTIFICATE;
    private static File MULTI_TOPIC_CLIENT_PRIVATE_KEY;

    private static File UNTRUSTED_CLIENT_CERTIFICATE;
    private static File UNTRUSTED_CLIENT_PRIVATE_KEY;

    private static File CA_CERTIFICATE;

    private static final String HOST = "localhost";
    private static final int PORT = 8443;

    private static final String DEFAULT_TOPIC = "com.relayrides.pushy";

    private static final int TOKEN_LENGTH = 32; // bytes

    private MockApnsServer server;
    private ApnsClient<SimpleApnsPushNotification> client;

    @Rule
    public Timeout globalTimeout = new Timeout(10000);

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        ApnsClientTest.EVENT_LOOP_GROUP = new NioEventLoopGroup();

        SINGLE_TOPIC_CLIENT_CERTIFICATE = new File(ApnsClientTest.class.getResource("/single-topic-client.crt").toURI());
        SINGLE_TOPIC_CLIENT_PRIVATE_KEY = new File(ApnsClientTest.class.getResource("/single-topic-client.pk8").toURI());

        MULTI_TOPIC_CLIENT_CERTIFICATE = new File(ApnsClientTest.class.getResource("/multi-topic-client.crt").toURI());
        MULTI_TOPIC_CLIENT_PRIVATE_KEY = new File(ApnsClientTest.class.getResource("/multi-topic-client.pk8").toURI());

        UNTRUSTED_CLIENT_CERTIFICATE = new File(ApnsClientTest.class.getResource("/untrusted-client.crt").toURI());
        UNTRUSTED_CLIENT_PRIVATE_KEY = new File(ApnsClientTest.class.getResource("/untrusted-client.pk8").toURI());

        CA_CERTIFICATE = new File(ApnsClientTest.class.getResource("/ca.crt").toURI());
    }

    @Before
    public void setUp() throws Exception {
        this.server = new MockApnsServer(EVENT_LOOP_GROUP);
        this.server.start(PORT).await().isSuccess();

        this.client = new ApnsClient<SimpleApnsPushNotification>(
                ApnsClientTest.getSslContextForTestClient(SINGLE_TOPIC_CLIENT_CERTIFICATE, SINGLE_TOPIC_CLIENT_PRIVATE_KEY),
                EVENT_LOOP_GROUP);

        this.client.connect(HOST, PORT).await();
    }

    @After
    public void tearDown() throws Exception {
        this.client.disconnect().await().isSuccess();
        this.server.shutdown().await().isSuccess();
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        ApnsClientTest.EVENT_LOOP_GROUP.shutdownGracefully().await();
    }

    @Test
    public void testReconnectionAfterClose() throws Exception {
        assertTrue(this.client.isConnected());
        assertTrue(this.client.disconnect().await().isSuccess());

        assertFalse(this.client.isConnected());

        assertTrue(this.client.connect(HOST, PORT).await().isSuccess());
        assertTrue(this.client.isConnected());
    }

    @Test
    public void testAutomaticReconnection() throws Exception {
        assertTrue(this.client.isConnected());

        this.server.shutdown().await();

        // Wait for the client to notice the GOAWAY; if it doesn't, the test will time out and fail
        while (this.client.isConnected()) {
            Thread.sleep(100);
        }

        assertFalse(this.client.isConnected());

        this.server.start(PORT).await();

        // Wait for the client to reconnect automatically; if it doesn't, the test will time out and fail
        while (!this.client.isConnected()) {
            Thread.sleep(500);
        }

        assertTrue(this.client.isConnected());
    }

    public void testConnectWithUntrustedCertificate() throws Exception {
        final ApnsClient<SimpleApnsPushNotification> untrustedClient = new ApnsClient<SimpleApnsPushNotification>(
                ApnsClientTest.getSslContextForTestClient(UNTRUSTED_CLIENT_CERTIFICATE, UNTRUSTED_CLIENT_PRIVATE_KEY),
                EVENT_LOOP_GROUP);

        final Future<Void> connectFuture = untrustedClient.connect(HOST, PORT).await();
        assertFalse(connectFuture.isSuccess());
        assertTrue(connectFuture.cause() instanceof SSLException);

        untrustedClient.disconnect().await();
    }

    public void testSendNotificationBeforeConnected() throws Exception {
        final ApnsClient<SimpleApnsPushNotification> unconnectedClient = new ApnsClient<SimpleApnsPushNotification>(
                ApnsClientTest.getSslContextForTestClient(SINGLE_TOPIC_CLIENT_CERTIFICATE, SINGLE_TOPIC_CLIENT_PRIVATE_KEY),
                EVENT_LOOP_GROUP);

        final String testToken = ApnsClientTest.generateRandomToken();

        this.server.registerToken(DEFAULT_TOPIC, testToken);

        final SimpleApnsPushNotification pushNotification = new SimpleApnsPushNotification(testToken, null, "test-payload");
        final Future<PushNotificationResponse<SimpleApnsPushNotification>> sendFuture =
                unconnectedClient.sendNotification(pushNotification).await();

        assertFalse(sendFuture.isSuccess());
        assertTrue(sendFuture.cause() instanceof IllegalStateException);
    }

    @Test
    public void testSendNotification() throws Exception {
        final String testToken = ApnsClientTest.generateRandomToken();

        this.server.registerToken(DEFAULT_TOPIC, testToken);

        final SimpleApnsPushNotification pushNotification = new SimpleApnsPushNotification(testToken, null, "test-payload");
        final PushNotificationResponse<SimpleApnsPushNotification> response =
                this.client.sendNotification(pushNotification).get();

        assertTrue(response.isSuccess());
    }

    @Test
    public void testSendNotificationWithBadTopic() throws Exception {
        final String testToken = ApnsClientTest.generateRandomToken();

        this.server.registerToken(DEFAULT_TOPIC, testToken);

        final SimpleApnsPushNotification pushNotification =
                new SimpleApnsPushNotification(testToken, "Definitely not a real topic", "test-payload", null,
                        DeliveryPriority.IMMEDIATE);

        final PushNotificationResponse<SimpleApnsPushNotification> response =
                this.client.sendNotification(pushNotification).get();

        assertFalse(response.isSuccess());
        assertEquals("TopicDisallowed", response.getRejectionReason());
        assertNull(response.getTokenExpirationTimestamp());
    }

    @Test
    public void testSendNotificationWithMissingTopic() throws Exception {
        final ApnsClient<SimpleApnsPushNotification> multiTopicClient = new ApnsClient<SimpleApnsPushNotification>(
                ApnsClientTest.getSslContextForTestClient(MULTI_TOPIC_CLIENT_CERTIFICATE, MULTI_TOPIC_CLIENT_PRIVATE_KEY),
                EVENT_LOOP_GROUP);

        multiTopicClient.connect(HOST, PORT).await();

        final String testToken = ApnsClientTest.generateRandomToken();

        this.server.registerToken(DEFAULT_TOPIC, testToken);

        final SimpleApnsPushNotification pushNotification = new SimpleApnsPushNotification(testToken, null, "test-payload");

        final PushNotificationResponse<SimpleApnsPushNotification> response =
                multiTopicClient.sendNotification(pushNotification).get();

        multiTopicClient.disconnect().await();

        assertFalse(response.isSuccess());
        assertEquals("MissingTopic", response.getRejectionReason());
        assertNull(response.getTokenExpirationTimestamp());
    }

    @Test
    public void testSendNotificationWithSpecifiedTopic() throws Exception {
        final ApnsClient<SimpleApnsPushNotification> multiTopicClient = new ApnsClient<SimpleApnsPushNotification>(
                ApnsClientTest.getSslContextForTestClient(MULTI_TOPIC_CLIENT_CERTIFICATE, MULTI_TOPIC_CLIENT_PRIVATE_KEY),
                EVENT_LOOP_GROUP);

        multiTopicClient.connect(HOST, PORT).await();

        final String testToken = ApnsClientTest.generateRandomToken();

        this.server.registerToken(DEFAULT_TOPIC, testToken);

        final SimpleApnsPushNotification pushNotification =
                new SimpleApnsPushNotification(testToken, DEFAULT_TOPIC, "test-payload", null, DeliveryPriority.IMMEDIATE);

        final PushNotificationResponse<SimpleApnsPushNotification> response =
                multiTopicClient.sendNotification(pushNotification).get();

        multiTopicClient.disconnect().await();

        assertTrue(response.isSuccess());
    }

    @Test
    public void testSendNotificationWithUnregisteredToken() throws Exception {
        final SimpleApnsPushNotification pushNotification =
                new SimpleApnsPushNotification(ApnsClientTest.generateRandomToken(), null, "test-payload");

        final PushNotificationResponse<SimpleApnsPushNotification> response =
                this.client.sendNotification(pushNotification).get();

        assertFalse(response.isSuccess());
        assertEquals("DeviceTokenNotForTopic", response.getRejectionReason());
        assertNull(response.getTokenExpirationTimestamp());
    }

    @Test
    public void testSendNotificationWithExpiredToken() throws Exception {
        final String testToken = ApnsClientTest.generateRandomToken();

        // APNs uses timestamps rounded to the nearest second; for ease of comparison, we test with timestamps that
        // just happen to fall on whole seconds.
        final Date roundedNow = new Date((System.currentTimeMillis() / 1000) * 1000);

        this.server.registerToken(DEFAULT_TOPIC, testToken, roundedNow);

        final SimpleApnsPushNotification pushNotification = new SimpleApnsPushNotification(testToken, null, "test-payload");
        final PushNotificationResponse<SimpleApnsPushNotification> response =
                this.client.sendNotification(pushNotification).get();

        assertFalse(response.isSuccess());
        assertEquals("Unregistered", response.getRejectionReason());
        assertEquals(roundedNow, response.getTokenExpirationTimestamp());
    }

    private static SslContext getSslContextForTestClient(final File certificate, final File privateKey) throws NoSuchAlgorithmException, KeyStoreException, UnrecoverableKeyException, IOException, CertificateException {
        return SslContextBuilder.forClient()
                .sslProvider(OpenSsl.isAlpnSupported() ? SslProvider.OPENSSL : SslProvider.JDK)
                .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                .keyManager(certificate, privateKey)
                .trustManager(CA_CERTIFICATE)
                .applicationProtocolConfig(new ApplicationProtocolConfig(Protocol.ALPN,
                        SelectorFailureBehavior.NO_ADVERTISE,
                        SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2))
                .build();
    }

    private static String generateRandomToken() {
        final byte[] tokenBytes = new byte[TOKEN_LENGTH];
        new Random().nextBytes(tokenBytes);

        final StringBuilder builder = new StringBuilder(TOKEN_LENGTH * 2);

        for (final byte b : tokenBytes) {
            final String hexString = Integer.toHexString(b & 0xff);

            if (hexString.length() == 1) {
                // We need a leading zero
                builder.append('0');
            }

            builder.append(hexString);
        }

        return builder.toString();
    }
}
