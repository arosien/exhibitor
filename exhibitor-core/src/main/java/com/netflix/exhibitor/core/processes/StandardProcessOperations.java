package com.netflix.exhibitor.core.processes;

import com.google.common.collect.Iterables;
import com.google.common.io.Closeables;
import com.google.common.io.Files;
import com.netflix.exhibitor.core.Exhibitor;
import com.netflix.exhibitor.core.activity.ActivityLog;
import com.netflix.exhibitor.core.config.InstanceConfig;
import com.netflix.exhibitor.core.config.IntConfigs;
import com.netflix.exhibitor.core.config.StringConfigs;
import com.netflix.exhibitor.core.state.ServerList;
import com.netflix.exhibitor.core.state.ServerSpec;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Date;
import java.util.Properties;

public class StandardProcessOperations implements ProcessOperations
{
    private final Exhibitor exhibitor;

    public StandardProcessOperations(Exhibitor exhibitor) throws IOException
    {
        this.exhibitor = exhibitor;
    }

    @Override
    public void cleanupInstance() throws Exception
    {
        Details             details = new Details(exhibitor);
        if ( !details.isValid() )
        {
            return;
        }

        // see http://zookeeper.apache.org/doc/r3.3.3/zookeeperAdmin.html#Ongoing+Data+Directory+Cleanup
        ProcessBuilder      builder = new ProcessBuilder
        (
            "java",
            "-cp",
            String.format("%s:%s:%s", details.zooKeeperJarPath.getPath(), details.log4jJarPath.getPath(), details.configDirectory.getPath()),
            "org.apache.zookeeper.server.PurgeTxnLog",
            details.dataDirectory.getPath(),
            details.dataDirectory.getPath(),
            "-n",
            Integer.toString(exhibitor.getConfigManager().getConfig().getInt(IntConfigs.CLEANUP_MAX_FILES))
        );

        exhibitor.getProcessMonitor().monitor(ProcessTypes.CLEANUP, builder.start(), "Cleanup task completed", ProcessMonitor.Mode.DESTROY_ON_INTERRUPT, ProcessMonitor.Streams.ERROR);
    }

    @Override
    public void killInstance() throws Exception
    {
        exhibitor.getLog().add(ActivityLog.Type.INFO, "Attempting to start/restart ZooKeeper");

        exhibitor.getProcessMonitor().destroy(ProcessTypes.ZOOKEEPER);

        ProcessBuilder          builder = new ProcessBuilder("jps");
        Process                 jpsProcess = builder.start();
        String                  pid = null;
        try
        {
            BufferedReader in = new BufferedReader(new InputStreamReader(jpsProcess.getInputStream()));
            for(;;)
            {
                String  line = in.readLine();
                if ( line == null )
                {
                    break;
                }
                String[]  components = line.split("[ \t]");
                if ( (components.length == 2) && components[1].equals("QuorumPeerMain") )
                {
                    pid = components[0];
                    break;
                }
            }
        }
        finally
        {
            jpsProcess.destroy();
        }

        if ( pid == null )
        {
            exhibitor.getLog().add(ActivityLog.Type.INFO, "jps didn't find instance - assuming ZK is not running");
        }
        else
        {
            builder = new ProcessBuilder("kill", "-9", pid);
            try
            {
                int     result = builder.start().waitFor();
                exhibitor.getLog().add(ActivityLog.Type.INFO, "Kill attempted result: " + result);
            }
            catch ( InterruptedException e )
            {
                // don't reset thread interrupted status

                exhibitor.getLog().add(ActivityLog.Type.ERROR, "Process interrupted while running: kill -9 " + pid);
                throw e;
            }
        }
    }

    @Override
    public void startInstance() throws Exception
    {
        Details         details = new Details(exhibitor);

        prepConfigFile(details);
        File            binDirectory = new File(details.zooKeeperDirectory, "bin");
        File            startScript = new File(binDirectory, "zkServer.sh");
        ProcessBuilder  builder = new ProcessBuilder(startScript.getPath(), "start").directory(binDirectory.getParentFile());

        exhibitor.getProcessMonitor().monitor(ProcessTypes.ZOOKEEPER, builder.start(), null, ProcessMonitor.Mode.LEAVE_RUNNING_ON_INTERRUPT, ProcessMonitor.Streams.BOTH);

        exhibitor.getLog().add(ActivityLog.Type.INFO, "Process started via: " + startScript.getPath());
    }

    private void prepConfigFile(Details details) throws IOException
    {
        InstanceConfig          config = exhibitor.getConfigManager().getConfig();

        ServerList serverList = new ServerList(config.getString(StringConfigs.SERVERS_SPEC));

        File                    idFile = new File(details.dataDirectory, "myid");
        ServerSpec us = Iterables.find(serverList.getSpecs(), ServerList.isUs(exhibitor.getThisJVMHostname()), null);
        if ( us != null )
        {
            Files.createParentDirs(idFile);
            String                  id = String.format("%d\n", us.getServerId());
            Files.write(id.getBytes(), idFile);
        }
        else
        {
            exhibitor.getLog().add(ActivityLog.Type.INFO, "Starting in standalone mode");
            if ( idFile.exists() && !idFile.delete() )
            {
                exhibitor.getLog().add(ActivityLog.Type.ERROR, "Could not delete ID file: " + idFile);
            }
        }

        Properties      localProperties = new Properties();
        localProperties.putAll(details.properties);

        localProperties.setProperty("clientPort", Integer.toString(config.getInt(IntConfigs.CLIENT_PORT)));

        String          portSpec = String.format(":%d:%d", config.getInt(IntConfigs.CONNECT_PORT), config.getInt(IntConfigs.ELECTION_PORT));
        for ( ServerSpec spec : serverList.getSpecs() )
        {
            localProperties.setProperty("server." + spec.getServerId(), spec.getHostname() + portSpec);
        }

        File            configFile = new File(details.configDirectory, "zoo.cfg");
        OutputStream out = new BufferedOutputStream(new FileOutputStream(configFile));
        try
        {
            localProperties.store(out, "Auto-generated by Exhibitor - " + new Date());
        }
        finally
        {
            Closeables.closeQuietly(out);
        }
    }
}
