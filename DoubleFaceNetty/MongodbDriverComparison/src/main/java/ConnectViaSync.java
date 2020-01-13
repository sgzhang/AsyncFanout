import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.MongoClientURI;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;

import javax.swing.event.DocumentEvent;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.mongodb.client.model.Filters.eq;

public class ConnectViaSync {
    final private String[] hosts = {"192.168.10.151:27017",
            "192.168.10.146:27017",
            "192.168.10.144:27017",
            "192.168.10.149:27017",
            "192.168.10.152:27017",};
    private static HashMap<MongoClient, Integer> clients = new HashMap<>();  // <mongo_client, range>
    private MongoCollection<Document> collection1, collection2, collection3, collection4, collection5;
    private List<Thread> threadPool = null;  // thread_pool for client
    private ExecutorService workerThreadPool = null;
    private final CountDownLatch countDownLatch = new CountDownLatch(1);  // control all threads start at the same time

    private static Logger myLogger = null;

    /**
     * initialize connections to mongodb server
     * @param concurrency connection pool size
     */
    public void init(int concurrency) {
        System.getProperty("log4j.configurationFile", "log4j2.xml");
        // custom log level for sub-queries
        final Level SERVER1 = Level.forName("SERVER1", 410);
        final Level SERVER2 = Level.forName("SERVER2", 420);
        final Level SERVER3 = Level.forName("SERVER3", 430);
        final Level SERVER4 = Level.forName("SERVER4", 440);
        final Level SERVER5 = Level.forName("SERVER5", 450);
        myLogger = LogManager.getLogger(ConnectViaSync.class);

        MongoClientOptions options = MongoClientOptions.builder()
                .connectionsPerHost(concurrency)
                .threadsAllowedToBlockForConnectionMultiplier(concurrency)
                .build();
        for (int i = 0; i < hosts.length; i++) {
            MongoClient m = new MongoClient(new ServerAddress(hosts[i], 27017), options);
            clients.put(m, i*100000);
            switch (i) {
                case 0: collection1 = m.getDatabase("ycsb").getCollection("usertable");break;
                case 1: collection2 = m.getDatabase("ycsb").getCollection("usertable");break;
                case 2: collection3 = m.getDatabase("ycsb").getCollection("usertable");break;
                case 3: collection4 = m.getDatabase("ycsb").getCollection("usertable");break;
                case 4: collection5 = m.getDatabase("ycsb").getCollection("usertable");break;
                default: break;
            }
            myLogger.log(Level.DEBUG, "initialize connection to -> "+hosts[i]);
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
                    this.threadPool.add(new Thread(new Task(), "client-"+i));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            myLogger.debug(e.getStackTrace());
        }
    }

    /**
     * set worker thread pool for fan-out
     * @param workerNum # of worker
     */
    public void setWorkerThreadPool(final int workerNum) {
        try {
            if (this.workerThreadPool == null) {
                workerThreadPool = Executors.newFixedThreadPool(workerNum);
            }
            myLogger.debug("worker thread pool -> "+workerThreadPool.toString());
        } catch (Exception e) {
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
     * runnable task
     */
    class Task implements Runnable {
        final Random random = new Random();
        final StringBuilder timestamp = new StringBuilder();

        @Override
        public void run() {
            try {
                countDownLatch.await();
                while (!Thread.currentThread().isInterrupted()) {
                    Long start,end;
                    start = System.currentTimeMillis();
                    CountDownLatch latch = new CountDownLatch(5);  // fan-out lock
//                    int counter = 5;
//                    Object lock = new Object();

                    timestamp.append(start);

                    // fan-out phase
                    // for some reason, I cannot dynamic change the collection/mongoclient in Task class
                    workerThreadPool.submit(new Runnable() {
                        @Override
                        public void run() {
                            Document d = null;
                            Long start = System.currentTimeMillis();
                            d = collection1.find(eq("_id",
                                    "user" + (random.nextInt(100000) + 0)
                            )).first();
                            Long end = System.currentTimeMillis();
                            myLogger.log(Level.getLevel("SERVER1"),
                                    start+","+end+",StoriesOfTheDay,"+(end-start));
                            latch.countDown();
                        }
                    });
                    workerThreadPool.submit(new Runnable() {
                        @Override
                        public void run() {
                            Document d = null;
                            Long start = System.currentTimeMillis();
                            d = collection2.find(eq("_id",
                                    "user" + (random.nextInt(100000) + 100000)
                            )).first();
                            Long end = System.currentTimeMillis();
                            myLogger.log(Level.getLevel("SERVER2"),
                                    start+","+end+",StoriesOfTheDay,"+(end-start));
                            latch.countDown();
                        }
                    });
                    workerThreadPool.submit(new Runnable() {
                        @Override
                        public void run() {
                            Document d = null;
                            Long start = System.currentTimeMillis();
                            d = collection3.find(eq("_id",
                                    "user" + (random.nextInt(100000) + 200000)
                            )).first();
                            Long end = System.currentTimeMillis();
                            myLogger.log(Level.getLevel("SERVER3"),
                                    start+","+end+",StoriesOfTheDay,"+(end-start));
                            latch.countDown();
                        }
                    });
                    workerThreadPool.submit(new Runnable() {
                        @Override
                        public void run() {
                            Document d = null;
                            Long start = System.currentTimeMillis();
                            d = collection4.find(eq("_id",
                                    "user" + (random.nextInt(100000) + 300000)
                            )).first();
                            Long end = System.currentTimeMillis();
                            myLogger.log(Level.getLevel("SERVER4"),
                                    start+","+end+",StoriesOfTheDay,"+(end-start));
                            latch.countDown();
                        }
                    });
                    workerThreadPool.submit(new Runnable() {
                        @Override
                        public void run() {
                            Document d = null;
                            Long start = System.currentTimeMillis();
                            d = collection5.find(eq("_id",
                                    "user" + (random.nextInt(100000) + 400000)
                            )).first();
                            Long end = System.currentTimeMillis();
                            myLogger.log(Level.getLevel("SERVER5"),
                                    start+","+end+",StoriesOfTheDay,"+(end-start));
                            latch.countDown();
                        }});

//                    reator.Util.doSelectionSort(500);  // time consumer [insertion sort for 100 num]

                    latch.await();

                    end = System.currentTimeMillis();

                    timestamp.append(",").append(end).append(",StoriesOfTheDay,").append((end-start));
//                    System.out.println(timestamp);
                    myLogger.log(Level.INFO, timestamp);
                    timestamp.setLength(0);
                }
            } catch (Exception i) {
                i.printStackTrace();
                myLogger.debug(Arrays.toString(i.getStackTrace()));
            }
        }
    }

    public static void main(String args[]) {
        ConnectViaSync connectViaSync = new ConnectViaSync();
        final int threadNum = 100;
        final int connPerHost = 100;
        final int experimentPeriod = 10000;  // by seconds
        connectViaSync.init(100);
        connectViaSync.setThreadPool(threadNum);
        connectViaSync.setWorkerThreadPool(500);

        connectViaSync.startThreadPool();
        try {
            Thread.sleep(experimentPeriod*1000);
            connectViaSync.endThreadPool();
        } catch (InterruptedException i) {
            i.printStackTrace();
        }

    }
}
