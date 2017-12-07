package gf.channel.webtest.client;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.core.client.GWT;
import com.google.gwt.core.shared.GwtIncompatible;
import com.google.gwt.dom.client.Element;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.logging.client.HasWidgetsLogHandler;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.*;
import gf.channel.webtest.GwtLauncher;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by anton on 02/10/2015.
 */
public class MessageChannelTestUi implements EntryPoint {
    private final static Logger log = Logger.getLogger(MessageChannelTestUi.class.getName());

    @Override
    public void onModuleLoad() {
        Window.setTitle("Message Channel Test");
        GWT.setUncaughtExceptionHandler(new GWT.UncaughtExceptionHandler() {
            @Override
            public void onUncaughtException(Throwable e) {
                log.log(Level.SEVERE, "Uncaught exception", e);
            }
        });

        RootPanel.get().add(new Label("Module base URL: " + GWT.getModuleBaseURL()));
        HorizontalPanel buttons = new HorizontalPanel();
        buttons.getElement().setId("tests");
        RootPanel.get().add(buttons);

        Button button;
        int testCounter = 0;

        button = new Button("Simple exchange", (ClickHandler) event -> {
            final Element b = event.getRelativeElement();
            new SimpleExchangeTest().run(result ->
                    b.addClassName(result ? "success" : "failed"));
        });
        button.getElement().setId("test-" + ++testCounter);
        button.getElement().addClassName("test");
        buttons.add(button);

        button = new Button("Quick bulk send", (ClickHandler) event -> {
            final Element b = event.getRelativeElement();
            new QuickBulkTest().run(result ->
                    b.addClassName(result ? "success" : "failed"));
        });
        button.getElement().setId("test-" + ++testCounter);
        button.getElement().addClassName("test");
        buttons.add(button);

        button = new Button("Ping pong", (ClickHandler) event -> {
            final Element b = event.getRelativeElement();
            new PingPongTest().run(result ->
                    b.addClassName(result ? "success" : "failed"));
        });
        button.getElement().setId("test-" + ++testCounter);
        button.getElement().addClassName("test");
        buttons.add(button);

        VerticalPanel logArea = new VerticalPanel();
        Logger.getLogger("").addHandler(new HasWidgetsLogHandler(logArea));
        RootPanel.get().add(logArea);
        log.finest("Started up");
    }

    @GwtIncompatible
    public static void main(String[] args) {
        new GwtLauncher(MessageChannelTestUi.class).superDevMode().launch();
    }
}
