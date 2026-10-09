package com.sky.task;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

@Component
@Slf4j
public class OrderTask {
    @Autowired
    OrderMapper orderMapper;

    /**
     * 定时处理超时订单
     */
    @Scheduled(cron = "0 * * * * ?")//每分钟一次触发
    public void processTimeOutTask() {
        log.info("定时处理超时订单: {}",new Date());
        LocalDateTime localDateTime = LocalDateTime.now().plusMinutes(-15);
        List<Orders> ordersList = orderMapper.getByStatusAndOrderTimeLT(Orders.PENDING_PAYMENT, localDateTime);

        if(!ordersList.isEmpty()){
            for (Orders orders : ordersList) {
            orders.setStatus(Orders.CANCELLED);
            orders.setCancelTime(LocalDateTime.now());
            orders.setCancelReason("订单超时未支付，系统自动取消");
            orderMapper.update(orders);
            }
        }
    }

    /**
     * 定时处理长时间配送中的订单
     */
    @Scheduled(cron = "0 0 1 * * ?")//每天凌晨1点触发
    public void processDeliveryTask() {
        log.info("定时处理订单长时间派送中。。。: {}", new Date());
        LocalDateTime localDateTime = LocalDateTime.now().plusHours(-1);
        List<Orders> ordersList = orderMapper.getByStatusAndOrderTimeLT(Orders.DELIVERY_IN_PROGRESS, localDateTime);

        if (!ordersList.isEmpty()) {
            for (Orders orders : ordersList) {
                orders.setStatus(Orders.COMPLETED);
                orders.setDeliveryTime(LocalDateTime.now());
                orderMapper.update(orders);
            }
        }
    }
}
