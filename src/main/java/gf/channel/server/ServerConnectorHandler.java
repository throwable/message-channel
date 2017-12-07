package gf.channel.server;

/**
 * Handler that receives connector events
 */
public interface ServerConnectorHandler {
	void onConnected( ServerConnector connector, String connectionId );
	void onDisconnected( ServerConnector connector, String connectionId );
	void onMessage( ServerConnector connector, String connectionId, Object message );
}
