package gf.channel.server.servlet;

import gf.channel.server.AbstractSocketServerConnector;
import gf.channel.shared.GwtStreamerSerializer;
import gf.channel.shared.MessageSerializer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.servlet.*;

/**
 * Created by akuranov on 28/09/2015.
 */
public class JettyWSConnectionServlet extends WebSocketServlet
{
    /** Create session on incoming connection. If false session may be created by external auth facility. */
    // TODO:
    protected boolean createSession;

    /** Dispose session on connection close */
    // TODO:
    protected boolean disposeSessionOnConnectionClose;

    /** Close connection if was not initialized during this time */
    protected int connectionInitTimeout = 5000;

    /** Max client message size */
    protected int maxMessageSize = 256000;

    protected Timer timer = new Timer("ws-idle-connections", true);


    protected AbstractSocketServerConnector connector = new AbstractSocketServerConnector() {
        @Override
        protected void socketSend(@Nullable Object websocketSession, @Nonnull String text) {
            Session session = (Session) websocketSession;
            if (session != null && session.isOpen()) {
                try {
                    session.getRemote().sendString(text);
                } catch (Exception ex) {}
            }
        }

        @Override
        protected void socketClose(@Nullable Object websocketSession) {
            Session session = (Session) websocketSession;
            if (session != null) {
                try {
                    session.close();
                } catch (Exception ex) { /* ignore */}
            }
        }
    };

    @Override
    public void init(ServletConfig config) throws ServletException {
        this.createSession = "true".equalsIgnoreCase( config.getInitParameter( "createSession" ) );
        this.disposeSessionOnConnectionClose = "true".equalsIgnoreCase( config.getInitParameter( "disposeSessionOnConnectionClose" ) );
        try { this.connectionInitTimeout = Integer.parseInt(config.getInitParameter("heartbeatInterval")); } catch ( Exception ex ) {}
        try { this.maxMessageSize = Integer.parseInt(config.getInitParameter("maxMessageSize")); } catch ( Exception ex ) {}
        try { connector.setHeartbeatInterval(Integer.parseInt(config.getInitParameter("heartbeatInterval"))); } catch ( Exception ex ) {}
        try { connector.setConnectionTimeout(Integer.parseInt(config.getInitParameter("connectionTimeout"))); } catch ( Exception ex ) {}
        try { connector.setMaxQueueLength(Integer.parseInt(config.getInitParameter("maxQueueLength"))); } catch ( Exception ex ) {}

        String mserClass = config.getInitParameter("messageSerializerClass");
        if (mserClass == null) {
            connector.setMessageSerializer(new GwtStreamerSerializer());
        } else {
            try {
                Class<?> cl = Thread.currentThread().getContextClassLoader().loadClass(mserClass);
                connector.setMessageSerializer((MessageSerializer) cl.newInstance());
            } catch (Exception ex) {
                throw new ServletException(ex);
            }
        }

        super.init(config);
    }

    @WebSocket
    public class WebSocketConnection
    {
        private Session session;
        private TimerTask interrupt;

        @OnWebSocketClose
        public void onClose(int statusCode, String reason) { }

        @OnWebSocketConnect
        public void onConnect(Session session) {
            this.session = session;
            //session.setIdleTimeout(connector.getHeartbeatInterval() + 10000);
            //connection.setMaxTextMessageSize(maxMessageSize);
            interrupt = new TimerTask() {
                @Override
                public void run() {
                    try {session.close();} catch (Exception ex) { /* ignore */}
                }
            };
            timer.schedule(interrupt, connectionInitTimeout);
        }

        @OnWebSocketMessage
        public void onMessage(String message) {
            if (interrupt != null) {
                interrupt.cancel();
                interrupt = null;
            }
            connector.service(session, message == null ? "" : message);
        }
    }


    private class ConnectorSocketCreator implements WebSocketCreator
    {
        @Override
        public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
        {
            return new WebSocketConnection();
        }
    }


    @Override
    public void configure(WebSocketServletFactory factory) {
        factory.getPolicy().setIdleTimeout(connector.getHeartbeatInterval() * 3);
        factory.getPolicy().setAsyncWriteTimeout(connector.getHeartbeatInterval() * 2);
        factory.getPolicy().setMaxTextMessageSize(maxMessageSize);
        factory.setCreator(new ConnectorSocketCreator());
    }
}
