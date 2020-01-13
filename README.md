# AsyncFanout
Application server implementations
Three types of Netty-based application servers equipped with three different types of MongoDB drivers：_AIOBackend_, _NettyBackend_, and _DoubleFaceNetty_.

We also have a fanout-query aware priority-based scheudling algorithm, which intends to mitigate the tail latency in fanout query scenario.
