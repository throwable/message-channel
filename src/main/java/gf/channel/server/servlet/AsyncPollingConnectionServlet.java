package gf.channel.server.servlet;

import gf.channel.server.ServerConnector;
import gf.channel.server.ServerConnectorHandler;
import gf.channel.shared.GwtStreamerSerializer;
import gf.channel.shared.MessageSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.*;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Connection protocol
 *
 * Connection init:
 *      Request
 *          GET http://host/path
 *      Response 200
 *          new connectionID
 *          long polling timeout
 *          connection timeout
 * Send message
 *      Request
 *          POST http://host/path?cid=connectionID
 *          number of messages received from server
 *          number of messages sent to server before
 *          message list separated by \n
 *      returns 200
 *          number of messages received from client
 *          number of messages sent fo client before
 *          message list separated by \n
 *      returns 410 (gone) if connection was closed
 * Long poll
 *      Request
 *          PUT http://host/path?cid=connectionID
 *          number of messages received from server
 *          number of messages sent to server before
 *      returns 200
 *          number of messages received from client
 *          number of messages sent fo client before
 *          message list separated by \n
 *      returns 204 (no content) if there is a new request is in progress or poll-timeout occurred
 *      returns 410 (gone) if connection was closed
 * Close connection by client:
 *      DELETE http://host/path?cid=connectionID
 *      returns 410 (gone)
 * Close connection by server:
 *      returns 410 (gone) for all http-requests (new posts and old polling)
 */
@SuppressWarnings("serial")
public class AsyncPollingConnectionServlet extends HttpServlet implements ServerConnector
{
    private final static Logger log = LoggerFactory.getLogger(AsyncPollingConnectionServlet.class);

    /**
	 * Servlet parameters
	 */

	/** Create session on incoming connection. If false session may be created by external auth facility. */
	protected boolean createSession;

	/** Dispose session on connection close */
	protected boolean disposeSessionOnConnectionClose;

    /** Refresh connection if interval was exceeded */
	protected int longPollingTimeout = 20000;

    /** Terminate connection if during this period was not received any packet */
    protected int connectionTimeout = 120000;

    /** Max client message size */
    protected int maxMessageSize = 256000;

	/** Terminate connection if queue size exceeds max length */
	protected int maxQueueLength = 300;

    private final static int HTTP_OK = 200;
    private final static int HTTP_NO_CONTENT = 204;
    private final static int HTTP_GONE = 410;  // connection closed
    private static final int HTTP_INVALID_METHOD = 405; // method not allowed

    private final static String PARAM_CONN_ID = "cid";

	//private AtomicLong connIdCount = new AtomicLong( 0 );

    protected MessageSerializer messageSerializer;

    protected Timer timer = new Timer("polling-cleaner", true);


	/** ConnectionId -> Connection */
	private ConcurrentHashMap<String,Connection> connections = new ConcurrentHashMap<String, Connection>();


	protected static class Connection implements AsyncListener {
		public String connId;
        public int receivedCounter;
        public volatile long lastUsedTimestamp = System.nanoTime();
        public boolean closing;

        @Nullable
        public HttpSession session;

		/** Serialized messages pending to send */
		public List<String> queue = new ArrayList<>(100);
        public long sentCounter;

		/** Thread that serves the sending queue or null if no AsyncContext associated with this thread */
        @Nullable
		public AsyncContext asyncContext;


        @Override
        public synchronized void onComplete(AsyncEvent asyncEvent) throws IOException {
            if (asyncEvent.getAsyncContext() == asyncContext)
                asyncContext = null;
        }
        @Override
        public void onTimeout(AsyncEvent asyncEvent) throws IOException {
            HttpServletResponse resp = (HttpServletResponse) asyncEvent.getAsyncContext().getResponse();
            resp.setStatus(HTTP_NO_CONTENT);
            asyncEvent.getAsyncContext().complete();

            if (asyncEvent.getAsyncContext() == asyncContext)
                asyncContext = null;
        }
        @Override
        public void onError(AsyncEvent asyncEvent) throws IOException {
            if (asyncEvent.getAsyncContext() == asyncContext)
                asyncContext = null;
        }
        @Override
        public void onStartAsync(AsyncEvent asyncEvent) throws IOException {
        }
    }
	
	
	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		this.createSession = "true".equalsIgnoreCase( config.getInitParameter( "createSession" ) );
		this.disposeSessionOnConnectionClose = "true".equalsIgnoreCase( config.getInitParameter( "disposeSessionOnConnectionClose" ) );
        try { this.maxMessageSize = Integer.parseInt(config.getInitParameter("maxMessageSize")); } catch ( Exception ex ) {}
		try { this.longPollingTimeout = Integer.parseInt( config.getInitParameter( "longPollingTimeout" ) ); } catch ( Exception ex ) {}
        try { this.connectionTimeout = Integer.parseInt( config.getInitParameter( "connectionTimeout" ) ); } catch ( Exception ex ) {}
        try { this.maxQueueLength = Integer.parseInt( config.getInitParameter( "maxQueueLength" ) ); } catch ( Exception ex ) {}

