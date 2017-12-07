package gf.channel.server;

import gf.channel.shared.GwtStreamerSerializer;
import org.jooby.Request;
import org.jooby.WebSocket;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Timer;
import java.util.TimerTask;

public class JoobyWebsocketRoute implements WebSocket.OnOpen {
    /** Close connection if was not initialized during this time */
    protected int connectionInitTimeout = 5000;

    /** Max client message size */
    // set globally by config file
    //protected int maxMessageSize = 256000;

    protected Timer timer = new Timer("ws-idle-connections", true);

    public JoobyWebsocketRoute() {
        if (connector.getMessageSerializer() == null)
            connector.setMessageSerializer(new GwtStreamerSerializer());
    }

    public int getConnectionInitTimeout() {
        return connectionInitTimeout;
    }

    public void setConnectionInitTimeout(int connectionInitTimeout) {
        this.connectionInitTimeout = connectionInitTimeout;
    }

    protected final AbstractSocketServerConnector connector = new AbstractSocketServerConnector() {
        @Override
        protected void socketSend(@Nullable Object websocketSession, @Nonnull String text) {
            WebSocket websocket = (WebSocket) websocketSession;
            if (websocket != null && websocket.isOpen()) {
                try {
                    websocket.send(text);
                } catch (Exception ex) {
                    /* ignore */
                }
            }
        }

        @Override
        protected void socketClose(@Nullable Object websocketSession) {
            WebSocket websocket = (WebSocket) websocketSession;
            if (websocket != null) {
                try {
                    websocket.close();
                } catch (Exception ex) { /* ignore */}
            }
        }
    };


    @Override
    public void onOpen(final Request req, final WebSocket ws) throws Exception {
        if (connector.getHandler() == null) {
            ServerConnectorHandler handler = ws.require(ServerConnectorHandler.class);
            connector.setHandler(handler);
        }
        final TimerTask interrupt = new TimerTask() {
            @Override
            public void run() {
                try {ws.close();} catch (Exception ex) { /* ignore */}
            }
        };
        timer.schedule(interrupt, connectionInitTimeout);
        ws.onMessage(message -> {
            interrupt.cancel();
            connector.service(ws, message.value() == null ? "" : message.value());
        });
    }
}
