# Chronos: An Automatical Testing Framework for Finding Timeout Bugs in Distributed Systems by Self-Adaptive Delay Model.

Delays are inevitable in complex distributed environments. Timeout mechanisms are commonly used to handle unexpected failures in distributed systems. However, incorrect timeout handling or implementation errors in timeout mechanisms can lead to system hang-ups or crashes. Such timeout bugs may be crucial and pose a significant threat to the availability and security of distributed systems.

In this work, we introduce Chronos, a general testing framework for automatically detecting timeout bugs in distributed systems with deep-priority transient delays. First, we propose general runtime delayed libraries that dynamically inject fine-grained delays in a Distributed System Under Test (DSUT). To effectively trigger delays and constantly explore timeout bugs in deep paths,
Chronos harnesses a deep-priority guided fuzzing that dynamically generates high-quality delay sequences in the runtime. Then, Chronos utilizes transient delays to eliminate the time overhead caused by actual delays and accelerate the test process.

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
