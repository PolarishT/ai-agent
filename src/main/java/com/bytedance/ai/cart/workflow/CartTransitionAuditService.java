package com.bytedance.ai.cart.workflow;

import com.bytedance.ai.cart.api.CartState;
import com.bytedance.ai.cart.persistence.CartTransitionAuditRepository;
import com.bytedance.ai.cart.persistence.ShoppingCartRecord;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartTransitionAuditService {

    private final CartTransitionAuditRepository repository;

    public CartTransitionAuditService(CartTransitionAuditRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(
            ShoppingCartRecord cart,
            CartState fromState,
            CartState toState,
            CartEvent event,
            CartCommand command,
            boolean success,
            String failureReason,
            String errorMessage
    ) {
        repository.save(
                cart == null ? null : cart.id(),
                cart == null ? null : cart.cartId(),
                fromState == null ? null : fromState.name(),
                toState == null ? CartState.IDLE.name() : toState.name(),
                event.name(),
                command == null ? "agent" : command.triggeredBy(),
                success,
                failureReason,
                errorMessage,
                metadata(cart, command)
        );
    }

    private Map<String, Object> metadata(ShoppingCartRecord cart, CartCommand command) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (command != null) {
            metadata.putAll(command.metadata());
            metadata.put("spuId", command.spuId());
            metadata.put("externalRef", command.externalRef());
            metadata.put("quantity", command.quantity());
        }
        if (cart != null) {
            metadata.put("itemCount", cart.itemCount());
            metadata.put("subtotalAmount", cart.subtotalAmount());
            metadata.put("version", cart.version());
        }
        return metadata;
    }
}
