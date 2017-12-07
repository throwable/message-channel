package gf.channel.server;

import gf.channel.client.ClientConnector;
import gf.channel.client.FallbackClientConnector;
import gf.channel.server.servlet.AsyncPollingConnectionServlet;
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

import javax.servlet.AsyncContext;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.LogManager;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.verify;

/**
 * Created by akuranov on 01/10/2015.
 */
public class TestFallbackConnector {
    static {
        final InputStream inputStream = TestWSConnectors.class.getResourceAsStream("/logging.properties");
        try {
            LogManager.getLogManager().readConfiguration(inputStream);
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }

    private final static org.slf4j.Logger log = LoggerFactory.getLogger(TestWSConnectors.class);

    private final static int PORT = 12345 + new Random().nextInt(20000);
    private final static String WS_PATH = "/rpc/ws";
    private final static String WS_URL = "http://localhost:"+PORT+WS_PATH;
    private final static String POLL_PATH = "/rpc/poll";
    private final static String POLL_URL = "http://localhost:"+PORT+POLL_PATH;
    private final static String WSBH_PATH = "/rpc/wsbh";
    private final static String WSBH_URL = "http://localhost:"+PORT+WSBH_PATH;
    private final static String POLLBH_PATH = "/rpc/pollbh";
    private final static String POLLBH_URL = "http://localhost:"+PORT+POLLBH_PATH;
    private static Server server;

    private static AbstractSocketServerConnector wsInstance;

    public static class MyWSServlet extends JettyWSConnectionServlet {
        public MyWSServlet() {
            wsInstance = connector;
        }
    }

    private static MyPollingServlet pollInstance;

    public static class MyPollingServlet extends AsyncPollingConnectionServlet {
        public MyPollingServlet() {
            pollInstance = this;
        }
    }

    public static class PollingBlackHoleServlet extends HttpServlet {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            AsyncContext conn = req.startAsync();
            conn.setTimeout(1000000);
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
        /*SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();*/
        log.info("Starting server...");
        server = new Server(PORT);

        ServletHandler handler = new ServletHandler();
        ServletHolder servletHolder = handler.addServletWithMapping(MyWSServlet.class, WS_PATH);
        servletHolder.setInitOrder(1);  // load on startup
        servletHolder = handler.addServletWithMapping(MyPollingServlet.class, POLL_PATH);
        servletHolder.setInitOrder(1);  // load on startup
        servletHolder = handler.addServletWithMapping(WsBlackHoleServlet.class, WSBH_PATH);
        servletHolder.setInitOrder(1);  // load on startup
        servletHolder = handler.addServletWithMapping(PollingBlackHoleServlet.class, POLLBH_PATH);
        servletHolder.setInitOrder(1);  // load on startup
        server.setHandler(handler);
        server.start();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        server.stop();
    }


    @Test
    public void testSimpleConnect() throws Exception
    {
        // server handler echoes (message * 10)
        final ServerConnectorHandler serverHandler = mock(ServerConnectorHandler.class);
        wsInstance.setHandler(serverHandler);
        pollInstance.setHandler(serverHandler);

        final String[] connectionId = new String[2];
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                if (invocationOnMock.getArguments()[0] == wsInstance)
                    connectionId[0] = (String) invocationOnMock.getArguments()[1];
                else
                    connectionId[1] = (String) invocationOnMock.getArguments()[1];
                return null;
            }
        }).when(serverHandler).onConnected(any(ServerConnector.class), anyString());
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                ServerConnector conn = (ServerConnector) invocationOnMock.getArguments()[0];
                String connId = (String) invocationOnMock.getArguments()[1];
                Integer num = Integer.parseInt((String) invocationOnMock.getArguments()[2]);
                conn.post(connId, ""+(num*10));
                return null;
            }
        }).when(serverHandler).onMessage(any(ServerConnector.class), anyString(), any());

        JavaWSClientConnector wsClient = new JavaWSClientConnector(WS_URL);
        JavaPollingClientConnector pollClient = new JavaPollingClientConnector(POLL_URL);
        FallbackClientConnector client = new FallbackClientConnector(wsClient, pollClient);
        client.setTryConnectionInParallel(true);

        ClientConnector.ConnectionHandler connectionHandler = mock(ClientConnector.ConnectionHandler.class);
        ClientConnector.MessageHandler messageHandler = mock(ClientConnector.MessageHandler.class);
        final List<String> results = new ArrayList<>();
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                String msg = (String) invocationOnMock.getArguments()[0];
                results.add(msg);
                return null;
            }
        }).when(messageHandler).onMessage(any());
        client.addConnectionHandler(connectionHandler);
        client.addMessageHandler(messageHandler);

        client.connect();

        for (int i = 0; i < 10; i++)
            client.post(String.valueOf(i));

        // waiting for the end
        for (int i = 0; i < 10; i++) {
            Thread.sleep(500);

            if (client.getState() == ClientConnector.State.ready)
                break;
        }
        assertEquals(ClientConnector.State.ready, client.getState());

        client.close();

        // waiting for the end
        for (int i = 0; i < 10; i++) {
            Thread.sleep(500);

            if (client.getState() == ClientConnector.State.closed)
                break;
        }
        assertEquals(ClientConnector.State.closed, client.getState());

        // server state flow
        verify(serverHandler).onConnected(eq(wsInstance), eq(connectionId[0]));
        verify(serverHandler, times(10)).onMessage(eq(wsInstance), eq(connectionId[0]), any());
        verify(serverHandler).onDisconnected(eq(wsInstance), eq(connectionId[0]));

        // client state flow
        verify(connectionHandler).onStateChanged(ClientConnector.State.closed, ClientConnector.State.connecting);
        verify(connectionHandler).onStateChanged(ClientConnector.State.connecting, ClientConnector.State.ready);
        verify(connectionHandler).onStateChanged(ClientConnector.State.ready, ClientConnector.State.closing);
        verify(connectionHandler).onStateChanged(ClientConnector.State.closing, ClientConnector.State.closed);

        // client echo
        assertEquals(10, results.size());

        for (int i = 0; i < 10; i++)
            assertEquals( ""+(i*10), results.get(i));
    }


    @Test
    public void testPollingConnect() throws Exception
    {
        // server handler echoes (message * 10)
        final ServerConnectorHandler serverHandler = mock(ServerConnectorHandler.class);
        wsInstance.setHandler(serverHandler);
        pollInstance.setHandler(serverHandler);

        final String[] connectionId = new String[2];
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                if (invocationOnMock.getArguments()[0] == wsInstance)
                    connectionId[0] = (String) invocationOnMock.getArguments()[1];
                else
                    connectionId[1] = (String) invocationOnMock.getArguments()[1];
                return null;
            }
        }).when(serverHandler).onConnected(any(ServerConnector.class), anyString());
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                ServerConnector conn = (ServerConnector) invocationOnMock.getArguments()[0];
                String connId = (String) invocationOnMock.getArguments()[1];
                Integer num = Integer.parseInt((String) invocationOnMock.getArguments()[2]);
                conn.post(connId, ""+(num*10));
                return null;
            }
        }).when(serverHandler).onMessage(any(ServerConnector.class), anyString(), any());

        JavaWSClientConnector wsClient = new JavaWSClientConnector(WSBH_URL);
        JavaPollingClientConnector pollClient = new JavaPollingClientConnector(POLL_URL);
        FallbackClientConnector client = new FallbackClientConnector(wsClient, pollClient);
        client.setTryConnectionInParallel(false);

        ClientConnector.ConnectionHandler connectionHandler = mock(ClientConnector.ConnectionHandler.class);
        ClientConnector.MessageHandler messageHandler = mock(ClientConnector.MessageHandler.class);
        final List<String> results = new ArrayList<>();
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                String msg = (String) invocationOnMock.getArguments()[0];
                results.add(msg);
                return null;
            }
        }).when(messageHandler).onMessage(any());
        client.addConnectionHandler(connectionHandler);
        client.addMessageHandler(messageHandler);

        client.connect();

        for (int i = 0; i < 10; i++)
            client.post(String.valueOf(i));

        // waiting for the end
        for (int i = 0; i < 10; i++) {
            Thread.sleep(1000);

            if (client.getState() == ClientConnector.State.ready)
                break;
        }
        assertEquals(ClientConnector.State.ready, client.getState());

        client.close();

        // waiting for the end
        for (int i = 0; i < 10; i++) {
            Thread.sleep(1000);

            if (client.getState() == ClientConnector.State.closed)
                break;
        }
        assertEquals(ClientConnector.State.closed, client.getState());

        // server state flow
        verify(serverHandler).onConnected(eq(pollInstance), eq(connectionId[1]));
        verify(serverHandler, times(10)).onMessage(eq(pollInstance), eq(connectionId[1]), any());
        verify(serverHandler).onDisconnected(eq(pollInstance), eq(connectionId[1]));

        // client state flow
        verify(connectionHandler).onStateChanged(ClientConnector.State.closed, ClientConnector.State.connecting);
        verify(connectionHandler).onStateChanged(ClientConnector.State.connecting, ClientConnector.State.ready);
        verify(connectionHandler).onStateChanged(ClientConnector.State.ready, ClientConnector.State.closing);
        verify(connectionHandler).onStateChanged(ClientConnector.State.closing, ClientConnector.State.closed);

        // client echo
        assertEquals(10, results.size());

        for (int i = 0; i < 10; i++)
            assertEquals( ""+(i*10), results.get(i));
    }


    @Test
    public void testEarlyClose() throws Exception
    {
        // server handler echoes (message * 10)
        final ServerConnectorHandler serverHandler = mock(ServerConnectorHandler.class);
        wsInstance.setHandler(serverHandler);
        pollInstance.setHandler(serverHandler);

        final String[] connectionId = new String[2];
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                if (invocationOnMock.getArguments()[0] == wsInstance)
                    connectionId[0] = (String) invocationOnMock.getArguments()[1];
                else
                    connectionId[1] = (String) invocationOnMock.getArguments()[1];
                return null;
            }
        }).when(serverHandler).onConnected(any(ServerConnector.class), anyString());
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                ServerConnector conn = (ServerConnector) invocationOnMock.getArguments()[0];
                String connId = (String) invocationOnMock.getArguments()[1];
                Integer num = Integer.parseInt((String) invocationOnMock.getArguments()[2]);
                conn.post(connId, ""+(num*10));
                return null;
            }
        }).when(serverHandler).onMessage(any(ServerConnector.class), anyString(), any());

        // use blackhole for WS to simulate long connecting
        JavaWSClientConnector wsClient = new JavaWSClientConnector(WSBH_URL);
        JavaPollingClientConnector pollClient = new JavaPollingClientConnector(POLL_URL);
        FallbackClientConnector client = new FallbackClientConnector(wsClient, pollClient);
        client.setTryConnectionInParallel(false);

        ClientConnector.ConnectionHandler connectionHandler = mock(ClientConnector.ConnectionHandler.class);
        ClientConnector.MessageHandler messageHandler = mock(ClientConnector.MessageHandler.class);
        final List<String> results = new ArrayList<>();
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                String msg = (String) invocationOnMock.getArguments()[0];
                results.add(msg);
                return null;
            }
        }).when(messageHandler).onMessage(any());
        client.addConnectionHandler(connectionHandler);
        client.addMessageHandler(messageHandler);

        client.connect();

        for (int i = 0; i < 10; i++)
            client.post(String.valueOf(i));

        client.close();

        // waiting for the end
        for (int i = 0; i < 20; i++) {
            Thread.sleep(500);

            if (client.getState() == ClientConnector.State.closed)
                break;
        }
        assertEquals(ClientConnector.State.closed, client.getState());

        // server state flow
        verify(serverHandler).onConnected(eq(pollInstance), eq(connectionId[1]));
        verify(serverHandler, times(10)).onMessage(eq(pollInstance), eq(connectionId[1]), any());
        verify(serverHandler).onDisconnected(eq(pollInstance), eq(connectionId[1]));

        // client state flow
        verify(connectionHandler).onStateChanged(ClientConnector.State.closed, ClientConnector.State.connecting);
        verify(connectionHandler).onStateChanged(ClientConnector.State.connecting, ClientConnector.State.closing);
        verify(connectionHandler).onStateChanged(ClientConnector.State.closing, ClientConnector.State.closed);
    }
}
