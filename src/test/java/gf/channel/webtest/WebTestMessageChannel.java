package gf.channel.webtest;

import gf.channel.webtest.client.MessageChannelTestUi;
import org.junit.*;
import org.openqa.selenium.*;
import org.openqa.selenium.firefox.FirefoxDriver;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 *
 * N O T E !!!
 *
 * You need to download and install selenium gecko webdriver before running this test:
 * https://github.com/mozilla/geckodriver/releases
 * and set up a path to executable:
 * webdriver.gecko.driver
 *
 */
public class WebTestMessageChannel {
    private static WebDriver webDriver;

    private static Thread thread;

    @BeforeClass
    public static void setup() {
        System.setProperty("webdriver.gecko.driver", "D:\\tools\\geckodriver.exe");
        thread = new Thread(() -> new GwtLauncher(MessageChannelTestUi.class).superDevMode().launch());
        thread.setDaemon(true);
        thread.start();

        webDriver = new FirefoxDriver();
        // AK: increase the timeout if page compilation takes a longer time
        webDriver.manage().timeouts().implicitlyWait(40, TimeUnit.SECONDS);
    }

    @AfterClass
    public static void stop() {
        thread.interrupt();
    }

    @After
    public void close() {
        webDriver.close();
    }

    @Test
    public void testWebsocket() throws Exception {
        for (int i = 0; i < 60; i++) {
            try {
                webDriver.get("http://localhost:8888/MessageChannelTestUi.html");
                break;
            } catch (WebDriverException ex) {
                Thread.sleep(1000);
            }
        }
        webDriver.findElement(By.id("tests"));  // wait for page load
        List<WebElement> tests = webDriver.findElements(By.className("test"));

        for (WebElement test : tests) {
            //test.click();
            String id = test.getAttribute("id");
            test.sendKeys(Keys.ENTER);
            Thread.sleep(2000);
            WebElement it = webDriver.findElement(By.id(id));
            String classes = it.getAttribute("class");
            if (!classes.contains("success"))
                Assert.fail("Failed: " + it.getText());
        }
    }
}
