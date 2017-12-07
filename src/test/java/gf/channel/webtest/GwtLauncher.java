package gf.channel.webtest;

import com.google.gwt.core.client.EntryPoint;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by akuranov on 03/02/2015.

 Google Web Toolkit 2.7.0
 DevMode [-[no]startServer] [-port port-number | "auto"] [-whitelist whitelist-string] [-blacklist blacklist-string] [-logdir directory] [-logLevel level] [-gen dir] [-bindAddress host-name-or-address] [-codeServerPort port-number | "auto"] [-[no]superDevMode] [-server servletContainerLauncher[:args]] [-startupUrl url] [-war dir] [-deploy dir] [-extra dir] [-modulePathPrefix ] [-workDir dir] [-XmethodNameDisplayMode NONE | ONLY_METHOD_NAME | ABBREVIATED | FULL] [-sourceLevel [auto, 1.6, 1.7]] [-XjsInteropMode [NONE, JS, CLOSURE]] [-[no]incremental] project[s]

 where
 -[no]startServer         Starts a servlet container serving the directory specified by the -war flag. (defaults to ON)
 -port                    Specifies the TCP port for the embedded web server (defaults to 8888)
 -whitelist               Allows the user to browse URLs that match the specified regexes (comma or space separated)
 -blacklist               Prevents the user browsing URLs that match the specified regexes (comma or space separated)
 -logdir                  Logs to a file in the given directory, as well as graphically
 -logLevel                The level of logging detail: ERROR, WARN, INFO, TRACE, DEBUG, SPAM, or ALL
 -gen                     Debugging: causes normally-transient generated types to be saved in the specified directory
 -bindAddress             Specifies the bind address for the code server and web server (defaults to 127.0.0.1)
 -codeServerPort          Specifies the TCP port for the code server (defaults to 9997 for classic Dev Mode or 9876 for Super Dev Mode)
 -[no]superDevMode        Runs Super Dev Mode instead of classic Development Mode. (defaults to ON)
 -server                  Specify a different embedded web server to run (must implement ServletContainerLauncher)
 -startupUrl              Automatically launches the specified URL
 -war                     The directory into which deployable output files will be written (defaults to 'war')
 -deploy                  The directory into which deployable but not servable output files will be written (defaults to 'WEB-INF/deploy' under the -war directory/jar, and may be the same as the -extra directory/jar)
 -extra                   The directory into which extra files, not intended for deployment, will be written
 -modulePathPrefix        The subdirectory inside the war dir where DevMode will create project directories. (defaults empty for top level)
 -workDir                 The compiler's working directory for internal use (must be writeable; defaults to a system temp dir)
 -XmethodNameDisplayMode  Emit extra information allow chrome dev tools to display Java identifiers in many places instead of JavaScript functions.
 -sourceLevel             Specifies Java source level (defaults to auto:1.7)
 -XjsInteropMode          Specifies JsInterop mode, either NONE, JS, or CLOSURE (defaults to NONE)
 -[no]incremental         Compiles faster by reusing data from the previous compile. (defaults to ON)
 and
 project[s]                Specifies the name(s) of the project(s) to host

 */
public class GwtLauncher {
    private String module;
    private Class<?> entryPoint;

    public String logLevel = "TRACE";
    public int port = 8888;
    public int codeServerPort = 9997;
    public String startupUrl;
    public boolean superDevMode;


    public GwtLauncher(Class<? extends EntryPoint> entryPoint) {
        this.entryPoint = entryPoint;
    }

    public GwtLauncher startupUrl(String startupUrl) {
        this.startupUrl = startupUrl;
        return this;
    }

    public GwtLauncher gwtModule(String gwtModule) {
        this.module = gwtModule;
        return this;
    }

