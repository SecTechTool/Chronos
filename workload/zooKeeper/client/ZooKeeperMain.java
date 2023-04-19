/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper;

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.admin.ZooKeeperAdmin;
import org.apache.zookeeper.cli.*;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.server.ExitCode;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.apache.zookeeper.util.ServiceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The command line client to ZooKeeper.
 *
 */
@InterfaceAudience.Public
public class ZooKeeperMain {

    private static final Logger LOG = LoggerFactory.getLogger(ZooKeeperMain.class);
    static final Map<String, String> commandMap = new HashMap<>();
    static final Map<String, CliCommand> commandMapCli = new HashMap<>();

    protected MyCommandOptions cl = new MyCommandOptions();
    protected HashMap<Integer, String> history = new HashMap<>();
    protected int commandCount = 0;
    protected boolean printWatches = true;
    protected int exitCode = ExitCode.EXECUTION_FINISHED.getValue();

    protected ZooKeeper zk;
    protected String host = "";
    private CountDownLatch connectLatch = null;

    public boolean getPrintWatches() {
        return printWatches;
    }

    static {
        commandMap.put("connect", "host:port");
        commandMap.put("history", "");
        commandMap.put("redo", "cmdno");
        commandMap.put("printwatches", "on|off");
        commandMap.put("quit", "");
        Stream.of(CommandFactory.Command.values())
            .map(command -> CommandFactory.getInstance(command))
            // add all commands to commandMapCli and commandMap
            .forEach(cliCommand ->{
                cliCommand.addToMap(commandMapCli);
                commandMap.put(
                        cliCommand.getCmdStr(),
                        cliCommand.getOptionStr());
            });
    }

    static void usage() {
        System.err.println("ZooKeeper -server host:port -client-configuration properties-file cmd args");
        List<String> cmdList = new ArrayList<>(commandMap.keySet());
        Collections.sort(cmdList);
        for (String cmd : cmdList) {
            System.err.println("\t" + cmd + " " + commandMap.get(cmd));
        }
    }

    private class MyWatcher implements Watcher {

        public void process(WatchedEvent event) {
            if (getPrintWatches()) {
                ZooKeeperMain.printMessage("WATCHER::");
                ZooKeeperMain.printMessage(event.toString());
            }
            if (connectLatch != null) {
                // connection success
                if (event.getType() == Event.EventType.None
                    && event.getState() == Event.KeeperState.SyncConnected) {
                    connectLatch.countDown();
                }
            }
        }

    }

    /**
     * A storage class for both command line options and shell commands.
     *
     */
    static class MyCommandOptions {

        private Map<String, String> options = new HashMap<>();
        private List<String> cmdArgs = null;
        private String command = null;
        public static final Pattern ARGS_PATTERN = Pattern.compile("\\s*([^\"\']\\S*|\"[^\"]*\"|'[^']*')\\s*");
        public static final Pattern QUOTED_PATTERN = Pattern.compile("^([\'\"])(.*)(\\1)$");

        public MyCommandOptions() {
            options.put("server", "localhost:2181");
            options.put("timeout", "30000");
        }

        public String getOption(String opt) {
            return options.get(opt);
        }

        public String getCommand() {
            return command;
        }

        public String getCmdArgument(int index) {
            return cmdArgs.get(index);
        }

        public int getNumArguments() {
            return cmdArgs.size();
        }

        public String[] getArgArray() {
            return cmdArgs.toArray(new String[0]);
        }

        /**
         * Parses a command line that may contain one or more flags
         * before an optional command string
         * @param args command line arguments
         * @return true if parsing succeeded, false otherwise.
         */
        public boolean parseOptions(String[] args) {
            List<String> argList = Arrays.asList(args);
            Iterator<String> it = argList.iterator();

            while (it.hasNext()) {
                String opt = it.next();
                try {
                    if (opt.equals("-server")) {
                        options.put("server", it.next());
                    } else if (opt.equals("-timeout")) {
                        options.put("timeout", it.next());
                    } else if (opt.equals("-r")) {
                        options.put("readonly", "true");
                    } else if (opt.equals("-client-configuration")) {
                        options.put("client-configuration", it.next());
                    } else if (opt.equals("-waitforconnection")) {
                        options.put("waitforconnection", "true");
                    }
                } catch (NoSuchElementException e) {
                    System.err.println("Error: no argument found for option " + opt);
                    return false;
                }

                if (!opt.startsWith("-")) {
                    command = opt;
                    cmdArgs = new ArrayList<>();
                    cmdArgs.add(command);
                    while (it.hasNext()) {
                        cmdArgs.add(it.next());
                    }
                    return true;
                }
            }
            return true;
        }

