package reator;

import app.Config;
import com.mongodb.BasicDBObject;
import com.mongodb.Block;
import com.mongodb.async.Priority;
import com.mongodb.async.SingleResultCallback;
import com.mongodb.async.SingleResultCallbackN;
import com.mongodb.async.client.MongoCollection;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.nio.AbstractNioChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.AsciiString;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static com.mongodb.client.model.Filters.*;

/**
 * Created by sgzhang on Feb 05, 2018.
 * E-mail szhan45@lsu.edu.
 */
public class NettyServerHandler extends ChannelInboundHandlerAdapter {
    final private MongoCollection<Document>[] collections;
    final private Random random = new Random();
    final private Logger myLogger;

    private static final AsciiString CONTENT_TYPE = AsciiString.cached("Content-Type");
    private static final AsciiString CONTENT_LENGTH = AsciiString.cached("Content-Length");
    private static final AsciiString CONNECTION = AsciiString.cached("Connection");
    private static final AsciiString KEEP_ALIVE = AsciiString.cached("keep-alive");
    private static final AsciiString KEEP_ALIVE_HEADER = AsciiString.cached("Keep-Alive");
    private static final AsciiString KEEP_ALIVE_TIMEOUT = AsciiString.cached("timeout=30, max=200");


    private int fanoutFactor = 1;
    private int rangeQuery = 1;
    private Long st = 0L, et = 0L;

    NettyServerHandler (final MongoCollection<Document>[] collections, final Logger myLogger) {
        this.collections = collections;
        this.myLogger = myLogger;
    }

    public void setRangeQuery(final int rangeQuery) {
        this.rangeQuery = rangeQuery;
    }

    public void setFanoutFactor(final int fanoutFactor) {
        this.fanoutFactor = fanoutFactor;
    }

    Block<Document> printDocumentBlock = new Block<Document>() {
        @Override
        public void apply(final Document document) {
        }
    };
    SingleResultCallback<Void> callbackWhenFinished = new SingleResultCallback<Void>() {
        @Override
        public void onResult(final Void result, final Throwable t) {
            System.out.println("Operation Finished!");
        }
    };


