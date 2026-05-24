package com.bytedance.ai.order.web;

import com.bytedance.ai.common.api.ApiResponse;
import com.bytedance.ai.order.api.OrderQueryFacade;
import com.bytedance.ai.order.api.OrderView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
