package com.alibaba.rocketmq.client.consumer.cacheable;

import com.alibaba.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import com.alibaba.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import com.alibaba.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import com.alibaba.rocketmq.common.message.Message;
import com.alibaba.rocketmq.common.message.MessageExt;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

public class FrontController implements MessageListenerConcurrently {

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
        for (Message message : messages) {
            final MessageHandler messageHandler = topicHandlerMap.get(message.getTopic());
            scheduledExecutorWorkerService.submit(new ProcessMessageTask(message, messageHandler,
                    scheduledExecutorDelayService));
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}