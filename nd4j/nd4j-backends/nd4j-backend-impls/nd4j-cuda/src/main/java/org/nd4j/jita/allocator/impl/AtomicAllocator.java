/*
 *  ******************************************************************************
 *  *
 *  *
 *  * This program and the accompanying materials are made available under the
 *  * terms of the Apache License, Version 2.0 which is available at
 *  * https://www.apache.org/licenses/LICENSE-2.0.
 *  *
 *  *  See the NOTICE file distributed with this work for additional
 *  *  information regarding copyright ownership.
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  * License for the specific language governing permissions and limitations
 *  * under the License.
 *  *
 *  * SPDX-License-Identifier: Apache-2.0
 *  *****************************************************************************
 */

package org.nd4j.jita.allocator.impl;

import lombok.Getter;
import lombok.NonNull;
import lombok.val;
import org.bytedeco.javacpp.Pointer;
import org.nd4j.jita.allocator.Allocator;
import org.nd4j.jita.allocator.enums.Aggressiveness;
import org.nd4j.jita.allocator.enums.AllocationStatus;
import org.nd4j.jita.allocator.pointers.CudaPointer;
import org.nd4j.jita.allocator.time.Ring;
import org.nd4j.jita.allocator.time.rings.LockedRing;
import org.nd4j.jita.conf.Configuration;
import org.nd4j.jita.conf.CudaEnvironment;
import org.nd4j.jita.constant.ConstantProtector;
import org.nd4j.jita.flow.FlowController;
import org.nd4j.jita.handler.MemoryHandler;
import org.nd4j.jita.handler.impl.CudaZeroHandler;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.memory.enums.MemoryKind;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.cache.ConstantHandler;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.jcublas.buffer.BaseCudaDataBuffer;
import org.nd4j.linalg.jcublas.context.CudaContext;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.nd4j.nativeblas.OpaqueDataBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Just-in-Time Allocator for CUDA
 *
 * This method is a basement for pre-allocated memory management for cuda.
 * Basically that's sophisticated garbage collector for both zero-copy memory, and multiple device memory.
 *
 * There's multiple possible data movement directions, but general path is:
 * host memory (issued on JVM side) ->
 *          zero-copy pinned memory (which is allocated for everything out there) ->
 *                  device memory (where data gets moved from zero-copy, if used actively enough)
 *
 * And the backward movement, if memory isn't used anymore (like if originating INDArray was trashed by JVM GC), or it's not popular enough to hold in device memory
 *
 * Mechanism is as lock-free, as possible. This achieved using three-state memory state signalling: Tick/Tack/Toe.
 * Tick: memory chunk (or its part) is accessed on device
 * Tack: memory chink (or its part) device access session was finished
 * Toe: memory chunk is locked for some reason. Possible reasons:
 *              Memory synchronization is ongoing, host->gpu or gpu->host
 *              Memory relocation is ongoing, zero->gpu, or gpu->zero, or gpu->host
 *              Memory removal is ongoing.
 *
 * So, basically memory being used for internal calculations, not interfered with manual changes (aka putRow etc), are always available without locks
 *
 *
 * @author raver119@gmail.com
 */
public class AtomicAllocator implements Allocator {
    private static final AtomicAllocator INSTANCE = new AtomicAllocator();

    private Configuration configuration;

    @Getter
    private transient MemoryHandler memoryHandler;


    // we have single tracking point for allocation points, since we're not going to cycle through it any time soon
    private Map<Long, AllocationPoint> allocationsMap = new ConcurrentHashMap<>();

    private static Logger log = LoggerFactory.getLogger(AtomicAllocator.class);

    /*
        locks for internal resources
     */
    private ReentrantReadWriteLock globalLock = new ReentrantReadWriteLock();
    private ReentrantReadWriteLock externalsLock = new ReentrantReadWriteLock();


    private final AtomicBoolean wasInitialised = new AtomicBoolean(false);

    private final Ring deviceLong = new LockedRing(30);
    private final Ring deviceShort = new LockedRing(30);

