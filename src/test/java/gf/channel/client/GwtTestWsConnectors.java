package gf.channel.client;

import com.google.gwt.core.client.GWT;
import com.google.gwt.junit.client.GWTTestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/************************************************************************
 *
 *
 *    N O T E !!!
 *
 *    These tests are broken due to lack of support of WebSockets by HtmlUnit.
 *
 *    To test websockets run gf.channel.webtest.client.MessageChannelTestUi
 *
 *
 * Created by akuranov on 01/10/2015.
 */
public class GwtTestWsConnectors extends CustomGwtTestCase {
    {
        prod = true;
        web = true;
        //runStyle = com.github.neothemachine.gwt.junit.RunStylePhantomJS.class;
    }
    /*@Override
    protected String getProjectName() {
        return "message-channel";
    }*/

    public String getModuleName() {
        return "gf.channel.MessageChannelJUnit";
    }

    /*
     * Actual version of HtmlUnit has a broken support of Websockets. Use superdevmode.
     */

    public void testWsSimpleExchange()
    {
        Logger log = Logger.getLogger("Test");
        log.info(GWT.getHostPageBaseURL());
        log.info(GWT.getModuleBaseURL());
        final List<Object> results = new ArrayList<>();
        final GwtWebSocketClientConnector client = new GwtWebSocketClientConnector("rpc/ws");
        client.addConnectionHandler(new ClientConnector.ConnectionHandler() {
            @Override
            public void onStateChanged(ClientConnector.State oldState, ClientConnector.State newState) {
                if (newState == ClientConnector.State.ready) {
                    for (int i = 0; i < 10; i++)
                        client.post(String.valueOf(i));
                } else if (newState == ClientConnector.State.closed) {
                    assertEquals(10, results.size());

                    for (int i = 0; i < 10; i++)
                        assertEquals("OK " + i, results.get(i));

                    finishTest();
                }
            }
        });
        client.addMessageHandler(new ClientConnector.MessageHandler() {
            @Override
            public void onMessage(Object message) {
                results.add(message);
                if (results.size() == 10)
                    client.close();
            }
        });
        client.connect();

        delayTestFinish(10000);
    }


    public void testWsQuickBulkSending()
    {
        final List<Object> results = new ArrayList<>();
        final GwtWebSocketClientConnector client = new GwtWebSocketClientConnector("rpc/ws");
        client.addConnectionHandler(new ClientConnector.ConnectionHandler() {
            @Override
            public void onStateChanged(ClientConnector.State oldState, ClientConnector.State newState) {
                if (newState == ClientConnector.State.ready) {
                } else if (newState == ClientConnector.State.closed) {
                    assertEquals(0, results.size());
                    finishTest();
                }
            }
        });
        client.addMessageHandler(new ClientConnector.MessageHandler() {
            @Override
            public void onMessage(Object message) {
                results.add(message);
                if (results.size() == 10)
                    client.close();
            }
        });
        client.connect();

        for (int i = 0; i < 10; i++)
            client.post(String.valueOf(i));

        client.close();

        delayTestFinish(10000);
    }

    public void testWsPingPongExchange()
    {
        final List<Object> results = new ArrayList<>();
        final GwtWebSocketClientConnector client = new GwtWebSocketClientConnector("rpc/ws");
        client.addConnectionHandler(new ClientConnector.ConnectionHandler() {
            @Override
            public void onStateChanged(ClientConnector.State oldState, ClientConnector.State newState) {
                if (newState == ClientConnector.State.ready) {
                    client.post("0");
                } else if (newState == ClientConnector.State.closed) {
                    assertEquals(10, results.size());

                    for (int i = 0; i < 10; i++)
                        assertEquals("OK " + i, results.get(i));

                    finishTest();
                }
            }
        });
        client.addMessageHandler(new ClientConnector.MessageHandler() {
            @Override
            public void onMessage(Object message) {
                results.add(message);
                if (results.size() == 10)
                    client.close();
                else
                    client.post(""+results.size());
            }
        });
        client.connect();

        delayTestFinish(10000);
    }
}
