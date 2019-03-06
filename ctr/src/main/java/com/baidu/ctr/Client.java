package com.baidu.ctr;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FilenameUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.ClassUtil;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.ApplicationConstants.Environment;
import org.apache.hadoop.yarn.api.protocolrecords.GetNewApplicationResponse;
import org.apache.hadoop.yarn.api.records.*;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.client.api.YarnClientApplication;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.log4j.Logger;

public class Client {
    static private Logger logger = Logger.getLogger(Client.class);
    private static final FsPermission CTR_DIR_PERM =
            FsPermission.createImmutable((short) 448);
    // Owner rw (600)
    private static final FsPermission CTR_FILE_PERM =
            FsPermission.createImmutable((short) 384);
    private static Configuration conf = new Configuration();

    public static void main(String[] args) throws Exception {
        if (UserGroupInformation.isSecurityEnabled()) {
            throw new Exception("SecurityEnabled , not support");
        }

        Client client = new Client();

        // 1. create and start a yarnClient
        YarnClient yarnClient = YarnClient.createYarnClient();
        yarnClient.init(conf);
        yarnClient.start();

        // 2. create an application
        YarnClientApplication app = yarnClient.createApplication();
        app.getApplicationSubmissionContext()
                .setKeepContainersAcrossApplicationAttempts(false);
        app.getApplicationSubmissionContext().setApplicationName(
                "com.baidu.ctr.ApplicationMaster");

        // 3. Set the app's resource usage, 100*10MB, 1vCPU
        Resource capability = Resource.newInstance(1000, 1);
        app.getApplicationSubmissionContext().setResource(capability);

        // 4. Set the app's localResource env and command by ContainerLaunchContext
        ContainerLaunchContext amContainer = createAMContainerLanunchContext(
                conf, app.getApplicationSubmissionContext().getApplicationId());
        app.getApplicationSubmissionContext().setAMContainerSpec(amContainer);

        // 5. submit to queue default
        app.getApplicationSubmissionContext().setPriority(
                Priority.newInstance(0));
        app.getApplicationSubmissionContext().setQueue("default");
        ApplicationId appId = yarnClient.submitApplication(app
                .getApplicationSubmissionContext());

        monitorApplicationReport(yarnClient, appId);
    }

    private static Path getAppDir(FileSystem fs, ApplicationId appId) {
        return new Path(fs.getHomeDirectory(), appId.toString());
    }

    private static ContainerLaunchContext createAMContainerLanunchContext(
            Configuration conf, ApplicationId appId) throws IOException {
        //Add this jar file to hdfs
        Map<String, LocalResource> localResources = new HashMap<String, LocalResource>();
        FileSystem fs = FileSystem.get(conf);
        String thisJar = ClassUtil.findContainingJar(Client.class);
        String thisJarBaseName = FilenameUtils.getName(thisJar);
        logger.info("thisJar is " + thisJar);

        // Directory to store temporary application resources
        Path appDir = getAppDir(fs, appId);

        FileSystem.mkdirs(fs, appDir, CTR_DIR_PERM);

        // Setup the LocalResources for the appmaster and containers
        addToLocalResources(fs, thisJar, appDir, localResources);

        //Set CLASSPATH environment
        Map<String, String> env = new HashMap<String, String>();
        StringBuilder classPathEnv = new StringBuilder(
                Environment.CLASSPATH.$$());
        classPathEnv.append(ApplicationConstants.CLASS_PATH_SEPARATOR);
        classPathEnv.append("./*");
        for (String c : conf
                .getStrings(
                        YarnConfiguration.YARN_APPLICATION_CLASSPATH,
                        YarnConfiguration.DEFAULT_YARN_CROSS_PLATFORM_APPLICATION_CLASSPATH)) {
            classPathEnv.append(ApplicationConstants.CLASS_PATH_SEPARATOR);
            classPathEnv.append(c.trim());
        }

        if (conf.getBoolean(YarnConfiguration.IS_MINI_YARN_CLUSTER, false)) {
            classPathEnv.append(':');
            classPathEnv.append(System.getProperty("java.class.path"));
        }
        env.put(Environment.CLASSPATH.name(), classPathEnv.toString());

        //Build the execute command
        List<String> commands = new LinkedList<String>();
        StringBuilder command = new StringBuilder();
        command.append(Environment.JAVA_HOME.$$()).append("/bin/java  ");
        command.append("-Dlog4j.configuration=container-log4j.properties ");
        command.append("-Dyarn.app.container.log.dir=" +
                ApplicationConstants.LOG_DIR_EXPANSION_VAR + " ");
        command.append("-Dyarn.app.container.log.filesize=0 ");
        command.append("-Dhadoop.root.logger=INFO,CLA ");
        command.append("com.baidu.ctr.ApplicationMaster ");
        command.append("1>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/stdout ");
        command.append("2>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/stderr ");
        commands.add(command.toString());

        ContainerLaunchContext amContainer = ContainerLaunchContext
                .newInstance(localResources, env, commands, null, null, null);
        return amContainer;
    }

    private static void addToLocalResources(FileSystem fs, String fileSrcPath,
                                            Path dst,
                                            Map<String, LocalResource> localResources)
            throws IllegalArgumentException, IOException {
        logger.info("hdfs copyFromLocalFile " + fileSrcPath + " =>" + dst);
        fs.copyFromLocalFile(new Path(fileSrcPath), dst);

//        fs.copyFromLocalFile(new Path("./ctr.tar.gz"), appDir);
        FileStatus scFileStatus = fs.getFileStatus(dst);
        LocalResource scRsrc = LocalResource.newInstance(
                ConverterUtils.getYarnUrlFromPath(dst), LocalResourceType.FILE,
                LocalResourceVisibility.APPLICATION, scFileStatus.getLen(),
                scFileStatus.getModificationTime());

        localResources.put(dst.toString(), scRsrc);
    }

    private static void monitorApplicationReport(YarnClient yarnClient, ApplicationId appId) throws YarnException, IOException {
        ApplicationReport appReport = yarnClient.getApplicationReport(appId);
        YarnApplicationState appState = appReport.getYarnApplicationState();
        while (appState != YarnApplicationState.FINISHED
                && appState != YarnApplicationState.KILLED
                && appState != YarnApplicationState.FAILED) {
            try {
                Thread.sleep(5 * 1000);
            } catch (InterruptedException e) {

            }
            appReport = yarnClient.getApplicationReport(appId);
            appState = appReport.getYarnApplicationState();
        }
        ApplicationReport report = yarnClient.getApplicationReport(appId);
        logger.info("Got application report " + ", clientToAMToken="
                + report.getClientToAMToken() + ", appDiagnostics="
                + report.getDiagnostics() + ", appMasterHost="
                + report.getHost() + ", appQueue=" + report.getQueue()
                + ", appMasterRpcPort=" + report.getRpcPort()
                + ", appStartTime=" + report.getStartTime()
                + ", yarnAppState="
                + report.getYarnApplicationState().toString()
                + ", distributedFinalState="
                + report.getFinalApplicationStatus().toString()
                + ", appTrackingUrl=" + report.getTrackingUrl()
                + ", appUser=" + report.getUser());
    }
}
