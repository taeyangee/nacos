/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.nacos.client.naming.core;

import com.alibaba.fastjson.JSON;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.ServiceInfo;
import com.alibaba.nacos.client.monitor.MetricsMonitor;
import com.alibaba.nacos.client.naming.backups.FailoverReactor;
import com.alibaba.nacos.client.naming.cache.DiskCache;
import com.alibaba.nacos.client.naming.net.NamingProxy;
import com.alibaba.nacos.client.naming.utils.UtilAndComs;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.concurrent.*;

import static com.alibaba.nacos.client.utils.LogUtils.NAMING_LOGGER;

/** 本地注册信息：推拉结合做更新
 * @author xuanyin
 */
public class HostReactor { /* 维护本地的服务发现信息 */

    private static final long DEFAULT_DELAY = 1000L;

    private static final long UPDATE_HOLD_INTERVAL = 5000L;

    private final Map<String, ScheduledFuture<?>> futureMap = new HashMap<String, ScheduledFuture<?>>();

    private Map<String, ServiceInfo> serviceInfoMap;  /* Map<service name@@clusters, serviceInfo(service/clusters/instances>)*/

    private Map<String, Object> updatingMap; /* 记录 正在走网络更新的 service */

    private PushReceiver pushReceiver; /* 推送接收器（基于udp）： server推送过来的服务发现信息 ， udp通信*/

    private EventDispatcher eventDispatcher; /* nacos实现的事件机制： 用于分发serviceInfo的变更时间， 本地可以注册listener到EventDispatcher做时间监听 */

    private NamingProxy serverProxy; /* http通信组件 */

    private FailoverReactor failoverReactor; /* 容错：基本思路是读本地文件 */

    private String cacheDir; /* 配合容错：本地文件路径 */

    private ScheduledExecutorService executor;

    public HostReactor(EventDispatcher eventDispatcher, NamingProxy serverProxy, String cacheDir) {
        this(eventDispatcher, serverProxy, cacheDir, false, UtilAndComs.DEFAULT_POLLING_THREAD_COUNT);
    }

