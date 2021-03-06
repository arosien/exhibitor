package com.netflix.exhibitor.application;

import com.netflix.exhibitor.core.Exhibitor;
import com.netflix.exhibitor.core.backup.BackupProvider;
import com.netflix.exhibitor.core.backup.filesystem.FileSystemBackupProvider;
import com.netflix.exhibitor.core.backup.s3.S3BackupProvider;
import com.netflix.exhibitor.core.config.ConfigProvider;
import com.netflix.exhibitor.core.config.DefaultProperties;
import com.netflix.exhibitor.core.config.filesystem.FileSystemConfigProvider;
import com.netflix.exhibitor.core.config.s3.S3ConfigArguments;
import com.netflix.exhibitor.core.config.s3.S3ConfigProvider;
import com.netflix.exhibitor.core.s3.PropertyBasedS3Credential;
import com.netflix.exhibitor.core.s3.S3ClientFactoryImpl;
import com.netflix.exhibitor.core.rest.UIContext;
import com.netflix.exhibitor.core.rest.jersey.JerseySupport;
import com.sun.jersey.api.core.DefaultResourceConfig;
import com.sun.jersey.spi.container.servlet.ServletContainer;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.ServletHolder;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;

public class ExhibitorMain implements Closeable
{
    private final Server server;

    private static final String FILESYSTEMCONFIG = "filesystemconfig";
    private static final String S3_CREDENTIALS = "s3credentials";
    private static final String S3_BACKUP = "s3backup";
    private static final String S3_CONFIG = "s3config";
    private static final String FILESYSTEMBACKUP = "filesystembackup";
    private static final String TIMEOUT = "timeout";
    private static final String LOGLINES = "loglines";
    private static final String HOSTNAME = "hostname";
    private static final String CONFIGCHECKMS = "configcheckms";
    private static final String HELP = "help";
    private static final String ALT_HELP = "?";
    private static final String HTTP_PORT = "port";