    private final Ring zeroLong = new LockedRing(30);
    private final Ring zeroShort = new LockedRing(30);

    public static AtomicAllocator getInstance() {
        if (INSTANCE == null)
            throw new RuntimeException("AtomicAllocator is NULL");
        return INSTANCE;
    }

    protected static ConstantProtector protector;

    private AtomicAllocator() {
        this.configuration = CudaEnvironment.getInstance().getConfiguration();
        applyConfiguration();

        this.memoryHandler = new CudaZeroHandler();

        this.memoryHandler.init(configuration, this);

        this.protector = ConstantProtector.getInstance();

    }

    protected Map<Long, AllocationPoint> allocationsMap(){
        return allocationsMap;
    }

    public void applyConfiguration() {
        CudaEnvironment.getInstance().notifyConfigurationApplied();

        NativeOpsHolder.getInstance().getDeviceNativeOps().enableDebugMode(configuration.isDebug());

        NativeOpsHolder.getInstance().getDeviceNativeOps().enableVerboseMode(configuration.isVerbose());

        NativeOpsHolder.getInstance().getDeviceNativeOps().enableP2P(configuration.isCrossDeviceAccessAllowed());

        NativeOpsHolder.getInstance().getDeviceNativeOps().setGridLimit(configuration.getMaximumGridSize());

        NativeOpsHolder.getInstance().getDeviceNativeOps().setOmpNumThreads(configuration.getMaximumBlockSize());

        NativeOpsHolder.getInstance().getDeviceNativeOps().setOmpMinThreads(configuration.getMinimumBlockSize());
    }



    /**
     * This method returns CudaContext for current thread
     *
     * @return
     */
    @Override
    public CudaContext getDeviceContext() {
        // FIXME: proper lock avoidance required here
        return memoryHandler.getDeviceContext();
    }

    /**
     * This method specifies Mover implementation to be used internally
     * @param memoryHandler
     */
    public void setMemoryHandler(@NonNull MemoryHandler memoryHandler) {
        globalLock.writeLock().lock();

        this.memoryHandler = memoryHandler;
        this.memoryHandler.init(configuration, this);

        globalLock.writeLock().unlock();
    }

    /**
     * Consume and apply configuration passed in as argument
     *
     * PLEASE NOTE: This method should only be used BEFORE any calculations were started.
     *
     * @param configuration configuration bean to be applied
     */
    @Override
    public void applyConfiguration(@NonNull Configuration configuration) {
        if (!wasInitialised.get()) {
            globalLock.writeLock().lock();

            this.configuration = configuration;

            globalLock.writeLock().unlock();
        }
    }


    /**
     * Returns current Allocator configuration
     *
     * @return current configuration
     */
    @Override
    public Configuration getConfiguration() {
        try {
            globalLock.readLock().lock();
            return configuration;
        } finally {
            globalLock.readLock().unlock();
        }
    }


    /**
     * This method returns actual device pointer valid for current object
     *
     * @param buffer
     */
    @Override
    public Pointer getPointer(@NonNull DataBuffer buffer, CudaContext context) {
        return memoryHandler.getDevicePointer(buffer, context);
    }

    public Pointer getPointer(DataBuffer buffer) {
        return memoryHandler.getDevicePointer(buffer, getDeviceContext());
    }

    /**
     * This method returns actual device pointer valid for specified shape of current object
     *
     * @param buffer
     * @param shape
     * @param isView
     */
    @Override
    @Deprecated
    public Pointer getPointer(DataBuffer buffer, AllocationShape shape, boolean isView, CudaContext context) {
        return memoryHandler.getDevicePointer(buffer, context);
    }

    /**
     * This method returns actual device pointer valid for specified INDArray
     *
     * @param array
     */
    @Override
    public Pointer getPointer(INDArray array, CudaContext context) {
        //    DataBuffer buffer = array.data().originalDataBuffer() == null ? array.data() : array.data().originalDataBuffer();
        if (array.isEmpty() || array.isS())
            throw new UnsupportedOperationException("Pew-pew");

        return memoryHandler.getDevicePointer(array.data(), context);
    }

