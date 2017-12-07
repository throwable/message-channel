package gf.channel.server;

import gf.channel.shared.MessageSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.http.HttpSession;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Connection protocol
 *
 * Client connection init:
 *      Request
 *          N
 *      Response
 *          N
 *          new connectionID
 *          client heartbeat timeout
 *          connection timeout
 *  Client send message:
 *      Request
 *          S
 *          connectionId
 *          number of messages received from server
 *          number of messages sent to server before (number of first message in queue)
 *          message list separated by \n
 *      Response (nothing)
 *      Response (connection closed)
 *          C
 *  Client reconnect message:
 *      Request
 *          R
 *          connectionId
 *          number of messages received from server
 *          number of messages sent to server before (number of first message in queue)
 *          message list separated by \n (queued on client side)
 *      Response
 *          S
 *          connectionId
 *          number of messages received from client
 *          number of messages sent to client before (number of first message in queue)
 *          message list separated by \n (queued on server side)
 *      Response (connection closed)
 *          C
 *  Client close request
 *      Request
 *          C
 *          connectionId
 *      Response (connection closed)
 *          C
 *  Client heartbeat
 *      Request
 *          H
 *          connectionId
 *          number of messages received from server
 *      Response
 *          HA
 *          number of messages received from client
 *      Response (connection closed)
 *          C
 *  Server send message:
 *      Request
 *          S
 *          number of messages received from client
 *          number of messages sent to client before (number of first message in queue)
 *          message list separated by \n
 *      Response (none)
 *  Server close request:
 *      Request
 *          CR
 *      Response (connection closed)
 *          CA
 *          connectionId
 */
@SuppressWarnings("serial")
public abstract class AbstractSocketServerConnector implements ServerConnector
{
    private final static Logger log = LoggerFactory.getLogger(AbstractSocketServerConnector.class);

    /**
	 * Connector parameters
	 */

    /** Refresh connection if interval was exceeded */
	protected int heartbeatInterval = 20000;

    /** Terminate connection if during this period was not received any packet */
    protected int connectionTimeout = 120000;

	/** Terminate connection if queue size exceeds max length */
	protected int maxQueueLength = 300;

    protected MessageSerializer messageSerializer;

    protected Timer timer = new Timer("polling-cleaner", true);

    private final static String SERVER_HEARTBEAT_ACK = "HA";
    private final static String SERVER_NEW_CONN = "N";
    private final static String SERVER_CLOSING = "CR"; // closing request from server
    private final static String SERVER_CLOSED = "C";
    private final static String SERVER_SEND = "S";  // send message

    private final static String CLIENT_NEW_CONN = "N";
    private final static String CLIENT_RETRY_CONN = "R";
    private final static String CLIENT_HEARTBEAT = "H";
    private final static String CLIENT_CLOSE = "C";
    private final static String CLIENT_CLOSING_ACK = "CA";
    private final static String CLIENT_SEND = "S";


	/** ConnectionId -> Connection */
	private ConcurrentHashMap<String,Connection> connections = new ConcurrentHashMap<String, Connection>();


	protected static class Connection {
		public String connId;
        public int receivedCounter;
        public volatile long lastUsedTimestamp = System.nanoTime();
        public boolean closing;

        @Nullable
        public HttpSession session;

		/** Serialized messages pending to send */
		public List<String> queue = new ArrayList<>(20);
        public long sentCounter;
        /** Represents physical connection */
        public WeakReference<Object> connection;
    }
	

