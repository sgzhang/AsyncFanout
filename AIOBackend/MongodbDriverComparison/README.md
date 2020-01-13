## Mongodb Driver Comparision
AIOBackend application server

#### 1. Experimental Setup
- MongoDB servers and YCSB workload data  
192.168.10.151 -- [&emsp;&emsp;&ensp;&nbsp;0, 100000]  
192.168.10.146 -- [100000, 200000]  
192.168.10.144 -- [200000, 300000]  
192.168.10.149 -- [300000, 400000]  
192.168.10.152 -- [400000, 500000]  

- Client  
192.168.10.140

#### 2. start up command
- java MongodbDriverComparison-doublefacenetty-snapshot-v1-all.jar -s d -t 10 -c 10 -p 10


>where "s", "Set the type of driver b -- BIO, d -- AIO, c -- Netty";
      "t", "Set the number of client threads";
      "c", "Set the connection pool size of each downstream tier server";
      "p", "Set the time period of experiments [s]";
      "h", "Lists of help";