        /**
         * Breaks a string into command + arguments.
         * @param cmdstring string of form "cmd arg1 arg2..etc"
         * @return true if parsing succeeded.
         */
        public boolean parseCommand(String cmdstring) {
            Matcher matcher = ARGS_PATTERN.matcher(cmdstring);

            List<String> args = new LinkedList<>();
            while (matcher.find()) {
                String value = matcher.group(1);
                if (QUOTED_PATTERN.matcher(value).matches()) {
                    // Strip off the surrounding quotes
                    value = value.substring(1, value.length() - 1);
                }
                args.add(value);
            }
            if (args.isEmpty()) {
                return false;
            }
            command = args.get(0);
            cmdArgs = args;
            return true;
        }

    }

    /**
     * Makes a list of possible completions, either for commands
     * or for zk nodes if the token to complete begins with /
     *
     */

    protected void addToHistory(int i, String cmd) {
        history.put(i, cmd);
    }

    public static List<String> getCommands() {
        List<String> cmdList = new ArrayList<>(commandMap.keySet());
        Collections.sort(cmdList);
        return cmdList;
    }

    protected String getPrompt() {
        return "[zk: " + host + "(" + zk.getState() + ")" + " " + commandCount + "] ";
    }

    public static void printMessage(String msg) {
        System.out.println("\n" + msg);
    }

    protected void connectToZK(String newHost) throws InterruptedException, IOException {
        if (zk != null && zk.getState().isAlive()) {
            zk.close();
        }

        host = newHost;
        boolean readOnly = cl.getOption("readonly") != null;
        if (cl.getOption("secure") != null) {
            System.setProperty(ZKClientConfig.SECURE_CLIENT, "true");
            System.out.println("Secure connection is enabled");
        }

        ZKClientConfig clientConfig = null;

        if (cl.getOption("client-configuration") != null) {
            try {
                clientConfig = new ZKClientConfig(cl.getOption("client-configuration"));
            } catch (QuorumPeerConfig.ConfigException e) {
                e.printStackTrace();
                ServiceUtils.requestSystemExit(ExitCode.INVALID_INVOCATION.getValue());
            }
        }

        if (cl.getOption("waitforconnection") != null) {
            connectLatch = new CountDownLatch(1);
        }

        int timeout = Integer.parseInt(cl.getOption("timeout"));
        zk = new ZooKeeperAdmin(host, timeout, new MyWatcher(), readOnly, clientConfig);
        if (connectLatch != null) {
            if (!connectLatch.await(timeout, TimeUnit.MILLISECONDS)) {
                zk.close();
                throw new IOException(KeeperException.create(KeeperException.Code.CONNECTIONLOSS));
            }
        }

    }

    public static void main(String[] args) throws IOException, InterruptedException {
        ZooKeeperMain main = new ZooKeeperMain(args);
        main.run();
    }

    public ZooKeeperMain(String[] args) throws IOException, InterruptedException {
        cl.parseOptions(args);
        System.out.println("Connecting to " + cl.getOption("server"));
        connectToZK(cl.getOption("server"));
    }

    public ZooKeeperMain(ZooKeeper zk) {
        this.zk = zk;
    }

    void run() throws IOException, InterruptedException {
        if (cl.getCommand() == null) {
            System.out.println("Welcome to ZooKeeper!");

            //new thread for fuzzing process!!!
            Thread myTest = new MyTestThread();
            myTest.start();

            boolean jlinemissing = false;
            // only use jline if it's in the classpath
            try {
                Class<?> consoleC = Class.forName("jline.console.ConsoleReader");
                Class<?> completorC = Class.forName("org.apache.zookeeper.JLineZNodeCompleter");

                System.out.println("JLine support is enabled");

                Object console = consoleC.getConstructor().newInstance();

                Object completor = completorC.getConstructor(ZooKeeper.class).newInstance(zk);
                Method addCompletor = consoleC.getMethod("addCompleter", Class.forName("jline.console.completer.Completer"));
                addCompletor.invoke(console, completor);

                String line;
                Method readLine = consoleC.getMethod("readLine", String.class);
                while ((line = (String) readLine.invoke(console, getPrompt())) != null) {
                    executeLine(line);
                }
            } catch (ClassNotFoundException
                | NoSuchMethodException
                | InvocationTargetException
                | IllegalAccessException
                | InstantiationException e
            ) {
                LOG.debug("Unable to start jline", e);
                jlinemissing = true;
            }

            if (jlinemissing) {
                System.out.println("JLine support is disabled");
                BufferedReader br = new BufferedReader(new InputStreamReader(System.in));

                String line;
                while ((line = br.readLine()) != null) {
                    executeLine(line);
                }
            }
        } else {
            // Command line args non-null.  Run what was passed.
            processCmd(cl);
        }
        ServiceUtils.requestSystemExit(exitCode);
    }

