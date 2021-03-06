package gf.channel.server;

import gf.channel.client.ClientConnector;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.jooby.Jooby;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.LogManager;

import static org.junit.Assert.assertEquals;
import static org.mockito.AdditionalMatchers.or;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

/**
 * Created by akuranov on 28/09/2015.
 */
public class TestJoobyWSConnectors {
    static {
        final InputStream inputStream = TestJoobyWSConnectors.class.getResourceAsStream("/logging.properties");
        try {
            LogManager.getLogManager().readConfiguration(inputStream);
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }

    private final static org.slf4j.Logger log = LoggerFactory.getLogger(TestJoobyWSConnectors.class);

    private final static int PORT = 12345 + new Random().nextInt(20000);
    private final static String PATH = "/rpc/ws";
    private final static String URL = "http://localhost:"+PORT+PATH;
    private static Jooby jooby;

    private static AbstractSocketServerConnector instance;

    @BeforeClass
    public static void setUp() throws Exception
    {
        /*SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();*/
        log.info("Starting server...");
        jooby = new Jooby();
        jooby.port(PORT);
        jooby.ws(PATH, new JoobyWebsocketRoute() {{
            instance = connector;
        }});
        jooby.get("/control", () -> "ok");
        new Thread(jooby::start).start();
        // wait when the server is up
        CloseableHttpClient client = HttpClients.createDefault();

        for (;;) {
            try {
                CloseableHttpResponse res = client.execute(new HttpGet("http://localhost:" + PORT + "/control"));
                if (res.getStatusLine().getStatusCode() == 200)
                    break;
            } catch (IOException e) {
                /* ignore */
            }
            Thread.sleep(1000);
        }
    }

    @AfterClass
    public static void tearDown() throws Exception {
        jooby.stop();
    }


    @Test
    public void testSimpleExchange() throws Exception
    {
        // server handler echoes (message * 10)
        final ServerConnectorHandler serverHandler = mock(ServerConnectorHandler.class);
        instance.setHandler(serverHandler);
        final String[] connectionId = new String[1];
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                connectionId[0] = (String) invocationOnMock.getArguments()[1];
                return null;
            }
        }).when(serverHandler).onConnected(any(ServerConnector.class), anyString());
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                String connId = (String) invocationOnMock.getArguments()[1];
                Integer num = Integer.parseInt((String) invocationOnMock.getArguments()[2]);
                instance.post(connId, ""+(num*10));
                return null;
            }
        }).when(serverHandler).onMessage(any(ServerConnector.class), anyString(), any());

        JavaWSClientConnector client = new JavaWSClientConnector(URL);
        ClientConnector.ConnectionHandler connectionHandler = mock(ClientConnector.ConnectionHandler.class);
        client.addConnectionHandler(connectionHandler);
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
        client.addMessageHandler(messageHandler);

        client.connect();

        for (int i = 0; i < 10; i++)
            client.post(String.valueOf(i));

        Thread.sleep(1000);

        client.close();

        // waiting for the end
        for (int i = 0; i < 10; i++) {
            Thread.sleep(500);

            if (client.getState() == ClientConnector.State.closed)
                break;
        }

        // server state flow
        verify(serverHandler).onConnected(any(ServerConnector.class), eq(connectionId[0]));
        verify(serverHandler, times(10)).onMessage(any(ServerConnector.class), eq(connectionId[0]), any());
        verify(serverHandler).onDisconnected(any(ServerConnector.class), eq(connectionId[0]));

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
    public void testQuickBulkSending() throws Exception
    {
        // server handler echoes (message * 10)
        final ServerConnectorHandler serverHandler = mock(ServerConnectorHandler.class);
        instance.setHandler(serverHandler);
        final String[] connectionId = new String[1];
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                connectionId[0] = (String) invocationOnMock.getArguments()[1];
                return null;
            }
        }).when(serverHandler).onConnected(any(ServerConnector.class), anyString());
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                String connId = (String) invocationOnMock.getArguments()[1];
                Integer num = Integer.parseInt((String) invocationOnMock.getArguments()[2]);
                instance.post(connId, ""+(num*10));
                return null;
            }
        }).when(serverHandler).onMessage(any(ServerConnector.class), anyString(), any());

        JavaWSClientConnector client = new JavaWSClientConnector(URL);
        ClientConnector.ConnectionHandler connectionHandler = mock(ClientConnector.ConnectionHandler.class);
        client.addConnectionHandler(connectionHandler);
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
        client.addMessageHandler(messageHandler);

        client.connect();

        for (int i = 0; i < 10; i++)
            client.post(String.valueOf(i));

        client.close();

        // waiting for ping-pong ends
        for (int i = 0; i < 10; i++) {
            Thread.sleep(500);

            if (client.getState() == ClientConnector.State.closed)
                break;
        }

        // server state flow
        verify(serverHandler).onConnected(any(ServerConnector.class), eq(connectionId[0]));
        verify(serverHandler, times(10)).onMessage(any(ServerConnector.class), eq(connectionId[0]), any());
        verify(serverHandler).onDisconnected(any(ServerConnector.class), eq(connectionId[0]));

        // client state flow
        verify(connectionHandler).onStateChanged(ClientConnector.State.closed, ClientConnector.State.connecting);
        /*verify(connectionHandler).onStateChanged(ClientConnector.State.connecting,
                or(ClientConnector.State.closing, ClientConnector.State.ready));*/
        verify(connectionHandler).onStateChanged(
                or(eq(ClientConnector.State.connecting),eq(ClientConnector.State.ready)),
                eq(ClientConnector.State.closing));
        verify(connectionHandler).onStateChanged(
                ClientConnector.State.closing, ClientConnector.State.closed);

        // server received all the messages
        for (int i = 0; i < 10; i++)
            verify(serverHandler).onMessage(any(ServerConnector.class), anyString(), eq(""+i));
    }



    @Test
    public void testPingPongExchange() throws Exception
    {
        // server handler echoes (num+1)
        final ServerConnectorHandler serverHandler = mock(ServerConnectorHandler.class);
        instance.setHandler(serverHandler);
        final String[] connectionId = new String[1];
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                connectionId[0] = (String) invocationOnMock.getArguments()[1];
                return null;
            }
        }).when(serverHandler).onConnected(any(ServerConnector.class), anyString());
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                String connId = (String) invocationOnMock.getArguments()[1];
                Integer num = Integer.parseInt((String) invocationOnMock.getArguments()[2]);
                instance.post(connId, ""+(num+1));
                return null;
            }
        }).when(serverHandler).onMessage(any(ServerConnector.class), anyString(), any());

        // client handler echoes (num+1) to server
        final JavaWSClientConnector client = new JavaWSClientConnector(URL);
        ClientConnector.ConnectionHandler connectionHandler = mock(ClientConnector.ConnectionHandler.class);
        client.addConnectionHandler(connectionHandler);
        ClientConnector.MessageHandler messageHandler = mock(ClientConnector.MessageHandler.class);
        final List<String> results = new ArrayList<>();
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                String msg = (String) invocationOnMock.getArguments()[0];
                results.add(msg);
                int num = Integer.parseInt(msg);
                if (num < 10)
                    client.post(""+(num+1));
                else
                    client.close();
                return null;
            }
        }).when(messageHandler).onMessage(any());
        client.addMessageHandler(messageHandler);

        client.connect();
        // init ping-pong
        client.post(String.valueOf(0));

        // waiting for ping-pong ends
        for (int i = 0; i < 10; i++) {
            Thread.sleep(500);

            if (client.getState() == ClientConnector.State.closed)
                break;
        }

        // server state flow
        verify(serverHandler).onConnected(any(ServerConnector.class), eq(connectionId[0]));
        verify(serverHandler, times(6)).onMessage(any(ServerConnector.class), eq(connectionId[0]), any());
        verify(serverHandler).onDisconnected(any(ServerConnector.class), eq(connectionId[0]));

        // client state flow
        verify(connectionHandler).onStateChanged(ClientConnector.State.closed, ClientConnector.State.connecting);
        verify(connectionHandler).onStateChanged(ClientConnector.State.connecting, ClientConnector.State.ready);
        verify(connectionHandler).onStateChanged(ClientConnector.State.ready, ClientConnector.State.closing);
        verify(connectionHandler).onStateChanged(ClientConnector.State.closing, ClientConnector.State.closed);

        // server and server received all the messages
        for (int i = 0; i < 10; i+=2) {
            verify(serverHandler).onMessage(any(ServerConnector.class), eq(connectionId[0]), eq("" + i));
            verify(messageHandler).onMessage(eq(""+(i+1)));
        }

        // after close we do not want to receive any message from server
        assertEquals(6, results.size());
    }


    @Test
    public void testServerQuickBulkSending() throws Exception
    {
        // server handler echoes (num+1)
        final ServerConnectorHandler serverHandler = mock(ServerConnectorHandler.class);
        instance.setHandler(serverHandler);
        final String[] connectionId = new String[1];
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                connectionId[0] = (String) invocationOnMock.getArguments()[1];
                for (int i = 0; i < 10; i++)
                    instance.post(connectionId[0], ""+i);
                instance.terminate(connectionId[0]);
                return null;
            }
        })
                .when(serverHandler)
                .onConnected(any(ServerConnector.class), anyString());

        /*doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                String connId = (String) invocationOnMock.getArguments()[1];
                Integer num = Integer.parseInt((String) invocationOnMock.getArguments()[2]);
                instance.post(connId, ""+(num+1));
                return null;
            }
        }).when(serverHandler).onMessage(any(ServerConnector.class), anyString(), any());*/

        // client handler echoes (num+1) to server
        final JavaWSClientConnector client = new JavaWSClientConnector(URL);
        ClientConnector.ConnectionHandler connectionHandler = mock(ClientConnector.ConnectionHandler.class);
        client.addConnectionHandler(connectionHandler);
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
        client.addMessageHandler(messageHandler);

        client.connect();

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
        verify(connectionHandler).onStateChanged(ClientConnector.State.ready, ClientConnector.State.closed);

        assertEquals(10, results.size());

        // server and server received all the messages
        for (int i = 0; i < 10; i++) {
            verify(messageHandler).onMessage(eq(""+i));
            assertEquals(""+i, results.get(i));
        }
    }
}
