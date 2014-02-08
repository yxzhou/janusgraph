package com.thinkaurelius.titan.diskstorage.log.kcvs;

import com.google.common.base.Preconditions;
import com.google.common.collect.*;
import com.thinkaurelius.titan.core.TitanException;
import com.thinkaurelius.titan.diskstorage.Entry;
import com.thinkaurelius.titan.diskstorage.ReadBuffer;
import com.thinkaurelius.titan.diskstorage.StaticBuffer;
import com.thinkaurelius.titan.diskstorage.StorageException;
import com.thinkaurelius.titan.diskstorage.configuration.ConfigOption;
import com.thinkaurelius.titan.diskstorage.configuration.Configuration;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.*;
import com.thinkaurelius.titan.diskstorage.log.*;
import com.thinkaurelius.titan.diskstorage.log.util.FutureMessage;
import com.thinkaurelius.titan.diskstorage.log.util.ProcessMessageJob;
import com.thinkaurelius.titan.diskstorage.util.*;
import com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration;
import static com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration.*;
import com.thinkaurelius.titan.graphdb.database.serialize.DataOutput;
import com.thinkaurelius.titan.util.system.BackgroundThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 * All time values in this class are in mircoseconds.
 *
 * @author Matthias Broecheler (me@matthiasb.com)
 */
public class KCVSLog implements Log, BackendOperation.TransactionalProvider {

    private static final Logger log = LoggerFactory.getLogger(KCVSLog.class);

    //########## Configuration Options #############

    public static final ConfigOption<Integer> LOG_MAX_WRITE_TIME = new ConfigOption<Integer>(LOG_NS,"max-write-time",
            "Maximum time in ms to try persisting log messages against the backend",
            ConfigOption.Type.MASKABLE, 10000, ConfigOption.positiveInt());

    public static final ConfigOption<Integer> LOG_MAX_READ_TIME = new ConfigOption<Integer>(LOG_NS,"max-read-time",
            "Maximum time in ms to try reading log messages from the backend",
            ConfigOption.Type.MASKABLE, 4000, ConfigOption.positiveInt());

    public static final ConfigOption<Integer> LOG_READ_LAG_TIME = new ConfigOption<Integer>(LOG_NS,"read-lag-time",
            "Maximum time in ms that it may take for reads to appear in the backend",
            ConfigOption.Type.MASKABLE, 500, ConfigOption.positiveInt());

    public static final ConfigOption<Boolean> LOG_KEY_CONSISTENT = new ConfigOption<Boolean>(LOG_NS,"key-consistent",
            "Whether to require consistency for log reads and writes",
            ConfigOption.Type.MASKABLE, false);

    //########## INTERNAL CONSTANTS #############

    /**
     * The time period that is stored under one key in the underlying KCVS.
     * This value should NEVER be changed since this will cause backwards incompatibility.
     * This setting is not configurable. If too many messages end up under one key, please
     * configure either 1) the number of buckets or 2) introduce partitioning.
     */
    public static final long TIMESLICE_INTERVAL = 100L * 1000 * 1000 ; //100 seconds

    /**
     * For batch sending to make sense against a KCVS, the maximum send/delivery delay must be at least
     * this number. If the delivery delay is configured to be smaller than this time interval, messages
     * will be send immediately since batching will likely be ineffective.
     */
    private final static int MIN_DELIVERY_DELAY = 10 * 1000; //10ms

    /**
     * Multiplier for the maximum number of messages to hold in the outgoing message queue before producing back pressure.
     * Multiplied with the message sending batch size.
     * If back pressure is a regular occurrence, decrease the sending interval or increase the sending batch size
     */
    private final static int BATCH_SIZE_MULTIPLIER = 10;
    /**
     * Wait time after close() is called for all ongoing jobs to finish and shut down.
     */
    private final static int CLOSE_DOWN_WAIT = 10000 * 1000; // 10 seconds
    /**
     * Time before a registered reader starts processing messages
     */
    private final static int INITIAL_READER_DELAY = 100*1000; //100 ms

    private final static long MS_TO_MICRO = 1000;

    //########## INTERNAL SETTING MANAGEMENT #############