    /**
     * This method returns actual host pointer valid for current object
     *
     * @param array
     */
    @Override
    public Pointer getHostPointer(INDArray array) {
        if (array.isEmpty())
            return null;

        synchronizeHostData(array);
        return memoryHandler.getHostPointer(array.data());
    }

    /**
     * This method returns actual host pointer valid for current object
     *
     * @param buffer
     */
    @Override
    public Pointer getHostPointer(DataBuffer buffer) {
        return memoryHandler.getHostPointer(buffer);
    }


    /**
     * This method should be called to make sure that data on host side is actualized
     *
     * @param array
     */
    @Override
    public void synchronizeHostData(INDArray array) {
        if (array.isEmpty() || array.isS())
            return;

        val buffer = array.data().originalDataBuffer() == null ? array.data() : array.data().originalDataBuffer();
        synchronizeHostData(buffer);
    }

    /**
     * This method should be called to make sure that data on host side is actualized
     *
     * @param buffer
     */
    @Override
    public void synchronizeHostData(DataBuffer buffer) {
        // we actually need synchronization only in device-dependant environment. no-op otherwise. managed by native code
        if(!buffer.wasClosed())
            NativeOpsHolder.getInstance().getDeviceNativeOps().dbSyncToPrimary(((BaseCudaDataBuffer) buffer).getOpaqueDataBuffer());
    }


    /**
     * This method returns CUDA deviceId for specified buffer
     *
     * @param array
     * @return
     */
    public Integer getDeviceId(INDArray array) {
        return getAllocationPoint(array).getDeviceId();
    }


    /**
     * This method releases memory allocated for this allocation point
     * @param point
     */
    public void freeMemory(AllocationPoint point) {
        if (point.getAllocationStatus() == AllocationStatus.DEVICE) {
            this.getMemoryHandler().getMemoryProvider().free(point);

            if (point.getHostPointer() != null) {
                point.setAllocationStatus(AllocationStatus.HOST);
                this.getMemoryHandler().getMemoryProvider().free(point);
                this.getMemoryHandler().forget(point, AllocationStatus.DEVICE);
            }
        } else {
            // call it only once
            if (point.getHostPointer() != null) {
                this.getMemoryHandler().getMemoryProvider().free(point);
                this.getMemoryHandler().forget(point, AllocationStatus.HOST);
            }
        }

        allocationsMap.remove(point.getObjectId());
    }

    /**
     * This method allocates required chunk of memory
     *
     * @param requiredMemory
     */
    @Override
    public AllocationPoint allocateMemory(DataBuffer buffer, AllocationShape requiredMemory, boolean initialize) {
        // by default we allocate on initial location
        AllocationPoint point = null;

        if (configuration.getMemoryModel() == Configuration.MemoryModel.IMMEDIATE) {
            point = allocateMemory(buffer, requiredMemory, memoryHandler.getInitialLocation(), initialize);
        } else if (configuration.getMemoryModel() == Configuration.MemoryModel.DELAYED) {
            // for DELAYED memory model we allocate only host memory, regardless of firstMemory configuration value
            point = allocateMemory(buffer, requiredMemory, AllocationStatus.HOST, initialize);
        }

        return point;
    }




    /**
     * This method allocates required chunk of memory in specific location
     * <p>
     * PLEASE NOTE: Do not use this method, unless you're 100% sure what you're doing
     *
     * @param requiredMemory
     * @param location
     */
    @Override
    public AllocationPoint allocateMemory(DataBuffer buffer, AllocationShape requiredMemory, AllocationStatus location, boolean initialize) {
        switch(location) {
            case HOST:
                OpaqueDataBuffer opaqueDataBuffer = OpaqueDataBuffer.externalizedDataBuffer(buffer.length(), buffer.dataType(), buffer.pointer(),null);
                return new AllocationPoint(opaqueDataBuffer,requiredMemory.getNumberOfBytes());
            case DEVICE:
                OpaqueDataBuffer opaqueDataBuffer2 =  OpaqueDataBuffer.allocateDataBuffer(buffer.length(),buffer.dataType(),true);
                return new AllocationPoint(opaqueDataBuffer2,requiredMemory.getNumberOfBytes());
            case DELAYED:
            case CONSTANT:
            case UNDEFINED:
            case DEALLOCATED:
            default:
                throw new UnsupportedOperationException("Unable to allocate memory.");
        }
    }