        String mserClass = config.getInitParameter("messageSerializerClass");
        if (mserClass == null) {
            messageSerializer = new GwtStreamerSerializer();
        } else {
            try {
                Class<?> cl = Thread.currentThread().getContextClassLoader().loadClass(mserClass);
                messageSerializer = (MessageSerializer) cl.newInstance();
            } catch (Exception ex) {
                throw new ServletException(ex);
            }
        }

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
                                closeConnection(conn, null, null);
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


	@Override
	protected void service(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException
	{
		String connId = req.getParameter(PARAM_CONN_ID);

		resp.setHeader("Connection", "keep-alive");
		resp.setContentType("text/plain");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Pragma", "no-cache");
        resp.setHeader("Cache-Control", "must-revalidate");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Cache-Control", "no-store");
        resp.setDateHeader("Expires", 0);

        if ( connId == null ) {
			/*
			 * new connection request
			 */
			Connection conn = new Connection();
			connId = createConnectionId();
			conn.connId = connId;

            if (createSession)
                conn.session = req.getSession();

			connections.put(connId, conn);

            try {
                resp.getOutputStream().print(
                        connId + "\n"
                                + longPollingTimeout + "\n"
                                + connectionTimeout + "\n"
                );
                resp.flushBuffer();
            } catch (Exception ex) {
                log.error("Connection error while creating connection: {}", connId, ex);
                connections.remove(connId);
                return;
            }

            onConnected(this, connId);
        }
        else {
			/*
			 * poll request
			 */
			Connection conn = connections.get(connId);

			// expired connection - disconnect
			if (conn == null) {
                resp.setHeader("Connection", "close");
                resp.setStatus(HTTP_GONE);
                return;
			}

            List<Object> msgs = new ArrayList<>();

            synchronized (conn) {
                // double check if was removed by other thread
                if (!connections.containsKey(connId)) {
                    // connection was already removed in other thread
                    resp.setHeader("Connection", "close");
                    resp.setStatus(HTTP_GONE);
                    return;
                }

                // old polling is in progress, terminate it
                if (conn.asyncContext != null) {
                    HttpServletResponse r = (HttpServletResponse) conn.asyncContext.getResponse();
                    r.setStatus(HTTP_NO_CONTENT);
                    conn.asyncContext.complete();
                    conn.asyncContext = null;
                }

                // session expired - disconnect
                if (createSession && req.getSession(false) == null) {
                    log.warn("Session expired for connection: {}", connId);
                    closeConnection(conn, req, resp);
                    onDisconnected(this, connId);
                    return;
                }

                if ("DELETE".equals(req.getMethod())) {
                    // request from client to close connection
                    closeConnection(conn, req, resp);
                    onDisconnected(this, connId);
                    return;
                }
                else if (!"POST".equals(req.getMethod()) && !"PUT".equals(req.getMethod())) {
                    resp.setStatus(HTTP_INVALID_METHOD);
                    return;
                }

                conn.lastUsedTimestamp = System.nanoTime();
                BufferedReader in = req.getReader();
                int msgSize = 0;

                long msgNumberReceivedFromServer = Long.parseLong(in.readLine());
                long firstMessageNumberOffset = Long.parseLong(in.readLine());
                //boolean wasMessagesReceived = false;

                // read and process messages
                for (long readMsgNumber = firstMessageNumberOffset; ; readMsgNumber++) {
                    String msg = in.readLine();
                    if (msg == null) break;
                    //wasMessagesReceived = true;

                    msgSize += msg.length();

                    if (msgSize > maxMessageSize) {
                        log.warn("Message too large to process: {}", connId);
                        closeConnection(conn, req, resp);
                        onDisconnected(this, connId);
                        return;
                    }

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
                    closeConnection(conn, req, resp);
                    onDisconnected(this, connId);
                    return;
                }

                // removing messages from queue confirmed by client
                while (conn.sentCounter < msgNumberReceivedFromServer) {
                    conn.queue.remove(0);
                    conn.sentCounter++;
                }

                if (!conn.queue.isEmpty()) {
                    resp.setStatus(HTTP_OK);
                    StringBuilder sb = new StringBuilder()
                            .append(conn.receivedCounter).append('\n')
                            .append(conn.sentCounter).append('\n');

                    for (String msg : conn.queue) {
                        sb.append(msg).append('\n');
                    }

                    resp.getOutputStream().print(sb.toString());
                } else if (conn.closing) {
                    // server-side connection close requested and no more messages pending in queue
                    closeConnection(conn, req, resp);
                    onDisconnected(this, connId);
                    return;
                } else if (/*wasMessagesReceived || */"POST".equals(req.getMethod())) {
                    resp.setStatus(HTTP_OK);
                    resp.getOutputStream().print(
                            conn.receivedCounter + "\n" + conn.sentCounter + "\n"
                    );
                }
                else {
                    // start long polling
                    AsyncContext asyncCtx = req.startAsync();
                    asyncCtx.setTimeout(longPollingTimeout);
                    asyncCtx.addListener(conn);
                    conn.asyncContext = asyncCtx;
                    conn.asyncContext = null;
                }
            }

            // process messages
            for (Object msg : msgs) {
                onMessage(this, connId, msg);
            }
        }
	}


    private void closeConnection(@Nonnull Connection conn,
                                 @Nullable HttpServletRequest req,
                                 @Nullable HttpServletResponse resp)
    {
        connections.remove(conn.connId);

        if (conn.asyncContext != null) {
            // old polling is in progress, terminate it
            HttpServletResponse r = (HttpServletResponse) conn.asyncContext.getResponse();
            r.setHeader("Connection", "close");
            r.setStatus(HTTP_GONE);
            conn.asyncContext.complete();
            conn.asyncContext = null;
        }

        if (resp != null) {
            resp.setHeader("Connection", "close");
            resp.setStatus(HTTP_GONE);
        }

        if (disposeSessionOnConnectionClose)
            if ( conn.session != null ) conn.session.invalidate();
    }


    @Override
	public void terminate( String connectionId )
	{
		Connection conn = connections.get(connectionId);
		
		if ( conn != null ) {
            synchronized (conn) {
                conn.closing = true;

                if (conn.asyncContext != null) {
                    // old polling is in progress, terminate it
                    HttpServletResponse r = (HttpServletResponse) conn.asyncContext.getResponse();
                    r.setStatus(HTTP_NO_CONTENT);
                    conn.asyncContext.complete();
                    conn.asyncContext = null;
                }
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
                    closeConnection(conn, null, null);
                    return;
                }

                String messageText = messageSerializer.toString(message);
                conn.queue.add(messageText);

                if (conn.asyncContext != null) {
                    try {
                        HttpServletResponse resp = (HttpServletResponse) conn.asyncContext.getResponse();
                        resp.setStatus(HTTP_OK);
                        StringBuilder sb = new StringBuilder()
                                .append(conn.receivedCounter).append('\n')
                                .append(conn.sentCounter).append('\n');

                        for (String msg : conn.queue) {
                            sb.append(msg).append('\n');
                        }

                        resp.getOutputStream().print(sb.toString());
                    } catch (IOException ex) {
                        log.warn("Error writing new message, connectionId={}", connectionId, ex);
                    }
                    // old polling is in progress, terminate it
                    conn.asyncContext.complete();
                    conn.asyncContext = null;
                }
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

    public MessageSerializer getMessageSerializer() {
        return messageSerializer;
    }

    public void setMessageSerializer(MessageSerializer messageSerializer) {
        this.messageSerializer = messageSerializer;
    }
}