    public void launch() {
        final String projectPath = getProjectPath(entryPoint);
        //final String webAppPath = findWebappFolder(project, projectPath);
        final String webAppPath = projectPath+"/target/gwt-webapp";
        if (!new File(webAppPath).exists())
            new File(webAppPath).mkdir();
        final String workDir = projectPath+"/target/gwt-work";
        if (!new File(workDir).exists())
            new File(workDir).mkdir();

        if (module == null) {
            String pkg = entryPoint.getName().substring(0, entryPoint.getName().lastIndexOf(".client."));
            module = pkg+"."+entryPoint.getSimpleName();
        }

        try {
            Files.list(Paths.get(projectPath, "src", "test", "resources", "webapp"))
                    .filter(f -> !Files.isDirectory(f))
                    .forEach(f -> {
                try {
                    Files.copy(f, Paths.get(webAppPath, f.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (startupUrl == null)
            startupUrl = module.substring(module.lastIndexOf('.')+1)+".html";

        List<String> params = new ArrayList<String>() {{
            add("-war");
            add(webAppPath);
            add("-gen");
            //add(projectPath + "/target/.generated");
            add(projectPath + "/target/gwt-gen");
            add("-logLevel");
            add(logLevel);
            add("-logdir");
            add(projectPath + "/target/gwt-logs");
            add("-port");
            add("" + port);
            add("-codeServerPort");
            add("" + codeServerPort);
            add("-workDir");
            add(workDir);
            add("-sourceLevel");
            add("auto");
            add("-bindAddress");
            add("0.0.0.0");
            add("-startupUrl");
            add(startupUrl);
            if (!superDevMode)
                add("-nosuperDevMode");
            add("-extra");
            // symbol map information
            add(projectPath + "/target/gwt-extra");
            add("-style");
            add("PRETTY");
        }};
        /*if (module == null) {
            List<String> gwtModules = discoverGwtModules(projectPath);
            params.addAll(gwtModules);
        } else*/
        params.add(module);

        String[] args = params.toArray(new String[0]);
        com.google.gwt.dev.DevMode.main(args);
    }

    public GwtLauncher superDevMode() {
        this.superDevMode = true;
        return this;
    }


    /**
     * Detect if current directory is inside the project or at the root project
     * @param entryPoint project to test
     * @return path to project from the current directory
     */
    /*private static String getProjectPath(String project) {
        if (project == null || "".equals(project))
            return ".";
        File[] ff = new File(".").listFiles();
        String path = null;
        for (File f : ff) {
            if (f.getName().equals(project)) {
                path = project;
                break;
            }
        }
        if (path == null)
            path = ".";
        return path;
    }*/
    private static String getProjectPath(Class<?> entryPoint) {
        String url = entryPoint.getResource(entryPoint.getSimpleName()+".class").getFile();
        String path = url.substring(0, url.lastIndexOf("/target/") );
        File f = new File(path);

        if (!f.exists())
            throw new IllegalArgumentException("Could not resolve project path");
        return f.getPath();
    }

    private String findWebappFolder(String project, String projectPath) {
        File[] ff = new File(projectPath+"/target").listFiles();
        for ( File f : ff ) {
            if ( f.isDirectory() && f.getName().startsWith(project) )
                return projectPath+"/target/"+f.getName();
        }

        throw new IllegalStateException( "Not found webapp folder for project "+project );
    }


    private List<String> discoverGwtModules(String projectPath) {
        List<String> lst = new ArrayList<>();
        File dir = new File(projectPath + "/src/main/resources");
        discoverModules(lst, dir);
        for (int i = 0; i < lst.size(); i++) {
            String m = lst.get(i);
            if (m.startsWith(dir.getPath())) {
                m = m.substring(dir.getPath().length()+1, m.length()-".gwt.xml".length())
                        .replace(File.separatorChar,'.');
            } else
                throw new RuntimeException("WFT: "+m);
            lst.set(i, m);
        }
        return lst;
    }

    private void discoverModules(List<String> lst, File dir) {
        File[] ff = dir.listFiles();

        for (File f : ff) {
            if (f.isDirectory() && !f.getName().startsWith(".") )
                discoverModules( lst, f );
            else if ( f.isFile() && f.getName().endsWith(".gwt.xml") )
                lst.add(f.getPath());
        }
    }
}