    /**
     * This method returns AllocationPoint POJO for specified tracking ID
     * @param objectId
     * @return
     */
    protected AllocationPoint getAllocationPoint(@NonNull Long objectId) {
        return allocationsMap.get(objectId);
    }

    /**
     * This method frees native system memory referenced by specified tracking id/AllocationPoint
     *
     * @param bucketId
     * @param objectId
     * @param point
     * @param copyback
     */
    protected void purgeZeroObject(Long bucketId, Long objectId, AllocationPoint point, boolean copyback) {
        allocationsMap.remove(objectId);

        memoryHandler.purgeZeroObject(bucketId, objectId, point, copyback);
    }

    /**
     * This method frees native device memory referenced by specified tracking id/AllocationPoint
     * @param threadId
     * @param deviceId
     * @param objectId
     * @param point
     * @param copyback
     */
    protected void purgeDeviceObject(Long threadId, Integer deviceId, Long objectId, AllocationPoint point,
                                     boolean copyback) {
        memoryHandler.purgeDeviceObject(threadId, deviceId, objectId, point, copyback);

        // since we can't allow java object without native memory, we explicitly specify that memory is handled using HOST memory only, after device memory is released
    }

    /**
     * This method seeks for unused zero-copy memory allocations
     *
     * @param bucketId Id of the bucket, serving allocations
     * @return size of memory that was deallocated
     */
    protected synchronized long seekUnusedZero(Long bucketId, Aggressiveness aggressiveness) {
        AtomicLong freeSpace = new AtomicLong(0);

        int totalElements = (int) memoryHandler.getAllocatedHostObjects(bucketId);

        // these 2 variables will contain jvm-wise memory access frequencies
        float shortAverage = zeroShort.getAverage();
        float longAverage = zeroLong.getAverage();

        // threshold is calculated based on agressiveness specified via configuration
        float shortThreshold = shortAverage / (Aggressiveness.values().length - aggressiveness.ordinal());
        float longThreshold = longAverage / (Aggressiveness.values().length - aggressiveness.ordinal());

        // simple counter for dereferenced objects
        AtomicInteger elementsDropped = new AtomicInteger(0);
        AtomicInteger elementsSurvived = new AtomicInteger(0);

        for (Long object : memoryHandler.getHostTrackingPoints(bucketId)) {
            AllocationPoint point = getAllocationPoint(object);

            // point can be null, if memory was promoted to device and was deleted there
            if (point == null)
                continue;

            if (point.getAllocationStatus() == AllocationStatus.HOST) {
                //point.getAccessState().isToeAvailable()
                //point.getAccessState().requestToe();

                /*
                    Check if memory points to non-existant buffer, using externals.
                    If externals don't have specified buffer - delete reference.
                 */
                if (point.getBuffer() == null) {
                    purgeZeroObject(bucketId, object, point, false);
                    //freeSpace.addAndGet(AllocationUtils.getRequiredMemory(point.getShape()));
                    throw new UnsupportedOperationException("Pew-pew");

                    //elementsDropped.incrementAndGet();
                    //continue;
                } else {
                    elementsSurvived.incrementAndGet();
                }

                //point.getAccessState().releaseToe();
            } else {
                //  log.warn("SKIPPING :(");
            }
        }



        //log.debug("Short average: ["+shortAverage+"], Long average: [" + longAverage + "]");
        //log.debug("Aggressiveness: ["+ aggressiveness+"]; Short threshold: ["+shortThreshold+"]; Long threshold: [" + longThreshold + "]");
        log.debug("Zero {} elements checked: [{}], deleted: {}, survived: {}", bucketId, totalElements,
                elementsDropped.get(), elementsSurvived.get());

        return freeSpace.get();
    }

