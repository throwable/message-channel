package gf.channel.client;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Using websocket with long polling fallback transport
 * Created by akuranov on 30/09/2015.
 */
public class FallbackClientConnector extends ClientConnector {
    private final static Logger log = Logger.getLogger(PollingClientConnector.class.getName());
    private final SocketClientConnector websocketTransport;
    private final PollingClientConnector pollingTransport;

    private boolean detectingPhase;

    private @Nullable Boolean websocketAvailable;
    private @Nullable Boolean pollingAvailable;

    private List<Object> queue = new ArrayList<>();

    private int transportDetectionConnectTimeout = 5000;
    private int transportDetectionReconnectionAttempts = 0;

    private int
            savedWsTransportConnectTimeout,
            savedWsTransportMaxRetries,
            savedPollingTransportRequestTimeout,
            savedPollingTransportMaxRetries,
            savedWsReconnectionDelay,
            savedPollingReconnectionDelay;

    private final int maxReconnectionAttempts;
    private final int reconnectionDelay;
    private boolean tryConnectionInParallel;

    private int connectionAttempts;


    public FallbackClientConnector(SocketClientConnector websocketTransport,
                                   PollingClientConnector pollingTransport) {
        this.websocketTransport = websocketTransport;
        this.pollingTransport = pollingTransport;
        this.savedPollingTransportMaxRetries = pollingTransport.getMaxReconnectionAttempts();
        this.savedPollingTransportRequestTimeout = pollingTransport.getRequestTimeout();
        this.savedWsTransportConnectTimeout = websocketTransport.getConnectTimeout();
        this.savedWsTransportMaxRetries = websocketTransport.getMaxReconnectionAttempts();
        this.savedPollingReconnectionDelay = pollingTransport.getReconnectionDelay();
        this.savedWsReconnectionDelay = websocketTransport.getReconnectionDelay();

        maxReconnectionAttempts = Math.max(
                websocketTransport.getMaxReconnectionAttempts(),
                pollingTransport.getMaxReconnectionAttempts());

        reconnectionDelay = Math.max(
                websocketTransport.getReconnectionDelay(),
                pollingTransport.getReconnectionDelay());

        this.websocketTransport.addConnectionHandler(new ConnectionHandler() {
            @Override
            public void onStateChanged(State oldState, State newState) {
                if (detectingPhase) {
                    if (oldState == State.connecting) {
                        if (newState == State.ready)
                            websocketAvailable = true;
                        else {
                            log.finest("FBCONN: websocket connection failed");
                            websocketAvailable = false;
                        }
                        checkDetectionComplete();
                    } else if (oldState != State.closed) {
                        log.severe("FBCONN: unexpected connection state");
                        websocketAvailable = false;
                        checkDetectionComplete();
                    }
                } else if (websocketAvailable != null && websocketAvailable) {
                    // retransmit event
                    changeState(newState);
                }
                // else simply discard event
            }
        });
        this.websocketTransport.addMessageHandler(new MessageHandler() {
            @Override
            public void onMessage(Object message) {
                if (websocketAvailable != null && websocketAvailable)
                    FallbackClientConnector.this.listeners.fireEvent(new MessageEvent(message));
            }
        });
        this.pollingTransport.addConnectionHandler(new ConnectionHandler() {
            @Override
            public void onStateChanged(State oldState, State newState) {
                if (detectingPhase) {
                    if (oldState == State.connecting) {
                        if (newState == State.ready)
                            pollingAvailable = true;
                        else {
                            pollingAvailable = false;
                            log.finest("FBCONN: long polling connection failed");
                        }
                    } else if (newState == State.closed) {
                        pollingAvailable = false;
                    }

                    checkDetectionComplete();
                } else if (pollingAvailable != null && pollingAvailable) {
                    // retransmit event
                    changeState(newState);
                }
                // else simply discard event
            }
        });
        this.pollingTransport.addMessageHandler(new MessageHandler() {
            @Override
            public void onMessage(Object message) {
                if (pollingAvailable != null && pollingAvailable)
                    FallbackClientConnector.this.listeners.fireEvent(new MessageEvent(message));
            }
        });
    }


    @Override
    public void connect() {
        if ( isStarted() )
            throw new IllegalStateException( "Connection already started" );

        changeState(State.connecting);
        queue.clear();
        connectionAttempts = 0;
        log.finest("FBCONN: trying connections");
        connectTryImpl();
    }


    private void connectTryImpl() {
        detectingPhase = true;
        pollingAvailable = null;
        websocketAvailable = null;

        websocketTransport.setConnectTimeout(transportDetectionConnectTimeout);
        pollingTransport.setRequestTimeout(transportDetectionConnectTimeout);
        websocketTransport.setMaxReconnectionAttempts(transportDetectionReconnectionAttempts);
        pollingTransport.setMaxReconnectionAttempts(transportDetectionReconnectionAttempts);
        websocketTransport.setReconnectionDelay(0);
        pollingTransport.setReconnectionDelay(0);

        // checking phase
        try {
            websocketTransport.connect();
        } catch (Exception ex) {
            // could not use
            log.log(Level.INFO, "FBCONN: could not use websocket transport: "+ex.getMessage());
            websocketAvailable = false;
        }

        if (tryConnectionInParallel) {
            try {
                pollingTransport.connect();
            } catch (Exception ex) {
                // could not use
                log.log(Level.SEVERE, "FBCONN: could not use polling transport: "+ex.getMessage(), ex);
                pollingAvailable = false;
            }
        }

        checkDetectionComplete();
    }


