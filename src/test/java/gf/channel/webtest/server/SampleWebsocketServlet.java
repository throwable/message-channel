package gf.channel.webtest.server;

import gf.channel.server.ServerConnector;
import gf.channel.server.ServerConnectorHandler;
import gf.channel.server.servlet.JettyWSConnectionServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;

/**
 * Sample servlet that for every received message returns OK [message]
 * Created by akuranov on 01/10/2015.
 */
public class SampleWebsocketServlet extends JettyWSConnectionServlet {
    private final static Logger log = LoggerFactory.getLogger(SampleWebsocketServlet.class);

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        connector.setHandler(new ServerConnectorHandler() {
            @Override
            public void onConnected(ServerConnector connector, String connectionId) {
                log.debug("Connection created: {}", connectionId);
            }

            @Override
            public void onDisconnected(ServerConnector connector, String connectionId) {
                log.debug("Connection closed: {}", connectionId);
            }

            @Override
            public void onMessage(ServerConnector connector, String connectionId, Object message) {
                log.debug("Message: {}\n{}", connectionId, message);
                connector.post(connectionId, "OK "+message);
            }
        });
    }
}