    /**
     * This method seeks for unused device memory allocations, for specified thread and device
     *
     * @param threadId Id of the thread, retrieved via Thread.currentThread().getId()
     * @param deviceId Id of the device
     * @return size of memory that was deallocated
     */
    protected long seekUnusedDevice(Long threadId, Integer deviceId, Aggressiveness aggressiveness) {
        AtomicLong freeSpace = new AtomicLong(0);


        //  int initialSize = allocations.size();

        // these 2 variables will contain jvm-wise memory access frequencies
        float shortAverage = deviceShort.getAverage();
        float longAverage = deviceLong.getAverage();

        // threshold is calculated based on agressiveness specified via configuration
        float shortThreshold = shortAverage / (Aggressiveness.values().length - aggressiveness.ordinal());
        float longThreshold = longAverage / (Aggressiveness.values().length - aggressiveness.ordinal());

        AtomicInteger elementsDropped = new AtomicInteger(0);
        AtomicInteger elementsMoved = new AtomicInteger(0);
        AtomicInteger elementsSurvived = new AtomicInteger(0);

        for (Long object : memoryHandler.getDeviceTrackingPoints(deviceId)) {
            AllocationPoint point = getAllocationPoint(object);

            //            if (point.getAccessState().isToeAvailable()) {
            //                point.getAccessState().requestToe();

            /*
                Check if memory points to non-existant buffer, using externals.
                If externals don't have specified buffer - delete reference.
             */
            if (point.getBuffer() == null) {
                if (point.getAllocationStatus() == AllocationStatus.DEVICE) {
                    // we deallocate device memory
                    purgeDeviceObject(threadId, deviceId, object, point, false);
                    //freeSpace.addAndGet(AllocationUtils.getRequiredMemory(point.getShape()));

                    // and we deallocate host memory, since object is dereferenced
                    //purgeZeroObject(point.getBucketId(), object, point, false);

                    //elementsDropped.incrementAndGet();
                    //continue;
                    throw new UnsupportedOperationException("Pew-pew");
                } ;
            } else {
                elementsSurvived.incrementAndGet();
            }

            /*
                Check, if memory can be removed from allocation.
                To check it, we just compare average rates for few tens of latest calls
             */
            /*
                long millisecondsTTL = configuration.getMinimumTTLMilliseconds();
                if (point.getRealDeviceAccessTime() < System.currentTimeMillis() - millisecondsTTL) {
                    // we could remove device allocation ONLY if it's older then minimum TTL
                    if (point.getTimerLong().getFrequencyOfEvents() < longThreshold && point.getTimerShort().getFrequencyOfEvents() < shortThreshold) {
                        //log.info("Removing object: " + object);
            
                        purgeDeviceObject(threadId, deviceId, object, point, true);
            
                        freeSpace.addAndGet(AllocationUtils.getRequiredMemory(point.getShape()));
            
                        elementsMoved.incrementAndGet();
            
                        //purgeDeviceObject(threadId, deviceId, object, point, true);
                    }
                }
            */
            //  point.getAccessState().releaseToe();
            //}
        }

        log.debug("Thread/Device [" + threadId + "/" + deviceId + "] elements purged: [" + elementsDropped.get()
                + "]; Relocated: [" + elementsMoved.get() + "]; Survivors: [" + elementsSurvived.get() + "]");

        return freeSpace.get();
    }

