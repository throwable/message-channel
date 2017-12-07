package gf.channel.server;

import gf.channel.client.PollingClientConnector;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;

/**
 * Created by anton on 25/09/2015.
 */
public class JavaPollingClientConnector extends PollingClientConnector {
    private ExecutorService executor = java.util.concurrent.Executors.newCachedThreadPool();
    private Timer timer = new Timer(true);

    private CloseableHttpClient httpclient;

    public JavaPollingClientConnector(String path) {
        super(path);
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(60 * 1000)
                .setConnectionRequestTimeout(60 * 1000)
                .setSocketTimeout(60 * 1000).build();
        httpclient = HttpClients.custom()
                .setMaxConnTotal(100)
                .setMaxConnPerRoute(100)
                .setDefaultRequestConfig(config)
                .build();
    }

    @Override
    protected Object httpSend(final String method, final @Nullable String paramLine,
                              final @Nullable String text, int timeout) {
        final AtomicBoolean aborted = new AtomicBoolean();
        final TimerTask task = new TimerTask() {
            @Override
            public void run() {
                aborted.set(true);
                synchronized(JavaPollingClientConnector.this) {
                    onReadyStateChange(this, 408 /*timeout*/, null);
                }
            }
        };

        timer.schedule(task, timeout);
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    CloseableHttpResponse res;
                    if ("GET".equals(method)) {
                        res = httpclient.execute(new HttpGet(url + "?" + paramLine));
                    } else if ("DELETE".equals(method)) {
                        res = httpclient.execute(new HttpDelete(url + "?" + paramLine));
                    } else if ("POST".equals(method)) {
                        HttpPost post = new HttpPost(url + "?" + paramLine);
                        post.setEntity(new StringEntity(text));
                        res = httpclient.execute(post);
                    } else if ("PUT".equals(method)) {
                        HttpPut post = new HttpPut(url + "?" + paramLine);
                        post.setEntity(new StringEntity(text));
                        res = httpclient.execute(post);
                    } else
                        throw new RuntimeException("Invalid http method: "+method);

                    task.cancel();
                    if (!aborted.get()) {
                        String resText = res.getEntity() != null ? EntityUtils.toString(res.getEntity()) : null;
                        synchronized(JavaPollingClientConnector.this) {
                            onReadyStateChange(task, res.getStatusLine().getStatusCode(), resText);
                        }
                    }
                } catch (Exception ex) {
                    task.cancel();
                    if (!aborted.get())
                        synchronized(JavaPollingClientConnector.this) {
                            onReadyStateChange(task, 503, null);
                        }
                }
            }
        });
        return task;
    }

    @Override
    protected synchronized void httpAbort(@Nonnull Object httpRequest) {
        ((TimerTask) httpRequest).cancel();
        ((TimerTask) httpRequest).run();
    }

    @Override
    protected void schedule(int delay, final @Nonnull Runnable task) {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                synchronized(JavaPollingClientConnector.this) {
                    task.run();
                }
            }
        }, delay);
    }

    @Override
    public synchronized void post(Object payload) {
        super.post(payload);
    }
}
