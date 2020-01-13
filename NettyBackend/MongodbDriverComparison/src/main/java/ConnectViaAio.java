import app.Config;
import com.mongodb.ReadPreference;
import com.mongodb.ServerAddress;
import com.mongodb.async.SingleResultCallback;
import com.mongodb.async.client.*;
import com.mongodb.connection.ClusterConnectionMode;
import com.mongodb.connection.ClusterSettings;
import com.mongodb.connection.ConnectionPoolSettings;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;

import java.util.*;
import java.util.concurrent.*;

import static com.mongodb.client.model.Filters.eq;
import static java.util.Arrays.asList;

/**
 * Created by sgzhang on Nov 17, 2017.
 * E-mail szhan45@lsu.edu.
 */
public class ConnectViaAio {
    protected static Logger myLogger = null;
//    final public String[] hosts = {"192.168.10.151:27017",
//            "192.168.10.146:27017",
//            "192.168.10.144:27017",
//            "192.168.10.149:27017",
//            "192.168.10.152:27017",};
    final public String[] hosts = Config.HOSTS;
    public List<Thread> threadPool = null;  // thread_pool for client
    public static HashMap<MongoClient, Integer> clients = new HashMap<>();
    public MongoCollection<Document> collection1, collection2, collection3, collection4, collection5;
    public final CountDownLatch countDownLatch = new CountDownLatch(1);  // control all threads start at the same time

    /**
     * initialize connections
     * @param connSize # of connection pool size
     * @param queueSize # of waiting queries
     */
    public void init(int connSize, int queueSize) {
        System.getProperty("log4j.configurationFile", "log4j2.xml");
        // custom log level for sub-queries
        final Level SERVER1 = Level.forName("SERVER1", 410);
        final Level SERVER2 = Level.forName("SERVER2", 420);
        final Level SERVER3 = Level.forName("SERVER3", 430);
        final Level SERVER4 = Level.forName("SERVER4", 440);
        final Level SERVER5 = Level.forName("SERVER5", 450);
        myLogger = LogManager.getLogger(ConnectViaAio.class);

        for (int i = 0; i < hosts.length; i++) {
            ClusterSettings clusterSettings = ClusterSettings.builder()
                    .mode(ClusterConnectionMode.SINGLE)
                    .maxWaitQueueSize(queueSize)
                    .hosts(asList(new ServerAddress(hosts[i]))).build();
            ConnectionPoolSettings connectionPoolSettings = ConnectionPoolSettings.builder()
                    .maxSize(connSize).build();
            MongoClientSettings mongoClientSettings = MongoClientSettings.builder()
                    .clusterSettings(clusterSettings)
                    .readPreference(ReadPreference.primary())
                    .connectionPoolSettings(connectionPoolSettings).build();

            MongoClient m = MongoClients.create(mongoClientSettings);
            clients.put(m, i*100000);
            switch (i) {
                case 0: collection1 = m.getDatabase("ycsb").getCollection("usertable");break;
                case 1: collection2 = m.getDatabase("ycsb").getCollection("usertable");break;
                case 2: collection3 = m.getDatabase("ycsb").getCollection("usertable");break;
                case 3: collection4 = m.getDatabase("ycsb").getCollection("usertable");break;
                case 4: collection5 = m.getDatabase("ycsb").getCollection("usertable");break;
                default: break;
            }
        }
    }

