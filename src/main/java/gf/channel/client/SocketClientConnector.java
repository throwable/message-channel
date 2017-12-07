package gf.channel.client;

import com.google.gwt.core.client.GWT;
import gf.channel.shared.LineIterator;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by akuranov on 28/09/2015.
 */
public abstract class SocketClientConnector extends ClientConnector
{
    private final static Logger log = Logger.getLogger(SocketClientConnector.class.getName());

    protected volatile String url;

    protected String connId;
    /** serialized messages */
    protected List<String> queue = new ArrayList<String>();
    protected long receivedCounter;
    protected long sentCounter;

    /**
     * Maximum number of sequential reconnection attempts
     */
    protected int maxReconnectionAttempts = 12;
    protected int reconnectionAttempts;

    /**
     * Timeout to connect/reconnect to server
     */
    protected int connectTimeout = 10000;

    /** Timeout before trying to re-connect */
    protected int reconnectionDelay = 5000;

    /**
     * Timeout of long-polling operation
     * value received from server while connecting
     * TODO:
     */
    protected int heartbeatInterval;

    /**
     * Timeout of connection. Close connection if there was no successful http request during this time.
     * Also timeout may be connectTimeout * maxReconnectionAttempts
     * value received from server while connecting
     */
    protected int connectionTimeout;
    protected long lastConnectionLostTimestamp;

    /** must not throw any exception if socket is open */
    protected abstract void socketConnect();

    /** must not throw any exception if socket is not open */
    protected abstract void socketClose();

    /** must not throw any exception if socket is not open */
    protected abstract void socketSend(String text);

    protected abstract void schedule(int delay, @Nonnull Runnable task);

    protected abstract void startScheduledTask(int delay, @Nonnull Runnable task);

    protected abstract void stopAllScheduledTasks();


    protected void socketConnect(int connectTimeout) {
        log.finest("SCONN: starting connection");
        // connection timeout
        schedule(connectTimeout, new Runnable() {
            @Override
            public void run() {
                log.finest("SCONN: connect timeout");
                try {
                    // MUST fire onConnectionClose() or onConnectionError()
                    socketClose();
                } catch (Exception ex) {
                }
            }
        });
        socketConnect();
    }


    public SocketClientConnector(String path) {
        if ( path.matches( "\\w+://.+" ) ) {
            // full url
            url = path;
        } else {
            // context-relative path
            url = GWT.getModuleBaseURL() + path;
        }

        if (url.startsWith("http:"))
            url = "ws:"+url.substring(5);
        else if (url.startsWith( "https:" ))
            url = "wss:"+url.substring(6);
        else
            throw new IllegalArgumentException("Invalid URL: "+path);
    }


    /** open connection callback */
    protected void onConnectionOpen() {
        if (connId == null) {
            // connect
            socketSend("N");
            log.finest("SCONN: creating connection...");
        }
        else {
            log.finest("SCONN: re-creating connection...");
            // re-connect
            StringBuilder sb = new StringBuilder()
                    .append("R").append('\n')
                    .append(connId).append('\n')
                    .append(receivedCounter).append('\n')
                    .append(sentCounter).append('\n');

            for (Object message : queue) {
                sb.append(message).append('\n');
            }
            socketSend(sb.toString());

            if (state == State.closing) {
                // reconnected but closing
                log.finest("SCONN: closing...");
                socketSend("C"+"\n"+connId);
            }
        }
    }


    /** connection error callback */
    protected void onConnectionError() {
        onConnectionClose();
    }


    /** connection close callback */
    protected void onConnectionClose() {
        stopAllScheduledTasks();

        // connection lost... retry
        if (state != State.closed) {
            if (state != State.connecting && this.state != State.closing) {
                lastConnectionLostTimestamp = System.currentTimeMillis();
                changeState(State.connecting);
                reconnectionAttempts = 0;
                lastConnectionLostTimestamp = System.currentTimeMillis();
                log.finest("SCONN: connection lost");
            } else {
                reconnectionAttempts++;
            }

            if (reconnectionAttempts > maxReconnectionAttempts
                    // connection too old
                    || connId != null && System.currentTimeMillis() > lastConnectionLostTimestamp + connectionTimeout) {
                log.finest("SCONN: could not reconnect");
                changeState(State.closed);
            } else
                // try first reconnect attempt without delay
                if (reconnectionAttempts == 0) {
                    log.finest("SCONN: reconnecting...");
                    socketConnect(connectTimeout);
                } else {
                    if (log.isLoggable(Level.FINEST))
                        log.finest("SCONN: reconnecting in " + reconnectionDelay);
                    schedule(reconnectionDelay, new Runnable() {
                        @Override
                        public void run() {
                            log.finest("SCONN: reconnecting...");
                            socketConnect(connectTimeout);
                        }
                    });
                }
        }
    }

