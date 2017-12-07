package gf.channel.server;

import gf.channel.client.SocketClientConnector;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.*;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.WebSocketSession;

import javax.annotation.Nonnull;
import java.net.URI;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by akuranov on 28/09/2015.
 */
public class JavaWSClientConnector extends SocketClientConnector {
    private static Logger log = Logger.getLogger(JavaWSClientConnector.class.getName());
    private Session session;
    private Timer timer = new Timer(true);

    public JavaWSClientConnector(String url) {
        super(url);
    }

    @WebSocket
    public class WebSocketConnection {
        @OnWebSocketMessage
        public void onMessage(String s) {
            synchronized (JavaWSClientConnector.this) {
                log.finest("SOCKET: onMessage");
                JavaWSClientConnector.this.onMessage(s);
            }
        }

        @OnWebSocketConnect
        public void onOpen(Session session) {
            synchronized (JavaWSClientConnector.this) {
                log.finest("SOCKET: onOpen");
                JavaWSClientConnector.this.session = session;
                JavaWSClientConnector.this.onConnectionOpen();
            }
        }

        @OnWebSocketClose
        public void onClose(int i, String s) {
            synchronized (JavaWSClientConnector.this) {
                log.finest("SOCKET: onClose");
                JavaWSClientConnector.this.onConnectionClose();
            }
        }
    }

    @Override
    protected void socketConnect() {
        final WebSocketClient client;
        try {
            client = new WebSocketClient();
        } catch (Exception ex) {
            throw new UnsupportedOperationException(ex);
        }

        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    client.start();
                    client.setConnectTimeout(connectTimeout);
                    ClientUpgradeRequest clientUpgradeRequest = new ClientUpgradeRequest();
                    log.finest("SOCKET: Connecting to "+url);
                    Future<Session> connect = client.connect(new WebSocketConnection(),
                            new URI(url), clientUpgradeRequest);
                    connect.get();
                } catch (Exception ex) {
                    //ex.printStackTrace();
                    timer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            synchronized (JavaWSClientConnector.this) {
                                log.finest("SOCKET: onError");
                                onConnectionError();
                            }
                        }
                    }, 0);
                }
            }
        }, 0 );
    }

    @Override
    protected synchronized void socketClose() {
        try {
            if (session != null && session.isOpen())
                session.close();
        } catch (Exception ex) {
            // TODO: close not supported
            log.log(Level.SEVERE, "SOCKET: Close", ex);
        }
    }

    @Override
    protected synchronized void socketSend(String text) {
        try {
            if (session != null && session.isOpen())
                session.getRemote().sendString(text);
        } catch (Exception ex) {
            // TODO: close not supported
            log.log(Level.SEVERE, "SOCKET: Send", ex);
        }
    }

    private TimerTask task;

    @Override
    protected synchronized void schedule(int delay, @Nonnull final Runnable task) {
        log.finest("SCHEDULE: new task: "+task);
        this.task = new TimerTask() {
            @Override
            public void run() {
                synchronized (JavaWSClientConnector.this) {
                    log.finest("SCHEDULE: running task: "+JavaWSClientConnector.this.task);
                    JavaWSClientConnector.this.task = null;
                    task.run();
                }
            }
        };
        timer.schedule(this.task, delay);
    }


    @Override
    protected synchronized void startScheduledTask(int delay, final @Nonnull Runnable task) {
        log.finest("SCHEDULE: new periodic task: "+task);
        if (this.task != null) {
            this.task.cancel();
        }
        this.task = new TimerTask() {
            @Override
            public void run() {
                log.finest("SCHEDULE: running periodic task: "+JavaWSClientConnector.this.task);
                task.run();
            }
        };
        timer.schedule(this.task, delay, delay);
    }

    @Override
    protected void stopAllScheduledTasks() {
        log.finest("SCHEDULE: stopped task: "+task);
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }
}
