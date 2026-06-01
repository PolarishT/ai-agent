package com.bytedance.ai.graph.order.web;

import com.bytedance.ai.common.api.ApiResponse;
import com.bytedance.ai.graph.order.api.OrderQueryFacade;
import com.bytedance.ai.graph.order.api.OrderView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接口，提供订单查询和下单能力。
 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class OrderController {

    private final OrderQueryFacade orderQueryFacade;

    public OrderController(OrderQueryFacade orderQueryFacade) {
        this.orderQueryFacade = orderQueryFacade;
    }

    @GetMapping("/order/{id}")
    public ApiResponse<OrderView> getOrder(@PathVariable("id") String orderId) {
        return ApiResponse.ok(orderQueryFacade.getOrder(orderId));
    }
}