    public void executeLine(String line) throws InterruptedException, IOException {
        if (!line.equals("")) {
            cl.parseCommand(line);
            addToHistory(commandCount, line);
            processCmd(cl);
            commandCount++;
        }
    }

    protected boolean processCmd(MyCommandOptions co) throws IOException, InterruptedException {
        boolean watch = false;
        try {
            watch = processZKCmd(co);
            exitCode = ExitCode.EXECUTION_FINISHED.getValue();
        } catch (CliException ex) {
            exitCode = ex.getExitCode();
            System.err.println(ex.getMessage());
        }
        return watch;
    }

    protected boolean processZKCmd(MyCommandOptions co) throws CliException, IOException, InterruptedException {
        String[] args = co.getArgArray();
        String cmd = co.getCommand();
        if (args.length < 1) {
            usage();
            throw new MalformedCommandException("No command entered");
        }

        if (!commandMap.containsKey(cmd)) {
            usage();
            throw new CommandNotFoundException("Command not found " + cmd);
        }

        boolean watch = false;

        LOG.debug("Processing {}", cmd);

        if (cmd.equals("quit")) {
            zk.close();
            ServiceUtils.requestSystemExit(exitCode);
        } else if (cmd.equals("redo") && args.length >= 2) {
            Integer i = Integer.decode(args[1]);
            if (commandCount <= i || i < 0) { // don't allow redoing this redo
                throw new MalformedCommandException("Command index out of range");
            }
            cl.parseCommand(history.get(i));
            if (cl.getCommand().equals("redo")) {
                throw new MalformedCommandException("No redoing redos");
            }
            history.put(commandCount, history.get(i));
            processCmd(cl);
        } else if (cmd.equals("history")) {
            for (int i = commandCount - 10; i <= commandCount; ++i) {
                if (i < 0) {
                    continue;
                }
                System.out.println(i + " - " + history.get(i));
            }
        } else if (cmd.equals("printwatches")) {
            if (args.length == 1) {
                System.out.println("printwatches is " + (printWatches ? "on" : "off"));
            } else {
                printWatches = args[1].equals("on");
            }
        } else if (cmd.equals("connect")) {
            if (args.length >= 2) {
                connectToZK(args[1]);
            } else {
                connectToZK(host);
            }
        }

        // Below commands all need a live connection
        if (zk == null || !zk.getState().isAlive()) {
            System.out.println("Not connected");
            return false;
        }

        // execute from commandMap
        CliCommand cliCmd = commandMapCli.get(cmd);
        if (cliCmd != null) {
            cliCmd.setZk(zk);
            watch = cliCmd.parse(args).exec();
        } else if (!commandMap.containsKey(cmd)) {
            usage();
        }
        return watch;
    }

    class MyTestThread extends Thread{

        List<String> paths = new ArrayList<String>();
        String[] apis = {"addWatch","addauth scheme auth", "close", "config", "connect", "create", "delete", "deleteall", "delquota","get", "getAcl", "getAllChildrenNumber", "getEphemerals", "history", "listquota", "ls","printwatches", "quit", "reconfig", "redo", "removewatches", "set", "setAcl", "setquota", "stat", "sync", "version", "whoami" };
        Map<String,Integer> traces = new HashMap<String,Integer>();
        Boolean isTesting = Boolean.TRUE;
        int MAX_FREQUENCY = 100000;