    /**
     * register client channel as higher priority
     * @param ctx
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest) {
            HttpRequest httpRequest = (HttpRequest) msg;
            final boolean keepAlive = HttpUtil.isKeepAlive(httpRequest);
//            System.out.println("is keepalive->" + keepAlive);
            final String reqTraceID = httpRequest.uri().contains("=") ? httpRequest.uri().split("=")[1]
                    : "0";
//            System.out.println(httpRequest.uri());
            final int fanout = random.nextInt(100) < 50 ? 3 : 5;  // random request
            final AtomicInteger fanoutCounter = new AtomicInteger(0);

            BasicDBObject regexQuery = new BasicDBObject();
            st = ((AbstractNioChannel) ctx.channel()).getSelectTimestamp();  // get select time
            StringBuilder response = new StringBuilder();

            if (fanout > 3) {
                final CountDownLatch counter = new CountDownLatch(fanout);
                for (int i = 0; i < fanout; i++) {
                    final int index = i % 5;
                    final Long _st = System.currentTimeMillis();

                    int randNum = random.nextInt(10000) * 5 + index;  // amazon kindle

                    List<Document> docs = new ArrayList<>();
                    final int hashcode;
                    if (Integer.parseInt(reqTraceID) > 0 && Integer.parseInt(reqTraceID) % 2 != 0) {
                        hashcode = Integer.parseInt(reqTraceID) + 1;
                    } else {
                        hashcode = Integer.parseInt(reqTraceID);
                    }

                    if (index < 5) {
                        collections[index].find(
                                eq("id", randNum)   // kindle amazon
                            ).first(new SingleResultCallbackN<Document>() {
                        Priority p = new Priority(hashcode, fanout);

                        @Override
                        public void setPriority(Priority p) {
                            this.p.hashedSource = p.hashedSource;
                            this.p.fanout = p.fanout;
                        }

                        @Override
                        public Priority getPriority() {
                            return this.p;
                        }

                        @Override
                        public void onResult(Document result, Throwable t) {
                        Util.doSelectionSort(1000);
                            if (result != null /* && !result.isEmpty() */) {
                                response.append("Host ")
                                        .append(Config.HOSTS[index])
                                        .append(" ->\r\n")
                                        .append(result.toJson())
                                        .append("\r\n\r\n");
                                counter.countDown();
                            }
                            if (counter.getCount() == 0) {
                                FullHttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                                httpResponse.content().writeBytes(response.toString().getBytes());
                                httpResponse.headers().set(CONTENT_TYPE, "text/plain");
                                httpResponse.headers().setInt(CONTENT_LENGTH, httpResponse.content().readableBytes());
                                if (!keepAlive) {
                                    ctx.write(httpResponse).addListener(ChannelFutureListener.CLOSE);
                                    ctx.flush();
                                } else {
                                    httpResponse.headers().set(KEEP_ALIVE_HEADER, KEEP_ALIVE_TIMEOUT);
                                    httpResponse.headers().set(CONNECTION, KEEP_ALIVE);
                                    ctx.writeAndFlush(httpResponse);
                                }
                                et = System.currentTimeMillis();
                                if (st > 0 && (et - st) > -1)
                                    myLogger.log(Level.INFO, st + "," + et + ",StoriesOfTheDay," + (et - st) + ",long," + reqTraceID);
                            }
                        }
                    });
                }
            }
            } else {
                final CountDownLatch counter = new CountDownLatch(fanout);
                for (int i = 0; i < fanout; i++) {
                    final int index = i % 5;
                    int randNum = random.nextInt(10000) * 5 + index;  // amazon kindle
                    final int hashcode;
                    if (Integer.parseInt(reqTraceID) > 0 && Integer.parseInt(reqTraceID) % 2 != 0) {
                        hashcode = Integer.parseInt(reqTraceID);
                    } else {
                        hashcode = Integer.parseInt(reqTraceID) + 1;
                    }
                    collections[index].find(
                        eq("id", randNum)   // kindle amazon
                       ).first(new SingleResultCallbackN<Document>() {
                        Priority p = new Priority(hashcode, fanout);

                        @Override
                        public void setPriority(Priority p) {
                            this.p.hashedSource = p.hashedSource;
                            this.p.fanout = p.fanout;
                        }

                        @Override
                        public Priority getPriority() {
                            return this.p;
                        }

                        @Override
                        public void onResult(Document result, Throwable t) {
                            Util.doSelectionSort(1000);
                            if (result != null /* && !result.isEmpty() */) {
                                response.append("Host ")
                                        .append(Config.HOSTS[index])
                                        .append(" ->\r\n")
                                        .append(result.toJson())
                                        .append("\r\n\r\n");
                                counter.countDown();
                            }
                            if (counter.getCount() == 0) {
                                FullHttpResponse httpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                                httpResponse.content().writeBytes(response.toString().getBytes());
                                httpResponse.headers().set(CONTENT_TYPE, "text/plain");
                                httpResponse.headers().setInt(CONTENT_LENGTH, httpResponse.content().readableBytes());
                                if (!keepAlive) {
                                    ctx.write(httpResponse).addListener(ChannelFutureListener.CLOSE);
                                    ctx.flush();
                                } else {
                                    httpResponse.headers().set(KEEP_ALIVE_HEADER, KEEP_ALIVE_TIMEOUT);
                                    httpResponse.headers().set(CONNECTION, KEEP_ALIVE);
                                    ctx.writeAndFlush(httpResponse);
                                }
                                et = System.currentTimeMillis();
                                if (st > 0 && (et - st) > -1)
                                    myLogger.log(Level.INFO, st + "," + et + ",StoriesOfTheDay," + (et - st) + ",short," + reqTraceID);
                            }
                        }
                    });
                }
            }
            myLogger.debug(((AbstractNioChannel) ctx.channel()).getPriority().hashedSource + "," + reqTraceID);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
//        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable t) {
        t.printStackTrace();
//        ctx.close();
    }
}