    public HostReactor(EventDispatcher eventDispatcher, NamingProxy serverProxy, String cacheDir,
                       boolean loadCacheAtStart, int pollingThreadCount) {

        executor = new ScheduledThreadPoolExecutor(pollingThreadCount, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setDaemon(true);
                thread.setName("com.alibaba.nacos.client.naming.updater");
                return thread;
            }
        });

        this.eventDispatcher = eventDispatcher;
        this.serverProxy = serverProxy;
        this.cacheDir = cacheDir;
        if (loadCacheAtStart) {
            this.serviceInfoMap = new ConcurrentHashMap<String, ServiceInfo>(DiskCache.read(this.cacheDir));
        } else {
            this.serviceInfoMap = new ConcurrentHashMap<String, ServiceInfo>(16);
        }

        this.updatingMap = new ConcurrentHashMap<String, Object>();
        this.failoverReactor = new FailoverReactor(this, cacheDir);
        this.pushReceiver = new PushReceiver(this);
    }

    public Map<String, ServiceInfo> getServiceInfoMap() {
        return serviceInfoMap;
    }

    public synchronized ScheduledFuture<?> addTask(UpdateTask task) {
        return executor.schedule(task, DEFAULT_DELAY, TimeUnit.MILLISECONDS);
    }

    public ServiceInfo processServiceJSON(String json) {
        ServiceInfo serviceInfo = JSON.parseObject(json, ServiceInfo.class); /* server端返回的最新数据*/
        ServiceInfo oldService = serviceInfoMap.get(serviceInfo.getKey()); /* 本地缓存的数据 */
        if (serviceInfo.getHosts() == null || !serviceInfo.validate()) { /* 坑：validate永真？？ 而且还捞了一包*/
            //empty or error push, just ignore
            return oldService;
        }

        boolean changed = false;

        if (oldService != null) { /* 分支： 本地有缓存过*/

            if (oldService.getLastRefTime() > serviceInfo.getLastRefTime()) {
                NAMING_LOGGER.warn("out of date data received, old-t: " + oldService.getLastRefTime()
                    + ", new-t: " + serviceInfo.getLastRefTime());
            }

            serviceInfoMap.put(serviceInfo.getKey(), serviceInfo); /* 更新缓存 */

            Map<String, Instance> oldHostMap = new HashMap<String, Instance>(oldService.getHosts().size());
            for (Instance host : oldService.getHosts()) {
                oldHostMap.put(host.toInetAddr(), host);
            }

            Map<String, Instance> newHostMap = new HashMap<String, Instance>(serviceInfo.getHosts().size());
            for (Instance host : serviceInfo.getHosts()) {
                newHostMap.put(host.toInetAddr(), host);
            }
            /* 这里往下一大串： 就是检测serviceinfo有没变动，然后写DiskCache，为容灾做准备。 优化：比对的方式很low，可以模仿ek加个checksum*/
            Set<Instance> modHosts = new HashSet<Instance>(); /* 更新过的实例*/
            Set<Instance> newHosts = new HashSet<Instance>(); /* 新增实例*/
            Set<Instance> remvHosts = new HashSet<Instance>(); /* 消亡实例 */

            List<Map.Entry<String, Instance>> newServiceHosts = new ArrayList<Map.Entry<String, Instance>>(
                newHostMap.entrySet());
            for (Map.Entry<String, Instance> entry : newServiceHosts) {
                Instance host = entry.getValue();
                String key = entry.getKey();
                if (oldHostMap.containsKey(key) && !StringUtils.equals(host.toString(),
                    oldHostMap.get(key).toString())) {
                    modHosts.add(host);
                    continue;
                }

                if (!oldHostMap.containsKey(key)) {
                    newHosts.add(host);
                }
            }

            for (Map.Entry<String, Instance> entry : oldHostMap.entrySet()) {
                Instance host = entry.getValue();
                String key = entry.getKey();
                if (newHostMap.containsKey(key)) {
                    continue;
                }

                if (!newHostMap.containsKey(key)) {
                    remvHosts.add(host);
                }

            }

            if (newHosts.size() > 0) {
                changed = true;
                NAMING_LOGGER.info("new ips(" + newHosts.size() + ") service: "
                    + serviceInfo.getKey() + " -> " + JSON.toJSONString(newHosts));
            }

            if (remvHosts.size() > 0) {
                changed = true;
                NAMING_LOGGER.info("removed ips(" + remvHosts.size() + ") service: "
                    + serviceInfo.getKey() + " -> " + JSON.toJSONString(remvHosts));
            }

            if (modHosts.size() > 0) {
                changed = true;
                NAMING_LOGGER.info("modified ips(" + modHosts.size() + ") service: "
                    + serviceInfo.getKey() + " -> " + JSON.toJSONString(modHosts));
            }

            serviceInfo.setJsonFromServer(json);

            if (newHosts.size() > 0 || remvHosts.size() > 0 || modHosts.size() > 0) {
                eventDispatcher.serviceChanged(serviceInfo);
                DiskCache.write(serviceInfo, cacheDir); /* serviceinfo有变动， 就重写容错 */
            }

        } else { /* 分支：本地没有缓存过改key的数据*/
            changed = true;
            NAMING_LOGGER.info("init new ips(" + serviceInfo.ipCount() + ") service: " + serviceInfo.getKey() + " -> " + JSON
                .toJSONString(serviceInfo.getHosts()));
            serviceInfoMap.put(serviceInfo.getKey(), serviceInfo);
            eventDispatcher.serviceChanged(serviceInfo);
            serviceInfo.setJsonFromServer(json);
            DiskCache.write(serviceInfo, cacheDir);
        }

        MetricsMonitor.getServiceInfoMapSizeMonitor().set(serviceInfoMap.size()); /* metric*/

        if (changed) {
            NAMING_LOGGER.info("current ips:(" + serviceInfo.ipCount() + ") service: " + serviceInfo.getKey() +
                " -> " + JSON.toJSONString(serviceInfo.getHosts()));
        }

        return serviceInfo;
    }

    private ServiceInfo getServiceInfo0(String serviceName, String clusters) {

        String key = ServiceInfo.getKey(serviceName, clusters);

        return serviceInfoMap.get(key);
    }

    public ServiceInfo getServiceInfoDirectlyFromServer(final String serviceName, final String clusters) throws NacosException {
        String result = serverProxy.queryList(serviceName, clusters, 0, false); /* udp = 0 , 表示没有走订阅模式 */
        if (StringUtils.isNotEmpty(result)) {
            return JSON.parseObject(result, ServiceInfo.class);
        }
        return null;
    }

    public ServiceInfo getServiceInfo(final String serviceName, final String clusters) {  /* service name = common-alarm, clusters = []*/

        NAMING_LOGGER.debug("failover-mode: " + failoverReactor.isFailoverSwitch());
        String key = ServiceInfo.getKey(serviceName, clusters);
        if (failoverReactor.isFailoverSwitch()) {
            return failoverReactor.getService(key); /* fail over： 大概就是查本地文件  ,  看样子走不进去啊？*/
        }

        ServiceInfo serviceObj = getServiceInfo0(serviceName, clusters); /* 从本地缓存中， 拉取serviceInfo */

        if (null == serviceObj) { /* 分支：本地没有记录 */
            serviceObj = new ServiceInfo(serviceName, clusters);

            serviceInfoMap.put(serviceObj.getKey(), serviceObj);

            updatingMap.put(serviceName, new Object());
            updateServiceNow(serviceName, clusters); /* 及时从server获取一下 */
            updatingMap.remove(serviceName);

        } else if (updatingMap.containsKey(serviceName)) { /* 分支：本地有记录 */

            if (UPDATE_HOLD_INTERVAL > 0) { /* 本地有记录，但正在请求更新，就等等 */   /* vs ek： ek在服务发现做网络请求的时候， 没有使用锁，而是设计了“版本”的概念，  */
                // hold a moment waiting for update finish
                synchronized (serviceObj) {
                    try {
                        serviceObj.wait(UPDATE_HOLD_INTERVAL); /* 超期就报错，超期设置为：5s */
                    } catch (InterruptedException e) {
                        NAMING_LOGGER.error("[getServiceInfo] serviceName:" + serviceName + ", clusters:" + clusters, e);
                    }
                }
            }
        }

        scheduleUpdateIfAbsent(serviceName, clusters); /* 并不总是投递任务，根据key 内部有去重*/

        return serviceInfoMap.get(serviceObj.getKey());
    }



    public void scheduleUpdateIfAbsent(String serviceName, String clusters) {
        if (futureMap.get(ServiceInfo.getKey(serviceName, clusters)) != null) { /* 任务投递后，会自己重新循环，这里就根据key做去重 */
            return;
        }

        synchronized (futureMap) {
            if (futureMap.get(ServiceInfo.getKey(serviceName, clusters)) != null) {
                return;
            }

            ScheduledFuture<?> future = addTask(new UpdateTask(serviceName, clusters));
            futureMap.put(ServiceInfo.getKey(serviceName, clusters), future);
        }
    }

    public void updateServiceNow(String serviceName, String clusters) {
        ServiceInfo oldService = getServiceInfo0(serviceName, clusters); /* 本地缓存中取出*/
        try {

            String result = serverProxy.queryList(serviceName, clusters, pushReceiver.getUDPPort(), false); /* 还传递了 client的udp端口，server项这个udp端推送更新消息 */

            if (StringUtils.isNotEmpty(result)) {
                processServiceJSON(result); /* 处理server返回的service info*/
            }
        } catch (Exception e) {
            NAMING_LOGGER.error("[NA] failed to update serviceName: " + serviceName, e);
        } finally {
            if (oldService != null) { /* 新一轮的获取动作正在wait，这里notify一下*/
                synchronized (oldService) {
                    oldService.notifyAll();
                }
            }
        }
    }

    public void refreshOnly(String serviceName, String clusters) {
        try {
            serverProxy.queryList(serviceName, clusters, pushReceiver.getUDPPort(), false);
        } catch (Exception e) {
            NAMING_LOGGER.error("[NA] failed to update serviceName: " + serviceName, e);
        }
    }

    public class UpdateTask implements Runnable {
        long lastRefTime = Long.MAX_VALUE;
        private String clusters;
        private String serviceName;

        public UpdateTask(String serviceName, String clusters) {
            this.serviceName = serviceName;
            this.clusters = clusters;
        }

        @Override
        public void run() {
            try {
                ServiceInfo serviceObj = serviceInfoMap.get(ServiceInfo.getKey(serviceName, clusters));

                if (serviceObj == null) { /* 分支： 本地没有该key的服务信息*/
                    updateServiceNow(serviceName, clusters); /* 请求server端，查询 service+clusters对应的服务信息 */
                    executor.schedule(this, DEFAULT_DELAY, TimeUnit.MILLISECONDS); /* 重新投递任务 */
                    return;
                }

                if (serviceObj.getLastRefTime() <= lastRefTime) {
                    updateServiceNow(serviceName, clusters); /* 更新serviceObj*/
                    serviceObj = serviceInfoMap.get(ServiceInfo.getKey(serviceName, clusters)); /*得到更新后的serviceObj*/
                } else {
                    // if serviceName already updated by push, we should not override it /* pull： 从server端的注册缓存中拉取， 而缓存也是滞后于事件的 */
                    // since the push data may be different from pull through force push  /* push： server端走事件、udp通知到client。 结论：push的结果要比pull准确 */
                    refreshOnly(serviceName, clusters); /* refresh是为了在server保活对应的push client,如此，本地的push recv才能收到消息*/
                }

                lastRefTime = serviceObj.getLastRefTime();

                if (!eventDispatcher.isSubscribed(serviceName, clusters) &&
                    !futureMap.containsKey(ServiceInfo.getKey(serviceName, clusters))) {
                    // abort the update task:
                    NAMING_LOGGER.info("update task is stopped, service:" + serviceName + ", clusters:" + clusters);
                    return;
                }

                executor.schedule(this, serviceObj.getCacheMillis(), TimeUnit.MILLISECONDS);


            } catch (Throwable e) {
                NAMING_LOGGER.warn("[NA] failed to update serviceName: " + serviceName, e);
            }

        }
    }
}
