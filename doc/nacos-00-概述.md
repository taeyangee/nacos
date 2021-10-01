# 参考
Nacos单机+CAP架构的整体源码流程 - process架构图    
# 版本
nacos 1.2.0

# Q
服务发现相关
服务端启动
客户端启动
服务注册
服务全量获取
服务增量获取
服务心跳（客户端）、健康检查（server端）
配置相关
动态配置新增
动态配置获取
服务meta数据管理
集群相关
选举
数据通信
springboot + nacos client

# 包结构
nacos-server中执行main方法位于console项目中：com.alibaba.nacos.Nacos类。
console项目依赖于naming和config项目；
naming项目依赖于core和api项目；
config项目依赖于core和api项目；
core项目依赖于common项目；
client项目依赖于common项目和client项目；
cmdb项目依赖于core和api项目；
console的Nacos类启动后，会通过包扫描来扫描所有的module相关目录，至此，项目启动。
来源： https://www.it610.com/article/1290688904059494400.htm


# 源码启动
https://www.shuzhiduo.com/A/8Bz8NDRx5x/
