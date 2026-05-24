/**
 * Cart workflow internals.
 *
 * <p>Contains state-machine transitions, guards, commands, events, and transition auditing.
 * Public cart read state lives in {@code cart.api.CartState} because it is part of {@code CartView}.
 */
package com.bytedance.ai.cart.workflow;
