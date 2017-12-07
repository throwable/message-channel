package gf.channel.server;

import gf.channel.client.ClientConnector;
import gf.channel.server.servlet.JettyWSConnectionServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.servlet.*;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;
import java.util.logging.LogManager;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

/**
 * Created by anton on 26/09/2015.
 */
public class TestWSFailedToConnect {
    static {
        final InputStream inputStream = TestWSFailedToConnect.class.getResourceAsStream("/logging.properties");
        try {
            LogManager.getLogManager().readConfiguration(inputStream);
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }

    private final static org.slf4j.Logger log = LoggerFactory.getLogger(TestWSFailedToConnect.class);

    private final static int PORT = 12345 + new Random().nextInt(20000);;
    private final static String PATH = "/rpc/ws";
    private final static String BLACK_HOLE_PATH = "/rpc/blackhole";
    private final static String URL = "http://localhost:"+PORT+PATH;
    private static Server server;

    private static AbstractSocketServerConnector instance;

    public static class MyWSServlet extends JettyWSConnectionServlet {
        public MyWSServlet() {
            instance = connector;
        }

        @Override
        public void init(ServletConfig config) throws ServletException {
            super.init(config);
            connector.setConnectionTimeout(10000);
            connector.setHeartbeatInterval(2000);
        }
    }

    public static class WsBlackHoleServlet extends WebSocketServlet {
        @WebSocket
        private class NullWebSocket {
        }

        @Override
        public void configure(WebSocketServletFactory webSocketServletFactory) {
            webSocketServletFactory.setCreator(new WebSocketCreator() {
                @Override
                public Object createWebSocket(ServletUpgradeRequest servletUpgradeRequest, ServletUpgradeResponse servletUpgradeResponse) {
                    return new NullWebSocket();
                }
            });
        }
    }

    @BeforeClass
    public static void setUp() throws Exception
    {
        log.info("Starting server...");
        server = new Server(PORT);

        ServletHandler handler = new ServletHandler();
        ServletHolder servletHolder = handler.addServletWithMapping(MyWSServlet.class, PATH);
        servletHolder.setInitOrder(1);  // load on startup
        handler.addServletWithMapping(WsBlackHoleServlet.class, BLACK_HOLE_PATH);
        server.setHandler(handler);
        server.start();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        server.stop();
    }


    @Test
    public void testConnectingRetriesWithTimeout() throws Exception
    {
        JavaWSClientConnector client = new JavaWSClientConnector("http://localhost:"+PORT+BLACK_HOLE_PATH);
        client.setMaxReconnectionAttempts(5);
        client.setReconnectionDelay(500);
        client.setConnectTimeout(500);
        ClientConnector.ConnectionHandler connectionHandler = mock(ClientConnector.ConnectionHandler.class);
        client.addConnectionHandler(connectionHandler);
        ClientConnector.MessageHandler messageHandler = mock(ClientConnector.MessageHandler.class);
        client.addMessageHandler(messageHandler);

        client.connect();

        // waiting
        for (int i = 0; i < 12; i++) {
            Thread.sleep(500);

            if (client.getState() == ClientConnector.State.closed)
                break;
        }
        assertEquals(ClientConnector.State.closed, client.getState());

        // client state flow
        verify(connectionHandler).onStateChanged(ClientConnector.State.closed, ClientConnector.State.connecting);
        verify(connectionHandler).onStateChanged(ClientConnector.State.connecting, ClientConnector.State.closed);
    }


    @Test
    public void testServiceUnavailable() throws Exception
    {
        JavaWSClientConnector client = new JavaWSClientConnector("http://localhost:"+PORT+"/xxx");
        client.setMaxReconnectionAttempts(5);
        client.setReconnectionDelay(500);
        client.setConnectTimeout(500);
        ClientConnector.ConnectionHandler connectionHandler = mock(ClientConnector.ConnectionHandler.class);
        client.addConnectionHandler(connectionHandler);
        ClientConnector.MessageHandler messageHandler = mock(ClientConnector.MessageHandler.class);
        client.addMessageHandler(messageHandler);

        client.connect();

        // waiting
        for (int i = 0; i < 12; i++) {
            Thread.sleep(500);

            if (client.getState() == ClientConnector.State.closed)
                break;
        }
        assertEquals(ClientConnector.State.closed, client.getState());

        // client state flow
        verify(connectionHandler).onStateChanged(ClientConnector.State.closed, ClientConnector.State.connecting);
        verify(connectionHandler).onStateChanged(ClientConnector.State.connecting, ClientConnector.State.closed);
    }


    @Test
    public void testSuccessfulReconnection() throws Exception
    {
        // server handler
        final ServerConnectorHandler serverHandler = mock(ServerConnectorHandler.class);
        instance.setHandler(serverHandler);
        final String[] connectionId = new String[1];
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                connectionId[0] = (String) invocationOnMock.getArguments()[1];
                return null;
            }
        })
                .when(serverHandler)
                .onConnected(any(ServerConnector.class), anyString());

        // client handler: write status codes
        class MyClientConnector extends JavaWSClientConnector {
            public MyClientConnector(String path) {
                super(path);
            }
            private String savedURL;
            public void simulateConnectionFault() {
                this.savedURL = url;
                this.url = "ws://localhost:" + PORT + BLACK_HOLE_PATH;
                socketClose();
            }
            public void restoreFromFault() {
                this.url = savedURL;
            }
        };
        final MyClientConnector client = new MyClientConnector(URL);
        ClientConnector.ConnectionHandler connectionHandler = mock(ClientConnector.ConnectionHandler.class);
        client.addConnectionHandler(connectionHandler);
        ClientConnector.MessageHandler messageHandler = mock(ClientConnector.MessageHandler.class);
        client.addMessageHandler(messageHandler);
        client.setMaxReconnectionAttempts(10);
        client.setReconnectionDelay(500);
        client.setConnectTimeout(1000);

        client.connect();

        // waiting for ready state
        for (int i = 0; i < 10; i++) {
            Thread.sleep(200);
            if (client.getState() == ClientConnector.State.ready)
                break;
        }
        assertEquals(ClientConnector.State.ready, client.getState());

        // simulate fault by changing URL
        //client.setURL("http://localhost:" + PORT + "/xxx");
        client.simulateConnectionFault();

        // waiting for connecting state
        for (int i = 0; i < 10; i++) {
            Thread.sleep(500);
            if (client.getState() == ClientConnector.State.connecting)
                break;
        }
        assertEquals(ClientConnector.State.connecting, client.getState());

        // send messages during connection fault
        client.post("1");
        client.post("2");
        client.post("3");
        instance.post(connectionId[0], "a");
        instance.post(connectionId[0], "b");
        instance.post(connectionId[0], "c");

        Thread.sleep(1000);

        // normalize URL and reconnect
        client.restoreFromFault();

        // waiting for connecting state
        for (int i = 0; i < 10; i++) {
            Thread.sleep(1000);
            if (client.getState() == ClientConnector.State.ready)
                break;
        }
        assertEquals(ClientConnector.State.ready, client.getState());

        client.close();
        // waiting for connecting state
        for (int i = 0; i < 10; i++) {
            Thread.sleep(200);
            if (client.getState() == ClientConnector.State.closed)
                break;
        }
        assertEquals(ClientConnector.State.closed, client.getState());

        // server state flow
        verify(serverHandler).onConnected(any(ServerConnector.class), eq(connectionId[0]));
        verify(serverHandler).onMessage(any(ServerConnector.class), eq(connectionId[0]), eq("1"));
        verify(serverHandler).onMessage(any(ServerConnector.class), eq(connectionId[0]), eq("2"));
        verify(serverHandler).onMessage(any(ServerConnector.class), eq(connectionId[0]), eq("3"));
        verify(serverHandler).onDisconnected(any(ServerConnector.class), eq(connectionId[0]));

        // client state flow
        verify(connectionHandler).onStateChanged(ClientConnector.State.closed, ClientConnector.State.connecting);
        verify(connectionHandler, times(2)).onStateChanged(ClientConnector.State.connecting, ClientConnector.State.ready);
        verify(connectionHandler).onStateChanged(ClientConnector.State.ready, ClientConnector.State.connecting);
        verify(connectionHandler).onStateChanged(ClientConnector.State.ready, ClientConnector.State.closing);
        verify(connectionHandler).onStateChanged(ClientConnector.State.closing, ClientConnector.State.closed);

        // client messages
        verify(messageHandler).onMessage(eq("a"));
        verify(messageHandler).onMessage(eq("b"));
        verify(messageHandler).onMessage(eq("c"));
    }



    @Test
    public void testFailedToReconnect() throws Exception
    {
        // server handler
        final ServerConnectorHandler serverHandler = mock(ServerConnectorHandler.class);
        instance.setHandler(serverHandler);
        final String[] connectionId = new String[1];
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                connectionId[0] = (String) invocationOnMock.getArguments()[1];
                return null;
            }
        })
                .when(serverHandler)
                .onConnected(any(ServerConnector.class), anyString());

        // client handler: write status codes
        class MyClientConnector extends JavaWSClientConnector {
            public MyClientConnector(String path) {
                super(path);
            }
            private String savedURL;
            public void simulateConnectionFault() {
                this.savedURL = url;
                this.url = "ws://localhost:" + PORT + BLACK_HOLE_PATH;
                socketClose();
            }
            public void restoreFromFault() {
                this.url = savedURL;
            }
        };
        final MyClientConnector client = new MyClientConnector(URL);
        ClientConnector.ConnectionHandler connectionHandler = mock(ClientConnector.ConnectionHandler.class);
        client.addConnectionHandler(connectionHandler);
        ClientConnector.MessageHandler messageHandler = mock(ClientConnector.MessageHandler.class);
        client.addMessageHandler(messageHandler);
        client.setMaxReconnectionAttempts(3);
        client.setReconnectionDelay(500);
        client.setConnectTimeout(500);

        client.connect();

        // waiting for ready state
        for (int i = 0; i < 10; i++) {
            Thread.sleep(200);
            if (client.getState() == ClientConnector.State.ready)
                break;
        }
        assertEquals(ClientConnector.State.ready, client.getState());

        // simulate fault by changing URL
        //client.setURL("http://localhost:" + PORT + "/xxx");
        client.simulateConnectionFault();

        // waiting for connecting state
        for (int i = 0; i < 10; i++) {
            Thread.sleep(1000);
            if (client.getState() == ClientConnector.State.connecting)
                break;
        }
        assertEquals(ClientConnector.State.connecting, client.getState());

        // send messages during connection fault
        client.post("1");
        client.post("2");
        client.post("3");
        instance.post(connectionId[0], "a");
        instance.post(connectionId[0], "b");
        instance.post(connectionId[0], "c");

        // waiting for connecting state
        for (int i = 0; i < 10; i++) {
            Thread.sleep(500);
            if (client.getState() == ClientConnector.State.closed)
                break;
        }
        assertEquals(ClientConnector.State.closed, client.getState());

        // server state flow
        verify(serverHandler).onConnected(any(ServerConnector.class), eq(connectionId[0]));
        verify(serverHandler, times(0)).onMessage(any(ServerConnector.class), eq(connectionId[0]), anyString());
        //verify(serverHandler).onDisconnected(any(ServerConnector.class), eq(connectionId[0]));

        // client state flow
        verify(connectionHandler).onStateChanged(ClientConnector.State.closed, ClientConnector.State.connecting);
        verify(connectionHandler).onStateChanged(ClientConnector.State.connecting, ClientConnector.State.ready);
        verify(connectionHandler).onStateChanged(ClientConnector.State.ready, ClientConnector.State.connecting);
        verify(connectionHandler, times(0)).onStateChanged(ClientConnector.State.ready, ClientConnector.State.closed);
        verify(connectionHandler).onStateChanged(ClientConnector.State.connecting, ClientConnector.State.closed);
    }
}
