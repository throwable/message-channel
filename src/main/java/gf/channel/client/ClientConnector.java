package gf.channel.client;

import java.util.EnumSet;

import com.google.gwt.event.shared.EventBus;
import com.google.gwt.event.shared.EventHandler;
import com.google.gwt.event.shared.GwtEvent;
import com.google.gwt.event.shared.SimpleEventBus;
import gf.channel.shared.DefaultStringSerializer;
import gf.channel.shared.GwtStreamerSerializer;
import gf.channel.shared.MessageSerializer;

/**
 * Connection state flow:
 * 
 * 	closed					initial state or connection was closed gracefully
 * 							(by client or server)
 * 		connecting			connecting and obtaining resource
 * 
 * 	connecting				connection and negotiation process is in progress
 * 							ready to receive client messages (enqueued)	
 * 		closed				failed to connect or connection unavailable
 * 		connected			ready for communication
 * 
 *  connected				ready for communications
 *  	closed				connection was closed by server (no more messages are available)
 *  	closing				connection close was requested by client (no more messages will be received or sent)
 *  						until server confirms connection close
 *  	connecting			automatic reconnection
 *
 * 	closing
 * 		closed				connection close confirmed by server
 * 
 * @author akuranov
 *
 */
public abstract class ClientConnector 
{
	public enum State {
		closed,			// connected was terminated
		connecting,		// connecting
		ready,			// connection established
		closing			// connection close was requested by client
	}
	
	
	public static class ConnectionEvent extends GwtEvent<ConnectionHandler>
	{
	    public static Type<ConnectionHandler> TYPE = new Type<ConnectionHandler>();
	    
	    private final State oldState;
	    private final State newState;
	    
	    public ConnectionEvent( State oldState, State newState ) {
	    	this.oldState = oldState;
	    	this.newState = newState;
	    }
	    
	    @Override
	    public Type<ConnectionHandler> getAssociatedType() {
	        return TYPE;
	    }

	    @Override
	    protected void dispatch(ConnectionHandler handler) {
	        handler.onStateChanged( oldState, newState );
	    }
	}
	
	
	public static class MessageEvent extends GwtEvent<MessageHandler>
	{
	    public static Type<MessageHandler> TYPE = new Type<MessageHandler>();
	    private final Object message;
	    
	    public MessageEvent(Object message) {
	    	this.message = message;
	    }
	    

	    @Override
	    protected void dispatch(MessageHandler handler) {
	        handler.onMessage( message );
	    }


		@Override
		public Type<MessageHandler> getAssociatedType() {
			return TYPE;
		}
	}
	
	
	public interface ConnectionHandler extends EventHandler
    {
        void onStateChanged( State oldState, State newState );
    }
    

	public interface MessageHandler extends EventHandler
    {
        void onMessage( Object message );
    }
    
	
	protected MessageSerializer messageSerializer = new GwtStreamerSerializer();
    protected EventBus listeners = new SimpleEventBus();
    
    /** Current state */
    protected State state = State.closed;
    
    
    public boolean isStarted() {
    	return state != State.closed && state != State.closing;
    }
    
	/**
	 * Connect channel to server.
	 * @throws IllegalStateException if already connected or connection is in progress
     * @throws UnsupportedOperationException if given transport could not be used
	 */
	public abstract void connect();
	
	
	/**
	 * Close connections gracefully. Release resources on server side.
	 */
	public abstract void close();
	
	
	public void addConnectionHandler(ConnectionHandler handler) {
		listeners.addHandler( ConnectionEvent.TYPE, handler );
	}

	public void addMessageHandler( MessageHandler handler ) {
		listeners.addHandler(MessageEvent.TYPE, handler);
	}
	
	
	/**
	 * Send one way message asynchronously.
	 * @throws IllegalStateException if state is closed
	 * @param payload any object that may be serialized by serializer
	 */
	public abstract void post(Object payload);
	
	
	protected void changeState( State newState ) {
		if (this.state != newState) {
			ConnectionEvent evt = new ConnectionEvent(this.state, newState);
			this.state = newState;
			listeners.fireEvent(evt);
		}
	}

	public State getState() {
		return state;
	}

    public MessageSerializer getMessageSerializer() {
        return messageSerializer;
    }

    public void setMessageSerializer(MessageSerializer messageSerializer) {
        this.messageSerializer = messageSerializer;
    }
}
