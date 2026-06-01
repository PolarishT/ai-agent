package com.bytedance.ai.graph.cartmanage.subgraph;

import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.bytedance.ai.graph.cartmanage.application.CartCommandService;
import com.bytedance.ai.graph.cartmanage.application.CartManageSlotFillingService;
import com.bytedance.ai.graph.cartmanage.application.CartQueryService;
import com.bytedance.ai.graph.cartmanage.application.InventoryQueryService;
import com.bytedance.ai.graph.cartmanage.application.ProductCatalogResolver;
import com.bytedance.ai.graph.cartmanage.persistence.PendingCartActionRepository;
import com.bytedance.ai.graph.cartmanage.subgraph.node.CartCheckStockNode;
import com.bytedance.ai.graph.cartmanage.subgraph.node.CartExecuteActionNode;
import com.bytedance.ai.graph.cartmanage.subgraph.node.CartFinalResponseNode;
import com.bytedance.ai.graph.cartmanage.subgraph.node.CartLoadContextNode;
import com.bytedance.ai.graph.cartmanage.subgraph.node.CartResolveActionNode;
import com.bytedance.ai.graph.cartmanage.subgraph.node.CartResolveCandidateNode;
import com.bytedance.ai.graph.cartmanage.subgraph.node.CartResolveTargetNode;
import com.bytedance.ai.graph.cartmanage.subgraph.node.CartSearchCatalogNode;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CandidateSelectionResolver;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CartCandidateMatcher;
import com.bytedance.ai.graph.cartmanage.subgraph.support.CartItemLookup;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class CartManageSubgraphFactory {

    private final CartLoadContextNode loadContextNode;
    private final CartResolveActionNode resolveActionNode;
    private final CartResolveTargetNode resolveTargetNode;
    private final CartSearchCatalogNode searchCatalogNode;
    private final CartResolveCandidateNode resolveCandidateNode;
    private final CartCheckStockNode checkStockNode;
    private final CartExecuteActionNode executeActionNode;
    private final CartFinalResponseNode finalResponseNode;

    public CartManageSubgraphFactory(
            CartQueryService cartQueryService,
            CartCommandService cartCommandService,
            InventoryQueryService inventoryQueryService,
            ProductCatalogResolver productCatalogResolver,
            PendingCartActionRepository pendingCartActionRepository,
            CartManageSlotFillingService slotFillingService,
            CandidateSelectionLlmService candidateSelectionLlmService
    ) {
        CartCandidateMatcher candidateMatcher = new CartCandidateMatcher();
        CandidateSelectionResolver candidateSelectionResolver = new CandidateSelectionResolver(candidateSelectionLlmService);
        CartItemLookup cartItemLookup = new CartItemLookup();

        this.loadContextNode = new CartLoadContextNode(pendingCartActionRepository, candidateSelectionResolver);
        this.resolveActionNode = new CartResolveActionNode();
        this.resolveTargetNode = new CartResolveTargetNode(slotFillingService, cartItemLookup);
        this.searchCatalogNode = new CartSearchCatalogNode(productCatalogResolver, pendingCartActionRepository, candidateMatcher);
        this.resolveCandidateNode = new CartResolveCandidateNode(pendingCartActionRepository, candidateSelectionResolver);
        this.checkStockNode = new CartCheckStockNode(inventoryQueryService);
        this.executeActionNode = new CartExecuteActionNode(cartQueryService, cartCommandService, cartItemLookup);
        this.finalResponseNode = new CartFinalResponseNode();
    }

    public StateGraph build() {
        try {
            return buildInternal();
        } catch (com.alibaba.cloud.ai.graph.exception.GraphStateException ex) {
            throw new IllegalStateException("cart_manage_subgraph compile failed", ex);
        }
    }

    private StateGraph buildInternal() throws com.alibaba.cloud.ai.graph.exception.GraphStateException {
        StateGraph subgraph = new StateGraph("cart_manage_subgraph", keyStrategyFactory());

        subgraph.addNode("cart_load_context", AsyncNodeAction.node_async(loadContextNode::apply));
        subgraph.addNode("cart_resolve_action", AsyncNodeAction.node_async(resolveActionNode::apply));
        subgraph.addNode("cart_resolve_target", AsyncNodeAction.node_async(resolveTargetNode::apply));
        subgraph.addNode("cart_search_catalog", AsyncNodeAction.node_async(searchCatalogNode::apply));
        subgraph.addNode("cart_resolve_candidate", AsyncNodeAction.node_async(resolveCandidateNode::apply));
        subgraph.addNode("cart_check_stock", AsyncNodeAction.node_async(checkStockNode::apply));
        subgraph.addNode("cart_execute_action", AsyncNodeAction.node_async(executeActionNode::apply));
        subgraph.addNode("cart_final_response", AsyncNodeAction.node_async(finalResponseNode::apply));

        subgraph.addEdge(StateGraph.START, "cart_load_context");
        subgraph.addEdge("cart_load_context", "cart_resolve_action");

        subgraph.addConditionalEdges(
                "cart_resolve_action",
                AsyncEdgeAction.edge_async(resolveActionNode::routeAfter),
                Map.of(
                        "VIEW", "cart_execute_action",
                        "CLEAR", "cart_execute_action",
                        "CONFIRM", "cart_resolve_candidate",
                        "ADD_REMOVE_UPDATE", "cart_resolve_target",
                        "UNKNOWN", "cart_final_response"
                )
        );

        subgraph.addConditionalEdges(
                "cart_resolve_target",
                AsyncEdgeAction.edge_async(resolveTargetNode::routeAfter),
                Map.of(
                        "HAS_IDS", "cart_check_stock",
                        "ADD_SEARCH", "cart_search_catalog",
                        "REMOVE_UPDATE_EXECUTE", "cart_execute_action",
                        "FINAL", "cart_final_response"
                )
        );

        subgraph.addConditionalEdges(
                "cart_search_catalog",
                AsyncEdgeAction.edge_async(searchCatalogNode::routeAfter),
                Map.of(
                        "NO_CANDIDATES", "cart_final_response",
                        "ONE_CANDIDATE", "cart_check_stock",
                        "MULTI_CANDIDATES", "cart_final_response"
                )
        );

        subgraph.addConditionalEdges(
                "cart_resolve_candidate",
                AsyncEdgeAction.edge_async(resolveCandidateNode::routeAfter),
                Map.of(
                        "HAS_IDS", "cart_check_stock",
                        "FINAL", "cart_final_response"
                )
        );

        subgraph.addConditionalEdges(
                "cart_check_stock",
                AsyncEdgeAction.edge_async(checkStockNode::routeAfter),
                Map.of(
                        "IN_STOCK", "cart_execute_action",
                        "OUT_OF_STOCK", "cart_final_response"
                )
        );

        subgraph.addEdge("cart_execute_action", "cart_final_response");
        subgraph.addEdge("cart_final_response", StateGraph.END);

        return subgraph;
    }

    private KeyStrategyFactory keyStrategyFactory() {
        return new KeyStrategyFactoryBuilder()
                .defaultStrategy(new ReplaceStrategy())
                .build();
    }
}
