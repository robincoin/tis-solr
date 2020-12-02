/**
 * Copyright (c) 2020 QingLang, Inc. <baisui@qlangtech.com>
 *
 * This program is free software: you can use, redistribute, and/or modify
 * it under the terms of the GNU Affero General Public License, version 3
 * or later ("AGPL"), as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.qlangtech.tis.coredefine.module.action;

import com.google.common.collect.Maps;
import com.qlangtech.tis.TIS;
import com.qlangtech.tis.plugin.PluginStore;
import com.qlangtech.tis.plugin.incr.IncrStreamFactory;
import com.qlangtech.tis.plugin.incr.WatchPodLog;
import com.qlangtech.tis.trigger.jst.ILogListener;
import com.qlangtech.tis.util.HeteroEnum;
import com.qlangtech.tis.util.PluginItems;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author 百岁（baisui@qlangtech.com）
 * @date 2020/04/13
 */
public class TISK8sDelegate {

    private static final Logger logger = LoggerFactory.getLogger(TISK8sDelegate.class);

    private static final ConcurrentHashMap<String, TISK8sDelegate> colIncrLogMap = new ConcurrentHashMap<>();

    static {
        PluginItems.addPluginItemsSaveObserver(new PluginItems.PluginItemsSaveObserver() {

            @Override
            public void afterSaved(PluginItems.PluginItemsSaveEvent event) {
                if (event.heteroEnum == HeteroEnum.PARAMS_CONFIG || event.heteroEnum == HeteroEnum.INCR_K8S_CONFIG) {
                    colIncrLogMap.values().forEach((r) -> {
                        r.close();
                    });
                    colIncrLogMap.clear();
                }
            }
        });
    }

    public static TISK8sDelegate getK8SDelegate(String collection) {
        TISK8sDelegate delegate = null;
        if ((delegate = colIncrLogMap.get(collection)) == null) {
            delegate = colIncrLogMap.computeIfAbsent(collection, (c) -> {
                return new TISK8sDelegate(c);
            });
        }
        return delegate;
    }

    private final String indexName;

    private final IncrStreamFactory k8sConfig;

    private IIncrSync incrSync;

    private IncrDeployment incrDeployment;

    private long latestIncrDeploymentFetchtime;

    private Map<String, WatchPodLog> watchPodLogMap = Maps.newHashMap();

    private TISK8sDelegate(String indexName) {
        if (StringUtils.isEmpty(indexName)) {
            throw new IllegalArgumentException("param indexName can not be null");
        }
        PluginStore<IncrStreamFactory> store = TIS.getPluginStore(null,indexName, IncrStreamFactory.class);
        this.k8sConfig = store.getPlugin();
        if (this.k8sConfig == null) {
            throw new IllegalStateException(" have not set k8s plugin");
        }
        this.incrSync = k8sConfig.getIncrSync();
        this.indexName = indexName;
    }

    public static void main(String[] args) throws Exception {
        IncrSpec incrSpec = new IncrSpec();
        incrSpec.setReplicaCount(1);
        incrSpec.setCpuLimit(Specification.parse("2"));
        incrSpec.setCpuRequest(Specification.parse("500m"));
        incrSpec.setMemoryLimit(Specification.parse("2G"));
        incrSpec.setMemoryRequest(Specification.parse("500M"));
        TISK8sDelegate incrK8s = new TISK8sDelegate("search4totalpay");
        incrK8s.isRCDeployment(true);
    }

    public void deploy(IncrSpec incrSpec, final long timestamp) throws Exception {
        this.incrSync.deploy(this.indexName, incrSpec, timestamp);
    }

    /**
     * 是否存在RC，有即证明已经完成发布流程
     *
     * @return
     */
    public boolean isRCDeployment(boolean canGetCache) {
        return getRcConfig(canGetCache) != null;
    }

    public IncrDeployment getRcConfig(boolean canGetCache) {
        long current = System.currentTimeMillis();
        // 40秒缓存
        if (!canGetCache || this.incrDeployment == null || (current > (latestIncrDeploymentFetchtime + 40000))) {
            latestIncrDeploymentFetchtime = current;
            this.incrDeployment = this.incrSync.getRCDeployment(this.indexName);
            this.watchPodLogMap.entrySet().removeIf((entry) -> {
                return !incrDeployment.getPods().stream().filter((p) -> StringUtils.equals(p.getName(), entry.getKey())).findFirst().isPresent();
            });
        }
        return incrDeployment;
    }

    /**
     * 删除增量实例
     */
    public void removeIncrProcess() {
        try {
            this.incrSync.removeInstance(this.indexName);
            this.cleanResource();
        } catch (Throwable e) {
            // 看一下RC 是否已经没有了如果是没有了 就直接回收资源
            if (this.incrSync.getRCDeployment(this.indexName) == null) {
                this.cleanResource();
            }
            throw new RuntimeException(this.indexName, e);
        }
    }

    private void cleanResource() {
        this.incrDeployment = null;
        TISK8sDelegate tisk8sDelegate = colIncrLogMap.remove(this.indexName);
        if (tisk8sDelegate != null) {
            tisk8sDelegate.close();
        }
    }

    /**
     * 列表pod，并且显示日志
     */
    public void listPodsAndWatchLog(String podName, ILogListener listener) {
        WatchPodLog watchPodLog = null;
        synchronized (this) {
            if ((watchPodLog = this.watchPodLogMap.get(podName)) == null) {
                watchPodLog = this.incrSync.listPodAndWatchLog(this.indexName, podName, listener);
                this.watchPodLogMap.put(podName, watchPodLog);
            } else {
                watchPodLog.addListener(listener);
            }
        }
    }

    public void close() {
        this.watchPodLogMap.values().forEach((r) -> {
            r.close();
        });
    // if (this.watchPodLog != null) {
    // this.watchPodLog.close();
    // }
    }
}
