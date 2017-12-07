package gf.channel.client;

import com.google.gwt.core.shared.GwtIncompatible;
import com.google.gwt.junit.client.GWTTestCase;

import java.io.File;

/**
 * Created by akuranov on 05/02/2015.
 *

 JUnitShell [-port port-number | "auto"] [-whitelist whitelist-string] [-blacklist blacklist-string] [-logdir directory] [-logLevel level] [-gen dir] [-codeServerPort port-number | "auto"] [-war dir] [-deploy dir] [-extra dir] [-workDir dir] [-sourceLevel [auto, 1.6, 1.7]] [-style style] [-[no]checkAssertions] [-X[no]checkCasts] [-X[no]classMetadata] [-[no]draftCompile] [-localWorkers count] [-Xnamespace PACKAGE, NONE] [-optimize level] [-[no]devMode] [-testMethodTimeout minutes] [-testBeginTimeout minutes] [-runStyle runstyle[:args]] [-[no]showUi] [-Xtries 1] [-userAgents userAgents] [-[no]incremental] [-XjsInteropMode [NONE, JS, CLOSURE]]
 where
 -port                 Specifies the TCP port for the embedded web server (defaults to 8888)
 -whitelist            Allows the user to browse URLs that match the specified regexes (comma or space separated)
 -blacklist            Prevents the user browsing URLs that match the specified regexes (comma or space separated)
 -logdir               Logs to a file in the given directory, as well as graphically
 -logLevel             The level of logging detail: ERROR, WARN, INFO, TRACE, DEBUG, SPAM, or ALL
 -gen                  Debugging: causes normally-transient generated types to be saved in the specified directory
 -codeServerPort       Specifies the TCP port for the code server (defaults to 9997 for classic Dev Mode or 9876 for Super Dev Mode)
 -war                  The directory into which deployable output files will be written (defaults to 'war')
 -deploy               The directory into which deployable but not servable output files will be written (defaults to 'WEB-INF/deploy' under the -war directory/jar, and may be the same as the -extra directory/jar)
 -extra                The directory into which extra files, not intended for deployment, will be written
 -workDir              The compiler's working directory for internal use (must be writeable; defaults to a system temp dir)
 -sourceLevel          Specifies Java source level (defaults to auto:1.7)
 -style                Script output style: OBF[USCATED], PRETTY, or DETAILED (defaults to OBF)
 -[no]checkAssertions  Include assert statements in compiled output. (defaults to OFF)
 -X[no]checkCasts      EXPERIMENTAL: Insert run-time checking of cast operations. (defaults to ON)
 -X[no]classMetadata   EXPERIMENTAL: Include metadata for some java.lang.Class methods (e.g. getName()). (defaults to ON)
 -[no]draftCompile     Compile quickly with minimal optimizations. (defaults to OFF)
 -localWorkers         The number of local workers to use when compiling permutations
 -Xnamespace           Puts most JavaScript globals into namespaces. Default: PACKAGE for -draftCompile, otherwise NONE
 -optimize             Sets the optimization level used by the compiler.  0=none 9=maximum.
 -[no]devMode          Runs tests in Development Mode, using the Java virtual machine. (defaults to ON)
 -testMethodTimeout    Set the test method timeout, in minutes
 -testBeginTimeout     Set the test begin timeout (time for clients to contact server), in minutes
 -runStyle             Selects the runstyle to use for this test.  The name is a suffix of com.google.gwt.junit.RunStyle or is a fully qualified class name, and may be followed with a colon and an argument for this runstyle.  The specified class mustextend RunStyle.
 -[no]showUi           Causes the log window and browser windows to be displayed; useful for debugging. (defaults to OFF)
 -Xtries               EXPERIMENTAL: Sets the maximum number of attempts for running each test method
 -userAgents           Specify the user agents to reduce the number of permutations for remote browser tests; e.g. ie8,safari,gecko1_8
 -[no]incremental      Compiles faster by reusing data from the previous compile. (defaults to OFF)
 -XjsInteropMode       Specifies JsInterop mode, either NONE, JS, or CLOSURE (defaults to NONE)

 */
public abstract class CustomGwtTestCase extends GWTTestCase
{
    /**
     * To set this property from command like use -Dgwt.test.prod=true
     */
    public Boolean prod;

    public String logLevel = "INFO";
    public Integer port;
    public Integer codeServerPort;
    public boolean incremental = true;
    public boolean web = false;

    /**
     * Override this method to make a custom JUnitShell initialization
     */
    @GwtIncompatible
    protected void gwtInit() {
    }


    @GwtIncompatible
    @Override
    protected void runTest() throws Throwable {
        gwtInit();
        final String projectPath = getProjectPath();
        //final String webAppPath = findWebappFolder(project,projectPath)+"-junit";
        final String webAppPath = projectPath+"/target/gwt-webapp";
        StringBuilder sb = new StringBuilder();
        sb
                .append(" -war ").append(webAppPath)
                .append(" -gen ").append(projectPath + "/target/gwt-gen")
                .append(" -logdir ").append(projectPath + "/target/gwt-logs")
                .append(" -workDir ").append(projectPath + "/target/gwt-work")
                .append(" -logLevel ").append(logLevel);
        if ( port != null )
            sb.append(" -port ").append(port);

        if (codeServerPort != null)
            sb.append(" -codeServerPort ").append(codeServerPort);

        sb.append(" -sourceLevel ").append("auto");

        if (incremental)
            sb.append(' ').append("-incremental");

        if (prod == null) {
            prod = "true".equalsIgnoreCase( System.getProperty("gwt.test.prod") );
        }

        if (prod)
            sb.append(" -prod");

        if (web)
            sb.append(" -web");

        System.setProperty("gwt.args", sb.toString());
        super.runTest();
    }


    /**
     * Get project name (corresponds to module path from root folder)
     * @return name of project
     */
    //protected abstract String getProjectName();


    /**
     * Detect if current directory is inside the project or at the root project
     * @return path to project from the current directory
     */
    /*@GwtIncompatible
    private String getProjectPath() {
        final String project = getProjectName();

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
    @GwtIncompatible
    private String getProjectPath() {
        String url = this.getClass().getResource(this.getClass().getSimpleName()+".class").getFile();
        String path = url.substring(0, url.lastIndexOf("/target/") );
        File f = new File(path);

        if (!f.exists())
            throw new IllegalArgumentException("Could not resolve project path");
        return f.getPath();
    }


    @GwtIncompatible
    private String findWebappFolder(String project, String projectPath) {
        File[] ff = new File(projectPath+"/target").listFiles();
        for ( File f : ff ) {
            if ( f.isDirectory() && f.getName().startsWith(project) )
                return projectPath+"/target/"+f.getName();
        }

        throw new IllegalStateException( "Not found webapp folder for project "+project );
    }
}