    public static void main(String[] args) throws Exception
    {
        String      hostname = Exhibitor.getHostname();

        Options     options  = new Options();
        options.addOption(null, FILESYSTEMCONFIG, true, "Path to store Exhibitor properties (cannot be used with s3config). Exhibitor uses file system locks so you can specify a shared location so as to enable complete ensemble management. Default location is the working directory.");
        options.addOption(null, S3_CREDENTIALS, true, "Required if you use s3backup or s3config. Argument is the path to an AWS credential properties file with two properties: " + PropertyBasedS3Credential.PROPERTY_S3_KEY_ID + " and " + PropertyBasedS3Credential.PROPERTY_S3_SECRET_KEY);
        options.addOption(null, S3_BACKUP, true, "If true, enables AWS S3 backup of ZooKeeper log files (s3credentials must be provided as well).");
        options.addOption(null, S3_CONFIG, true, "Enables AWS S3 shared config files as opposed to file system config files (s3credentials must be provided as well). Argument is [bucket name]:[key].");
        options.addOption(null, FILESYSTEMBACKUP, true, "If true, enables file system backup of ZooKeeper log files.");
        options.addOption(null, TIMEOUT, true, "Connection timeout (ms) for ZK connections. Default is 30000.");
        options.addOption(null, LOGLINES, true, "Max lines of logging to keep in memory for display. Default is 1000.");
        options.addOption(null, HOSTNAME, true, "Hostname to use for this JVM. Default is: " + hostname);
        options.addOption(null, CONFIGCHECKMS, true, "Period (ms) to check config file. Default is: 30000");
        options.addOption(null, HTTP_PORT, true, "Port for the HTTP Server. Default is: 8080");
        options.addOption(ALT_HELP, HELP, false, "Print this help");

        CommandLine         commandLine;
        try
        {
            CommandLineParser   parser = new PosixParser();
            commandLine = parser.parse(options, args);
            if ( commandLine.hasOption('?') || commandLine.hasOption(HELP) || (commandLine.getArgList().size() > 0) )
            {
                printHelp(options);
                return;
            }
        }
        catch ( ParseException e )
        {
            printHelp(options);
            return;
        }

        if ( !checkMutuallyExclusive(options, commandLine, S3_BACKUP, FILESYSTEMBACKUP) )
        {
            return;
        }
        if ( !checkMutuallyExclusive(options, commandLine, S3_CONFIG, FILESYSTEMCONFIG) )
        {
            return;
        }

        PropertyBasedS3Credential   awsCredentials = null;
        if ( commandLine.hasOption(S3_CREDENTIALS) )
        {
            awsCredentials = new PropertyBasedS3Credential(new File(commandLine.getOptionValue(S3_CREDENTIALS)));
        }

        BackupProvider      backupProvider = null;
        if ( "true".equalsIgnoreCase(commandLine.getOptionValue(S3_BACKUP)) )
        {
            if ( awsCredentials == null )
            {
                System.err.println("s3backup not specified");
                printHelp(options);
                return;
            }
            backupProvider = new S3BackupProvider(new S3ClientFactoryImpl(), awsCredentials);
        }
        else if ( "true".equalsIgnoreCase(commandLine.getOptionValue(FILESYSTEMBACKUP)) )
        {
            backupProvider = new FileSystemBackupProvider();
        }

        ConfigProvider      provider;
        if ( commandLine.hasOption(S3_CONFIG) )
        {
            if ( awsCredentials == null )
            {
                System.err.println("s3backup not specified");
                printHelp(options);
                return;
            }
            provider = new S3ConfigProvider(new S3ClientFactoryImpl(), awsCredentials, getS3Arguments(commandLine.getOptionValue(S3_CONFIG), options));
        }
        else
        {
            File        propertiesFile = commandLine.hasOption(FILESYSTEMCONFIG) ? new File(commandLine.getOptionValue(FILESYSTEMCONFIG)) : new File("exhibitor.properties");
            provider = new FileSystemConfigProvider(propertiesFile, DefaultProperties.get());
        }
        
        int         timeoutMs = Integer.parseInt(commandLine.getOptionValue(TIMEOUT, "30000"));
        int         logWindowSizeLines = Integer.parseInt(commandLine.getOptionValue(LOGLINES, "1000"));
        int         configCheckMs = Integer.parseInt(commandLine.getOptionValue(CONFIGCHECKMS, "30000"));
        String      useHostname = commandLine.getOptionValue(HOSTNAME, hostname);
        int         httpPort = Integer.parseInt(commandLine.getOptionValue(HTTP_PORT, "8080"));

        Exhibitor.Arguments     arguments = new Exhibitor.Arguments(timeoutMs, logWindowSizeLines, useHostname, configCheckMs);
        ExhibitorMain exhibitorMain = new ExhibitorMain(backupProvider, provider, arguments, httpPort);
        exhibitorMain.start();
        exhibitorMain.join();
    }

    private static boolean checkMutuallyExclusive(Options options, CommandLine commandLine, String option1, String option2)
    {
        if ( commandLine.hasOption(option1) && commandLine.hasOption(option2) )
        {
            System.err.println(option1 + " and " + option2 + " cannot be used at the same time");
            printHelp(options);
            return false;
        }
        return true;
    }

    private static S3ConfigArguments getS3Arguments(String value, Options options)
    {
        String[]        parts = value.split(":");
        if ( parts.length != 2 )
        {
            System.err.println("Bad s3config argument: " + value);
            printHelp(options);
            return null;
        }
        return new S3ConfigArguments(parts[0].trim(), parts[1].trim());
    }

    public ExhibitorMain(BackupProvider backupProvider, ConfigProvider configProvider, Exhibitor.Arguments arguments, int httpPort) throws Exception
    {
        Exhibitor               exhibitor = new Exhibitor(configProvider, null, backupProvider, arguments);
        exhibitor.start();

        DefaultResourceConfig   application = JerseySupport.newApplicationConfig(new UIContext(exhibitor));
        ServletContainer        container = new ServletContainer(application);
        server = new Server(httpPort);
        Context root = new Context(server, "/", Context.SESSIONS);
        root.addServlet(new ServletHolder(container), "/*");
    }

    public void start() throws Exception
    {
        server.start();
    }

    public void join() throws Exception
    {
        server.join();
    }

    @Override
    public void close() throws IOException
    {
        server.destroy();
    }

    private static void printHelp(Options options)
    {
        HelpFormatter       formatter = new HelpFormatter();
        formatter.printHelp("ExhibitorMain", options);
        System.exit(0);
    }
}