    /**
     * All system settings use this value for the partition id (first 4 byte of the key).
     * This is all 1s in binary representation which would be an illegal value for a partition id. Hence,
     * we avoid conflict.
     */
    private final static int SYSTEM_PARTITION_ID = 0xFFFFFFFF;

    /**
     * The first byte of any system column is used to indicate the type of column. The next two variables define
     * the prefixes for message counter columns (i.e. keeping of the log message numbers) and for the marker columns
     * (i.e. keeping track of the timestamps to which it has been read)
     */
    private final static byte MESSAGE_COUNTER = 1;
    private final static byte MARKER_PREFIX = 2;
    /**
     * Since the message counter column is nothing but the prefix, we can define it statically up front
     */
    private final static StaticBuffer MESSAGE_COUNTER_COLUMN = new WriteByteBuffer(1).putByte(MESSAGE_COUNTER).getStaticBuffer();

    /**
     * Associated {@link LogManager}
     */
    private final KCVSLogManager manager;
    /**
     * Name of the log - logs are uniquely identified by name
     */
    private final String name;
    /**
     * The KCVSStore wrapped by this log
     */
    private final KeyColumnValueStore store;
    /**
     * The read marker which indicates where to start reading from the log
     */
    private final ReadMarker readMarker;

    /**
     * The number of buckets into which each time slice is subdivided. Increasing the number of buckets load balances
     * the reads and writes to the log.
     */
    private final int numBuckets;
    private final boolean keyConsistentOperations;

    private final int sendBatchSize;
    private final long maxSendDelay;
    private final long maxWriteTime;
    /**
     * Used for batch addition of messages to the log. Newly added entries are buffered in this queue before being written in batch
     */
    private final ArrayBlockingQueue<MessageEnvelope> outgoingMsg;
    /**
     * Background thread which periodically writes out the queued up messages. TODO: consider batching messages across ALL logs
     */
    private final SendThread sendThread;

    private final int numReadThreads;
    private final int maxReadMsg;
    private final long readPollingInterval;
    private final long readLagTime;
    private final long maxReadTime;
    private final boolean allowReadMarkerRecovery = true;

    /**
     * Thread pool to read messages in the specified interval from the various keys in a time slice AND to process
     * messages. So, both reading and processing messages is done in the same thread pool.
     */
    private ScheduledExecutorService readExecutor;
    /**
     * Individual jobs that pull messages from the keys that comprise one time slice
     */
    private MessagePuller[] msgPullers;

    /**
     * Counter used to write messages in a round-robin fashion
     */
    private final AtomicLong numBucketCounter;
    /**
     * Counter for the message ids of this sender
     */
    private final AtomicLong numMsgCounter;
    /**
     * Registered readers for this log
     */
    private final List<MessageReader> readers;
    /**
     * Whether this log is open (i.e. accepts writes)
     */
    private volatile boolean isOpen;