        @Override
        public void run() {
            System.out.println("start new thread for workload generating!");
            while(true){
                try {
                    Thread.sleep(100);
                    testCaseGenerating();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        public void testCaseGenerating(){
            try {
                Thread.sleep(20);
//                String cmd = getCMDbyModel();
                String cmd = getCMDRandom();
                System.out.println("execute: "+ cmd);
                executeLine(cmd);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        public  String getCMDRandom(){
            int n = apis.length;
            Random rn = new Random();
            int index = rn.nextInt(n);
            String api = apis[index];
            api = completeAPI(api);x
            return api;
        }

        public String completeAPI(String api){
            Random rn = new Random();
            switch (api){
                case "addWatch":
                    int possible = rn.nextInt(2);
                    if (possible%2==0){
                        api = api + " -m PERSISTENT "+ getRandomPath();
                    }else{
                        api = api + " -m PERSISTENT_RECURSIVE "+ getRandomPath();
                    }
                    break;
                case "addauth scheme auth":
                    return api;
                case "close":
                    return api;
                case "config":
                    possible = rn.nextInt(4);
                    if(possible%4==0) {
                        api += " -c";
                    }else if(possible%4==1) {
                        api += " -w";
                    }else if(possible%4==1) {
                        api += " -s";
                    }else{
                        break;
                    }
                    break;
                case "connect":
                    possible = rn.nextInt(3);
                    if(possible%3==0) {
                        api += " 127.0.0.1:2181";
                    }else if(possible%3==1) {
                        api += " 127.0.0.1:2182";
                    }else{
                        api += " 127.0.0.1:2183";
                    }
                    break;
                case "create":
                    possible = rn.nextInt(4);
                    if(possible%4==0) {
                        api = api + " -s " + getRandomPath() + " "+createRandomData();
                    }else if(possible%4==1) {
                        api += " -e ";
                    }else if(possible%4==2) {
                        api += " -c ";
                    }else{
                        api += " -t ttl ";
                    }
                    break;
                case "delete":
                    possible = rn.nextInt(2);
                    if(possible%2==0){
                        api = api + " -v "+ getRandomVersion() + " "+ getRandomPath();
                    }else{
                        api = api + " "+getRandomPath();
                    }
                    break;
                case "deleteall":
                    possible = rn.nextInt(2);
                    if(possible%2==0){
                        api = api + " "+ getRandomPath() + " -b "+ getRandomSize() ;
                    }else{
                        api = api + " "+getRandomPath();
                    }
                    break;
                case "delquota":
                    possible = rn.nextInt(5);
                    if(possible%5==0) {
                        api = api + " -n " + getRandomPath();
                    }else if(possible%5==1) {
                        api = api + " -b "+ getRandomPath();
                    }else if(possible%5==2) {
                        api = api + " -N "+ getRandomPath();
                    }else if(possible%5==3) {
                        api = api + " -B "+ getRandomPath();
                    }else{
                        api = api + " "+ getRandomPath();
                    }
                    break;
                case "get":
                    possible = rn.nextInt(3);
                    if(possible%3==0){
                        api = api + " -s " + getRandomPath() ;
                    }else if(possible%3==0){
                        api = api + " -w " + getRandomPath();
                    }else{
                        api = api + " " + getRandomPath();
                    }
                    break;
                case "getAcl":
                    possible = rn.nextInt(2);
                    if(possible%2==0){
                        api = api + " -s " + getRandomPath() ;
                    }else{
                        api = api + " " + getRandomPath();
                    }
                    break;
                case "getAllChildrenNumber":
                    api = api + " " + getRandomPath();
                    break;
                case "getEphemerals":
                    api = api + " " + getRandomPath();
                    break;
                case "history":
                    break;
                case "listquota":
                    api = api + " " + getRandomPath();
                    break;
                case "ls":
                    possible = rn.nextInt(4);
                    if(possible%4==0) {
                        api = api + " -s " + getRandomPath();
                    }else if(possible%4==1) {
                        api = api + " -w "+ getRandomPath();
                    }else if(possible%4==2) {
                        api = api + " -R "+ getRandomPath();
                    }else{
                        api = api + " "+ getRandomPath();
                    }
                    break;
                case "printwatches":
                    possible = rn.nextInt(2);
                    if(possible%2==0) {
                        api += " on " ;
                    }else {
                        api += " off ";
                    }
                    break;
                case "reconfig":
                    possible = rn.nextInt(3);
                    if(possible%3==0) {
                        api += " -s " ;
                    }else if(possible%3==1) {
                        api = api + " -v  "+ getRandomVersion();
                    }else{
                        api = api + " -file " + getRandomPath();
                    }
                    break;
                case "redo":
                    api = api + " " + rn.nextInt(500)%500;
                    break;
                case "removewatches":
                    api = api + " " + getRandomPath();
                    possible = rn.nextInt(3);
                    if(possible%3==0) {
                        api += " -c " ;
                    }else if(possible%3==1) {
                        api = api + " -d  "+ getRandomVersion();
                    }else{
                        api = api + " -a " + getRandomPath();
                    }
                    if(rn.nextInt(3)%3==0){
                        api += " -l";
                    }
                    break;
                case "set":
                    possible = rn.nextInt(3);
                    if(possible%3==0){
                        api = api + " -s " + getRandomPath() +" " + getRandomData();
                    }else if(possible%3==0){
                        api = api + " -v " + getRandomVersion()+ " " + getRandomPath()+" " + getRandomData();
                    }else{
                        api = api + " " + getRandomPath()+" " + getRandomData();
                    }
                    break;
                case "setAcl":
                    possible = rn.nextInt(3);
                    if(possible%3==0){
                        api = api + " -s " + getRandomPath() + " acl" ;
                    }else if(possible%3==1){
                        api = api + " -R " + getRandomPath() + " acl";
                    }else{
                        api = api + " -v " + getRandomVersion() + " " + getRandomPath() + " acl";
                    }
                    break;
                case "setquota":
                    possible = rn.nextInt(5);
                    if(possible%5==0) {
                        api = api + " -n " + getRandomData() +" "+ getRandomPath();
                    }else if(possible%5==1) {
                        api = api + " -b "+ getRandomData() +" "+ getRandomPath();
                    }else if(possible%5==2) {
                        api = api + " -N "+ getRandomData() +" "+ getRandomPath();
                    }else if(possible%5==3) {
                        api = api + " -B "+ getRandomData() +" "+ getRandomPath();
                    }else{
                        api = api + " "+ getRandomData() +" "+ getRandomPath();
                    }
                    break;
                case "quit":
                    return "";
                case "stat":
                    possible = rn.nextInt(2);
                    if(possible%2==0) {
                        api = api + " -w " + getRandomPath();
                    }else{
                        api = api + " " + getRandomPath();
                    }
                    break;
                case "sync":
                    api = api + " " + getRandomPath();
                    break;
                default:
                    break;
            }
            return api;
        }

        public String createRandomData(){
//            String className = Thread.currentThread().getStackTrace()[2].getClassName();
//            String methodName = Thread.currentThread().getStackTrace()[2].getMethodName();
//            String key = className+"-"+methodName;
//            System.out.println("Instrumentation Success! key is: "+key);
//            if(isTesting && traces.get(key)<=MAX_FREQUENCY){
//                traces.put(key,traces.get(key)+1) ;
//            }

//            System.out.println(className+" "+methodName);
            return getAlphanumericString();
        }

        public String getRandomPath(){
//            String className = Thread.currentThread().getStackTrace()[2].getClassName();
//            String methodName = Thread.currentThread().getStackTrace()[2].getMethodName();
//            String key = className+"-"+methodName;
//            System.out.println("Instrumentation Success! key is: "+key);
//            if(isTesting && traces.get(key)<=MAX_FREQUENCY){
//                traces.put(key,traces.get(key)+1) ;
//            }
//
//            System.out.println(className+" "+methodName);
            return "/"+getAlphanumericString();
        }

        public String getRandomData(){
            int leftLimit = 97; // letter 'a'
            int rightLimit = 122; // letter 'z'
            int targetStringLength = 10;
            Random random = new Random();

            String generatedString = random.ints(leftLimit, rightLimit + 1)
                    .limit(targetStringLength)
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                    .toString();
            return generatedString;
        }

        public String getAlphanumericString (){
            int leftLimit = 48; // numeral '0'
            int rightLimit = 122; // letter 'z'
            int targetStringLength = 10;
            Random random = new Random();

            String generatedString = random.ints(leftLimit, rightLimit + 1)
                    .filter(i -> (i <= 57 || i >= 65) && (i <= 90 || i >= 97))
                    .limit(targetStringLength)
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                    .toString();

//            System.out.println(generatedString);
            return generatedString;
        }

        public int getRandomVersion(){
            Random rn = new Random();
            return rn.nextInt(1001);
        }

        public int getRandomSize(){
            Random rn = new Random();
            return rn.nextInt(1001);
        }

    }
}