    /**
     * set number of thread pool
     * @param threadsNum # of client
     */
    public void setThreadPool(final int threadsNum) {
        try {
            if (this.threadPool == null) {
                this.threadPool = new ArrayList<>(threadsNum);
                for (int i = threadsNum; i > 0; i--) {
                    this.threadPool.add(new Thread(new ConnectViaAio.Task(), "client-"+i));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            myLogger.debug(e.getStackTrace());
        }
    }

    /**
     * start service of thread pool
     */
    public void startThreadPool() {
        try {
            if (this.threadPool != null) {
                for (Thread t : this.threadPool)
                    t.start();
            }
        } catch (Exception e) {
            e.printStackTrace();
            myLogger.debug(e.getStackTrace());
        } finally {
            countDownLatch.countDown();
        }
    }

    /**
     * close the thread pool
     */
    public void endThreadPool() {
        try {
            for (Map.Entry<MongoClient, Integer> entry : clients.entrySet()) {
                entry.getKey().close();
            }
            if (this.threadPool != null) {
                for (Thread t : this.threadPool)
                    t.interrupt();
            }
        } catch (Exception e) {
            e.printStackTrace();
            myLogger.debug(e.getStackTrace());
        }
    }

    /**
     * runnable task for clients
     */
    class Task implements Runnable {
        long start, end;
        final StringBuilder timestamp = new StringBuilder();
        MongoClient mongoClient = null;
        MongoDatabase mongoDatabase = null;
        MongoCollection collection = null;
        Document document = null;
        List<CompletableFuture<Document>> results = new ArrayList<>();
        final Random random = new Random();

        @Override
        public void run() {
            try {
                countDownLatch.await();

                while (!Thread.currentThread().isInterrupted()) {
                    start = System.currentTimeMillis();
                    timestamp.append(start);

                    // fan-out phase
                    // still, for some reason, I cannot dynamically change the variables within runnable class
                    CompletableFuture<Document> r1 = new CompletableFuture<>();
                    Long start1 = System.currentTimeMillis();
                    collection1.find(eq("_id",
                            "user"+(random.nextInt(100000)+0)
                    )).first(new SingleResultCallback<Document>() {
                        @Override
                        public void onResult(Document result, Throwable t) {
                            r1.complete(result);
                            Long end1 = System.currentTimeMillis();
                            myLogger.log(Level.getLevel("SERVER1"),
                                    start1+","+end1+",StoriesOfTheDay,"+(end1-start1));
                        }
                    });
                    results.add(r1);
//                    CompletableFuture<Document> r2 = new CompletableFuture<>();
//                    Long start2 = System.currentTimeMillis();
//                    collection2.find(eq("_id",
//                            "user"+(random.nextInt(100000)+100000)
//                    )).first(new SingleResultCallback<Document>() {
//                        @Override
//                        public void onResult(Document result, Throwable t) {
//                            r2.complete(result);
//                            Long end2 = System.currentTimeMillis();
//                            myLogger.log(Level.getLevel("SERVER2"),
//                                    start2+","+end2+",StoriesOfTheDay,"+(end2-start2));
//                        }
//                    });
//                    results.add(r2);
//                    CompletableFuture<Document> r3 = new CompletableFuture<>();
//                    Long start3 = System.currentTimeMillis();
//                    collection3.find(eq("_id",
//                            "user"+(random.nextInt(100000)+200000)
//                    )).first(new SingleResultCallback<Document>() {
//                        @Override
//                        public void onResult(Document result, Throwable t) {
//                            r3.complete(result);
//                            Long end3 = System.currentTimeMillis();
//                            myLogger.log(Level.getLevel("SERVER3"),
//                                    start3+","+end3+",StoriesOfTheDay,"+(end3-start3));
//                        }
//                    });
//                    results.add(r3);
//                    CompletableFuture<Document> r4 = new CompletableFuture<>();
//                    Long start4 = System.currentTimeMillis();
//                    collection4.find(eq("_id",
//                            "user"+(random.nextInt(100000)+300000)
//                    )).first(new SingleResultCallback<Document>() {
//                        @Override
//                        public void onResult(Document result, Throwable t) {
//                            r4.complete(result);
//                            Long end4 = System.currentTimeMillis();
//                            myLogger.log(Level.getLevel("SERVER4"),
//                                    start4+","+end4+",StoriesOfTheDay,"+(end4-start4));
//                        }
//                    });
//                    results.add(r4);
//                    CompletableFuture<Document> r5 = new CompletableFuture<>();
//                    Long start5 = System.currentTimeMillis();
//                    collection5.find(eq("_id",
//                            "user"+(random.nextInt(100000)+400000)
//                    )).first(new SingleResultCallback<Document>() {
//                        @Override
//                        public void onResult(Document result, Throwable t) {
//                            r5.complete(result);
//                            Long end5 = System.currentTimeMillis();
//                            myLogger.log(Level.getLevel("SERVER5"),
//                                    start5+","+end5+",StoriesOfTheDay,"+(end5-start5));
//                        }
//                    });
//                    results.add(r5);
//
                    for (CompletableFuture<Document> c : results) {
                        Document d = c.get(3, TimeUnit.SECONDS);
                        if (!d.containsKey("_id")) {
                            System.err.println("error request");
                        }
                    }

                    end = System.currentTimeMillis();
                    timestamp.append(",").append(end).append(",StoriesOfTheDay,").append((end-start));
//                    System.out.println(timestamp);
                    myLogger.log(Level.INFO, timestamp);

                    timestamp.setLength(0);  // clear timestamp
                    results.clear();  // clear results
                }
            } catch (Exception  i) {
                i.printStackTrace();
                myLogger.debug(i.getStackTrace());
            }
        }
    }

    public static void main(String args[]) {
        ConnectViaAio connectViaAio = new ConnectViaAio();
        final int threadNum = 10;
        final int connPerHost = 10;
        final int experimentPeriod = 10;  // by seconds
        connectViaAio.init(10,50);
        connectViaAio.setThreadPool(threadNum);

        connectViaAio.startThreadPool();
        try {
            Thread.sleep(experimentPeriod*1000);
            connectViaAio.endThreadPool();
        } catch (InterruptedException i) {
            i.printStackTrace();
        }

    }
}
