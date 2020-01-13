package reator;

import app.Config;
import com.mongodb.ReadPreference;
import com.mongodb.ServerAddress;
import com.mongodb.async.client.MongoClient;
import com.mongodb.async.client.MongoClientSettings;
import com.mongodb.async.client.MongoClients;
import com.mongodb.async.client.MongoCollection;
import com.mongodb.connection.*;
import com.mongodb.connection.netty.NettyStreamFactoryFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static java.util.Arrays.asList;

/**
 * Created by sgzhang on Feb 05, 2018.
 * E-mail szhan45@lsu.edu.
 */
public class NettyServer {
    private final String host;
    private final int port;
    private final int upStreamWorkersNum;
    private final int downStreamWorkerNum;
    private final Config config = new Config();
    private static MongoCollection[] collections = new MongoCollection[5];
    private final Logger myLogger;
    private NioEventLoopGroup boss;
    private final NioEventLoopGroup worker;

    private int fanoutFactor = 1;
    private int rangeQuery = 1;
    ServerBootstrap serverBootstrap = new ServerBootstrap();

    public NettyServer(final String host, final int port, final int upStreamWorkersNum, final int downStreamWorkerNum,
                       final boolean isAIO) {
        System.getProperty("log4j.configurationFile", "log4j2.xml");
        // custom log level for sub-queries
        final Level SERVER1 = Level.forName("SERVER1", 410);
        final Level SERVER2 = Level.forName("SERVER2", 420);
        final Level SERVER3 = Level.forName("SERVER3", 430);
        final Level SERVER4 = Level.forName("SERVER4", 440);
        final Level SERVER5 = Level.forName("SERVER5", 450);
        this.myLogger = LogManager.getLogger(NettyServer.class);

        this.host = host;
        this.port = port;
        this.upStreamWorkersNum = upStreamWorkersNum;
        this.downStreamWorkerNum = downStreamWorkerNum;
        this.boss = new NioEventLoopGroup(1);
        this.worker = new NioEventLoopGroup(this.downStreamWorkerNum);
//        this.worker = new EpollEventLoopGroup(this.downStreamWorkerNum);

        openCollection(isAIO);
        serverBootstrap.group(boss)  // netty-server
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.SO_RCVBUF, 10240*8)
                .childOption(ChannelOption.SO_SNDBUF, 10240*8)
                .childOption(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
//                .childOption(ChannelOption.AUTO_READ, false)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) {
                        NettyServerHandler handler = new NettyServerHandler(collections, myLogger);
                        handler.setFanoutFactor(fanoutFactor);
                        handler.setRangeQuery(rangeQuery);
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(handler);
                    }
                });
    }

    public void setRangeQuery(int rangeQuery) {
        this.rangeQuery = rangeQuery;
    }

    public void setFanoutFactor(int fanoutFactor) {
        this.fanoutFactor = fanoutFactor;
    }

    public void start() {
        try {
            ChannelFuture channelFuture = serverBootstrap.bind(this.port).sync();
            channelFuture.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }
    }

    private void openCollection(final boolean isAIO) {
        for (String host : Config.HOSTS) {
            ClusterSettings clusterSettings = ClusterSettings.builder()
                    .mode(ClusterConnectionMode.SINGLE)
//                    .maxWaitQueueSize(1)
                    .hosts(asList(new ServerAddress(host))).build();
            ConnectionPoolSettings connectionPoolSettings = ConnectionPoolSettings.builder()
                    .maxWaitTime(5, TimeUnit.SECONDS)
//                    .maxWaitQueueSize(1)
                    .maxSize(upStreamWorkersNum).build();
            MongoClientSettings mongoClientSettings = null;
            if (isAIO) {
                try {
                    mongoClientSettings = MongoClientSettings.builder()
                            .clusterSettings(clusterSettings)
                            .readPreference(ReadPreference.primary())
                            .socketSettings(SocketSettings.builder().sendBufferSize(10240*8)
                                    .receiveBufferSize(10240*8).build())
                            .connectionPoolSettings(connectionPoolSettings).build();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                mongoClientSettings = MongoClientSettings.builder()
                        .streamFactoryFactory(NettyStreamFactoryFactory.builder()
                                .eventLoopGroup(boss).build())
                        .clusterSettings(clusterSettings)
                        .readPreference(ReadPreference.primary())
                        .connectionPoolSettings(connectionPoolSettings).build();
            }

            MongoClient m = MongoClients.create(mongoClientSettings);
            collections[Arrays.asList(Config.HOSTS).indexOf(host)] = m.getDatabase("reddit").
                    getCollection("comments");
        }
    }

    public static void main(String[] args) {
        new NettyServer("localhost", 9000, 10, 1, true);
    }

}