    /*private class UnifiedGarbageCollectorThread extends Thread implements Runnable {
        private final ReferenceQueue<BaseDataBuffer> queue;
        private int threadId;
        private int deviceId;
        private AtomicLong stopper = new AtomicLong(System.currentTimeMillis());

        public UnifiedGarbageCollectorThread(Integer threadId, @NonNull ReferenceQueue<BaseDataBuffer> queue) {
            this.queue = queue;
            this.setDaemon(true);
            this.setName("UniGC thread " + threadId);
            this.threadId = threadId;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    GarbageBufferReference reference = threadId == 0 ? (GarbageBufferReference) queue.poll() : (GarbageBufferReference) queue.remove();
                    if (reference != null) {
                        AllocationPoint point = reference.getPoint();

                        // skipping any allocation that is coming from workspace
                        if (point.isAttached()) {
                            // TODO: remove allocation point as well?
                            if (!allocationsMap.containsKey(point.getObjectId()))
                                throw new RuntimeException();

                            getFlowController().waitTillReleased(point);

                            getFlowController().getEventsProvider().storeEvent(point.getLastWriteEvent());
                            getFlowController().getEventsProvider().storeEvent(point.getLastReadEvent());

                            allocationsMap.remove(point.getObjectId());

                            continue;
                        }

                        if (threadId == 0)
                            stopper.set(System.currentTimeMillis());

                        //log.info("Purging {} bytes...", AllocationUtils.getRequiredMemory(point.getShape()));

                        if (point.getAllocationStatus() == AllocationStatus.HOST) {
                            purgeZeroObject(point.getBucketId(), point.getObjectId(), point, false);
                        } else if (point.getAllocationStatus() == AllocationStatus.DEVICE) {
                            purgeDeviceObject(0L, point.getDeviceId(), point.getObjectId(), point, false);

                            // and we deallocate host memory, since object is dereferenced
                            purgeZeroObject(point.getBucketId(), point.getObjectId(), point, false);
                        }

                    } else {
                        try {
                            if (threadId == 0) {
                                // we don't call for System.gc if last memory allocation was more then 3 seconds ago
                                if (Nd4j.getMemoryManager().isPeriodicGcActive()) {
                                    long ct = System.currentTimeMillis();
                                    if (useTracker.get() > ct - 3000 && ct > Nd4j.getMemoryManager().getLastGcTime() + Nd4j.getMemoryManager().getAutoGcWindow()) {
                                        Nd4j.getMemoryManager().invokeGc();
                                    } else {
                                        LockSupport.parkNanos(50000L);
                                    }
                                } else {
                                    LockSupport.parkNanos(50000L);
                                }
                            }
                        } catch (Exception e) {

                        }
                    }
                } catch (InterruptedException e) {
                    // do nothing
                }
            }
        }
    }*/

    /**
     * This class implements garbage collector for memory allocated on host system.
     *
     *  There's only 1 possible reason of deallocation event: object that reference some memory chunk was removed by JVM gc.
     */
    private class ZeroGarbageCollectorThread extends Thread implements Runnable {

        private final Long bucketId;
        private final AtomicBoolean terminate;

        public ZeroGarbageCollectorThread(Long bucketId, AtomicBoolean terminate) {
            this.bucketId = bucketId;
            this.terminate = terminate;

            this.setName("zero gc thread " + bucketId);
            this.setDaemon(true);
        }

        @Override
        public void run() {
            log.debug("Starting zero GC for thread: " + bucketId);
            long lastCheck = System.currentTimeMillis();
            while (!terminate.get()) {

                /*
                    Check for zero-copy garbage
                 */
                //   log.info("ZeroGC started...");
                /*
                    We want allocations to take in account multiple things:
                    1. average access rates for last X objects
                    2. total number of currently allocated objects
                    3. total allocated memory size
                    4. desired aggressiveness
                */
                try {
                    Thread.sleep(Math.max(configuration.getMinimumTTLMilliseconds(), 10000));
                    //if (bucketId == 0)
                    //System.gc();
                } catch (Exception e) {
                    // we can have interruption here, to force gc
                }

                Aggressiveness aggressiveness = configuration.getHostDeallocAggressiveness();

                // if we have too much objects, or total allocated memory has met 75% of max allocation - use urgent mode
                if ((memoryHandler.getAllocatedHostObjects(bucketId) > 500000 || memoryHandler
                        .getAllocatedHostMemory() > (configuration.getMaximumZeroAllocation() * 0.75))
                        && aggressiveness.ordinal() < Aggressiveness.URGENT.ordinal())
                    aggressiveness = Aggressiveness.URGENT;

                if (memoryHandler.getAllocatedHostMemory() > (configuration.getMaximumZeroAllocation() * 0.85))
                    aggressiveness = Aggressiveness.IMMEDIATE;

                if (memoryHandler.getAllocatedHostMemory() < (configuration.getMaximumZeroAllocation() * 0.25)
                        && (memoryHandler.getAllocatedHostObjects(bucketId) < 5000)
                        && lastCheck > System.currentTimeMillis() - 30000) {
                    ; // i don't want deallocation to be fired on lower thresholds. just no sense locking stuff
                    //log.debug("Skipping zero GC round: ["+zeroUseCounter.get()+"/" +zeroAllocations.get(threadId).size() + "]");
                } else {
                    seekUnusedZero(bucketId, aggressiveness);
                    lastCheck = System.currentTimeMillis();
                }
            }
        }
    }

