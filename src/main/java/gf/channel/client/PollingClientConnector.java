package gf.channel.client;

import com.google.gwt.core.client.GWT;
import gf.channel.shared.LineIterator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Long-Polling client connector. Implementation of protocol.
 * Created by anton on 25/09/2015.
 */
public abstract class PollingClientConnector extends ClientConnector {
    private final static Logger log = Logger.getLogger(PollingClientConnector.class.getName());

    protected volatile String url;

    protected String connId;
    protected List<String> queue = new ArrayList<>();
    protected long receivedCounter;
    protected long sentCounter;

    /**
     * Maximum number of sequential reconnection attempts
     */
    protected int maxReconnectionAttempts = 12;
    protected int reconnectionAttempts;

    /** Timeout to connect/reconnect to server */
    protected int requestTimeout = 10000;

    /** Timeout before trying to re-connect */
    protected int reconnectionDelay = 5000;

    /**
     * Timeout of long-polling operation
     * value received from server while connecting
     */
    protected int longPollingTimeout;

    /**
     * Timeout of connection. Close connection if there was no successful http request during this time.
     * Also timeout may be connectTimeout * maxReconnectionAttempts
     * value received from server while connecting
     */
    protected int connectionTimeout;
    protected long lastConnectionLostTimestamp;

    @Nullable
    protected Object httpRequest;
    protected boolean longPollInProgress;


    public PollingClientConnector(String path)
    {
        if ( path.matches( "\\w+://.+" ) ) {
            // full url
            url = path;
		/*} else if ( path.startsWith( "/" ) ) {
			// server-absolute path
			String ctx = GWT.getHostPageBaseURL();
			String host = Pattern.compile( "(\\w+://.+)/.+" ).matcher( ctx ).group(1);
			url = host + path;*/
        } else {
            // context-relative path
            url = GWT.getModuleBaseURL() + path;
        }
    }


    protected void onReadyStateChange(Object httpRequest, int status, @Nullable String text)
    {
        if (this.httpRequest != httpRequest)
            // old request, a new one is in progress
            return;

        this.httpRequest = null;

        try {
            if (status == 200) {
                // Http OK
                /*String[] ss = text != null ?
                        text.split("\\r?\\n") : new String[0];*/
                LineIterator lit = new LineIterator(text);

                if (connId == null) {
                    // not connected
                    connId = lit.next();
                    longPollingTimeout = Integer.parseInt(lit.next());
                    connectionTimeout = Integer.parseInt(lit.next());
                    if (this.state == State.connecting)
                        changeState(State.ready);
                    if (log.isLoggable(Level.FINEST))
                        log.finest("PCONN: connection established: id=" + connId);
                } else {
                    if (this.state == State.connecting)
                        changeState(State.ready);

                    long msgCountReceivedFromClient = Long.parseLong(lit.next());
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

                    // we use synchronization here to send synchronous responses in the same HttpResponse
                    // protocol mismatch - older events were removed
                    if (msgCountReceivedFromClient < sentCounter) {
                        log.severe("Protocol mismatch, connection: " + connId);
                        changeState(State.closed);
                        return;
                    }

                    // removing messages from queue confirmed by client
                    while (sentCounter < msgCountReceivedFromClient) {
                        queue.remove(0);
                        sentCounter++;
                    }

                    if (log.isLoggable(Level.FINEST))
                        log.finest("PCONN: received " + msgs.size() + " message(s)\n" + text);

                    for (Object message : msgs)
                        listeners.fireEvent(new MessageEvent(message));
                }

                reconnectionAttempts = 0;
                exchange();
            } else if (status == 204) {
                // No content
                if (this.state == State.connecting)
                    changeState(State.ready);
                log.finest("Poll refresh");
                exchange();
            } else if (status == 410) {
                // Gone (closed)
                log.finest("PCONN: connection closed");
                changeState(State.closed);
            } else {
                if (log.isLoggable(Level.FINEST))
                    log.finest("PCONN: http error, status=" + status);

                // error or timeout
                if (this.state != State.connecting && this.state != State.closing) {
                    changeState(State.connecting);
                    reconnectionAttempts = 0;
                    lastConnectionLostTimestamp = System.currentTimeMillis();
                    log.finest("PCONN: connection lost");
                } else {
                    reconnectionAttempts++;
                }

                if (reconnectionAttempts > maxReconnectionAttempts
                        // connection too old
                        || connId != null && System.currentTimeMillis() > lastConnectionLostTimestamp + connectionTimeout) {
                    changeState(State.closed);
                    log.finest("PCONN: could not reconnect");
                } else
                    // try first reconnect attempt without delay
                    if (reconnectionAttempts == 0) {
                        log.finest("PCONN: reconnecting...");
                        exchange();
                    } else {
                        log.finest("PCONN: reconnecting in " + reconnectionDelay);
                        requestDeferredExchange(reconnectionDelay);
                    }
            }
        } catch (Exception ex) {
            log.log(Level.SEVERE, "PCONN: error processing messages: " + text, ex);
            GWT.reportUncaughtException(ex);
            changeState(State.closed);
        }
    }