    public KCVSLog(String name, KCVSLogManager manager, KeyColumnValueStore store, ReadMarker readMarker, Configuration config) {
        Preconditions.checkArgument(manager != null && name != null && readMarker != null && store != null && config!=null);
        this.name=name;
        this.manager=manager;
        this.store=store;
        this.readMarker=readMarker;

        this.keyConsistentOperations = config.get(LOG_KEY_CONSISTENT);
        this.numBuckets = config.get(LOG_NUM_BUCKETS);
        Preconditions.checkArgument(numBuckets>=1 && numBuckets<=Integer.MAX_VALUE);

        sendBatchSize = config.get(LOG_SEND_BATCH_SIZE);
        maxSendDelay = config.get(LOG_SEND_DELAY)*MS_TO_MICRO;
        maxWriteTime = config.get(LOG_MAX_WRITE_TIME)*MS_TO_MICRO;

        numReadThreads = config.get(LOG_READ_THREADS);
        maxReadMsg = config.get(LOG_READ_BATCH_SIZE);
        readPollingInterval = config.get(LOG_READ_INTERVAL)*MS_TO_MICRO;
        readLagTime = config.get(LOG_READ_LAG_TIME)*MS_TO_MICRO+maxSendDelay;
        maxReadTime = config.get(LOG_MAX_READ_TIME)*MS_TO_MICRO;


        if (maxSendDelay>=MIN_DELIVERY_DELAY) {
            outgoingMsg = new ArrayBlockingQueue<MessageEnvelope>(sendBatchSize*BATCH_SIZE_MULTIPLIER);
            sendThread = new SendThread();
            sendThread.start();
        } else {
            outgoingMsg = null;
            sendThread = null;
        }

        //These will be initialized when the first readers are registered (see below)
        readExecutor = null;
        msgPullers = null;

        this.numMsgCounter = new AtomicLong(readSetting(manager.senderId, MESSAGE_COUNTER_COLUMN, 0));
        this.numBucketCounter = new AtomicLong(0);
        this.readers = new ArrayList<MessageReader>();
        this.isOpen = true;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public synchronized void close() throws StorageException {
        this.isOpen = false;
        if (readExecutor!=null) readExecutor.shutdown();
        if (sendThread!=null) sendThread.close(CLOSE_DOWN_WAIT,TimeUnit.MICROSECONDS);
        if (readExecutor!=null) {
            try {
                readExecutor.awaitTermination(1,TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.error("Could not terminate reader thread pool for KCVSLog "+name+" due to interruption");
            }
            if (!readExecutor.isTerminated()) {
                readExecutor.shutdownNow();
                log.error("Reader thread pool for KCVSLog "+name+" did not shut down in time - could not clean up or set read markers");
            } else {
                for (MessagePuller puller : msgPullers) {
                    puller.close();
                }
            }
        }
        writeSetting(manager.senderId, MESSAGE_COUNTER_COLUMN, numMsgCounter.get());
        store.close();
        manager.closedLog(this);
    }

    @Override
    public StoreTransaction openTx() throws StorageException {
        StandardTransactionConfig config;
        if (keyConsistentOperations) {
            config = StandardTransactionConfig.of(manager.storeManager.getFeatures().getKeyConsistentTxConfig());
        } else {
            config = StandardTransactionConfig.of();
        }
        return manager.storeManager.beginTransaction(config);
    }

    /**
     * ###################################
     *  Message Serialization & Utility
     * ###################################
     */

    private static int getTimeSlice(long timestamp) {
        long value = timestamp/ TIMESLICE_INTERVAL;
        if (value>Integer.MAX_VALUE || value<0) throw new IllegalArgumentException("Timestamp overflow detected: " + timestamp);
        return (int)value;
    }

    private StaticBuffer getLogKey(final int partitionId, final int bucketId, final int timeslice) {
        Preconditions.checkArgument(partitionId>=0 && partitionId<(1<<manager.partitionBitWidth));
        Preconditions.checkArgument(bucketId>=0 && bucketId<numBuckets);
        DataOutput o = manager.serializer.getDataOutput(3 * 4);
        o.putInt((partitionId<<(32-manager.partitionBitWidth))); //Offset to put significant bits in front
        o.putInt(bucketId);
        o.putInt(timeslice);
        return o.getStaticBuffer();
    }

    private Entry writeMessage(KCVSMessage msg) {
        StaticBuffer content = msg.getContent();
        DataOutput out = manager.serializer.getDataOutput(8 + 8 + manager.senderId.length() + 2 + content.length());
        Preconditions.checkArgument(msg.getTimestampMicro()>0);
        out.putLong(msg.getTimestampMicro());
        out.writeObjectNotNull(manager.senderId);
        out.putLong(numMsgCounter.incrementAndGet());
        final int valuePos = out.getPosition();
        out.putBytes(content);
        return new StaticArrayEntry(out.getStaticBuffer(),valuePos);
    }

    private KCVSMessage parseMessage(Entry msg) {
        ReadBuffer r = msg.asReadBuffer();
        long timestamp = r.getLong();
        String senderId = manager.serializer.readObjectNotNull(r,String.class);
        return new KCVSMessage(msg.getValue(),timestamp,senderId);
    }

    /**
     * ###################################
     *  Message Sending
     * ###################################
     */

    @Override
    public Future<Message> add(StaticBuffer content) {
        return add(content,manager.defaultPartitionId);
    }

    @Override
    public Future<Message> add(StaticBuffer content, StaticBuffer key) {
        int partitionId = 0;
        //Get first 4 byte if exist in key
        for (int i=0;i<4;i++) {
            int b;
            if (key.length()>i) b = key.getByte(i) & 0xFF;
            else b = 0;
            partitionId = (partitionId<<8) + b;
        }
        assert manager.partitionBitWidth>=0 && manager.partitionBitWidth<=32;
        partitionId = partitionId>>>(32-manager.partitionBitWidth);
        return add(content, partitionId);
    }

    /**
     * Adds the given message (content) to the partition identified by the provided partitionId.
     *
     * @param content
     * @param partitionId
     * @return
     */
    private Future<Message> add(StaticBuffer content, int partitionId) {
        Preconditions.checkArgument(isOpen,"Log {} has been closed",name);
        Preconditions.checkArgument(content!=null && content.length()>0,"Content is empty");
        Preconditions.checkArgument(partitionId>=0 && partitionId<(1<<manager.partitionBitWidth),"Invalid partition id: %s",partitionId);
        long timestamp = Timestamps.MICRO.getTime();
        KCVSMessage msg = new KCVSMessage(content,timestamp,manager.senderId);
        FutureMessage fmsg = new FutureMessage(msg);

        StaticBuffer key=getLogKey(partitionId,(int)(numBucketCounter.incrementAndGet()%numBuckets),getTimeSlice(timestamp));
        MessageEnvelope envelope = new MessageEnvelope(fmsg,key,writeMessage(msg));

        if (outgoingMsg==null) {
            sendMessages(ImmutableList.of(envelope));
        } else {
            try {
                outgoingMsg.put(envelope); //Produces back pressure when full
            } catch (InterruptedException e) {
                throw new TitanException("Got interrupted waiting to send message",e);
            }
        }
        return fmsg;
    }

    /**
     * Helper class to hold the message and its serialization for writing
     */
    private static class MessageEnvelope {

        final FutureMessage<KCVSMessage> message;
        final StaticBuffer key;
        final Entry entry;

        private MessageEnvelope(FutureMessage<KCVSMessage> message, StaticBuffer key, Entry entry) {
            this.message = message;
            this.key = key;
            this.entry = entry;
        }
    }

    private void sendMessages(final List<MessageEnvelope> msgEnvelopes) {
        try {
            boolean success=BackendOperation.execute(new BackendOperation.Transactional<Boolean>() {
                @Override
                public Boolean call(StoreTransaction txh) throws StorageException {
                    ListMultimap<StaticBuffer,Entry> mutations = ArrayListMultimap.create();
                    for (MessageEnvelope env : msgEnvelopes) {
                        mutations.put(env.key,env.entry);
                    }
                    if (manager.storeManager.getFeatures().hasBatchMutation()) {
                        Map<StaticBuffer,KCVMutation> muts = new HashMap<StaticBuffer, KCVMutation>(mutations.keySet().size());
                        for (StaticBuffer key : mutations.keySet()) {
                            muts.put(key,new KCVMutation(mutations.get(key),KeyColumnValueStore.NO_DELETIONS));
                        }
                        manager.storeManager.mutateMany(ImmutableMap.of(store.getName(),muts),txh);
                    } else {
                        for (StaticBuffer key : mutations.keySet()) {
                            store.mutate(key,mutations.get(key),KeyColumnValueStore.NO_DELETIONS,txh);
                        }
                    }
                    return Boolean.TRUE;
                }
                @Override
                public String toString() {
                    return "messageSending";
                }
            },this, maxWriteTime);
            Preconditions.checkState(success);
            log.debug("Wrote {} messages to backend",msgEnvelopes.size());
            for (MessageEnvelope msgEnvelope : msgEnvelopes)
                msgEnvelope.message.delivered();
        } catch (TitanException e) {
            for (MessageEnvelope msgEnvelope : msgEnvelopes)
                msgEnvelope.message.failed(e);
            throw e;
        }
    }

    private class SendThread extends BackgroundThread {

        private List<MessageEnvelope> toSend;

        public SendThread() {
            super("KCVSLogSend"+name, false);
            toSend = new ArrayList<MessageEnvelope>(sendBatchSize*3/2);
        }

        private long timeSinceFirstMsg() {
            if (!toSend.isEmpty()) return Math.max(0,Timestamps.MICRO.getTime()-toSend.get(0).message.getMessage().getTimestampMicro());
            else return 0;
        }

        private long maxWaitTime() {
            if (!toSend.isEmpty()) return Math.max(0,maxSendDelay-timeSinceFirstMsg());
            else return Long.MAX_VALUE;
        }

        @Override
        protected void waitCondition() throws InterruptedException {
            MessageEnvelope msg = outgoingMsg.poll(maxWaitTime(), TimeUnit.MICROSECONDS);
            if (msg!=null) toSend.add(msg);
        }

        @Override
        protected void action() {
            MessageEnvelope msg;
            while (toSend.size()<sendBatchSize && (msg=outgoingMsg.poll())!=null) {
                toSend.add(msg);
            }
            if (!toSend.isEmpty() && (timeSinceFirstMsg()>=maxSendDelay || toSend.size()>=sendBatchSize)) {
                sendMessages(toSend);
                toSend.clear();
            }
        }

        @Override
        protected void cleanup() {
            if (!toSend.isEmpty() || !outgoingMsg.isEmpty()) {
                //There are still messages waiting to be sent
                toSend.addAll(outgoingMsg);
                for (int i=0;i<toSend.size();i=i+sendBatchSize) {
                    List<MessageEnvelope> subset = toSend.subList(i,Math.min(toSend.size(),i+sendBatchSize));
                    sendMessages(subset);
                }
            }
        }
    }

    /**
     * ###################################
     *  Message Reading
     * ###################################
     */

    @Override
    public synchronized void registerReader(MessageReader... reader) {
        Preconditions.checkArgument(isOpen,"Log {} has been closed",name);
        Preconditions.checkArgument(reader!=null && reader.length>0,"Must specify at least one reader");
        registerReaders(Arrays.asList(reader));
    }

    @Override
    public synchronized void registerReaders(Iterable<MessageReader> readers) {
        Preconditions.checkArgument(isOpen,"Log {} has been closed",name);
        Preconditions.checkArgument(!Iterables.isEmpty(readers),"Must specify at least one reader");
        boolean firstRegistration = this.readers.isEmpty();
        for (MessageReader reader : readers) {
            Preconditions.checkNotNull(reader);
            if (!this.readers.contains(reader)) this.readers.add(reader);
        }
        if (firstRegistration && !this.readers.isEmpty()) {
            readExecutor = new ScheduledThreadPoolExecutor(numReadThreads,new RejectedExecutionHandler() {
            //Custom rejection handler so that messages are processed in-thread when executor has been closed
                @Override
                public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                    r.run();
                }
            });
            msgPullers = new MessagePuller[manager.readPartitionIds.length*numBuckets];
            int pos = 0;
            for (int partitionId : manager.readPartitionIds) {
                for (int bucketId = 0; bucketId < numBuckets; bucketId++) {
                    msgPullers[pos]=new MessagePuller(partitionId,bucketId);
                    readExecutor.scheduleWithFixedDelay(msgPullers[pos],INITIAL_READER_DELAY,readPollingInterval,TimeUnit.MICROSECONDS);
                    pos++;
                }
            }
        }
    }

    @Override
    public synchronized boolean unregisterReader(MessageReader reader) {
        Preconditions.checkArgument(isOpen,"Log {} has been closed",name);
        return this.readers.remove(reader);
    }

    private class MessagePuller implements Runnable {

        private final int bucketId;
        private final int partitionId;

        private long nextTimestamp;


        private MessagePuller(final int partitionId, final int bucketId) {
            this.bucketId = bucketId;
            this.partitionId = partitionId;
            if (!readMarker.hasIdentifier()) {
                this.nextTimestamp = readMarker.getStartTimeMicro();
            } else {
                this.nextTimestamp = readSetting(readMarker.getIdentifier(),getMarkerColumn(partitionId,bucketId),readMarker.getStartTimeMicro());
            }
        }

        @Override
        public void run() {
            if (allowReadMarkerRecovery) setReadMarker();
            final int timeslice = getTimeSlice(nextTimestamp);
            long maxTime = Math.min(Timestamps.MICRO.getTime() - readLagTime, (timeslice + 1) * TIMESLICE_INTERVAL);
            StaticBuffer logKey = getLogKey(partitionId,bucketId,timeslice);
            KeySliceQuery query = new KeySliceQuery(logKey, ByteBufferUtil.getLongBuffer(nextTimestamp), ByteBufferUtil.getLongBuffer(maxTime));
            query.setLimit(maxReadMsg);
            List<Entry> entries= BackendOperation.execute(getOperation(query),KCVSLog.this,maxReadTime);
            prepareMessageProcessing(entries);
            if (entries.size()>=maxReadMsg) {
                //Adjust maxTime to next timepoint
                Entry lastEntry = entries.get(entries.size()-1);
                maxTime = lastEntry.getLong(0)+2; //Adding 2 microseconds (=> very few extra messages), not adding one to avoid that the slice is possibly empty
                //Retrieve all messages up to this adjusted timepoint (no limit this time => get all entries to that point)
                query = new KeySliceQuery(logKey, ByteBufferUtil.nextBiggerBuffer(lastEntry.getColumn()),ByteBufferUtil.getLongBuffer(maxTime));
                List<Entry> extraEntries = BackendOperation.execute(getOperation(query),KCVSLog.this,maxReadTime);
                prepareMessageProcessing(extraEntries);
            }
            nextTimestamp=maxTime;
        }

        private void prepareMessageProcessing(List<Entry> entries) {
            for (Entry entry : entries) {
                KCVSMessage message = parseMessage(entry);
                for (MessageReader reader : readers) {
                    readExecutor.submit(new ProcessMessageJob(message,reader));
                }
            }
        }

        private void setReadMarker() {
            if (readMarker.hasIdentifier()) {
                writeSetting(readMarker.getIdentifier(), getMarkerColumn(partitionId, bucketId), nextTimestamp);
            }
        }

        private void close() {
            setReadMarker();
        }

        private BackendOperation.Transactional<List<Entry>> getOperation(final KeySliceQuery query) {
            return new BackendOperation.Transactional<List<Entry>>() {
                @Override
                public List<Entry> call(StoreTransaction txh) throws StorageException {
                    return store.getSlice(query,txh);
                }
                @Override
                public String toString() {
                    return "messageReading@"+partitionId+":"+bucketId;
                }
            };
        }

    }

    /**
     * ###################################
     *  Getting/setting Log Settings
     * ###################################
     */

    private StaticBuffer getMarkerColumn(int partitionId, int bucketId) {
        DataOutput out = manager.serializer.getDataOutput(1+ 4 + 4);
        out.putByte(MARKER_PREFIX);
        out.putInt(partitionId);
        out.putInt(bucketId);
        return out.getStaticBuffer();
    }


    private StaticBuffer getSettingKey(String identifier) {
        DataOutput out = manager.serializer.getDataOutput(4 + 2 + identifier.length());
        out.putInt(SYSTEM_PARTITION_ID);
        out.writeObjectNotNull(identifier);
        return out.getStaticBuffer();
    }

    private long readSetting(String identifier, final StaticBuffer column, long defaultValue) {
        final StaticBuffer key = getSettingKey(identifier);
        StaticBuffer value = BackendOperation.execute(new BackendOperation.Transactional<StaticBuffer>() {
            @Override
            public StaticBuffer call(StoreTransaction txh) throws StorageException {
                return KCVSUtil.get(store,key,column,txh);
            }
            @Override
            public String toString() {
                return "readingLogSetting";
            }
        },this,maxReadTime);
        if (value==null) return defaultValue;
        else {
            Preconditions.checkArgument(value.length()==8);
            return value.getLong(0);
        }
    }

    private void writeSetting(String identifier, final StaticBuffer column, long value) {
        final StaticBuffer key = getSettingKey(identifier);
        final Entry add = StaticArrayEntry.of(column, ByteBufferUtil.getLongBuffer(value));
        Boolean status = BackendOperation.execute(new BackendOperation.Transactional<Boolean>() {
            @Override
            public Boolean call(StoreTransaction txh) throws StorageException {
                store.mutate(key,ImmutableList.of(add),KeyColumnValueStore.NO_DELETIONS,txh);
                return Boolean.TRUE;
            }
            @Override
            public String toString() {
                return "writingLogSetting";
            }
        },this, maxWriteTime);
        Preconditions.checkState(status);
    }


}