    /**
     * This class implements garbage collection for memory regions allocated on devices.
     * For each device 1 thread is launched.
     *
     * There's 2 basic reasons for deallocation:
     *  1. Memory isn't used anymore. I.e. INDArray object referencing specific memory chunk was removed by JVM gc.
     *  2. Memory wasn't used for quite some time.
     */
    private class DeviceGarbageCollectorThread extends Thread implements Runnable {

        private final Integer deviceId;
        private final AtomicBoolean terminate;

        public DeviceGarbageCollectorThread(Integer deviceId, AtomicBoolean terminate) {
            this.deviceId = deviceId;
            this.terminate = terminate;
            this.setName("device gc thread [" + deviceId + "]");
            this.setDaemon(true);
        }

        @Override
        public void run() {
            log.info("Starting device GC for device: " + deviceId);
            long lastCheck = System.currentTimeMillis();
            while (!terminate.get()) {
                /*
                    Check for device garbage
                 */

                try {
                    Thread.sleep(Math.max(configuration.getMinimumTTLMilliseconds(), 5000));
                } catch (Exception e) {
                    // we can have interruption here, to force gc

                }

                //log.info("DeviceGC started...");
                Aggressiveness aggressiveness = configuration.getGpuDeallocAggressiveness();

                // if we have too much objects, or total allocated memory has met 75% of max allocation - use urgent mode
                if ((memoryHandler.getAllocatedDeviceObjects(deviceId) > 100000
                        || memoryHandler.getAllocatedDeviceMemory(
                        deviceId) > (configuration.getMaximumDeviceAllocation() * 0.75))
                        && aggressiveness.ordinal() < Aggressiveness.URGENT.ordinal())
                    aggressiveness = Aggressiveness.URGENT;

                if (memoryHandler.getAllocatedDeviceMemory(
                        deviceId) > (configuration.getMaximumDeviceAllocation() * 0.85))
                    aggressiveness = Aggressiveness.IMMEDIATE;

                if (memoryHandler.getAllocatedDeviceMemory(
                        deviceId) < (configuration.getMaximumDeviceAllocation() * 0.25)
                        && (memoryHandler.getAllocatedDeviceObjects(deviceId) < 500)
                        && lastCheck > System.currentTimeMillis() - 30000) {
                    // i don't want deallocation to be fired on lower thresholds. just no sense locking stuff
                } else {
                    seekUnusedDevice(0L, this.deviceId, aggressiveness);
                    lastCheck = System.currentTimeMillis();
                }


            }
        }
    }


    /**
     * This method returns the number of tracked zero-copy allocations
     *
     * @return
     */
    public long getTotalAllocatedHostMemory() {
        return 0L; // memoryHandler.getAllocationStatistics().row(AllocationStatus.HOST).get(0);
    }

    /**
     * This method returns the number of all tracked memory chunks
     *
     * @return
     */
    protected int getTotalTrackingPoints() {
        return allocationsMap.size();
    }