    private void checkDetectionComplete() {
        assert detectingPhase;

        if (websocketAvailable != null) {
            if (websocketAvailable) {
                // use websocket transport instead of polling
                try {pollingTransport.close();} catch(Exception e) {}
                pollingAvailable = false;
                detectingPhase = false;
                for (Object msg : queue)
                    websocketTransport.post(msg);
                queue.clear();

                websocketTransport.setMaxReconnectionAttempts(savedWsTransportMaxRetries);
                websocketTransport.setConnectTimeout(savedWsTransportConnectTimeout);
                websocketTransport.setReconnectionDelay(savedWsReconnectionDelay);
                log.info("FBCONN: connection opened using websocket transport");
                if (state == State.connecting)
                    changeState(State.ready);
                else if (state == State.closing)
                    websocketTransport.close();
            }
            else if (pollingAvailable != null) {
                if (pollingAvailable) {
                    // use polling transport
                    detectingPhase = false;
                    for (Object msg : queue)
                        pollingTransport.post(msg);
                    queue.clear();

                    pollingTransport.setMaxReconnectionAttempts(savedPollingTransportMaxRetries);
                    pollingTransport.setRequestTimeout(savedPollingTransportRequestTimeout);
                    pollingTransport.setReconnectionDelay(savedPollingReconnectionDelay);
                    log.info("FBCONN: connection opened using long polling transport");
                    if (state == State.connecting)
                        changeState(State.ready);
                    else if (state == State.closing)
                        pollingTransport.close();
                } else {
                    // connection not available
                    // try to re-establish connection
                    if (state != State.closed) {
                        connectionAttempts++;

                        if (connectionAttempts > maxReconnectionAttempts) {
                            log.finest("FBCONN: could not establish connection");
                            changeState(State.closed);
                        } else {
                            // try first reconnect attempt without delay
                            if (connectionAttempts == 0) {
                                log.finest("FBCONN: reconnecting...");
                                connectTryImpl();
                            } else {
                                if (log.isLoggable(Level.FINEST))
                                    log.finest("FBCONN: reconnecting in " + reconnectionDelay);
                                // use scheduler of pollingTransport
                                pollingTransport.schedule(reconnectionDelay, new Runnable() {
                                    @Override
                                    public void run() {
                                        log.finest("FBCONN: reconnecting...");
                                        connectTryImpl();
                                    }
                                });
                            }
                        }
                    }
                }
            }
            else if (!tryConnectionInParallel) {
                try {
                    pollingTransport.connect();
                } catch (Exception ex) {
                    // could not use
                    log.log(Level.SEVERE, "FBCONN: could not use polling transport: " + ex.getMessage(), ex);
                    pollingAvailable = false;
                }
            }
        }
        // else continue connecting
    }


    @Override
    public void close() {
        if (detectingPhase) {
            if (!isStarted())
                throw new IllegalStateException("Connection is not started");

            // close transports forcibly
            /*try {
                websocketTransport.close();
            } catch (Exception ex) {
            }
            try {
                pollingTransport.close();
            } catch (Exception ex) {
            }*/
            changeState(State.closing);
        } else {
            assert websocketAvailable != null;
            assert pollingAvailable != null;
            if (websocketAvailable)
                websocketTransport.close();
            else if (pollingAvailable)
                pollingTransport.close();
        }
    }


    @Override
    public void post(Object payload) {
        if (detectingPhase) {
            if (!isStarted())
                throw new IllegalStateException("Connection is not started");

            // close transports forcibly
            queue.add(payload);
        } else {
            assert websocketAvailable != null;
            assert pollingAvailable != null;
            if (websocketAvailable)
                websocketTransport.post(payload);
            else if (pollingAvailable)
                pollingTransport.post(payload);
        }
    }


    public int getTransportDetectionConnectTimeout() {
        return transportDetectionConnectTimeout;
    }

    public void setTransportDetectionConnectTimeout(int transportDetectionConnectTimeout) {
        this.transportDetectionConnectTimeout = transportDetectionConnectTimeout;
    }

    public int getTransportDetectionReconnectionAttempts() {
        return transportDetectionReconnectionAttempts;
    }

    public void setTransportDetectionReconnectionAttempts(int transportDetectionReconnectionAttempts) {
        this.transportDetectionReconnectionAttempts = transportDetectionReconnectionAttempts;
    }

    public boolean isTryConnectionInParallel() {
        return tryConnectionInParallel;
    }

    public void setTryConnectionInParallel(boolean tryConnectionInParallel) {
        this.tryConnectionInParallel = tryConnectionInParallel;
    }
}
