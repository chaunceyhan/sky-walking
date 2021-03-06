package org.skywalking.apm.collector.stream.worker.impl;

import java.util.List;
import java.util.Map;
import org.skywalking.apm.collector.core.queue.EndOfBatchCommand;
import org.skywalking.apm.collector.stream.worker.AbstractLocalAsyncWorker;
import org.skywalking.apm.collector.stream.worker.ClusterWorkerContext;
import org.skywalking.apm.collector.stream.worker.ProviderNotFoundException;
import org.skywalking.apm.collector.stream.worker.Role;
import org.skywalking.apm.collector.stream.worker.WorkerException;
import org.skywalking.apm.collector.stream.worker.impl.data.Data;
import org.skywalking.apm.collector.stream.worker.impl.data.DataCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author pengys5
 */
public abstract class PersistenceWorker extends AbstractLocalAsyncWorker {

    private final Logger logger = LoggerFactory.getLogger(PersistenceWorker.class);

    private DataCache dataCache;

    public PersistenceWorker(Role role, ClusterWorkerContext clusterContext) {
        super(role, clusterContext);
        dataCache = new DataCache();
    }

    @Override public void preStart() throws ProviderNotFoundException {
        super.preStart();
    }

    @Override protected final void onWork(Object message) throws WorkerException {
        if (message instanceof EndOfBatchCommand || message instanceof FlushAndSwitch) {
            if (dataCache.trySwitchPointer()) {
                dataCache.switchPointer();
            }
        } else {
            if (dataCache.currentCollectionSize() >= 1000) {
                if (dataCache.trySwitchPointer()) {
                    dataCache.switchPointer();
                }
            }
            aggregate(message);
        }
    }

    public List<?> buildBatchCollection() throws WorkerException {
        List<?> batchCollection;
        try {
            while (dataCache.getLast().isHolding()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    logger.warn("thread wake up");
                }
            }
            batchCollection = prepareBatch(dataCache.getLast().asMap());
        } finally {
            dataCache.releaseLast();
        }
        return batchCollection;
    }

    protected abstract List<?> prepareBatch(Map<String, Data> dataMap);

    private void aggregate(Object message) {
        dataCache.hold();
        Data data = (Data)message;

        if (dataCache.containsKey(data.id())) {
            getRole().dataDefine().mergeData(data, dataCache.get(data.id()));
        } else {
            if (dataCache.currentCollectionSize() < 1000) {
                dataCache.put(data.id(), data);
            }
        }

        dataCache.release();
    }
}
