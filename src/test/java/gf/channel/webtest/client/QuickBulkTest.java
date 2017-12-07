package gf.channel.webtest.client;

import com.google.gwt.user.client.Timer;
import gf.channel.client.ClientConnector;
import gf.channel.client.GwtWebSocketClientConnector;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Created by anton on 02/10/2015.
 */
public class QuickBulkTest {
    public void run(final Consumer<Boolean> callback)
    {
        final Logger log = Logger.getLogger("Test");
        log.info("Starting quick bulk send test");
        boolean[] resultHolder = new boolean[1];
        final Timer timer = new Timer() {
            @Override
            public void run() {
                log.severe("TEST FAILED: TIMEOUT");
                callback.accept(false);
            }
        };

        final List<Object> results = new ArrayList<>();
        final GwtWebSocketClientConnector client = new GwtWebSocketClientConnector("rpc/ws");
        client.addConnectionHandler(new ClientConnector.ConnectionHandler() {
            @Override
            public void onStateChanged(ClientConnector.State oldState, ClientConnector.State newState) {
                if (newState == ClientConnector.State.ready) {
                } else if (newState == ClientConnector.State.closed) {
                    timer.cancel();

                    if (results.size() != 0) {
                        log.severe("TEST FAILED: received some results");
                        callback.accept(false);
                        return;
                    }

                    log.info("TEST PASSED!");
                    callback.accept(true);
                }
            }
        });
        client.addMessageHandler(new ClientConnector.MessageHandler() {
            @Override
            public void onMessage(Object message) {
                results.add(message);
            }
        });
        client.connect();

        for (int i = 0; i < 10; i++)
            client.post(String.valueOf(i));

        client.close();

        timer.schedule(10000);
    }
}