    protected void exchange() {
        if (scheduled)
            return;

        if (httpRequest != null) {
            if (longPollInProgress) {
                Object httpOld = httpRequest;
                httpRequest = null;
                httpAbort(httpOld);
            } else
                return;
        }

        longPollInProgress = false;

        if (this.connId == null) {
            this.httpRequest = httpSend("GET", null, null, requestTimeout);
            log.finest("HTTP GET");
        }
        else {
            StringBuilder sb = new StringBuilder()
                .append(receivedCounter).append('\n')
                .append(sentCounter).append('\n');

            for (Object message : queue) {
                sb.append(message).append('\n');
            }

            if (!queue.isEmpty()) {
                // Post with return
                httpRequest = httpSend("POST", "cid=" + connId, sb.toString(), requestTimeout);
                log.finest("HTTP POST");
            } else {
                // Close o long-poll
                longPollInProgress = true;
                httpRequest = httpSend(
                        this.state == State.closing ? "DELETE":"PUT",
                        "cid=" + connId, sb.toString(), longPollingTimeout + 5000);
                log.finest("HTTP "+(this.state == State.closing ? "DELETE":"PUT"));
            }
        }
    }


    protected abstract Object httpSend(String method, @Nullable String paramLine, @Nullable String text, int timeout);

    protected abstract void httpAbort(@Nonnull Object httpRequest);

    protected abstract void schedule(int delay, @Nonnull Runnable task);


    private boolean scheduled;
    private final Runnable scheduleTask = new Runnable() {
        @Override
        public void run() {
            scheduled = false;
            exchange();
        }
    };

    protected void requestDeferredExchange(int delay) {
        if (!scheduled) {
            schedule(delay, scheduleTask);
        }
    }


    @Override
    public void connect() {
        if (isStarted())
            throw new IllegalStateException( "Connection already started" );

        queue.clear();
        this.connId = null;
        this.receivedCounter = 0;
        this.sentCounter = 0;
        this.reconnectionAttempts = 0;
        changeState(State.connecting);
        log.finest("PCONN: connecting");
        // multiple invocations may be grouped in one request later
        requestDeferredExchange(0);
    }


    @Override
    public void close() {
        if (!isStarted())
            throw new IllegalStateException( "Connection is not started" );

        log.finest("PCONN: closing connection");

        changeState(State.closing);
        // multiple invocations may be grouped in one request later
        requestDeferredExchange(0);
    }

    @Override
    public void post(Object payload) {
        if (!isStarted())
            throw new IllegalStateException( "Connection is not started" );

        String msg = messageSerializer.toString(payload);
        queue.add(msg);
        if (log.isLoggable(Level.FINEST))
            log.finest("PCONN: posted message: "+msg);

        // multiple invocations may be grouped in one request later
        requestDeferredExchange(0);
    }

    public int getMaxReconnectionAttempts() {
        return maxReconnectionAttempts;
    }

    public void setMaxReconnectionAttempts(int maxReconnectionAttempts) {
        this.maxReconnectionAttempts = maxReconnectionAttempts;
    }

    public int getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(int requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int getReconnectionDelay() {
        return reconnectionDelay;
    }

    public void setReconnectionDelay(int reconnectionDelay) {
        this.reconnectionDelay = reconnectionDelay;
    }

    public String getConnectionId() {
        return connId;
    }
}