    public AbstractSocketServerConnector() {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    long timestamp = System.nanoTime();
                    List<Connection> connectionsCloned = new ArrayList<Connection>(connections.values());
                    for (Connection conn : connectionsCloned) {
                        if (TimeUnit.MILLISECONDS.convert(
                                timestamp - conn.lastUsedTimestamp,
                                TimeUnit.NANOSECONDS) > connectionTimeout) {
                            synchronized (conn) {
                                closeConnection(conn);
                            }
                        }
                        else if (TimeUnit.MILLISECONDS.convert(
                                timestamp - conn.lastUsedTimestamp,
                                TimeUnit.NANOSECONDS) > heartbeatInterval + 10000) {
                            // close underlying connection for connections that exceeds their
                            // heartbeat interval
                            synchronized (conn) {
                                if (conn.connection != null)
                                    socketClose(conn.connection.get());
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("WTF!", e);
                }
            }
        }, 30000, 10000);
    }


	protected String createConnectionId() {
		//return Long.toString(connIdCount.incrementAndGet());
        String uuid;
        do {
            uuid = UUID.randomUUID().toString();
        } while (connections.containsKey(uuid));
        return uuid;
    }


    protected abstract void socketSend(@Nullable Object connection, @Nonnull String text);
    protected abstract void socketClose(@Nullable Object connection);


    /**
     * Called by subsystem to serve request
     * @param connection underlying physical connection object
     * @param messageText data
     */
	public void service(@Nonnull Object connection, @Nonnull String messageText)
    {
        String[] lines = messageText.split("\\r?\\n");
        if (lines.length == 0) {
            log.warn("Protocol mismatch (empty message)");
            socketClose(connection);
            return;
        }
        int iLine = 0;
        String cmd = lines[iLine++];

        if (CLIENT_NEW_CONN.equals(cmd)) {
            createConnection(connection);
        }
        else {
			/*
			 * poll request
			 */
            if (lines.length == 1) {
                log.warn("Protocol mismatch (no connId), cmd={}", cmd);
                socketClose(connection);
                return;
            }
            final String connId = lines[iLine++];
			Connection conn = connections.get(connId);

			// expired connection - disconnect
			if (conn == null) {
                socketSend(connection, SERVER_CLOSED+ "\n" + "Expired");
                socketClose(connection);
                return;
			}

            List<Object> msgs = new ArrayList<>();

            synchronized (conn) {
                // double check if was removed by other thread
                if (!connections.containsKey(connId)) {
                    // connection was already removed in other thread
                    socketSend(connection, SERVER_CLOSED+ "\n" + "Expired");
                    socketClose(connection);
                    return;
                }

                @Nullable Object physicalConnection = conn.connection != null ? conn.connection.get() : null;

                // a new connection was requested (reconnect)... terminate an old one
                if (physicalConnection != connection) {
                    if (physicalConnection != null) {
                        //socketSend(physicalConnection, SERVER_CLOSED + "\n" + "Reconnected");
                        socketClose(physicalConnection);
                    }
                    conn.connection = new WeakReference<>(connection);
                    physicalConnection = connection;
                }

                // session expired - disconnect
                /*if (createSession && req.getSession(false) == null) {
                    log.warn("Session expired for connection: {}", connId);
                    closeConnection(conn, req, resp);
                    onDisconnected(this, connId);
                    return;
                }
                */

                if (CLIENT_CLOSE.equals(cmd)) {
                    // close request from client
                    socketSend(physicalConnection, SERVER_CLOSED + "\n" + "Closed");
                    closeConnection(conn);
                    return;
                } else if (CLIENT_CLOSING_ACK.equals(cmd)) {
                    // closing ACK from client
                    if (conn.closing) {
                        closeConnection(conn);
                    } else {
                        log.warn("Protocol mismatch (closing state required)");
                        closeConnection(conn);
                    }
                    return;
                }

                conn.lastUsedTimestamp = System.nanoTime();

                try {
                    long msgNumberReceivedFromServer = Long.parseLong(lines[iLine++]);

                    if (CLIENT_HEARTBEAT.equals(cmd)) {
                        // client heartbeat
                        if (!conn.closing) {
                            StringBuilder out = new StringBuilder()
                                    .append(SERVER_HEARTBEAT_ACK).append('\n')
                                    .append(conn.receivedCounter).append('\n');
                            socketSend(physicalConnection, out.toString());
                        }
                        return;
                    }

                    long firstMessageNumberOffset = Long.parseLong(lines[iLine++]);

                    // read and process messages
                    for (long readMsgNumber = firstMessageNumberOffset; iLine < lines.length; readMsgNumber++) {
                        String msg = lines[iLine++];

                        // discard duplicates
                        if (readMsgNumber < conn.receivedCounter)
                            continue;

                        Object  message = messageSerializer.fromString(msg);
                        msgs.add(message);
                        conn.receivedCounter++;
                    }

                    // we use synchronization here to send synchronous responses in the same HttpResponse
                    // protocol mismatch - older events were removed
                    if (msgNumberReceivedFromServer < conn.sentCounter) {
                        log.warn("Protocol mismatch, connection: {}", connId);
                        closeConnection(conn);
                        return;
                    }

                    // removing messages from queue confirmed by client
                    while (conn.sentCounter < msgNumberReceivedFromServer) {
                        conn.queue.remove(0);
                        conn.sentCounter++;
                    }

                    if (CLIENT_SEND.equals(cmd)) {
                        // simple send
                    } else if (CLIENT_RETRY_CONN.equals(cmd)) {
                        // reconnect-retry
                        StringBuilder out = new StringBuilder()
                                .append(SERVER_SEND).append('\n')
                                .append(conn.receivedCounter).append('\n')
                                .append(conn.sentCounter).append('\n');

                        for (String msg : conn.queue) {
                            out.append(msg).append('\n');
                        }
                        socketSend(physicalConnection, out.toString());

                        if (conn.closing) {
                            socketSend(physicalConnection, SERVER_CLOSING + "\n" + "Close");
                            return;
                        }
                    } else {
                        log.warn("Protocol mismatch (invalid command): {}", cmd);
                        closeConnection(conn);
                        return;
                    }
                } catch (Exception ex) {
                    log.warn("Protocol mismatch", ex);
                    closeConnection(conn);
                    return;
                }

                if (conn.closing)
                    // do not process messages when closing connection
                    return;
            }

            // process messages
            for (Object msg : msgs) {
                onMessage(this, connId, msg);
            }
        }
	}


    /**
     * New connection request
     */
    protected void createConnection(@Nonnull Object connection) {
        Connection conn = new Connection();
        String connId = createConnectionId();
        conn.connId = connId;
        conn.connection = new WeakReference<Object>(connection);

        connections.put(connId, conn);

        try {
            StringBuilder out = new StringBuilder()
                    .append(SERVER_NEW_CONN).append('\n')
                    .append(connId).append('\n')
                    .append(heartbeatInterval).append('\n')
                    .append(connectionTimeout);
            socketSend(connection, out.toString());
        } catch (Exception ex) {
            log.error("Connection error while creating connection: {}", connId, ex);
            connections.remove(connId);
            return;
        }

        onConnected(this, connId);
    }


    protected void closeConnection(@Nonnull Connection conn)
    {
        connections.remove(conn.connId);
        onDisconnected(this, conn.connId);
        @Nullable Object physicalConnection = conn.connection != null ? conn.connection.get() : null;
        socketClose(physicalConnection);
    }


    @Override
	public void terminate( String connectionId )
	{
		Connection conn = connections.get(connectionId);
		
		if ( conn != null ) {
            synchronized (conn) {
                @Nullable Object physicalConnection = conn.connection != null ? conn.connection.get() : null;
                conn.closing = true;
                socketSend(physicalConnection, SERVER_CLOSING + '\n');
            }
        }
	}

	
	@Override
	public synchronized void post(String connectionId, Object message) {
        Connection conn = connections.get(connectionId);
		
		if ( conn != null ) {
            // TODO: implement here a delay before sending message to accumulate several
            // messages in one response
			synchronized (conn) {
                if (conn.closing)
                    return;

                if (conn.queue.size() >= maxQueueLength) {
                    log.warn("Queue is full, connId={}", conn.connId);
                    closeConnection(conn);
                    onDisconnected(this, conn.connId);
                    return;
                }

                String messageText = messageSerializer.toString(message);
                conn.queue.add(messageText);

                @Nullable Object physicalConnection = conn.connection != null ? conn.connection.get() : null;
                StringBuilder out = new StringBuilder()
                        .append(SERVER_SEND).append('\n')
                        .append(conn.receivedCounter).append('\n')
                        .append(conn.sentCounter + conn.queue.size()-1).append('\n');
                out.append(messageText).append('\n');
                socketSend(physicalConnection, out.toString());
            }
		}
	}


	protected ServerConnectorHandler handler;

	public void setHandler( ServerConnectorHandler handler ) {
		this.handler = handler;
	}

    public ServerConnectorHandler getHandler() {
        return handler;
    }

	@Override
	public void onConnected(ServerConnector connector, String connectionId) {
		if ( handler != null )
			try {
				handler.onConnected(connector, connectionId);
			} catch ( Exception ex ) {
				log.error("onConnected() error", ex);
			}
	}


	@Override
	public void onDisconnected(ServerConnector connector, String connectionId) {
		if ( handler != null )
			try {
				handler.onDisconnected(connector, connectionId);
			} catch ( Exception ex ) {
				log.error("onDisconnected() error", ex);
			}
	}


	@Override
	public void onMessage(ServerConnector connector, String connectionId,
			Object message)
	{
		if ( handler != null )
			try {
				handler.onMessage(connector, connectionId, message);
			} catch ( Exception ex ) {
				log.error("onMessage() error", ex);
			}
	}

    public int getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(int heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public int getMaxQueueLength() {
        return maxQueueLength;
    }

    public void setMaxQueueLength(int maxQueueLength) {
        this.maxQueueLength = maxQueueLength;
    }

    public MessageSerializer getMessageSerializer() {
        return messageSerializer;
    }

    public void setMessageSerializer(MessageSerializer messageSerializer) {
        this.messageSerializer = messageSerializer;
    }
}
