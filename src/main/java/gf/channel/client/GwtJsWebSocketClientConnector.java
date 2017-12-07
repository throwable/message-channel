package gf.channel.client;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.user.client.Timer;

import javax.annotation.Nonnull;

/**
 * Created by akuranov on 01/10/2015.
 */
public class GwtJsWebSocketClientConnector extends SocketClientConnector {
    public GwtJsWebSocketClientConnector(String path) {
        super(path);
    }

    private JavaScriptObject websocket;

    @Override
    protected native void socketConnect() /*-{
		if ("WebSocket" in $wnd) {
			var ws = new WebSocket(this.@gf.channel.client.SocketClientConnector::url);
			//this.@gf.channel.client.GwtJsWebSocketClientConnector::websocket = ws;
			var x = this;
			ws.onopen = $entry(function() {
				x.@gf.channel.client.GwtJsWebSocketClientConnector::onOpenCallback()();
				//x.@gf.channel.client.GwtJsWebSocketClientConnector::socketSend(Ljava/lang/String;)('N');
			});
			ws.onmessage = $entry(function(evt) {
			    alert('Received: '+evt.data);
				x.@gf.channel.client.GwtJsWebSocketClientConnector::onMessageCallback(Ljava/lang/String;)(""+evt.data);
			});
			ws.onclose = $entry(function() {
			    alert("closed");
				x.@gf.channel.client.GwtJsWebSocketClientConnector::onCloseCallback()();
			});
			ws.onerror = $entry(function(error) {
			    alert("error");
				x.@gf.channel.client.GwtJsWebSocketClientConnector::onErrorCallback()();
			});
		} else {
			// The browser doesn't support WebSocket
			this.@gf.channel.client.SocketClientConnector::onConnectionError()();
		}
	}-*/;

    /*@Override
    public void connect() {
        socketConnect();
    }*/

    public native void connect1() /*-{
		if ("WebSocket" in $wnd) {
			var ws = new WebSocket(this.@gf.channel.client.SocketClientConnector::url);
			this.@gf.channel.client.GwtJsWebSocketClientConnector::websocket = ws;
			var x = this;
			ws.onopen = function() {
				x.@gf.channel.client.GwtJsWebSocketClientConnector::onOpenCallback()();
			}
			ws.onmessage = function(evt) {
			    alert('Received: '+evt.data);
				x.@gf.channel.client.GwtJsWebSocketClientConnector::onMessageCallback(Ljava/lang/String;)(evt.data);
			}
			ws.onclose = function() {
			    alert("closed");
				x.@gf.channel.client.GwtJsWebSocketClientConnector::onCloseCallback()();
			}
			ws.onerror = function(error) {
			    alert("error");
				x.@gf.channel.client.GwtJsWebSocketClientConnector::onErrorCallback()();
			}
		} else {
			// The browser doesn't support WebSocket
			this.@gf.channel.client.SocketClientConnector::onConnectionError()();
		}
    }-*/;

    private void onMessageCallback(String text) {
        onMessage(text);
    }

    private void onOpenCallback() {
        onConnectionOpen();
    }

    private void onCloseCallback() {
        onConnectionClose();
    }

    private void onErrorCallback() {
        onConnectionError();
    }

    @Override
    protected native void socketClose() /*-{
		var ws = this.@gf.channel.client.GwtJsWebSocketClientConnector::websocket;
		ws.close();
	}-*/;

    /*protected void socketSend(String text) {
        socketSend1(text);
        GWT.log("test");
    }*/

    @Override
    protected native void socketSend(String text) /*-{
		var ws = this.@gf.channel.client.GwtJsWebSocketClientConnector::websocket;
        alert('Send: '+ws+" "+ws.readyState+": "+text);
		//ws.send(text);
	}-*/;

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
