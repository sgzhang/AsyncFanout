import org.apache.commons.cli.*;

/**
 * entry of customised client
 */
public class Main {
    public static void main(String[] args) {
        // apache command cli
        Options options = new Options();
        options.addOption("s", true, "Set the type of driver\n\tb -- BIO\n\ta -- AIO\n\tn -- Netty or NIO");
        options.addOption("t", true, "Set the number of client threads");
        options.addOption("c", true, "Set the connection pool size of each downstream tier server");
        options.addOption("p", true, "Set the time period of experiments [s]");
        options.addOption("h", false, "Lists of help");

        // parser
        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine command = parser.parse(options, args);
            HelpFormatter formatter = new HelpFormatter();

            int threadNum = 0, experimentPeriod = 0, connSize = 0;
            if (command.hasOption("t") && command.hasOption("c") && command.hasOption("p") &&
                    command.hasOption("s")) {
                threadNum = Integer.parseInt(command.getOptionValue("t"));
                experimentPeriod = Integer.parseInt(command.getOptionValue("p"));
                connSize = Integer.parseInt(command.getOptionValue("c"));
            } else {
                formatter.printHelp("Main", options);
                System.exit(1);
            }

            if (command.hasOption("s")) {
                switch (command.getOptionValue("s")) {
                    case "b":
                    case "B":
                        ConnectViaSync connectViaSync = new ConnectViaSync();
                        connectViaSync.init(connSize);
                        connectViaSync.setThreadPool(threadNum);
                        connectViaSync.setWorkerThreadPool(threadNum * 8);

                        connectViaSync.startThreadPool();
                        try {
                            Thread.sleep(experimentPeriod * 1000);
                            connectViaSync.endThreadPool();
                            System.exit(0);
                        } catch (InterruptedException i) {
                            i.printStackTrace();
                        }
                    case "a":
                    case "A":
                        ConnectViaAio connectViaAio = new ConnectViaAio();
                        connectViaAio.init(connSize, threadNum);
                        connectViaAio.setThreadPool(threadNum);

                        connectViaAio.startThreadPool();
                        try {
                            Thread.sleep(experimentPeriod * 1000);
                            connectViaAio.endThreadPool();
                            System.exit(0);
                        } catch (InterruptedException i) {
                            i.printStackTrace();
                        }
                        break;
                    case "n":
                    case "N":
                        ConnectViaNetty connectViaNetty = new ConnectViaNetty();
                        connectViaNetty.init(connSize, threadNum);
                        connectViaNetty.setThreadPool(threadNum);

                        connectViaNetty.startThreadPool();
                        try {
                            Thread.sleep(experimentPeriod * 1000);
                            connectViaNetty.endThreadPool();
                            System.exit(0);
                        } catch (InterruptedException i) {
                            i.printStackTrace();
                        }
                        break;
                    default:
                        formatter.printHelp("help", options);
                        break;
                }
            } else {
                formatter.printHelp("help", options);
                System.exit(1);
            }
        } catch (ParseException e) {
            e.printStackTrace();
        }
    }
}
