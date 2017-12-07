package gf.channel.client;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.user.client.Timer;
import com.google.gwt.xhr.client.ReadyStateChangeHandler;
import com.google.gwt.xhr.client.XMLHttpRequest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Created by akuranov on 01/10/2015.
 */
public class GwtPollingClientConnector extends PollingClientConnector {
    public GwtPollingClientConnector(String path) {
        super(path);
    }

    private Timer httpTimeout;

    private final ReadyStateChangeHandler handler = new ReadyStateChangeHandler() {
        @Override
        public void onReadyStateChange(XMLHttpRequest xhr) {
            if ( xhr.getReadyState() == XMLHttpRequest.DONE ) {
                if (httpTimeout != null) {
                    httpTimeout.cancel();
                    httpTimeout = null;
                }
                GwtPollingClientConnector.this.onReadyStateChange(
                        xhr, xhr.getStatus(), xhr.getResponseText());
            }
        }
    };

    @Override
    protected Object httpSend(String method, @Nullable String paramLine, @Nullable String text, int timeout) {
        final XMLHttpRequest xhr = XMLHttpRequest.create();
        xhr.setOnReadyStateChange(handler);
        if (paramLine != null)
            xhr.open(method, url + "?" + paramLine);
        else
            xhr.open(method, url);
        xhr.setRequestHeader("Cache-Control", "no-cache");
        xhr.setRequestHeader("Content-Type", "text/plain;charset=UTF-8");
        xhr.send(text);
        httpTimeout = new Timer() {
            @Override
            public void run() {
                if (httpTimeout == this) {
                    httpTimeout = null;
                    xhr.abort();
                    onReadyStateChange(xhr, xhr.getStatus(), xhr.getResponseText());
                }
            }
        };
        httpTimeout.schedule(timeout);
        return xhr;
    }

    @Override
    protected void httpAbort(@Nonnull Object httpRequest) {
        XMLHttpRequest xhr = (XMLHttpRequest) httpRequest;
        xhr.abort();

    }

    @Override
    protected void schedule(int delay, final @Nonnull Runnable task) {
        if (delay > 0) {
            new Timer() {
                @Override
                public void run() {
                    task.run();
                }
            }.schedule(delay);
        } else
            Scheduler.get().scheduleDeferred(new Scheduler.ScheduledCommand() {
                @Override
                public void execute() {
                    task.run();
                }
            });
    }
}
