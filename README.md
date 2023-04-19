# Chronos: An Automatical Testing Framework for Finding Timeout Bugs in Distributed Systems by Self-Adaptive Delay Model.

Delays are inevitable in complex distributed environments. And timeout mechanism is commonly used as a failover mechanism in distributed systems. However, incorrect timeout handling or implementation vulnerabilities in timeout mechanisms can lead to system hang-ups or crashes. Such timeout bugs are crucial and pose a significant threat to the availability and security of distributed systems.

In this work, we introduce Chronos, a non-intrusive testing framework for automatically detecting timeout bugs in distributed systems with the self-adaptive delay model. First, we propose general runtime delay libraries that are dynamically injecting fine-grained delays in distributed systems under test (DSUT) in real time. Then, Chronos utilizes transient delays to eliminate the time overhead caused by natural delays and accelerate the test process. To effectively trigger transient delays and constantly explore deeper logic paths, Chronos harnesses the self-adaptive delay model which dynamically updates delay execution probability. We implemented and evaluated Chronos on four widely used distributed systems, including HDFS, ZooKeeper, MySQLCluster, and Go-Ethereum. Compared with the state-of-the-art tools ChaosBlade and FIFUZZ, Chronos achieves 21.52%, and 17.95% more branch coverage, respectively. Furthermore, Chronos has detected 27 serious previously unknown vulnerabilities, all of which have been confirmed and repaired by the corresponding maintainers.


## Directory Structure

Directory libs includes runtime delay libraries.

Directory includes workloader for HDFS, ZooKeeper, MySQL-Cluster, Geth


# Quickstart

## Chronos for HDFS
1. Setup HDFS environment, can be found in https://hadoop.apache.org/docs/r1.2.1/hdfs_user_guide.html.
2. Replace the runtime libraries with our delayed libraries in your environment.
3. Setup a test network
4. Start HDFS workloader 


## Chronos for ZooKeeper
1. Setup ZooKeeper environment, can be found in https://zookeeper.apache.org/documentation.html.
2. Replace the runtime libraries with our delayed libraries in your environment.
3. Setup a test network
4. Start ZooKeeper workloader 

## Chronos for Geth
1. Setup Go-Etheruem environment, can be found in https://geth.ethereum.org/docs.
2. Replace the runtime libraries with our delayed libraries in your environment.
3. Setup a test network
4. Start Geth workloader 

## Chronos for MySQL-Cluster
1. Setup MySQL-Cluster environment, https://dev.mysql.com/doc/index-cluster.html.
2. Replace the runtime libraries with our delayed libraries in your environment.
3. Setup a test network
4. Start MySQL-Cluster workloader 


# Troubleshooting
Create an issue for questions and bug reports.
