package com.alibaba.rocketmq.client.consumer.cacheable;

import com.alibaba.rocketmq.client.consumer.DefaultMQPushConsumer;
import com.alibaba.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import com.alibaba.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import com.alibaba.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import com.alibaba.rocketmq.client.exception.MQClientException;
import com.alibaba.rocketmq.client.producer.concurrent.DefaultLocalMessageStore;
import com.alibaba.rocketmq.common.consumer.ConsumeFromWhere;
import com.alibaba.rocketmq.common.message.Message;
import com.alibaba.rocketmq.common.message.MessageExt;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class CacheableConsumer
{
    private String consumerGroupName;

    private DefaultLocalMessageStore localMessageStore;

    private final ConcurrentHashMap<String, MessageHandler> topicHandlerMap;

    private static final AtomicLong CONSUMER_NAME_COUNTER = new AtomicLong();

    private static final String BASE_INSTANCE_NAME = "CacheableConsumer@";

    private DefaultMQPushConsumer defaultMQPushConsumer;

    private boolean started;

    private int corePoolSizeForDelayTasks = 2;

    private int corePoolSizeForWorkTasks = 10;

    private ScheduledExecutorService scheduledExecutorDelayService = Executors
            .newScheduledThreadPool(corePoolSizeForDelayTasks);

    private ScheduledExecutorService scheduledExecutorWorkerService = Executors
            .newScheduledThreadPool(corePoolSizeForWorkTasks);

    private static String getInstanceName() {
        try {
            return BASE_INSTANCE_NAME + InetAddress.getLocalHost().getHostAddress() + "_" + CONSUMER_NAME_COUNTER.incrementAndGet();
        } catch (UnknownHostException e) {
            return BASE_INSTANCE_NAME + "127.0.0.1_" + CONSUMER_NAME_COUNTER.incrementAndGet();
        }
    }

    public CacheableConsumer(String consumerGroupName) {
        this.consumerGroupName = consumerGroupName;
        this.topicHandlerMap = new ConcurrentHashMap<String, MessageHandler>();
        defaultMQPushConsumer = new DefaultMQPushConsumer(consumerGroupName);
        localMessageStore = new DefaultLocalMessageStore(consumerGroupName);
        defaultMQPushConsumer.setInstanceName(getInstanceName());
        defaultMQPushConsumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
    }

    public CacheableConsumer registerMessageHandler(MessageHandler messageHandler) throws MQClientException {
        if (started) {
            throw new IllegalStateException("Please register before start");
        }

        if (null == messageHandler.getTopic() || messageHandler.getTopic().trim().isEmpty()) {
            throw new RuntimeException("Topic cannot be null or empty");
        }

        topicHandlerMap.putIfAbsent(messageHandler.getTopic(), messageHandler);
        defaultMQPushConsumer.subscribe(messageHandler.getTopic(),
                null == messageHandler.getTag() ? messageHandler.getTag() : "*");
        return this;
    }

    public CacheableConsumer registerMessageHandler(Collection<MessageHandler> messageHandlers)
            throws MQClientException {
        for (MessageHandler messageHandler : messageHandlers) {
            registerMessageHandler(messageHandler);
        }
        return this;
    }

    public void start() throws InterruptedException, MQClientException {
        if (topicHandlerMap.isEmpty()) {
            throw new RuntimeException("Please at least configure one message handler to subscribe one topic");
        }

        MessageListenerConcurrently frontController = new FrontController(topicHandlerMap,
                scheduledExecutorWorkerService, scheduledExecutorDelayService);
        defaultMQPushConsumer.registerMessageListener(frontController);
        defaultMQPushConsumer.start();
        started = true;
    }

    public void setConsumerGroupName(String consumerGroupName) {
        this.consumerGroupName = consumerGroupName;
    }

    public boolean isStarted() {
        return started;
    }
}


class FrontController implements MessageListenerConcurrently {

    private final ConcurrentHashMap<String, MessageHandler> topicHandlerMap;

    private final ScheduledExecutorService scheduledExecutorWorkerService;

    private final ScheduledExecutorService scheduledExecutorDelayService;

    public FrontController(ConcurrentHashMap<String, MessageHandler> topicHandlerMap,
                           ScheduledExecutorService scheduledExecutorWorkerService,
                           ScheduledExecutorService scheduledExecutorDelayService) {
        this.topicHandlerMap = topicHandlerMap;
        this.scheduledExecutorDelayService = scheduledExecutorDelayService;
        this.scheduledExecutorWorkerService = scheduledExecutorWorkerService;

    }


    @Override
    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messages,
                                                    ConsumeConcurrentlyContext context) {
        for (final Message message : messages) {
            final MessageHandler messageHandler = topicHandlerMap.get(message.getTopic());
            scheduledExecutorWorkerService.submit(new Runnable() {
                @Override
                public void run() {
                    int result = messageHandler.handle(message);
                    if (result > 0) {
                        scheduledExecutorDelayService.schedule(
                                new DelayTask(scheduledExecutorDelayService, messageHandler, message),
                                result, TimeUnit.MILLISECONDS);



                        //TODO Implement a message store with the following features.
                        // 1) index for quick access;
                        // 2) able to persist timestamp to execute;
                        // 3) able to mark and sweep deprecated data without fraction.
                        //localMessageStore.stash(message);
                    }
                }
            });
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}



class DelayTask implements Runnable {
    private final Message message;

    private final MessageHandler messageHandler;

    private final ScheduledExecutorService executorService;

    public DelayTask(ScheduledExecutorService executorService, MessageHandler messageHandler,
                     Message message) {
        this.message = message;
        this.messageHandler = messageHandler;
        this.executorService = executorService;
    }

    @Override
    public void run() {
        int result = messageHandler.handle(message);
        if (result > 0) {
            this.executorService.schedule(this, result, TimeUnit.MILLISECONDS);
        }

    }

}