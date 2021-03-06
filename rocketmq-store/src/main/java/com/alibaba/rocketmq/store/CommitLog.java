/**
 * Copyright (C) 2010-2013 Alibaba Group Holding Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.rocketmq.store;

import com.alibaba.rocketmq.common.ServiceThread;
import com.alibaba.rocketmq.common.UtilAll;
import com.alibaba.rocketmq.common.constant.LoggerName;
import com.alibaba.rocketmq.common.message.MessageAccessor;
import com.alibaba.rocketmq.common.message.MessageConst;
import com.alibaba.rocketmq.common.message.MessageDecoder;
import com.alibaba.rocketmq.common.message.MessageExt;
import com.alibaba.rocketmq.common.sysflag.MessageSysFlag;
import com.alibaba.rocketmq.store.config.BrokerRole;
import com.alibaba.rocketmq.store.config.FlushDiskType;
import com.alibaba.rocketmq.store.ha.HAService;
import com.alibaba.rocketmq.store.schedule.ScheduleMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;


/**
 * CommitLog实现
 *
 * @author shijia.wxr<vintage.wang@gmail.com>
 * @since 2013-7-21
 */
public class CommitLog {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggerName.StoreLoggerName);

    // 每个消息对应的MAGIC CODE daa320a7
    public final static int MessageMagicCode = 0xAABBCCDD ^ 1880681586 + 8;

    // 文件末尾空洞对应的MAGIC CODE cbd43194
    private final static int BlankMagicCode = 0xBBCCDDEE ^ 1880681586 + 8;

    // 存储消息的队列
    private final MappedFileQueue mappedFileQueue;

    // 存储顶层对象
    private final DefaultMessageStore defaultMessageStore;

    // CommitLog刷盘服务
    private final FlushCommitLogService flushCommitLogService;

    // 存储消息时的回调接口
    private final AppendMessageCallback appendMessageCallback;

    // 用来保存每个ConsumeQueue的当前最大Offset信息
    private HashMap<String/* topic-queueId */, Long/* offset */> topicQueueTable = new HashMap<String, Long>(1024);

    //true: Can lock, false : in lock.
    private AtomicBoolean putMessageSpinLock = new AtomicBoolean(true);

    private ReentrantLock putMessageNormalLock = new ReentrantLock(); // NonfairSync

    private final ThreadLocal<ByteBuffer> encodedMsgByteBuffer = new ThreadLocal<>();

    /**
     * 构造函数
     */
    public CommitLog(final DefaultMessageStore defaultMessageStore) {
        this.mappedFileQueue = new MappedFileQueue(defaultMessageStore.getMessageStoreConfig().getStorePathCommitLog(),
                defaultMessageStore.getMessageStoreConfig().getMappedFileSizeCommitLog(),
                defaultMessageStore.getAllocateMappedFileService());
        this.defaultMessageStore = defaultMessageStore;

        if (FlushDiskType.SYNC_FLUSH == defaultMessageStore.getMessageStoreConfig().getFlushDiskType()) {
            this.flushCommitLogService = new GroupCommitService();
        } else {
            this.flushCommitLogService = new FlushRealTimeService();
        }

        this.appendMessageCallback = new DefaultAppendMessageCallback(defaultMessageStore.getMessageStoreConfig()
                .getMaxMessageSize());
    }


    public boolean load() {
        boolean result = this.mappedFileQueue.load();
        LOGGER.info("load commit log " + (result ? "OK" : "Failed"));
        return result;
    }


    public void start() {
        this.flushCommitLogService.start();
    }


    public void shutdown() {
        this.flushCommitLogService.shutdown();
    }


    public long getMinOffset() {
        MappedFile mappedFile = this.mappedFileQueue.getFirstMappedFileOnLock();
        if (mappedFile != null) {
            if (mappedFile.isAvailable()) {
                return mappedFile.getFileFromOffset();
            } else {
                return this.rollNextFile(mappedFile.getFileFromOffset());
            }
        }

        return -1;
    }


    public long rollNextFile(final long offset) {
        int mappedFileSize = this.defaultMessageStore.getMessageStoreConfig().getMappedFileSizeCommitLog();
        return (offset + mappedFileSize - offset % mappedFileSize);
    }


    public long getMaxOffset() {
        return this.mappedFileQueue.getMaxOffset();
    }


    public int deleteExpiredFile(//
                                 final long expiredTime, //
                                 final int deleteFilesInterval, //
                                 final long intervalForcibly,//
                                 final boolean cleanImmediately//
    ) {
        return this.mappedFileQueue.deleteExpiredFileByTime(expiredTime, deleteFilesInterval,
                intervalForcibly, cleanImmediately);
    }


    /**
     * 读取CommitLog数据，数据复制时使用
     */
    public SelectMappedBufferResult getData(final long offset) {
        return this.getData(offset, (0 == offset));
    }


    public SelectMappedBufferResult getData(final long offset, final boolean returnFirstOnNotFound) {
        int mappedFileSize = this.defaultMessageStore.getMessageStoreConfig().getMappedFileSizeCommitLog();
        MappedFile mappedFile = this.mappedFileQueue.findMappedFileByOffset(offset, returnFirstOnNotFound);
        if (mappedFile != null) {
            int pos = (int) (offset % mappedFileSize);
            SelectMappedBufferResult result = mappedFile.selectMappedBuffer(pos);
            return result;
        }

        return null;
    }


    /**
     * 正常退出时，数据恢复，所有内存数据都已经刷盘
     */
    public void recoverNormally() {
        boolean checkCRCOnRecover = this.defaultMessageStore.getMessageStoreConfig().isCheckCRCOnRecover();
        final List<MappedFile> mappedFiles = this.mappedFileQueue.getMappedFiles();
        if (!mappedFiles.isEmpty()) {
            // 从倒数第三个文件开始恢复
            int index = mappedFiles.size() - 3;
            if (index < 0)
                index = 0;

            MappedFile mappedFile = mappedFiles.get(index);
            ByteBuffer byteBuffer = mappedFile.sliceByteBuffer();
            long processOffset = mappedFile.getFileFromOffset();
            long mappedFileOffset = 0;
            while (true) {
                DispatchRequest dispatchRequest = this.checkMessageAndReturnSize(byteBuffer, checkCRCOnRecover);
                int size = dispatchRequest.getMsgSize();
                // 正常数据
                if (size > 0) {
                    mappedFileOffset += size;
                }
                // 文件中间读到错误
                else if (size == -1) {
                    LOGGER.info("recover physics file end, " + mappedFile.getFileName());
                    break;
                }
                // 走到文件末尾，切换至下一个文件
                // 由于返回0代表是遇到了最后的空洞，这个可以不计入truncate offset中
                else if (size == 0) {
                    index++;
                    if (index >= mappedFiles.size()) {
                        // 当前条件分支不可能发生
                        LOGGER.info("recover last 3 physics file over, last mapped file "
                                + mappedFile.getFileName());
                        break;
                    } else {
                        mappedFile = mappedFiles.get(index);
                        byteBuffer = mappedFile.sliceByteBuffer();
                        processOffset = mappedFile.getFileFromOffset();
                        mappedFileOffset = 0;
                        LOGGER.info("recover next physics file, " + mappedFile.getFileName());
                    }
                }
            }

            processOffset += mappedFileOffset;
            this.mappedFileQueue.setCommittedWhere(processOffset);
            this.mappedFileQueue.truncateDirtyFiles(processOffset);
        }
    }


    public DispatchRequest checkMessageAndReturnSize(java.nio.ByteBuffer byteBuffer, final boolean checkCRC) {
        return this.checkMessageAndReturnSize(byteBuffer, checkCRC, true);
    }


    /**
     * 服务端使用 检查消息并返回消息大小
     *
     * @return 0 表示走到文件末尾 >0 正常消息 -1 消息校验失败
     */
    public DispatchRequest checkMessageAndReturnSize(java.nio.ByteBuffer byteBuffer, final boolean checkCRC,
                                                     final boolean readBody) {
        try {
            java.nio.ByteBuffer byteBufferMessage =
                    ((DefaultAppendMessageCallback) this.appendMessageCallback).getMsgStoreItemMemory();
            byte[] bytesContent = byteBufferMessage.array();

            // 1 TOTALSIZE
            int totalSize = byteBuffer.getInt();

            // 2 MAGICCODE
            int magicCode = byteBuffer.getInt();
            switch (magicCode) {
                case MessageMagicCode:
                    break;
                case BlankMagicCode:
                    return new DispatchRequest(0);
                default:
                    LOGGER.warn("found an illegal magic code 0x" + Integer.toHexString(magicCode));
                    return new DispatchRequest(-1);
            }

            // 3 BODYCRC
            int bodyCRC = byteBuffer.getInt();

            // 4 QUEUEID
            int queueId = byteBuffer.getInt();

            // 5 FLAG
            int flag = byteBuffer.getInt();
            flag = flag + 0;

            // 6 QUEUEOFFSET
            long queueOffset = byteBuffer.getLong();

            // 7 PHYSICALOFFSET
            long physicOffset = byteBuffer.getLong();

            // 8 SYSFLAG
            int sysFlag = byteBuffer.getInt();

            // 9 BORNTIMESTAMP
            long bornTimeStamp = byteBuffer.getLong();
            bornTimeStamp = bornTimeStamp + 0;

            // 10 BORNHOST（IP+PORT）
            byteBuffer.get(bytesContent, 0, 8);

            // 11 STORETIMESTAMP
            long storeTimestamp = byteBuffer.getLong();

            // 12 STOREHOST（IP+PORT）
            byteBuffer.get(bytesContent, 0, 8);

            // 13 RECONSUMETIMES
            int reconsumeTimes = byteBuffer.getInt();

            // 14 Prepared Transaction Offset
            long preparedTransactionOffset = byteBuffer.getLong();

            // 15 BODY
            int bodyLen = byteBuffer.getInt();
            if (bodyLen > 0) {
                if (readBody) {
                    byteBuffer.get(bytesContent, 0, bodyLen);

                    // 校验CRC
                    if (checkCRC) {
                        int crc = UtilAll.crc32(bytesContent, 0, bodyLen);
                        if (crc != bodyCRC) {
                            LOGGER.warn("CRC check failed " + crc + " " + bodyCRC);
                            return new DispatchRequest(-1);
                        }
                    }
                } else {
                    byteBuffer.position(byteBuffer.position() + bodyLen);
                }
            }

            // 16 TOPIC
            byte topicLen = byteBuffer.get();
            byteBuffer.get(bytesContent, 0, topicLen);
            String topic = new String(bytesContent, 0, topicLen);

            long tagsCode = 0;
            String keys = "";

            // 17 properties
            short propertiesLength = byteBuffer.getShort();
            if (propertiesLength > 0) {
                byteBuffer.get(bytesContent, 0, propertiesLength);
                String properties = new String(bytesContent, 0, propertiesLength);
                Map<String, String> propertiesMap = MessageDecoder.string2messageProperties(properties);

                keys = propertiesMap.get(MessageConst.PROPERTY_KEYS);
                String tags = propertiesMap.get(MessageConst.PROPERTY_TAGS);
                if (tags != null && tags.length() > 0) {
                    tagsCode =
                            MessageExtBrokerInner.tagsString2tagsCode(
                                    MessageExt.parseTopicFilterType(sysFlag), tags);
                }

                // 定时消息处理
                {
                    String t = propertiesMap.get(MessageConst.PROPERTY_DELAY_TIME_LEVEL);
                    if (ScheduleMessageService.SCHEDULE_TOPIC.equals(topic) && t != null) {
                        int delayLevel = Integer.parseInt(t);

                        if (delayLevel > this.defaultMessageStore.getScheduleMessageService()
                                .getMaxDelayLevel()) {
                            delayLevel = this.defaultMessageStore.getScheduleMessageService().getMaxDelayLevel();
                        }

                        if (delayLevel > 0) {
                            tagsCode = this.defaultMessageStore.getScheduleMessageService()
                                    .computeDeliverTimestamp(delayLevel, storeTimestamp);
                        }
                    }
                }
            }

            return new DispatchRequest(//
                    topic,// 1
                    queueId,// 2
                    physicOffset,// 3
                    totalSize,// 4
                    tagsCode,// 5
                    storeTimestamp,// 6
                    queueOffset,// 7
                    keys,// 8
                    sysFlag,// 9
                    preparedTransactionOffset// 10
            );
        } catch (BufferUnderflowException e) {
            byteBuffer.position(byteBuffer.limit());
        } catch (Exception e) {
            byteBuffer.position(byteBuffer.limit());
        }

        return new DispatchRequest(-1);
    }


    public void recoverAbnormally() {
        // 根据最小时间戳来恢复
        boolean checkCRCOnRecover = this.defaultMessageStore.getMessageStoreConfig().isCheckCRCOnRecover();
        final List<MappedFile> mappedFiles = this.mappedFileQueue.getMappedFiles();
        if (!mappedFiles.isEmpty()) {
            // 寻找从哪个文件开始恢复
            int index = mappedFiles.size() - 1;
            MappedFile mappedFile = null;
            for (; index >= 0; index--) {
                mappedFile = mappedFiles.get(index);
                if (this.isMappedFileMatchedRecover(mappedFile)) {
                    LOGGER.info("recover from this mapped file " + mappedFile.getFileName());
                    break;
                }
            }

            if (index < 0) {
                index = 0;
                mappedFile = mappedFiles.get(index);
            }

            ByteBuffer byteBuffer = mappedFile.sliceByteBuffer();
            long processOffset = mappedFile.getFileFromOffset();
            long mappedFileOffset = 0;
            while (true) {
                DispatchRequest dispatchRequest = this.checkMessageAndReturnSize(byteBuffer, checkCRCOnRecover);
                int size = dispatchRequest.getMsgSize();
                // 正常数据
                if (size > 0) {
                    mappedFileOffset += size;
                    this.defaultMessageStore.putDispatchRequest(dispatchRequest);
                }
                // 文件中间读到错误
                else if (size == -1) {
                    LOGGER.info("recover physics file end, " + mappedFile.getFileName());
                    break;
                }
                // 走到文件末尾，切换至下一个文件
                // 由于返回0代表是遇到了最后的空洞，这个可以不计入truncate offset中
                else if (size == 0) {
                    index++;
                    if (index >= mappedFiles.size()) {
                        // 当前条件分支正常情况下不应该发生
                        LOGGER.info("recover physics file over, last mapped file " + mappedFile.getFileName());
                        break;
                    } else {
                        mappedFile = mappedFiles.get(index);
                        byteBuffer = mappedFile.sliceByteBuffer();
                        processOffset = mappedFile.getFileFromOffset();
                        mappedFileOffset = 0;
                        LOGGER.info("recover next physics file, " + mappedFile.getFileName());
                    }
                }
            }

            processOffset += mappedFileOffset;
            this.mappedFileQueue.setCommittedWhere(processOffset);
            this.mappedFileQueue.truncateDirtyFiles(processOffset);

            // 清除ConsumeQueue的多余数据
            this.defaultMessageStore.truncateDirtyLogicFiles(processOffset);
        }
        // 物理文件都被删除情况下
        else {
            this.mappedFileQueue.setCommittedWhere(0);
            this.defaultMessageStore.destroyLogics();
        }
    }


    private boolean isMappedFileMatchedRecover(final MappedFile mappedFile) {
        ByteBuffer byteBuffer = mappedFile.sliceByteBuffer();

        int magicCode = byteBuffer.getInt(MessageDecoder.MESSAGE_MAGIC_CODE_POSITION);
        if (magicCode != MessageMagicCode) {
            return false;
        }

        long storeTimestamp = byteBuffer.getLong(MessageDecoder.MESSAGE_STORE_TIMESTAMP_POSITION);
        if (0 == storeTimestamp) {
            return false;
        }

        if (this.defaultMessageStore.getMessageStoreConfig().isMessageIndexEnable()//
                && this.defaultMessageStore.getMessageStoreConfig().isMessageIndexSafe()) {
            if (storeTimestamp <= this.defaultMessageStore.getStoreCheckpoint().getMinTimestampIndex()) {
                LOGGER.info("find check timestamp, {} {}", //
                        storeTimestamp,//
                        UtilAll.timeMillisToHumanString(storeTimestamp));
                return true;
            }
        } else {
            if (storeTimestamp <= this.defaultMessageStore.getStoreCheckpoint().getMinTimestamp()) {
                LOGGER.info("find check timestamp, {} {}", //
                        storeTimestamp,//
                        UtilAll.timeMillisToHumanString(storeTimestamp));
                return true;
            }
        }

        return false;
    }


    public PutMessageResult putMessage(final MessageExtBrokerInner msg) {
        // 设置消息体BODY CRC（考虑在客户端设置最合适）
        msg.setBodyCRC(UtilAll.crc32(msg.getBody()));
        // 返回结果
        AppendMessageResult result = null;

        StoreStatsService storeStatsService = this.defaultMessageStore.getStoreStatsService();

        String topic = msg.getTopic();
        int queueId = msg.getQueueId();
        long tagsCode = msg.getTagsCode();

        final int tranType = MessageSysFlag.getTransactionValue(msg.getSysFlag());
        if (tranType == MessageSysFlag.TransactionNotType//
                || tranType == MessageSysFlag.TransactionCommitType) {
            // 延时投递
            if (msg.getDelayTimeLevel() > 0) {
                if (msg.getDelayTimeLevel() > this.defaultMessageStore.getScheduleMessageService().getMaxDelayLevel()) {
                    msg.setDelayTimeLevel(this.defaultMessageStore.getScheduleMessageService().getMaxDelayLevel());
                }

                topic = ScheduleMessageService.SCHEDULE_TOPIC;
                queueId = ScheduleMessageService.delayLevel2QueueId(msg.getDelayTimeLevel());
                tagsCode = this.defaultMessageStore.getScheduleMessageService().computeDeliverTimestamp(
                        msg.getDelayTimeLevel(), msg.getStoreTimestamp());

                /*
                 * 备份真实的topic，queueId
                 */
                MessageAccessor.putProperty(msg, MessageConst.PROPERTY_REAL_TOPIC, msg.getTopic());
                MessageAccessor.putProperty(msg, MessageConst.PROPERTY_REAL_QUEUE_ID,
                        String.valueOf(msg.getQueueId()));
                msg.setPropertiesString(MessageDecoder.messageProperties2String(msg.getProperties()));

                msg.setTopic(topic);
                msg.setQueueId(queueId);
            }
        }

        int msgLength = computeMsgLength(msg);
        if (msgLength > defaultMessageStore.getMessageStoreConfig().getMaxMessageSize()) {
            LOGGER.warn("message size exceeded, msg total size: {}", msgLength);
            result = new AppendMessageResult(AppendMessageStatus.MESSAGE_SIZE_EXCEEDED);
            return new PutMessageResult(PutMessageStatus.MESSAGE_ILLEGAL, result);
        }

        final ByteBuffer encodedMsg = encode(msg);

        // 写文件要加锁
        long eclipseTimeInLock = 0;
        MappedFile mappedFileToUnlock = null;
        lockForPutMessage();
        try {
            long beginLockTimestamp = this.defaultMessageStore.getSystemClock().now();

            // 尝试写入
            MappedFile mappedFile = this.mappedFileQueue.getLastMappedFile();
            if (null == mappedFile) {
                LOGGER.error("create mapped file1 error, topic: " + msg.getTopic() + " clientAddr: "
                        + msg.getBornHostString());
                return new PutMessageResult(PutMessageStatus.CREATE_MAPPED_FILE_FAILED, null);
            }
            result = mappedFile.appendMessage(msg, encodedMsg, this.appendMessageCallback);
            switch (result.getStatus()) {
                // 成功追加消息
                case PUT_OK:
                    break;
                // 走到文件末尾
                case END_OF_FILE:
                    mappedFileToUnlock = mappedFile;
                    // 创建新文件，重新写消息
                    mappedFile = this.mappedFileQueue.getLastMappedFile();
                    if (null == mappedFile) {
                        // XXX: warn and notify me
                        LOGGER.error("create mapped file2 error, topic: " + msg.getTopic() + " clientAddr: "
                                + msg.getBornHostString());
                        return new PutMessageResult(PutMessageStatus.CREATE_MAPPED_FILE_FAILED, result);
                    }
                    result = mappedFile.appendMessage(msg, encodedMsg, this.appendMessageCallback);
                    break;
                // 未知错误
                case UNKNOWN_ERROR:
                    return new PutMessageResult(PutMessageStatus.UNKNOWN_ERROR, result);
                default:
                    return new PutMessageResult(PutMessageStatus.UNKNOWN_ERROR, result);
            }

            DispatchRequest dispatchRequest = new DispatchRequest(//
                    topic,// 1
                    queueId,// 2
                    result.getWroteOffset(),// 3
                    result.getWroteBytes(),// 4
                    tagsCode,// 5
                    msg.getStoreTimestamp(),// 6
                    result.getLogicOffset(),// 7
                    msg.getKeys(),// 8
                    /*
                     * 事务部分
                     */
                    msg.getSysFlag(),// 9
                    msg.getPreparedTransactionOffset());// 10

            this.defaultMessageStore.putDispatchRequest(dispatchRequest);

            eclipseTimeInLock = this.defaultMessageStore.getSystemClock().now() - beginLockTimestamp;
        } finally {
            releasePutMessageLock();
        }

        if (eclipseTimeInLock > 1000) {
            // XXX: warn and notify me
            LOGGER.warn("putMessage in lock eclipse time(ms) " + eclipseTimeInLock);
        }

        if (null != mappedFileToUnlock) {
            this.defaultMessageStore.unlockMappedFile(mappedFileToUnlock);
        }

        // 返回结果
        PutMessageResult putMessageResult = new PutMessageResult(PutMessageStatus.PUT_OK, result);

        // 统计消息SIZE
        storeStatsService.getSinglePutMessageTopicSizeTotal(topic).addAndGet(result.getWroteBytes());

        GroupCommitRequest request = null;

        // 同步刷盘
        if (FlushDiskType.SYNC_FLUSH == this.defaultMessageStore.getMessageStoreConfig().getFlushDiskType()) {
            GroupCommitService service = (GroupCommitService) this.flushCommitLogService;
            if (msg.isWaitStoreMsgOK()) {
                request = new GroupCommitRequest(result.getWroteOffset() + result.getWroteBytes());
                service.putRequest(request);
                boolean flushOK = request.waitForFlush(this.defaultMessageStore.getMessageStoreConfig()
                        .getSyncFlushTimeout());
                if (!flushOK) {
                    LOGGER.error("do group commit, wait for flush failed, topic: " + msg.getTopic() + " tags: "
                            + msg.getTags() + " client address: " + msg.getBornHostString());
                    putMessageResult.setPutMessageStatus(PutMessageStatus.FLUSH_DISK_TIMEOUT);
                }
            } else {
                service.wakeUp();
            }
        }
        // 异步刷盘
        else {
            this.flushCommitLogService.wakeUp();
        }

        // 同步双写
        if (BrokerRole.SYNC_MASTER == this.defaultMessageStore.getMessageStoreConfig().getBrokerRole()) {
            HAService service = this.defaultMessageStore.getHaService();
            if (msg.isWaitStoreMsgOK()) {
                // 判断是否要等待
                if (service.isSlaveOK(result.getWroteOffset() + result.getWroteBytes())) {
                    if (null == request) {
                        request = new GroupCommitRequest(result.getWroteOffset() + result.getWroteBytes());
                    }
                    service.putRequest(request);

                    service.getWaitNotifyObject().wakeUpAll();

                    boolean flushOK =
                            // TODO 此处参数与刷盘公用是否合适
                            request.waitForFlush(this.defaultMessageStore.getMessageStoreConfig().getSyncFlushTimeout());
                    if (!flushOK) {
                        LOGGER.error("do sync transfer other node, wait return, but failed, topic: "
                                + msg.getTopic() + " tags: " + msg.getTags() + " client address: "
                                + msg.getBornHostString());
                        putMessageResult.setPutMessageStatus(PutMessageStatus.FLUSH_SLAVE_TIMEOUT);
                    }
                }
                // Slave异常
                else {
                    // 告诉发送方，Slave异常
                    putMessageResult.setPutMessageStatus(PutMessageStatus.SLAVE_NOT_AVAILABLE);
                }
            }
        }

        // 向发送方返回结果
        return putMessageResult;
    }


    /**
     * 根据offset获取特定消息的存储时间 如果出错，则返回-1
     */
    public long pickupStoreTimestamp(final long offset, final int size) {
        if (offset > this.getMinOffset()) {
            SelectMappedBufferResult result = this.getMessage(offset, size);
            if (null != result) {
                try {
                    return result.getByteBuffer().getLong(MessageDecoder.MESSAGE_STORE_TIMESTAMP_POSITION);
                } finally {
                    result.release();
                }
            }
        }

        return -1;
    }


    /**
     * 读取消息
     */
    public SelectMappedBufferResult getMessage(final long offset, final int size) {
        int mappedFileSize = this.defaultMessageStore.getMessageStoreConfig().getMappedFileSizeCommitLog();
        MappedFile mappedFile = this.mappedFileQueue.findMappedFileByOffset(offset, (0 == offset));
        if (mappedFile != null) {
            int pos = (int) (offset % mappedFileSize);
            SelectMappedBufferResult result = mappedFile.selectMappedBuffer(pos, size);
            return result;
        }

        return null;
    }


    public HashMap<String, Long> getTopicQueueTable() {
        return topicQueueTable;
    }


    public void setTopicQueueTable(HashMap<String, Long> topicQueueTable) {
        this.topicQueueTable = topicQueueTable;
    }


    public void destroy() {
        this.mappedFileQueue.destroy();
    }


    public boolean appendData(long startOffset, byte[] data) {
        // 写文件要加锁
        synchronized (this) {
            // 尝试写入
            MappedFile mappedFile = this.mappedFileQueue.getLastMappedFile(startOffset);
            if (null == mappedFile) {
                LOGGER.error("appendData getLastMappedFile error  " + startOffset);
                return false;
            }

            return mappedFile.appendMessage(data);
        }
    }


    public boolean retryDeleteFirstFile(final long intervalForcibly) {
        return this.mappedFileQueue.retryDeleteFirstFile(intervalForcibly);
    }

    abstract class FlushCommitLogService extends ServiceThread {
    }

    /**
     * 异步实时刷盘服务
     */
    class FlushRealTimeService extends FlushCommitLogService {
        private static final int RetryTimesOver = 3;
        private long lastFlushTimestamp = 0;
        private long printTimes = 0;


        public void run() {
            CommitLog.LOGGER.info(this.getServiceName() + " service started");

            while (!this.isStopped()) {
                boolean flushCommitLogTimed =
                        CommitLog.this.defaultMessageStore.getMessageStoreConfig().isFlushCommitLogTimed();

                int interval =
                        CommitLog.this.defaultMessageStore.getMessageStoreConfig()
                                .getFlushIntervalCommitLog();
                int flushPhysicQueueLeastPages =
                        CommitLog.this.defaultMessageStore.getMessageStoreConfig()
                                .getFlushCommitLogLeastPages();

                int flushPhysicQueueThoroughInterval =
                        CommitLog.this.defaultMessageStore.getMessageStoreConfig()
                                .getFlushCommitLogThoroughInterval();

                boolean printFlushProgress = false;

                // 定时刷盘，定时打印刷盘进度
                long currentTimeMillis = System.currentTimeMillis();
                if (currentTimeMillis >= (this.lastFlushTimestamp + flushPhysicQueueThoroughInterval)) {
                    this.lastFlushTimestamp = currentTimeMillis;
                    flushPhysicQueueLeastPages = 0;
                    printFlushProgress = ((printTimes++ % 10) == 0);
                }

                try {
                    // 定时刷盘
                    if (flushCommitLogTimed) {
                        Thread.sleep(interval);
                    }
                    // 实时刷盘
                    else {
                        this.waitForRunning(interval);
                    }

                    if (printFlushProgress) {
                        this.printFlushProgress();
                    }

                    CommitLog.this.mappedFileQueue.commit(flushPhysicQueueLeastPages);
                    long storeTimestamp = CommitLog.this.mappedFileQueue.getStoreTimestamp();
                    if (storeTimestamp > 0) {
                        CommitLog.this.defaultMessageStore.getStoreCheckpoint().setPhysicMsgTimestamp(
                                storeTimestamp);
                    }
                } catch (Exception e) {
                    CommitLog.LOGGER.warn(this.getServiceName() + " service has exception. ", e);
                    this.printFlushProgress();
                }
            }

            // 正常shutdown时，要保证全部刷盘才退出
            boolean result = false;
            for (int i = 0; i < RetryTimesOver && !result; i++) {
                result = CommitLog.this.mappedFileQueue.commit(0);
                CommitLog.LOGGER.info(this.getServiceName() + " service shutdown, retry " + (i + 1) + " times "
                        + (result ? "OK" : "Not OK"));
            }

            this.printFlushProgress();

            CommitLog.LOGGER.info(this.getServiceName() + " service end");
        }


        @Override
        public String getServiceName() {
            return FlushCommitLogService.class.getSimpleName();
        }


        private void printFlushProgress() {
            CommitLog.LOGGER.info("how much disk fall behind memory, "
                    + CommitLog.this.mappedFileQueue.howMuchFallBehind());
        }


        @Override
        public long getJoinTime() {
            // 由于CommitLog数据量较大，所以回收时间要更长
            return 1000 * 60 * 5;
        }
    }

    /**
     * Spin util acquired the lock.
     */
    private void lockForPutMessage() {
        if (this.defaultMessageStore.getMessageStoreConfig().isUseReentrantLockWhenPutMessage()) {
            putMessageNormalLock.lock();
        } else {
            boolean flag;
            do {
                flag = this.putMessageSpinLock.compareAndSet(true, false);
            } while (!flag);
        }
    }

    private void releasePutMessageLock() {
        if (this.defaultMessageStore.getMessageStoreConfig().isUseReentrantLockWhenPutMessage()) {
            putMessageNormalLock.unlock();
        } else {
            this.putMessageSpinLock.compareAndSet(false, true);
        }
    }

    public static class GroupCommitRequest {
        // 当前消息对应的下一个Offset
        private final long nextOffset;
        // 异步通知对象
        private final CountDownLatch countDownLatch = new CountDownLatch(1);
        // 刷盘是否成功
        private volatile boolean flushOK = false;


        public GroupCommitRequest(long nextOffset) {
            this.nextOffset = nextOffset;
        }


        public long getNextOffset() {
            return nextOffset;
        }


        public void wakeupCustomer(final boolean flushOK) {
            this.flushOK = flushOK;
            this.countDownLatch.countDown();
        }


        public boolean waitForFlush(long timeout) {
            try {
                boolean result = this.countDownLatch.await(timeout, TimeUnit.MILLISECONDS);
                return result || this.flushOK;
            } catch (InterruptedException e) {
                e.printStackTrace();
                return false;
            }
        }
    }

    /**
     * GroupCommit Service
     */
    class GroupCommitService extends FlushCommitLogService {
        private volatile List<GroupCommitRequest> requestsWrite = new ArrayList<GroupCommitRequest>();
        private volatile List<GroupCommitRequest> requestsRead = new ArrayList<GroupCommitRequest>();


        public synchronized void putRequest(final GroupCommitRequest request) {
            synchronized (this.requestsWrite) {
                this.requestsWrite.add(request);
                if (!this.hasNotified) {
                    this.hasNotified = true;
                    this.notify();
                }
            }
        }


        private void swapRequests() {
            List<GroupCommitRequest> tmp = this.requestsWrite;
            this.requestsWrite = this.requestsRead;
            this.requestsRead = tmp;
        }


        private void doCommit() {
            if (!this.requestsRead.isEmpty()) {
                synchronized (this.requestsRead) {
                    for (GroupCommitRequest req : this.requestsRead) {
                        // 消息有可能在下一个文件，所以最多刷盘2次
                        boolean flushOK = false;
                        for (int i = 0; (i < 2) && !flushOK; i++) {
                            flushOK = (CommitLog.this.mappedFileQueue.getCommittedWhere() >= req.getNextOffset());

                            if (!flushOK) {
                                CommitLog.this.mappedFileQueue.commit(0);
                            }
                        }

                        req.wakeupCustomer(flushOK);
                    }

                    long storeTimestamp = CommitLog.this.mappedFileQueue.getStoreTimestamp();
                    if (storeTimestamp > 0) {
                        CommitLog.this.defaultMessageStore.getStoreCheckpoint().setPhysicMsgTimestamp(storeTimestamp);
                    }

                    this.requestsRead.clear();
                }
            } else {
                // 由于个别消息设置为不同步刷盘，所以会走到此流程
                CommitLog.this.mappedFileQueue.commit(0);
            }
        }


        public void run() {
            CommitLog.LOGGER.info(this.getServiceName() + " service started");

            while (!this.isStopped()) {
                try {
                    this.waitForRunning(0);
                    this.doCommit();
                } catch (Exception e) {
                    CommitLog.LOGGER.warn(this.getServiceName() + " service has exception. ", e);
                }
            }

            // 在正常shutdown情况下，等待请求到来，然后再刷盘
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                CommitLog.LOGGER.warn("GroupCommitService Exception, ", e);
            }

            synchronized (this) {
                this.swapRequests();
            }

            this.doCommit();

            CommitLog.LOGGER.info(this.getServiceName() + " service end");
        }


        @Override
        protected void onWaitEnd() {
            this.swapRequests();
        }


        @Override
        public String getServiceName() {
            return GroupCommitService.class.getSimpleName();
        }


        @Override
        public long getJoinTime() {
            // 由于CommitLog数据量较大，所以回收时间要更长
            return 1000 * 60 * 5;
        }
    }

    private int computeMsgLength(MessageExtBrokerInner msgInner) {
        final byte[] propertiesData = msgInner.getPropertiesString() == null ? null : msgInner.getPropertiesString().getBytes();
        final int propertiesLength = propertiesData == null ? 0 : propertiesData.length;
        final byte[] topicData = msgInner.getTopic().getBytes();
        final int topicLength = topicData.length;

        final int bodyLength = msgInner.getBody() == null ? 0 : msgInner.getBody().length;

        final int msgLen = 4 // 1 TOTALSIZE
                + 4 // 2 MAGICCODE
                + 4 // 3 BODYCRC
                + 4 // 4 QUEUEID
                + 4 // 5 FLAG
                + 8 // 6 QUEUEOFFSET
                + 8 // 7 PHYSICALOFFSET
                + 4 // 8 SYSFLAG
                + 8 // 9 BORNTIMESTAMP
                + 8 // 10 BORNHOST
                + 8 // 11 STORETIMESTAMP
                + 8 // 12 STOREHOSTADDRESS
                + 4 // 13 RECONSUMETIMES
                + 8 // 14 Prepared Transaction Offset
                + 4 + bodyLength // 14 BODY
                + 1 + topicLength // 15 TOPIC
                + 2 + propertiesLength // 16 propertiesLength
                + 0;
        return msgLen;
    }

    private ByteBuffer encode(MessageExtBrokerInner msgInner) {

        final byte[] propertiesData = msgInner.getPropertiesString() == null ? null : msgInner.getPropertiesString().getBytes();
        final int propertiesLength = propertiesData == null ? 0 : propertiesData.length;

        final byte[] topicData = msgInner.getTopic().getBytes();
        final int topicLength = topicData.length;

        final int bodyLength = msgInner.getBody() == null ? 0 : msgInner.getBody().length;

        final int msgLen = computeMsgLength(msgInner);

        // Note, we are using
        ByteBuffer msgStoreItemMemory = encodedMsgByteBuffer.get();
        if (msgStoreItemMemory == null) {
            msgStoreItemMemory = ByteBuffer.allocateDirect(defaultMessageStore.getMessageStoreConfig().getMaxMessageSize() + MappedFile.END_FILE_MIN_BLANK_LENGTH);
            encodedMsgByteBuffer.set(msgStoreItemMemory);
        }

        // set position to 0, limit to capacity.
        msgStoreItemMemory.clear();

        // 1 TOTALSIZE
        msgStoreItemMemory.putInt(msgLen);
        // 2 MAGICCODE
        msgStoreItemMemory.putInt(CommitLog.MessageMagicCode);
        // 3 BODYCRC
        msgStoreItemMemory.putInt(msgInner.getBodyCRC());
        // 4 QUEUEID
        msgStoreItemMemory.putInt(msgInner.getQueueId());
        // 5 FLAG
        msgStoreItemMemory.putInt(msgInner.getFlag());
        // 6 QUEUEOFFSET, will be overridden in synchronized block.
        msgStoreItemMemory.putLong(0L);
        // 7 PHYSICALOFFSET, will be overridden in synchronized block.
        msgStoreItemMemory.putLong(0L);
        // 8 SYSFLAG
        msgStoreItemMemory.putInt(msgInner.getSysFlag());
        // 9 BORNTIMESTAMP
        msgStoreItemMemory.putLong(msgInner.getBornTimestamp());
        // 10 BORNHOST
        msgStoreItemMemory.put(msgInner.getBornHostBytes());
        // 11 STORETIMESTAMP, will be overridden in synchronized block.
        msgStoreItemMemory.putLong(msgInner.getStoreTimestamp());
        // 12 STOREHOSTADDRESS
        msgStoreItemMemory.put(msgInner.getStoreHostBytes());
        // 13 RECONSUMETIMES
        msgStoreItemMemory.putInt(msgInner.getReconsumeTimes());
        // 14 Prepared Transaction Offset
        msgStoreItemMemory.putLong(msgInner.getPreparedTransactionOffset());
        // 15 BODY
        msgStoreItemMemory.putInt(bodyLength);
        if (bodyLength > 0)
            msgStoreItemMemory.put(msgInner.getBody());
        // 16 TOPIC
        msgStoreItemMemory.put((byte) topicLength);
        msgStoreItemMemory.put(topicData);
        // 17 PROPERTIES
        msgStoreItemMemory.putShort((short) propertiesLength);
        if (propertiesLength > 0) {
            msgStoreItemMemory.put(propertiesData);
        }

        msgStoreItemMemory.flip();

        return msgStoreItemMemory;
    }

    class DefaultAppendMessageCallback implements AppendMessageCallback {
        // 文件末尾空洞最小定长
        private static final int END_FILE_MIN_BLANK_LENGTH = 4 + 4;
        // 存储消息ID
        private final ByteBuffer msgIdMemory;
        // 存储消息内容
        private final ByteBuffer msgStoreItemMemory;


        DefaultAppendMessageCallback(final int size) {
            this.msgIdMemory = ByteBuffer.allocate(MessageDecoder.MSG_ID_LENGTH);
            this.msgStoreItemMemory = ByteBuffer.allocate(size + END_FILE_MIN_BLANK_LENGTH);
        }


        public ByteBuffer getMsgStoreItemMemory() {
            return msgStoreItemMemory;
        }


        public AppendMessageResult doAppend(final long fileFromOffset, final ByteBuffer byteBuffer, final int maxBlank,
                                            final Object msg, final ByteBuffer encodedMsg) {
            /*
             * 生成消息ID STORETIMESTAMP + STOREHOSTADDRESS + OFFSET <br>
             */
            MessageExtBrokerInner msgInner = (MessageExtBrokerInner) msg;
            // PHY OFFSET
            long wroteOffset = fileFromOffset + byteBuffer.position();
            String msgId = MessageDecoder.createMessageId(this.msgIdMemory, msgInner.getStoreHostBytes(), wroteOffset);

            /*
             * 记录ConsumeQueue信息
             */
            String key = msgInner.getTopic() + "-" + msgInner.getQueueId();
            Long queueOffset = CommitLog.this.topicQueueTable.get(key);
            if (null == queueOffset) {
                queueOffset = 0L;
                CommitLog.this.topicQueueTable.put(key, queueOffset);
            }

            /*
             * 事务消息需要特殊处理
             */
            final int tranType = MessageSysFlag.getTransactionValue(msgInner.getSysFlag());
            switch (tranType) {
                // Prepared和Rollback都是不可以消费的消息，不会进入消费队列
                case MessageSysFlag.TransactionPreparedType:
                case MessageSysFlag.TransactionRollbackType:
                    queueOffset = 0L;
                    break;
                case MessageSysFlag.TransactionNotType:
                case MessageSysFlag.TransactionCommitType:
                default:
                    break;
            }

            final int msgLen = encodedMsg.remaining();

            // 判断是否有足够空余空间
            if ((msgLen + END_FILE_MIN_BLANK_LENGTH) > maxBlank) {
                this.resetMsgStoreItemMemory(maxBlank);
                // 1 TOTALSIZE
                this.msgStoreItemMemory.putInt(maxBlank);
                // 2 MAGICCODE
                this.msgStoreItemMemory.putInt(CommitLog.BlankMagicCode);
                // 3 剩余空间可能是任何值
                //

                // 此处长度特意设置为maxBlank
                byteBuffer.put(this.msgStoreItemMemory.array(), 0, maxBlank);
                return new AppendMessageResult(AppendMessageStatus.END_OF_FILE, wroteOffset, maxBlank, msgId,
                        msgInner.getStoreTimestamp(), queueOffset);
            }

            // Update byte buffer to reflect actual commit log written offset and logical queue offset.
            encodedMsg.position(20);
            encodedMsg.putLong(queueOffset);
            encodedMsg.putLong(fileFromOffset + byteBuffer.position());
            encodedMsg.position(56);
            encodedMsg.putLong(defaultMessageStore.getSystemClock().now());
            encodedMsg.rewind();

            // 向队列缓冲区写入消息
            byteBuffer.put(encodedMsg);

            AppendMessageResult result = new AppendMessageResult(AppendMessageStatus.PUT_OK, wroteOffset, msgLen, msgId,
                    msgInner.getStoreTimestamp(), queueOffset);

            switch (tranType) {
                case MessageSysFlag.TransactionPreparedType:
                case MessageSysFlag.TransactionRollbackType:
                    break;
                case MessageSysFlag.TransactionNotType:
                case MessageSysFlag.TransactionCommitType:
                    // 更新下一次的ConsumeQueue信息
                    CommitLog.this.topicQueueTable.put(key, ++queueOffset);
                    break;
                default:
                    break;
            }

            // 返回结果
            return result;
        }


        private void resetMsgStoreItemMemory(final int length) {
            this.msgStoreItemMemory.flip();
            this.msgStoreItemMemory.limit(length);
        }
    }


    public void removeQueueFromTopicQueueTable(final String topic, final int queueId) {
        String key = topic + "-" + queueId;
        synchronized (this) {
            this.topicQueueTable.remove(key);
        }

        LOGGER.info("removeQueueFromTopicQueueTable OK Topic: {} QueueId: {}", topic, queueId);
    }

    public void updateCommitLogStorePath() {
        mappedFileQueue.setStorePath(defaultMessageStore.getMessageStoreConfig().getStorePathCommitLog());
    }
}
