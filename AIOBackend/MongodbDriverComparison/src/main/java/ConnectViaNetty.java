import com.mongodb.ReadPreference;
import com.mongodb.ServerAddress;
import com.mongodb.async.client.MongoClient;
import com.mongodb.async.client.MongoClientSettings;
import com.mongodb.async.client.MongoClients;
import com.mongodb.connection.ClusterConnectionMode;
import com.mongodb.connection.ClusterSettings;
import com.mongodb.connection.ConnectionPoolSettings;
import com.mongodb.connection.netty.NettyStreamFactoryFactory;
import io.netty.channel.nio.NioEventLoopGroup;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;

import static java.util.Arrays.asList;

/**
 * Created by sgzhang on Nov 20, 2017.
 * E-mail szhan45@lsu.edu.
 */
public class ConnectViaNetty extends ConnectViaAio {
    @Override
    public void init(int connSize, int queueSize) {
        System.getProperty("log4j.configurationFile", "log4j2.xml");
        // custom log level for sub-queries
        final Level SERVER1 = Level.forName("SERVER1", 410);
        final Level SERVER2 = Level.forName("SERVER2", 420);
        final Level SERVER3 = Level.forName("SERVER3", 430);
        final Level SERVER4 = Level.forName("SERVER4", 440);
        final Level SERVER5 = Level.forName("SERVER5", 450);
        myLogger = LogManager.getLogger(ConnectViaNetty.class);

        for (int i = 0; i < hosts.length; i++) {
            ClusterSettings clusterSettings = ClusterSettings.builder()
                    .mode(ClusterConnectionMode.SINGLE)
                    .maxWaitQueueSize(queueSize)
                    .hosts(asList(new ServerAddress(hosts[i]))).build();
            ConnectionPoolSettings connectionPoolSettings = ConnectionPoolSettings.builder()
                    .maxSize(connSize).build();
            MongoClientSettings mongoClientSettings = MongoClientSettings.builder()
                    .streamFactoryFactory(NettyStreamFactoryFactory.builder().build())
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
}
