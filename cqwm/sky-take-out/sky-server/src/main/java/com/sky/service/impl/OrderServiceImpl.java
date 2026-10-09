package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.*;
import com.sky.entity.AddressBook;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.entity.ShoppingCart;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.*;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.websocket.WebSocketServer;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class OrderServiceImpl implements OrderService {
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WebSocketServer webSocketServer;

    /**
     * 提交订单
     */
    @Transactional
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {
        //1.
        //处理订单异常情况(地址为空？购物车为空？）
        AddressBook addressBook = addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        if (addressBook == null) {
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }
        //查询当前用户购物车
        Long userId = BaseContext.getCurrentId();
        List<ShoppingCart> cartList = shoppingCartMapper.list(userId);
        if (cartList.isEmpty()) {
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }
        //2.
        //向订单表插入一条数据
        Orders orders = new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO, orders);
        orders.setOrderTime(LocalDateTime.now());
        orders.setPayStatus(Orders.UN_PAID);
        orders.setStatus(Orders.PENDING_PAYMENT);
        orders.setNumber(String.valueOf(System.currentTimeMillis()));
        orders.setPhone(addressBook.getPhone());
        orders.setUserId(userId);
        orders.setCheckoutTime(LocalDateTime.now());
        orders.setConsignee(addressBook.getConsignee());
        orders.setPhone(addressBook.getPhone());
        orders.setAddress(addressBook.getDetail());

        orderMapper.insert(orders);
        //向订单明细表插入N条数据
        List<OrderDetail> orderDetails = new ArrayList<>();
        for (ShoppingCart cart : cartList) {
            OrderDetail orderDetail = new OrderDetail();
            BeanUtils.copyProperties(cart, orderDetail);
            orderDetail.setOrderId(orders.getId());
            orderDetails.add(orderDetail);
        }
        orderDetailMapper.insertBatch(orderDetails);
        //3.
        //清空购物车
        shoppingCartMapper.clean(userId);
        //返回订单确认页面需要的VO
        return OrderSubmitVO.builder()
                .id(orders.getId())
                .orderTime(orders.getOrderTime())
                .orderNumber(orders.getNumber())
                .orderAmount(orders.getAmount())
                .build();
    }

    /**
     * 订单支付
     * <p>
     * 【教学环境-模拟支付】真实微信支付需要 商户号(mchid) + APIv3密钥 + 商户私钥证书 + 已备案的HTTPS回调域名，
     * 这些个人开发者拿不到，所以这里跳过"统一下单/唤起收银台/异步回调"三步，
     * 直接把订单置为【待接单+已支付】，其余状态流转与真实链路完全一致。
     * 真实实现的代码已在下方注释保留，将来有商户资质时去掉注释即可切回。
     *
     * @param ordersPaymentDTO
     * @return
     */
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        Long userId = BaseContext.getCurrentId();

        // 1、按订单号查出订单，并校验它属于当前登录用户
        Orders ordersDB = orderMapper.getByNumber(ordersPaymentDTO.getOrderNumber());
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        if (!userId.equals(ordersDB.getUserId())) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        /* ===================== 真实微信支付（需商户资质，教学环境不可用） =====================
        User user = userMapper.getById(userId);
        JSONObject jsonObject = weChatPayUtil.pay(
                ordersPaymentDTO.getOrderNumber(),
                ordersDB.getAmount(),
                "苍穹外卖订单",
                user.getOpenid());
        // 已支付过的订单不能重复支付
        if (jsonObject.containsKey("code") && "ORDERPAID".equals(jsonObject.getString("code"))) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        OrderPaymentVO orderPaymentVO = jsonObject.toJavaObject(OrderPaymentVO.class);
        orderPaymentVO.setPackageStr(jsonObject.getString("package"));
        return orderPaymentVO;
        ================================================================================ */

        // 2、模拟支付成功：直接改库
        paySuccess(ordersPaymentDTO.getOrderNumber());

        // 返回空的支付参数（前端不再调用 wx.requestPayment）
        return new OrderPaymentVO();
    }

    /**
     * 支付成功，修改订单状态
     * 状态流转：待付款(1)+未支付(0) → 待接单(2)+已支付(1)，并记录结账时间
     *
     * @param outTradeNo
     */
    public void paySuccess(String outTradeNo) {
        // 根据订单号查询当前订单
        Orders ordersDB = orderMapper.getByNumber(outTradeNo);

        // 构造要更新的字段（用 builder 只传需要改的字段，配合 XML 的动态 set）
        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID)
                .checkoutTime(LocalDateTime.now())
                .build();

        orderMapper.update(orders);
        //通过websocket 通知客户端 type OrderId content
        Map map = new HashMap();
        map.put("type",1);
        map.put("OrderId",ordersDB.getId());
        map.put("content","订单号" + ordersDB.getNumber() + "已支付");
        String jsonString = JSON.toJSONString(map);
        webSocketServer.sendToAllClient(jsonString);
    }

    /**
     * 用户查看历史订单
     */
    public PageResult historyOrders(OrdersPageQueryDTO ordersPageQueryDTO) {
        // 只查当前登录用户自己的订单（不设 userId 会查出所有人的订单）
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());

        PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize());
        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);

        // 把 Orders 转成 OrderVO，并给每个订单填上菜品明细
        // （前端历史订单页渲染的是 item.orderDetailList，Orders 实体里没有这个字段）
        List<OrderVO> orderVOList = new ArrayList<>();
        for (Orders orders : page) {
            OrderVO orderVO = new OrderVO();
            BeanUtils.copyProperties(orders, orderVO);
            orderVO.setOrderDetailList(orderDetailMapper.getByOrderId(orders.getId()));
            orderVOList.add(orderVO);
        }

        return new PageResult(page.getTotal(), orderVOList);
    }

    /**
     * 根据id查询订单详情
     *
     * @param id
     * @return
     */
    public OrderVO getOrderDetailById(Long id) {
        // 根据 id 查询订单
        Orders orders = orderMapper.getById(id);
        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        // 把 Orders 转成 OrderVO，并给每个订单填上菜品明细
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders, orderVO);
        orderVO.setOrderDetailList(orderDetailMapper.getByOrderId(orders.getId()));

        return orderVO;
    }

    /**
     * 用户取消订单
     * 可取消的两种状态：
     * 待付款 + 未支付 → 直接取消
     * 待接单 + 已支付 → 取消并退款
     */
    public void cancel(Long id) {
        Orders orders = orderMapper.getById(id);
        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        // 校验订单归属当前登录用户
        Long currentUserId = BaseContext.getCurrentId();
        if (!currentUserId.equals(orders.getUserId())) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        boolean isPendingUnpaid = Orders.PENDING_PAYMENT.equals(orders.getStatus())
                && Orders.UN_PAID.equals(orders.getPayStatus());
        boolean isToBeConfirmedPaid = Orders.TO_BE_CONFIRMED.equals(orders.getStatus())
                && Orders.PAID.equals(orders.getPayStatus());

        if (!isPendingUnpaid && !isToBeConfirmedPaid) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders update = Orders.builder()
                .id(orders.getId())
                .status(Orders.CANCELLED)
                .cancelTime(LocalDateTime.now())
                .cancelReason("用户取消订单")
                .build();

        if (isToBeConfirmedPaid) {
            // TODO: 调用退款接口（微信退款 / 模拟退款）
            update.setPayStatus(Orders.REFUND);
        }

        orderMapper.update(update);
    }

    /**
     * 用户再来一单：把历史订单的菜品全部写回当前用户的购物车
     * 前端随后跳转购物车页，用户确认后再正常下单
     */
    @Transactional
    public void repetition(Long id) {
        // 1. 校验订单归属
        Orders orders = orderMapper.getById(id);
        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        Long userId = BaseContext.getCurrentId();
        if (!userId.equals(orders.getUserId())) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        // 2. 查出该订单所有菜品明细
        List<OrderDetail> detailList = orderDetailMapper.getByOrderId(id);

        // 3. 逐条写回购物车（OrderDetail → ShoppingCart，字段名高度对齐）
        for (OrderDetail detail : detailList) {
            ShoppingCart cart = ShoppingCart.builder()
                    .userId(userId)
                    .name(detail.getName())
                    .dishId(detail.getDishId())
                    .setmealId(detail.getSetmealId())
                    .dishFlavor(detail.getDishFlavor())
                    .number(detail.getNumber())
                    .amount(detail.getAmount())
                    .image(detail.getImage())
                    .createTime(LocalDateTime.now())
                    .build();
            shoppingCartMapper.add(cart);
        }
    }

    public PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO) {
        PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize());
        Page<OrderVO> page = orderMapper.conditionSearch(ordersPageQueryDTO);
        return new PageResult(page.getTotal(), page.getResult());
    }

    public OrderStatisticsVO getStatistics() {
        OrderStatisticsVO orderStatisticsVO = new OrderStatisticsVO();
        orderStatisticsVO.setConfirmed(orderMapper.getStatistics(Orders.CONFIRMED.toString()));
        orderStatisticsVO.setDeliveryInProgress(orderMapper.getStatistics(Orders.DELIVERY_IN_PROGRESS.toString()));
        orderStatisticsVO.setDeliveryInProgress(orderMapper.getStatistics(Orders.DELIVERY_IN_PROGRESS.toString()));
        return orderStatisticsVO;
    }

    public void confirm(Long id) {
        Orders orders = orderMapper.getById(id);
        if (orders != null) {
            orders.setStatus(Orders.CONFIRMED);
            orders.setCheckoutTime(LocalDateTime.now());
            orderMapper.update(orders);
        }
    }

    public void rejection(OrdersRejectionDTO ordersRejectionDTO) {
        Orders orders = orderMapper.getById(ordersRejectionDTO.getId());
        if (orders != null) {
            orders.setStatus(Orders.CANCELLED);
            orders.setRejectionReason(ordersRejectionDTO.getRejectionReason());
            orders.setCancelTime(LocalDateTime.now());
            orderMapper.update(orders);
        }
    }

    public void adminCancel(OrdersCancelDTO ordersCancelDTO) {
        Orders orders = orderMapper.getById(ordersCancelDTO.getId());
        if (orders != null) {
            orders.setStatus(Orders.CANCELLED);
            orders.setCancelReason(ordersCancelDTO.getCancelReason());
            orders.setCancelTime(LocalDateTime.now());
            orderMapper.update(orders);
    }
    }

    public void delivery(Long id) {
        Orders orders = orderMapper.getById(id);
        if (orders != null) {
            orders.setStatus(Orders.DELIVERY_IN_PROGRESS);
            orderMapper.update(orders);
        }
    }

    public void complete(Long id) {
        Orders orders = orderMapper.getById(id);
        if (orders != null) {
            orders.setStatus(Orders.COMPLETED);
            orderMapper.update(orders);
        }
    }

    public void reminder(Long id) {
        Orders orders = orderMapper.getById(id);
        if (orders != null) {
            Map map = new HashMap();
            map.put("type",2);
            map.put("OrderId",orders.getId());
            map.put("content","订单号" + orders.getNumber() + "已确认");
            String jsonString = JSON.toJSONString(map);
            webSocketServer.sendToAllClient(jsonString);
        }else{
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
    }
}
