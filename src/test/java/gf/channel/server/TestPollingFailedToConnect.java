package gf.channel.server;

import gf.channel.client.ClientConnector;
import gf.channel.server.servlet.AsyncPollingConnectionServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
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
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

/**
 * Created by anton on 26/09/2015.
 */
public class TestPollingFailedToConnect {
    static {
        final InputStream inputStream = TestPollingFailedToConnect.class.getResourceAsStream("/logging.properties");
        try {
            LogManager.getLogManager().readConfiguration(inputStream);
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }

    private final static org.slf4j.Logger log = LoggerFactory.getLogger(TestPollingFailedToConnect.class);

    private final static int PORT = 12345 + new Random().nextInt(20000);;
    private final static String PATH = "/rpc/push";
    private final static String BLACK_HOLE_PATH = "/rpc/blackhole";
    private final static String URL = "http://localhost:"+PORT+PATH;
    private static Server server;

    private static MyPollingServlet instance;

    public static class MyPollingServlet extends AsyncPollingConnectionServlet {
        public MyPollingServlet() {
            instance = this;
        }

        @Override
        public void init(ServletConfig config) throws ServletException {
            super.init(config);
            this.connectionTimeout = 10000;
            this.longPollingTimeout = 2000;
        }
    }

    public static class BlackHoleServlet extends HttpServlet {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            AsyncContext conn = req.startAsync();
            conn.setTimeout(1000000);
        }
    }


    @BeforeClass
    public static void setUp() throws Exception
    {
        log.info("Starting server...");
        server = new Server(PORT);

        ServletHandler handler = new ServletHandler();
        ServletHolder servletHolder = handler.addServletWithMapping(MyPollingServlet.class, PATH);
        servletHolder.setInitOrder(1);  // load on startup
        handler.addServletWithMapping(BlackHoleServlet.class, BLACK_HOLE_PATH);
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
        JavaPollingClientConnector client = new JavaPollingClientConnector("http://localhost:"+PORT+BLACK_HOLE_PATH);
        client.setMaxReconnectionAttempts(3);
        client.setReconnectionDelay(500);
        client.setRequestTimeout(500);
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

        // client state flow
        verify(connectionHandler).onStateChanged(ClientConnector.State.closed, ClientConnector.State.connecting);
        verify(connectionHandler).onStateChanged(ClientConnector.State.connecting, ClientConnector.State.closed);
    }


    @Test
    public void testServiceUnavailable() throws Exception
    {
        JavaPollingClientConnector client = new JavaPollingClientConnector("http://localhost:"+PORT+"/xxx");
        client.setMaxReconnectionAttempts(3);
        client.setReconnectionDelay(500);
        client.setRequestTimeout(500);
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

        // client state flow
        verify(connectionHandler).onStateChanged(ClientConnector.State.closed, ClientConnector.State.connecting);
        verify(connectionHandler).onStateChanged(ClientConnector.State.connecting, ClientConnector.State.closed);
    }


    @Test
    public void testLongPollingRefresh() throws Exception
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
        final List<Integer> statusCodes = new ArrayList<>();
        final JavaPollingClientConnector client = new JavaPollingClientConnector(URL) {
            @Override
            protected void onReadyStateChange(Object httpRequest, int status, String text) {
                if (this.httpRequest == httpRequest)
                    statusCodes.add(status);
                super.onReadyStateChange(httpRequest, status, text);
            }
        };
        ClientConnector.ConnectionHandler connectionHandler = mock(ClientConnector.ConnectionHandler.class);
        client.addConnectionHandler(connectionHandler);
        ClientConnector.MessageHandler messageHandler = mock(ClientConnector.MessageHandler.class);
        client.addMessageHandler(messageHandler);

        client.connect();

        Thread.sleep(3000);

        client.close();

        // waiting
        for (int i = 0; i < 10; i++) {
            Thread.sleep(500);

            if (client.getState() == ClientConnector.State.closed)
                break;
        }

        // server state flow
        verify(serverHandler).onConnected(any(ServerConnector.class), eq(connectionId[0]));
        verify(serverHandler, times(0)).onMessage(any(ServerConnector.class), eq(connectionId[0]), any());
        verify(serverHandler).onDisconnected(any(ServerConnector.class), eq(connectionId[0]));

        // client state flow
        verify(connectionHandler).onStateChanged(ClientConnector.State.closed, ClientConnector.State.connecting);
        verify(connectionHandler).onStateChanged(ClientConnector.State.connecting, ClientConnector.State.ready);
        verify(connectionHandler).onStateChanged(ClientConnector.State.ready, ClientConnector.State.closing);
        verify(connectionHandler).onStateChanged(ClientConnector.State.closing, ClientConnector.State.closed);

        // HTTP-NO-CONTENT was received
        assertTrue(statusCodes.contains(204));
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
        final List<Integer> statusCodes = new ArrayList<>();
        class MyClientConnector extends JavaPollingClientConnector {
            public MyClientConnector(String path) {
                super(path);
            }

            @Override
            protected void onReadyStateChange(Object httpRequest, int status, String text) {
                if (this.httpRequest == httpRequest)
                    statusCodes.add(status);
                super.onReadyStateChange(httpRequest, status, text);
            }

            public void setURL(String url) {
                this.url = url;
            }
        };
        final MyClientConnector client = new MyClientConnector(URL);
        ClientConnector.ConnectionHandler connectionHandler = mock(ClientConnector.ConnectionHandler.class);
        client.addConnectionHandler(connectionHandler);
        ClientConnector.MessageHandler messageHandler = mock(ClientConnector.MessageHandler.class);
        client.addMessageHandler(messageHandler);
        client.setMaxReconnectionAttempts(10);
        client.setReconnectionDelay(500);
        client.setRequestTimeout(500);

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
        client.setURL("http://localhost:" + PORT + BLACK_HOLE_PATH);

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

        Thread.sleep(1000);

        // normalize URL and reconnect
        client.setURL(URL);

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

        // HTTP-NO-CONTENT was received
        assertTrue(statusCodes.contains(204));
        // timeout was received
        assertTrue(statusCodes.contains(408));
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
        final List<Integer> statusCodes = new ArrayList<>();
        class MyClientConnector extends JavaPollingClientConnector {
            public MyClientConnector(String path) {
                super(path);
            }

            @Override
            protected void onReadyStateChange(Object httpRequest, int status, String text) {
                if (this.httpRequest == httpRequest)
                    statusCodes.add(status);
                super.onReadyStateChange(httpRequest, status, text);
            }

            public void setURL(String url) {
                this.url = url;
            }
        };
        final MyClientConnector client = new MyClientConnector(URL);
        ClientConnector.ConnectionHandler connectionHandler = mock(ClientConnector.ConnectionHandler.class);
        client.addConnectionHandler(connectionHandler);
        ClientConnector.MessageHandler messageHandler = mock(ClientConnector.MessageHandler.class);
        client.addMessageHandler(messageHandler);
        client.setMaxReconnectionAttempts(3);
        client.setReconnectionDelay(500);
        client.setRequestTimeout(500);

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
        client.setURL("http://localhost:" + PORT + BLACK_HOLE_PATH);

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

        // HTTP-NO-CONTENT was received
        assertTrue(statusCodes.contains(204));
        // timeout was received
        assertTrue(statusCodes.contains(408));
    }
}
