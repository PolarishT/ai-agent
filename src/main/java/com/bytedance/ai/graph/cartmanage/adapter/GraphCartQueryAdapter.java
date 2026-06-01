package com.bytedance.ai.graph.cartmanage.adapter;

import com.bytedance.ai.graph.cart.api.CartQueryFacade;
import com.bytedance.ai.graph.cart.api.CartView;
import com.bytedance.ai.graph.cartmanage.application.CartQueryService;
import org.springframework.stereotype.Service;

/**
 * Adapter that satisfies the cart-manage workflow's read contract by delegating to the cart
 * bounded-context's {@link CartQueryFacade}. Bridges the conversation-scoped facade signature to
 * the cart-manage signature expected by the graph layer.
 */
@Service
public class GraphCartQueryAdapter implements CartQueryService {

    private final CartQueryFacade cartQueryFacade;

    public GraphCartQueryAdapter(CartQueryFacade cartQueryFacade) {
        this.cartQueryFacade = cartQueryFacade;
    }

    @Override
    public CartView getUserCart(String userId, String conversationId) {
        return cartQueryFacade.getActiveCart(userId, conversationId);
    }
}
