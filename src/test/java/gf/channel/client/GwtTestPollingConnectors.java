package gf.channel.client;

import com.google.gwt.core.client.GWT;
import com.google.gwt.junit.client.GWTTestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Created by akuranov on 01/10/2015.
 */
public class GwtTestPollingConnectors extends CustomGwtTestCase {
    /*{
        prod = true;
        runStyle = com.github.neothemachine.gwt.junit.RunStylePhantomJS.class;
    }
    @Override
    protected String getProjectName() {
        return "message-channel";
    }*/

    public String getModuleName() {
        return "gf.channel.MessageChannelJUnit";
    }

    public void testPollSimpleExchange()
    {
        Logger log = Logger.getLogger("Test");
        log.info(GWT.getHostPageBaseURL());
        log.info(GWT.getModuleBaseURL());
        final List<Object> results = new ArrayList<>();
        final GwtPollingClientConnector client = new GwtPollingClientConnector("rpc/poll");
        client.addConnectionHandler(new ClientConnector.ConnectionHandler() {
            @Override
            public void onStateChanged(ClientConnector.State oldState, ClientConnector.State newState) {
                if (newState == ClientConnector.State.ready) {
                    /*new Timer() {
                        @Override
                        public void run() {
                            client.close();
                        }
                    }.schedule(3000);*/ // does not work with htmlunit:(
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


    public void testPollQuickBulkSending()
    {
        final List<Object> results = new ArrayList<>();
        final GwtPollingClientConnector client = new GwtPollingClientConnector("rpc/poll");
        client.addConnectionHandler(new ClientConnector.ConnectionHandler() {
            @Override
            public void onStateChanged(ClientConnector.State oldState, ClientConnector.State newState) {
                if (newState == ClientConnector.State.ready) {
                    /*new Timer() {
                        @Override
                        public void run() {
                            client.close();
                        }
                    }.schedule(3000);*/ // does not work with htmlunit:(
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

    public void testPollPingPongExchange()
    {
        final List<Object> results = new ArrayList<>();
        final GwtPollingClientConnector client = new GwtPollingClientConnector("rpc/poll");
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
                    client.post("" + results.size());
            }
        });
        client.connect();

        delayTestFinish(10000);
    }
}
