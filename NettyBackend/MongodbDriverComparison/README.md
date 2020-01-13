## Mongodb Driver Comparision
Experiments of architecturally different MongoDB driver implementation, including thread-based, 
asynchronous non-blocking I/O (AIO), and synchronous non-blocking I/O (NIO). Fan-out is a 
popular and important feature for scaling out database tier, in order to efficiently take
advantage of such feature, appropriate implementation of middleware (between app servers and
database servers) should be able to multiplex connections via less overheads.

#### 1. Experimental Setup
- MongoDB servers and YCSB workload data  
192.168.10.151 -- [&emsp;&emsp;&ensp;&nbsp;0, 100000]  
192.168.10.146 -- [100000, 200000]  
192.168.10.144 -- [200000, 300000]  
192.168.10.149 -- [300000, 400000]  
192.168.10.152 -- [400000, 500000]  

- Client  
192.168.10.140

#### 2. Versions
- 2.0 the first reactor thread does NOT block

