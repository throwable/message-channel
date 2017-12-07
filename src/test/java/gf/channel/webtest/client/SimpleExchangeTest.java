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
public class SimpleExchangeTest {
    public void run(final Consumer<Boolean> callback)
    {
        final Logger log = Logger.getLogger("Test");
        log.info("Starting simple exchange test");
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
                    /*new Timer() {
                        @Override
                        public void run() {
                            client.close();
                        }
                    }.schedule(3000);*/ // does not work with htmlunit:(
                    for (int i = 0; i < 10; i++)
                        client.post(String.valueOf(i));
                } else if (newState == ClientConnector.State.closed) {
                    timer.cancel();

                    if (results.size() != 10) {
                        log.severe("TEST FAILED: not all results were received");
                        callback.accept(false);
                        return;
                    }

                    for (int i = 0; i < 10; i++)
                        if (!("OK " + i).equals(results.get(i))) {
                            log.severe("TEST FAILED: received results are invalid");
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
                if (results.size() == 10)
                    client.close();
            }
        });
        client.connect();

        timer.schedule(10000);
    }
}