    /** receive message callback */
    protected void onMessage(String text)
    {
        /*String[] ss = text != null ?
                text.split("\\r?\\n") : new String[0];*/
        LineIterator lit = new LineIterator(text);
        String cmd = lit.next();

        if (connId == null) {
            // not connected
            if (!"N".equals(cmd)) {
                log.severe("SCONN: protocol mismatch (expecting N)");
                changeState(State.closed);
                socketClose();
                return;
            }
            reconnectionAttempts = 0;
            connId = lit.next();
            heartbeatInterval = Integer.parseInt(lit.next());
            connectionTimeout = Integer.parseInt(lit.next());

            if (log.isLoggable(Level.FINEST))
                log.finest("SCONN: connection created: id=" + connId);

            if (this.state == State.connecting)
                changeState(State.ready);

            if (!queue.isEmpty()) {
                if (log.isLoggable(Level.FINEST))
                    log.finest("SCONN: sending queued messages: size=" + queue.size());
                StringBuilder sb = new StringBuilder()
                        .append("S").append('\n')
                        .append(connId).append('\n')
                        .append(receivedCounter).append('\n')
                        .append(sentCounter).append('\n');

                for (Object message : queue) {
                    sb.append(message).append('\n');
                }
                socketSend(sb.toString());

                if (state == State.closing) {
                    // reconnected but closing
                    log.finest("SCONN: requesting server close...");
                    socketSend("C"+"\n"+connId);
                }
            }

            // heartbeat
            startScheduledTask(heartbeatInterval, new Runnable() {
                @Override
                public void run() {
                    if (state == State.ready) {
                        StringBuilder sb = new StringBuilder()
                                .append("H").append('\n')
                                .append(connId).append('\n')
                                .append(receivedCounter).append('\n')
                                .append(sentCounter).append('\n');
                        socketSend(sb.toString());
                        log.finest("SCONN: sending heartbeat...");
                    }
                }
            });
        }
        else if ("S".equals(cmd) || "HA".equals(cmd)) {
            // server send or heartbeat
            if (this.state == State.connecting) {
                log.finest("SCONN: reconnected");
                changeState(State.ready);

                // reconnected - heartbeat
                startScheduledTask(heartbeatInterval, new Runnable() {
                    @Override
                    public void run() {
                        if (state == State.ready) {
                            StringBuilder sb = new StringBuilder()
                                    .append("H").append('\n')
                                    .append(connId).append('\n')
                                    .append(receivedCounter).append('\n')
                                    .append(sentCounter).append('\n');
                            socketSend(sb.toString());
                            log.finest("SCONN: sending heartbeat...");
                        }
                    }
                });
            }

            if (reconnectionAttempts > 0)
                reconnectionAttempts = 0;

            long msgCountReceivedFromClient = Long.parseLong(lit.next());

            // we use synchronization here to send synchronous responses in the same HttpResponse
            // protocol mismatch - older events were removed
            if (msgCountReceivedFromClient < sentCounter) {
                if (log.isLoggable(Level.FINEST))
                   log.finest("SCONN: protocol mismatch, connection id=" + connId);
                changeState(State.closed);
                return;
            }

            // removing messages from queue confirmed by client
            while (sentCounter < msgCountReceivedFromClient) {
                queue.remove(0);
                sentCounter++;
            }

            if (state == State.closing)
                return;

            if ("S".equals(cmd)) {
                long readMsgNumber = Long.parseLong(lit.next());
                List<Object> msgs = new ArrayList<>();

                // read and process messages
                while (lit.hasNext()) {
                    // discard duplicates
                    if (readMsgNumber < receivedCounter) {
                        readMsgNumber++;
                        continue;
                    }
                    readMsgNumber++;

                    String messageString = lit.next();
                    Object  message = messageSerializer.fromString(messageString);
                    msgs.add(message);
                    receivedCounter++;
                }

                if (log.isLoggable(Level.FINEST))
                    log.finest("SCONN: received "+msgs.size()+" message(s)\n"+text);

                for (Object message : msgs)
                    listeners.fireEvent(new MessageEvent(message));
            }
            else {
                log.finest("SCONN: heartbeat reply");
            }
        }
        else if ("CR".equals(cmd)) {
            // server connection close request
            log.finest("SCONN: connection closed by server close request");
            socketSend("CA" + "\n" + connId);
            changeState(State.closed);
        }
        else if ("C".equals(cmd)) {
            // server connection close
            changeState(State.closed);
            socketClose();
            log.finest("SCONN: connection closed gracefully");
        }
        else {
            // error
            log.severe("SCONN: protocol mismatch: unknown command: "+cmd);
            changeState(State.closed);
            socketClose();
        }
    }


    @Override
    public void connect() {
        if ( isStarted() )
            throw new IllegalStateException("Connection already started");

        queue.clear();
        this.connId = null;
        this.receivedCounter = 0;
        this.sentCounter = 0;
        this.reconnectionAttempts = 0;
        changeState(State.connecting);
        log.finest("SCONN: connecting");
        // multiple invocations may be grouped in one request later
        socketConnect(connectTimeout);
    }

    @Override
    public void close() {
        if (!isStarted())
            throw new IllegalStateException( "Connection is not started" );

        log.finest("SCONN: closing connection");

        // server connection close request
        if (state == State.ready)
            socketSend("C"+"\n"+connId);
        changeState(State.closing);
    }

    @Override
    public void post(Object payload)
    {
        if ( !isStarted() )
            throw new IllegalStateException("Connection is not started");

        String msg = messageSerializer.toString(payload);
        queue.add(msg);

        if (state == State.ready) {
            StringBuilder sb = new StringBuilder()
                    .append("S").append('\n')
                    .append(connId).append('\n')
                    .append(receivedCounter).append('\n')
                    .append(sentCounter + queue.size()-1).append('\n')
                    .append(msg).append('\n');
            socketSend(sb.toString());
            if (log.isLoggable(Level.FINEST))
                log.finest("SCONN: sending message: " + msg);
        } else {
            if (log.isLoggable(Level.FINEST))
                log.finest("SCONN: queueing message: " + msg);
        }
    }

    public int getMaxReconnectionAttempts() {
        return maxReconnectionAttempts;
    }

    public void setMaxReconnectionAttempts(int maxReconnectionAttempts) {
        this.maxReconnectionAttempts = maxReconnectionAttempts;
    }

    public int getReconnectionDelay() {
        return reconnectionDelay;
    }

    public void setReconnectionDelay(int reconnectionDelay) {
        this.reconnectionDelay = reconnectionDelay;
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }
}