    /**
     * This method returns total amount of memory allocated on specified device
     *
     * @param deviceId
     * @return
     */
    public long getTotalAllocatedDeviceMemory(Integer deviceId) {
        return 0L;//; memoryHandler.getAllocationStatistics().row(AllocationStatus.DEVICE).get(deviceId);
    }

    /**
     * This method implements asynchronous memcpy, if that's available on current hardware
     *
     * @param dstBuffer
     * @param srcPointer
     * @param length
     * @param dstOffset
     */
    @Override
    public void memcpyAsync(DataBuffer dstBuffer, Pointer srcPointer, long length, long dstOffset) {
        this.memoryHandler.memcpyAsync(dstBuffer, srcPointer, length, dstOffset);
    }

    @Override
    public void memcpySpecial(DataBuffer dstBuffer, Pointer srcPointer, long length, long dstOffset) {
        this.memoryHandler.memcpySpecial(dstBuffer, srcPointer, length, dstOffset);
    }

    @Override
    public void memcpyDevice(DataBuffer dstBuffer, Pointer srcPointer, long length, long dstOffset,
                             CudaContext context) {
        this.memoryHandler.memcpyDevice(dstBuffer, srcPointer, length, dstOffset, context);
    }

    /**
     * This method implements blocking memcpy
     *
     * @param dstBuffer
     * @param srcPointer
     * @param length
     * @param dstOffset
     */
    @Override
    public void memcpyBlocking(DataBuffer dstBuffer, Pointer srcPointer, long length, long dstOffset) {
        this.memoryHandler.memcpyBlocking(dstBuffer, srcPointer, length, dstOffset);
    }

    /**
     * This method implements blocking memcpy
     *
     * @param dstBuffer
     * @param srcBuffer
     */
    @Override
    public void memcpy(DataBuffer dstBuffer, DataBuffer srcBuffer) {
        this.memoryHandler.memcpy(dstBuffer, srcBuffer);
    }

    @Override
    public void tickHostWrite(DataBuffer buffer) {
        getAllocationPoint(buffer).tickHostWrite();
    }

    @Override
    public void tickHostWrite(INDArray array) {
        getAllocationPoint(array.data()).tickHostWrite();
    }

    @Override
    public void tickDeviceWrite(INDArray array) {
        getAllocationPoint(array.data()).tickDeviceWrite();
    }

    @Override
    public AllocationPoint getAllocationPoint(INDArray array) {
        return getAllocationPoint(array.data());
    }

    @Override
    public AllocationPoint getAllocationPoint(DataBuffer buffer) {
        return ((BaseCudaDataBuffer) buffer).getAllocationPoint();
    }

    /**
     * This method returns deviceId for current thread
     * All values >= 0 are considered valid device IDs, all values < 0 are considered stubs.
     *
     * @return
     */
    @Override
    public Integer getDeviceId() {
        return memoryHandler.getDeviceId();
    }

    /** Returns {@link #getDeviceId()} wrapped as a {@link Pointer}. */
    @Override
    public Pointer getDeviceIdPointer() {
        return new CudaPointer(getDeviceId());
    }

    @Override
    public void registerAction(CudaContext context, INDArray result, INDArray... operands) {
        memoryHandler.registerAction(context, result, operands);
    }

    @Override
    public FlowController getFlowController() {
        return memoryHandler.getFlowController();
    }

    @Override
    public DataBuffer getConstantBuffer(int[] array) {
        return Nd4j.getConstantHandler().getConstantBuffer(array, DataType.INT);
    }

    @Override
    public DataBuffer getConstantBuffer(float[] array) {
        return Nd4j.getConstantHandler().getConstantBuffer(array, DataType.FLOAT);
    }

    @Override
    public DataBuffer getConstantBuffer(double[] array) {
        return Nd4j.getConstantHandler().getConstantBuffer(array, DataType.DOUBLE);
    }

    @Override
    public DataBuffer moveToConstant(DataBuffer dataBuffer) {
        Nd4j.getConstantHandler().moveToConstantSpace(dataBuffer);
        return dataBuffer;
    }
}
