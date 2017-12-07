package gf.channel.client;

import com.google.gwt.user.client.Timer;
import elemental.client.Browser;
import elemental.events.Event;
import elemental.events.EventListener;
import elemental.events.MessageEvent;
import elemental.html.WebSocket;

import javax.annotation.Nonnull;

/**
 * Created by anton on 02/10/2015.
 */
public class GwtWebSocketClientConnector extends SocketClientConnector {
    private WebSocket webSocket;

    public GwtWebSocketClientConnector(String path) {
        super(path);
    }

    @Override
    protected void socketConnect() {
        webSocket = Browser.getWindow().newWebSocket(url);
        webSocket.setOnopen(new EventListener() {
            @Override
            public void handleEvent(Event evt) {
                onConnectionOpen();
            }
        });
        webSocket.setOnerror(new EventListener() {
            @Override
            public void handleEvent(Event evt) {
                onConnectionError();
            }
        });
        webSocket.setOnmessage(new EventListener() {
            @Override
            public void handleEvent(Event evt) {
                onMessage(((elemental.events.MessageEvent) evt).getData().toString());
            }
        });
        webSocket.setOnclose(new EventListener() {
            @Override
            public void handleEvent(Event evt) {
                onConnectionClose();
            }
        });
    }

    @Override
    protected void socketClose() {
        webSocket.close();
    }

    @Override
    protected void socketSend(String text) {
        webSocket.send(text);
    }

    private Timer delayedTask;

    @Override
    protected void schedule(int delay, final @Nonnull Runnable task) {
        delayedTask = new Timer() {
            @Override
            public void run() {
                if (delayedTask == this) {
                    delayedTask = null;
                    task.run();
                }
            }
        };
        delayedTask.schedule(delay);
    }

    private Timer periodicTask;

    @Override
    protected void startScheduledTask(int delay, final @Nonnull Runnable task) {
        periodicTask = new Timer() {
            @Override
            public void run() {
                if (periodicTask == this) {
                    periodicTask = null;
                    task.run();
                }
            }
        };
        periodicTask.scheduleRepeating(delay);
    }

    @Override
    protected void stopAllScheduledTasks() {
        if (periodicTask != null) {
            periodicTask.cancel();
            periodicTask = null;
        }
        if (delayedTask != null) {
            delayedTask.cancel();
            delayedTask = null;
        }
    }
}
