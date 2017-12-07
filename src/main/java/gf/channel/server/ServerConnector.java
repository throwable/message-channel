package gf.channel.server;




public interface ServerConnector extends ServerConnectorHandler
{
	/**
	 * Terminate session.
	 * Must not throw any exception if session does not exist or if error occurs.
	 * In case of error error must manage correctly life cycle of session firing
	 * onDisconnect event.
	 * Must not block operation.
	 *  
	 * @param connectionId
	 */
	void terminate( String connectionId );
	
	/**
	 * Post message to client.
	 * Must not throw any exception if session does not exist or if error occurs.
	 * In case of error error must manage correctly life cycle of session firing
	 * onDisconnect event.
	 * Must not block operation.
	 *  
	 * @param connectionId
	 */
	void post( String connectionId, Object message );
}
